#!/usr/bin/env python3
"""走 HTTP API 的端到端验证：载入 → 下断点 → 挂起启动 → 命中 → 读寄存器 → 单步 → 时间线 → 对象图"""
import json, time, urllib.request, urllib.parse, sys

PORT = sys.argv[1] if len(sys.argv) > 1 else "8080"
BASE = f"http://127.0.0.1:{PORT}"

def call(verb, path, **params):
    url = BASE + path + ("?" + urllib.parse.urlencode(params) if params else "")
    req = urllib.request.Request(url, method=verb)
    with urllib.request.urlopen(req, timeout=120) as r:
        body = r.read().decode()
    return json.loads(body) if body else None

def wait_suspended(timeout=60):
    end = time.time() + timeout
    while time.time() < end:
        st = call("GET", "/api/state")
        if st and st["status"] == "suspended":
            return st
        time.sleep(0.4)
    return None

fails = []
def check(name, cond, detail=""):
    print(("  ✅ " if cond else "  ❌ ") + name + (f"  {detail}" if detail else ""))
    if not cond: fails.append(name)

print("1) 设备与应用探测")
b = call("GET", "/api/bootstrap")
check("发现在线设备", b["ok"], b.get("serial", ""))
target = [a for a in b["apps"] if a["pkg"] == "com.smaliscope.testapp"]
check("测试应用在设备上", bool(target))
# 可调试标记 = 有进程且该进程在 adb jdwp 列表里。应用没在跑时它本来就该是 false
# （刚重装过就是这种情况），此时不该判失败——后面的 /api/start 会把它拉起来。
if target and target[0]["pid"] is None:
    print("     · 应用当前未运行，可调试标记要等启动后才为真，跳过该项")
else:
    check("运行中的测试应用被识别为可调试", bool(target) and target[0]["debuggable"])

print("2) 载入 APK 并做静态分析")
s = call("POST", "/api/session", pkg="com.smaliscope.testapp")
check("APK 解析成功", s["ok"], f"{s['classCount']} 个类")

m = call("GET", "/api/method", **{"class": "com.smaliscope.testapp.Calc",
                                  "method": "compute", "sig": "(II)I"})
check("拿到方法模型", m is not None and len(m["instructions"]) > 0,
      f"{len(m['instructions'])} 条指令 / {len(m['blocks'])} 个基本块")
check("指令带中文解释", any(i["doc"] for i in m["instructions"]))
mul = next((i for i in m["instructions"] if i["opcode"] == "mul-int"), None)
check("数据流读写集正确", mul is not None and len(mul["reads"]) == 2 and len(mul["writes"]) == 1,
      f"reads={mul['reads']} writes={mul['writes']}" if mul else "")

print("3) 下断点并挂起启动")
bp = call("POST", "/api/bp", **{"class": "com.smaliscope.testapp.Calc",
                                "method": "compute", "sig": "(II)I", "pc": mul["dexPc"]})
check("断点已登记", bp["dexPc"] == mul["dexPc"])
call("POST", "/api/start")

st = wait_suspended()
check("断点命中", st is not None and st["reason"] == "断点命中",
      st["message"] if st else "超时")
if not st:
    sys.exit(1)

f = st["frames"][0]
check("停在正确位置", f["method"] == "compute" and f["dexPc"] == mul["dexPc"],
      f"{f['fqcn']}.{f['method']} @ {f['dexPc']}")
regs = {r["name"]: r for r in f["registers"]}
check("参数寄存器读出实参 compute(3,4)",
      regs["p1"]["value"] == "3" and regs["p2"]["value"] == "4",
      f"p1={regs['p1']['value']} p2={regs['p2']['value']}")
check("this 被识别", regs["p0"]["hint"] == "this", regs["p0"]["value"])
check("deopt 提示已给出", st["deoptWarning"])

print("4) 指令级单步与寄存器 diff")
seen_pcs = [f["dexPc"]]
changed_any = False
for _ in range(6):
    call("POST", "/api/control", action="over")
    time.sleep(0.5)
    st = wait_suspended(20)
    if not st: break
    fr = st["frames"][0]
    seen_pcs.append(fr["dexPc"])
    if any(r["changed"] for r in fr["registers"]):
        changed_any = True
check("单步逐条推进", len(seen_pcs) >= 6, " → ".join(map(str, seen_pcs)))
check("检测到寄存器变化", changed_any)
check("走到了循环回边", len(set(seen_pcs)) < len(seen_pcs), "说明沿 goto 绕回了循环头")

print("5) 执行时间线")
tl = call("GET", "/api/timeline")
check("时间线记录了快照", len(tl) >= 6, f"{len(tl)} 个快照")
check("快照含寄存器状态", bool(tl[-1]["registers"]))

print("6) 对象图")
obj_reg = next((r for r in st["frames"][0]["registers"] if r["expandable"]), None)
if obj_reg:
    node = call("GET", "/api/object", id=obj_reg["objectId"])
    names = [x["name"] for x in node["fields"]]
    check("对象字段可展开", len(node["fields"]) > 0, f"{node['label']}: {names}")
    check("已过滤 ART 内部字段", not any(n.startswith("shadow$") for n in names))
else:
    check("对象图", False, "没有可展开的寄存器")

print("7) Java 视图")
j = call("GET", "/api/java", **{"class": "com.smaliscope.testapp.Calc"})
check("jadx 反编译成功", j["ok"] and "public int compute" in j["code"])

print("8) 收尾")
call("POST", "/api/control", action="resume")
print()
print(("全部通过" if not fails else f"失败 {len(fails)} 项: {fails}"))
sys.exit(1 if fails else 0)
