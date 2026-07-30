#!/usr/bin/env python3
"""
录一段「真机 + 工作台」并排的演示，输出 gif + mp4 到 docs/images/。

调试是逐步进行的动作，所以这里逐步驱动：每走一步，同时抓真机截图和工作台截图，
拼成一帧。两边天然同步——同一帧里手机的状态和工具的状态就是同一时刻的。

前置：真机已连（ANDROID_SERIAL 指向它）、已装 testapp、屏幕保持常亮、本机有 Chrome + ffmpeg。
工作台需以指向该真机的方式启动（脚本会自己拉起一个专用端口的 serve）。

    python3 scripts/record-demo.py <设备序列号>
"""
import asyncio, base64, json, os, pathlib, shutil, subprocess, sys, time
import urllib.request, urllib.parse
from PIL import Image, ImageDraw, ImageFont

try:
    import websockets
except ImportError:
    sys.exit("需要 websockets：pip3 install websockets")

SERIAL = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("ANDROID_SERIAL", "")
if not SERIAL:
    sys.exit("用法: record-demo.py <设备序列号>")

PORT = "8791"
BASE = f"http://127.0.0.1:{PORT}"
PKG = "com.smaliscope.testapp"
ROOT = pathlib.Path(__file__).resolve().parent.parent
BIN = ROOT / "build/install/smaliscope/bin/smaliscope"
OUT = ROOT / "docs/images"
FRAMES = pathlib.Path("/private/tmp/claude-501/-Users-cj-code-github-SmaliScope/"
                      "ea7690ac-879d-4f81-8e07-c76cb3d6fb1d/scratchpad/frames")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")

WB_W, WB_H = 1500, 940          # 工作台视口
CANVAS_H = 900                  # 每帧统一高度
BAR_H = 64                      # 顶部说明条

def adb(*args, binary=False):
    p = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True,
                       timeout=60, env={**os.environ, "ANDROID_SERIAL": SERIAL})
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")

def wake():
    # MIUI 会息屏/回锁，录制中周期性唤醒，保证真机帧不是全黑。
    adb("shell", "su", "-c", "input keyevent KEYCODE_WAKEUP")

def api(verb, path, **params):
    url = BASE + path + ("?" + urllib.parse.urlencode(params) if params else "")
    req = urllib.request.Request(url, method=verb)
    with urllib.request.urlopen(req, timeout=180) as r:
        body = r.read().decode()
    return json.loads(body) if body else None

def wait_suspended(timeout=90):
    end = time.time() + timeout
    while time.time() < end:
        st = api("GET", "/api/state")
        if st and st["status"] == "suspended":
            return st
        time.sleep(0.3)
    return None


def load_font(size):
    for p in ["/System/Library/Fonts/PingFang.ttc",
              "/System/Library/Fonts/STHeiti Medium.ttc",
              "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"]:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()

FONT = load_font(30)
FONT_SM = load_font(21)


