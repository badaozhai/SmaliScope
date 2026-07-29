#!/usr/bin/env python3
"""把 SmaliScope 当 MCP server 驱动一遍，模拟 agent 的真实用法。"""
import json, subprocess, sys, threading

BIN = "./build/install/smaliscope/bin/smaliscope"

proc = subprocess.Popen([BIN, "mcp"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE, text=True, bufsize=1)

def drain_stderr():
    for line in proc.stderr:
        sys.stderr.write("  [server] " + line)
threading.Thread(target=drain_stderr, daemon=True).start()

_id = 0
def rpc(method, params=None, notify=False):
    global _id
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        msg["params"] = params
    if not notify:
        _id += 1
        msg["id"] = _id
    proc.stdin.write(json.dumps(msg) + "\n")
    proc.stdin.flush()
    if notify:
        return None
    line = proc.stdout.readline()
    if not line:
        raise RuntimeError("server 关闭了连接")
    return json.loads(line)

def call(tool, **args):
    r = rpc("tools/call", {"name": tool, "arguments": args})
    if "error" in r:
        return f"[协议错误] {r['error']['message']}"
    res = r["result"]
    text = "\n".join(c["text"] for c in res["content"])
    return ("[isError] " if res.get("isError") else "") + text

fails = []
def check(name, cond, detail=""):
    print(("  ✅ " if cond else "  ❌ ") + name + (f"  {detail}" if detail else ""))
    if not cond: fails.append(name)

print("1) MCP 握手")
r = rpc("initialize", {"protocolVersion": "2025-06-18",
                       "capabilities": {}, "clientInfo": {"name": "test", "version": "0"}})
check("initialize 成功", "result" in r, r.get("result", {}).get("serverInfo", {}).get("name", ""))
check("协商到协议版本", r["result"]["protocolVersion"] == "2025-06-18")
check("声明了 tools 能力", "tools" in r["result"]["capabilities"])
check("带了给模型的使用说明", len(r["result"].get("instructions", "")) > 50)
rpc("notifications/initialized", notify=True)

print("2) 工具清单")
r = rpc("tools/list")
tools = r["result"]["tools"]
names = [t["name"] for t in tools]
check("工具已注册", len(tools) >= 15, f"{len(tools)} 个")
for need in ["list_apps", "load_app", "disassemble", "set_breakpoint",
             "start_debug", "step", "read_registers", "expand_object"]:
    check(f"有 {need}", need in names)
check("每个工具都有 inputSchema",
      all(t.get("inputSchema", {}).get("type") == "object" for t in tools))

print("3) ping")
check("ping 有响应", "result" in rpc("ping"))

print("4) 走一遍真实调试流程")
out = call("list_apps")
check("list_apps 找到可调试应用", "com.smaliscope.testapp" in out and "●" in out)

out = call("load_app", package="com.smaliscope.testapp")
check("load_app 成功", "解析出" in out, out.strip().splitlines()[0])

out = call("disassemble", **{"class": "Calc", "method": "compute"})
check("disassemble 带 dex_pc 与读写集", "dex_pc" in out and "读" in out and "基本块" in out)
mul_pc = None
for line in out.splitlines():
    if "mul-int" in line:
        mul_pc = int(line.split()[0]); break
check("找到 mul-int 的偏移", mul_pc is not None, f"dex_pc={mul_pc}")

out = call("set_breakpoint", **{"class": "Calc", "method": "compute", "dexPc": mul_pc})
check("set_breakpoint 成功", "已设置" in out, out.strip())

out = call("start_debug", timeoutMs=90000)
check("start_debug 命中断点", "命中" in out and "寄存器" in out)
check("读出了实参 compute(3,4)", "p1" in out and " 3" in out and "p2" in out)
print("     ---- start_debug 返回给模型的原文 ----")
for line in out.splitlines()[:14]:
    print("     " + line)
print("     ----")

out = call("step", mode="over", count=4)
check("step 多步返回轨迹", "轨迹" in out and "→" in out)
check("step 标出了变化的寄存器", "本步变化" in out or "单步完成" in out)

out = call("read_stack")
check("read_stack 返回调用栈", out.startswith("#0"))

out = call("read_registers")
check("read_registers 可用", "dex_pc" in out)

print("5) 错误处理")
out = call("set_breakpoint", **{"class": "NoSuchClass", "method": "x", "dexPc": 0})
check("未知类返回 isError 而非崩溃", out.startswith("[isError]"), out.strip()[:60])
out = call("no_such_tool")
check("未知工具有友好提示", "未知工具" in out)

call("stop_debug")
proc.stdin.close()
proc.wait(timeout=10)

print()
print("全部通过" if not fails else f"失败 {len(fails)} 项: {fails}")
sys.exit(1 if fails else 0)
