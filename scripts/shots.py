#!/usr/bin/env python3
"""
重新生成 README 里的界面截图。

需要：模拟器在跑、测试应用已安装、工作台已启动（默认 8080）、本机有 Chrome。
它会把调试器驱动到一个有代表性的状态（断点命中 + 单步几次），再用 Chrome headless
经 CDP 截图，输出到 docs/images/。

    python3 scripts/shots.py [端口]
"""
import asyncio, base64, json, os, pathlib, shutil, subprocess, sys, tempfile, time
import urllib.request, urllib.parse

try:
    import websockets
except ImportError:
    sys.exit("需要 websockets：pip3 install websockets")

PORT = sys.argv[1] if len(sys.argv) > 1 else "8080"
BASE = f"http://127.0.0.1:{PORT}"
PKG = "com.smaliscope.testapp"
OUT = pathlib.Path(__file__).resolve().parent.parent / "docs" / "images"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

# 截图尺寸：宽到足以让三栏都舒展开；scale 2 出 Retina 清晰度。
WIDTH, HEIGHT, SCALE = 1500, 940, 2


def api(verb, path, **params):
    url = BASE + path + ("?" + urllib.parse.urlencode(params) if params else "")
    req = urllib.request.Request(url, method=verb)
    with urllib.request.urlopen(req, timeout=180) as r:
        body = r.read().decode()
    return json.loads(body) if body else None


def drive_to_interesting_state():
    """把调试器开到「断点命中 + 走了几步」——空界面截图没有说服力。"""
    print("· 载入应用并做静态分析…")
    api("POST", "/api/session", pkg=PKG)

    m = api("GET", "/api/method", **{"class": f"{PKG}.Calc", "method": "compute", "sig": "(II)I"})
    mul = next(i for i in m["instructions"] if i["opcode"] == "mul-int")

    print(f"· 在 mul-int (dex_pc={mul['dexPc']}) 下断点…")
    api("POST", "/api/bp", **{"class": f"{PKG}.Calc", "method": "compute",
                              "sig": "(II)I", "pc": mul["dexPc"]})
    api("POST", "/api/start")

    print("· 等待命中…")
    for _ in range(150):
        st = api("GET", "/api/state")
        if st and st["status"] == "suspended":
            break
        time.sleep(0.4)
    else:
        sys.exit("等待断点命中超时")

    # 走 5 步刚好绕完一次循环回到 mul-int：那里读写俱全，数据流条有内容，
    # 且 v0/v1 已经被赋过值、带变化高亮。停在 goto 上截图就什么都看不到。
    print("· 单步几次，让时间线与寄存器变化都有内容…")
    for _ in range(5):
        api("POST", "/api/control", action="over")
        for _ in range(50):
            if api("GET", "/api/state")["status"] == "suspended":
                break
            time.sleep(0.2)
    print("· 状态就绪")


class Cdp:
    def __init__(self, ws):
        self.ws, self._id = ws, 0

    async def send(self, method, **params):
        self._id += 1
        await self.ws.send(json.dumps({"id": self._id, "method": method, "params": params}))
        while True:
            msg = json.loads(await self.ws.recv())
            if msg.get("id") == self._id:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error']}")
                return msg.get("result", {})

    async def js(self, expr):
        r = await self.send("Runtime.evaluate", expression=expr, awaitPromise=True,
                            returnByValue=True)
        return r.get("result", {}).get("value")

    async def shot(self, path):
        r = await self.send("Page.captureScreenshot", format="png")
        path.write_bytes(base64.b64decode(r["data"]))
        kb = path.stat().st_size // 1024
        print(f"  ✅ {path.name}  ({kb} KB)")


async def capture():
    OUT.mkdir(parents=True, exist_ok=True)
    profile = tempfile.mkdtemp(prefix="smaliscope-shots-")
    chrome = subprocess.Popen(
        [CHROME, "--headless=new", "--disable-gpu", "--no-first-run",
         "--no-default-browser-check", "--hide-scrollbars",
         f"--user-data-dir={profile}", "--remote-debugging-port=9333",
         f"--window-size={WIDTH},{HEIGHT}", "about:blank"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        # 直接取页面级 target 的 ws 地址：浏览器级是 /devtools/browser/<id>，
        # 页面级是 /devtools/page/<id>，两条路径不能靠拼字符串互转。
        page_ws = None
        for _ in range(60):
            try:
                with urllib.request.urlopen("http://127.0.0.1:9333/json", timeout=2) as r:
                    for t in json.load(r):
                        if t.get("type") == "page" and t.get("webSocketDebuggerUrl"):
                            page_ws = t["webSocketDebuggerUrl"]
                            break
                if page_ws:
                    break
            except Exception:
                pass
            time.sleep(0.25)
        if not page_ws:
            sys.exit("拿不到 Chrome 页面调试地址")

        async with websockets.connect(page_ws, max_size=64 * 1024 * 1024) as ws:
            c = Cdp(ws)
            await c.send("Page.enable")
            await c.send("Runtime.enable")
            await c.send("Emulation.setDeviceMetricsOverride",
                         width=WIDTH, height=HEIGHT, deviceScaleFactor=SCALE, mobile=False)

            await c.send("Page.navigate", url=BASE)
            # 等前端 restore 完成：类树填好、且已恢复到挂起状态。
            for _ in range(80):
                ready = await c.js(
                    "(document.querySelectorAll('#classList .item').length > 0) && "
                    "document.querySelectorAll('#smali .row').length > 0")
                if ready:
                    break
                await asyncio.sleep(0.25)
            await asyncio.sleep(1.2)   # 让滚动与高亮动画落定

            print("· 截图中…")
            await c.shot(OUT / "workbench.png")

            async def tab(name, filename, extra=None):
                await c.js(
                    f"(()=>{{const t=[...document.querySelectorAll('#tabs .tab')]"
                    f".find(x=>x.dataset.panel==='{name}'); t && t.click();}})()")
                await asyncio.sleep(0.6)
                if extra:
                    await c.js(extra)
                    await asyncio.sleep(1.0)
                await c.shot(OUT / filename)

            # 对象图：点开寄存器面板里第一个可展开的对象
            await tab("object", "object-graph.png",
                      extra="(()=>{const v=document.querySelector('#registers .val.clickable');"
                            "v && v.click();})()")
            await tab("timeline", "timeline.png")
            await tab("java", "java-view.png")
            # 控制流图不单独出图：主图默认就停在这个标签上，再来一张是重复的。
    finally:
        chrome.terminate()
        shutil.rmtree(profile, ignore_errors=True)


if __name__ == "__main__":
    if not os.path.exists(CHROME):
        sys.exit(f"没找到 Chrome：{CHROME}")
    drive_to_interesting_state()
    asyncio.run(capture())
    print(f"\n完成，输出在 {OUT}")