def compose(phone_png: bytes, wb_png: bytes, caption: str, idx: int):
    phone = Image.open(pathlib.Path(_dump(phone_png, "p"))).convert("RGB")
    # 裁到内容区（状态栏 + 标题 + 按钮 + 结果都在顶部约 42% 内），
    # 去掉下方大片空白，手机面板不至于是细长空条。
    phone = phone.crop((0, 0, phone.width, int(phone.height * 0.42)))
    wb = Image.open(pathlib.Path(_dump(wb_png, "w"))).convert("RGB")

    def scaled(im, h):
        return im.resize((max(1, round(im.width * h / im.height)), h), Image.LANCZOS)

    ph = scaled(phone, CANVAS_H)
    wbh = scaled(wb, CANVAS_H)

    gap, pad = 24, 24
    W = pad + ph.width + gap + wbh.width + pad
    H = BAR_H + pad + CANVAS_H + pad + 34
    canvas = Image.new("RGB", (W, H), (18, 21, 27))
    d = ImageDraw.Draw(canvas)

    # 顶部说明条
    d.rectangle([0, 0, W, BAR_H], fill=(26, 30, 37))
    d.text((pad, BAR_H // 2), f"● {caption}", font=FONT, fill=(216, 222, 233), anchor="lm")

    y = BAR_H + pad
    canvas.paste(ph, (pad, y))
    canvas.paste(wbh, (pad + ph.width + gap, y))
    # 每一侧的标注
    d.text((pad + ph.width / 2, y + CANVAS_H + 6),
           "真机 · Redmi Note 13（Android 14, 未 root 改造原包）",
           font=FONT_SM, fill=(139, 149, 165), anchor="ma")
    d.text((pad + ph.width + gap + wbh.width / 2, y + CANVAS_H + 6),
           "SmaliScope 工作台", font=FONT_SM, fill=(139, 149, 165), anchor="ma")

    p = FRAMES / f"f{idx:02d}.png"
    canvas.save(p)
    print(f"  帧 {idx}: {caption}  ({W}x{H})")
    return p

def _dump(b: bytes, tag):
    p = FRAMES / f"_{tag}.png"
    p.write_bytes(b)
    return str(p)


class Cdp:
    def __init__(self, ws): self.ws, self._id = ws, 0
    async def send(self, method, **params):
        self._id += 1
        await self.ws.send(json.dumps({"id": self._id, "method": method, "params": params}))
        while True:
            m = json.loads(await self.ws.recv())
            if m.get("id") == self._id:
                if "error" in m: raise RuntimeError(f"{method}: {m['error']}")
                return m.get("result", {})
    async def js(self, expr):
        r = await self.send("Runtime.evaluate", expression=expr, awaitPromise=True, returnByValue=True)
        return r.get("result", {}).get("value")
    async def shot(self) -> bytes:
        r = await self.send("Page.captureScreenshot", format="png")
        return base64.b64decode(r["data"])


async def main():
    FRAMES.mkdir(parents=True, exist_ok=True)
    for f in FRAMES.glob("*.png"): f.unlink()
    OUT.mkdir(parents=True, exist_ok=True)

    # 1) 拉起指向真机的工作台
    print("· 启动工作台（指向真机）…")
    serve = subprocess.Popen([str(BIN), "serve", "--port", PORT],
                             env={**os.environ, "ANDROID_SERIAL": SERIAL},
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(5)

    # 2) 起 headless Chrome
    profile = FRAMES / "chrome"
    chrome = subprocess.Popen(
        [CHROME, "--headless=new", "--disable-gpu", "--no-first-run", "--hide-scrollbars",
         f"--user-data-dir={profile}", "--remote-debugging-port=9344",
         f"--window-size={WB_W},{WB_H}", "about:blank"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    page_ws = None
    for _ in range(60):
        try:
            with urllib.request.urlopen("http://127.0.0.1:9344/json", timeout=2) as r:
                for t in json.load(r):
                    if t.get("type") == "page" and t.get("webSocketDebuggerUrl"):
                        page_ws = t["webSocketDebuggerUrl"]; break
            if page_ws: break
        except Exception: pass
        time.sleep(0.25)
    if not page_ws: sys.exit("拿不到 Chrome 页面调试地址")

    try:
        async with websockets.connect(page_ws, max_size=64 * 1024 * 1024) as ws:
            c = Cdp(ws)
            await c.send("Page.enable"); await c.send("Runtime.enable")
            await c.send("Emulation.setDeviceMetricsOverride",
                         width=WB_W, height=WB_H, deviceScaleFactor=2, mobile=False)
            await c.send("Page.navigate", url=BASE)
            await asyncio.sleep(3)

            idx = 0
            async def frame(caption, hold=2):
                nonlocal idx
                wake()
                await asyncio.sleep(0.4)
                phone = adb("exec-out", "screencap", "-p", binary=True)
                wb = await c.shot()
                p = compose(phone, wb, caption, idx)
                for _ in range(hold):            # 每个逻辑步骤停留 hold 帧
                    (FRAMES / f"seq{idx:03d}.png").write_bytes(p.read_bytes()); idx += 1

            # 3) 准备：载入应用、选方法、下断点（都通过前端点，SSE 会驱动 UI）
            print("· 载入应用并选中 Calc.compute…")
            api("POST", "/api/session", pkg=PKG)
            m = api("GET", "/api/method", **{"class": f"{PKG}.Calc", "method": "compute", "sig": "(II)I"})
            mul = next(i for i in m["instructions"] if i["opcode"] == "mul-int")
            # 让前端也切到这个方法
            await c.js(f"selectClass('{PKG}.Calc')"); await asyncio.sleep(1.2)
            await c.js("(()=>{const b=[...document.querySelectorAll('#methodList .item')]"
                       ".find(x=>x.textContent.startsWith('compute'));b&&b.click();})()")
            await asyncio.sleep(1.2)
            bp = api("POST", "/api/bp", **{"class": f"{PKG}.Calc", "method": "compute",
                                           "sig": "(II)I", "pc": mul["dexPc"]})
            bp_id = bp["id"]
            await asyncio.sleep(1.0)

            # 开场帧：先让 App 正常显示一帧，证明这是台真在跑普通 App 的手机
            adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
            time.sleep(2.5)
            await frame("真机上一个普通 App；工作台里已给 compute() 下好断点", hold=3)

            # 4) 挂起启动
            print("· 挂起启动，等待命中…")
            api("POST", "/api/start")
            st = wait_suspended()
            if not st: sys.exit("断点未命中（真机可能息屏或被拦截）")
            await asyncio.sleep(1.5)            # 等 SSE 推到前端
            await frame("点开始调试：App 挂起重启，在 compute(3,4) 命中，p1=3 p2=4", hold=3)

            # 5) 单步几次
            for cap in ["单步：mul-int 执行，v2 得到 p1×v1",
                        "单步：add-int/2addr，v0 累加",
                        "单步：循环计数 v1 自增",
                        "单步：goto 跳回循环头（走过的路已着色）"]:
                api("POST", "/api/control", action="over")
                s = wait_suspended(30)
                if not s: break
                await asyncio.sleep(1.2)
                await frame(cap)

            # 6) 清掉断点再继续 → onCreate 才能跑完、真机画出结果
            #    断点在循环体内，不先删掉的话 resume 会立刻又命中下一圈，App 永远画不出来。
            print("· 清断点、继续运行，等真机画出结果…")
            api("POST", "/api/bp/remove", id=bp_id)
            await asyncio.sleep(0.8)
            api("POST", "/api/control", action="resume")
            time.sleep(3.5)
            await frame("清掉断点、点「继续」：App 恢复运行，真机画出 compute=17", hold=4)

    finally:
        chrome.terminate()
        serve.terminate()
        shutil.rmtree(profile, ignore_errors=True)

    # 7) 编码：mp4（清晰）+ gif（README 内联）
    print("· 编码 mp4 / gif…")
    seq = sorted(FRAMES.glob("seq*.png"))
    # 统一尺寸（compose 每帧宽度可能因手机/工具比例略有差异，取第一帧尺寸对齐）
    w0, h0 = Image.open(seq[0]).size
    w0 -= w0 % 2; h0 -= h0 % 2
    listfile = FRAMES / "list.txt"
    listfile.write_text("".join(f"file '{p}'\nduration 0.66\n" for p in seq) +
                        f"file '{seq[-1]}'\n")
    mp4 = OUT / "realdevice-demo.mp4"
    subprocess.run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(listfile),
                    "-vf", f"scale={w0}:{h0}:force_original_aspect_ratio=decrease,"
                           f"pad={w0}:{h0}:(ow-iw)/2:(oh-ih)/2:color=0x12151b,format=yuv420p",
                    "-r", "20", str(mp4)], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    gif = OUT / "realdevice-demo.gif"
    gw = 1100
    pal = FRAMES / "pal.png"
    vf = f"fps=12,scale={gw}:-1:flags=lanczos"
    subprocess.run(["ffmpeg", "-y", "-i", str(mp4), "-vf", f"{vf},palettegen=max_colors=128",
                    str(pal)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["ffmpeg", "-y", "-i", str(mp4), "-i", str(pal),
                    "-lavfi", f"{vf}[x];[x][1:v]paletteuse=dither=bayer",
                    str(gif)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f"\n完成：\n  {mp4}  ({mp4.stat().st_size//1024} KB)\n  {gif}  ({gif.stat().st_size//1024} KB)")


if __name__ == "__main__":
    if not os.path.exists(CHROME): sys.exit(f"没找到 Chrome：{CHROME}")
    asyncio.run(main())
