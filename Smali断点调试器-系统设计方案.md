# SmaliScope —— 面向新手的 DEX/smali 指令级断点调试器 · 系统设计方案

> 版本：v1.0（设计稿）
> 定位：USB 直连、开箱即用的 Android 应用 smali 动态调试器，核心卖点为「实时可视化调试过程」
> 内核：JDWP 指令级断点（Location.index = dex_pc）+ dexlib2 静态解析与类型推导 + jadx Java 视图
> 明确边界：不考虑壳/加固脱壳；不做 Frida 式 hook；不做插件生态与研究平台化——只把「断点单步」这一件事做到新手能用、能看懂

---

## 目录

1. 项目概述与设计目标
2. 技术选型与核心原理
3. 系统总体架构
4. 核心子系统详细设计
5. 实时可视化设计（核心）
6. 新手友好设计
7. UI 信息架构与页面详设
8. 数据模型
9. 失败路径与错误引导
10. 工程结构与构建发布
11. 实施路线图
12. 风险分析与对策
13. 附录 A：JDWP 命令集映射
14. 附录 B：smali 指令词典结构

---

## 1. 项目概述与设计目标

### 1.1 背景

现有 Android 动态调试工具对新手极不友好：smalidea（IntelliJ 插件）配置繁琐、无中文引导、报错晦涩；JEB/JADX 只能反编译看静态；Frida 是方法级 hook，看不到「一条 smali 指令执行前后寄存器怎么变」。新手真正想要的是一个**能像看动画一样看懂字节码执行过程**的工具——插上手机、点应用名、点红点下断点、单步，然后**亲眼看到 v0 从 0 变成 1**。

本项目（代号 **SmaliScope**）就是为这个诉求设计的。它不追求能力全，追求**把指令级调试的门槛降到零**。

### 1.2 设计目标（验收口径）

| # | 目标 | 验收标准 |
|---|------|----------|
| G1 | 零配置接入 | 插 USB → 选应用 → 自动改造+挂起+attach，全程无需理解 debuggable/adb forward/JDWP |
| G2 | 指令级断点 | 可在任意一条 smali 指令上下断点并命中，不依赖 `.line` 调试信息 |
| G3 | 实时可视化 | 每次单步后，变化的寄存器高亮、执行指针移动、数据流箭头刷新，均在同一帧内呈现 |
| G4 | 看得懂 | Java/smali 双视图同步；指令悬浮中文解释；寄存器带类型与值，不暴露 JDWP tag/slot 概念 |
| G5 | 错得住 | 四类高频失败（签名冲突/签名校验/类未加载/deopt 变慢）全部有中文引导，内核层自动兜底 |

### 1.3 非目标（本阶段）

- 不处理加固壳（用户自备已脱壳或未加固的 APK）；
- 不做 Frida hook、不做内存 patch、不做流量抓包；
- 不做多设备集控、不做 Web 研究平台、不做 MCP/Agent；
- 第一版不做寄存器写入、条件断点、表达式求值（见 §11 路线图）。

---

## 2. 技术选型与核心原理

### 2.1 为什么必须是 JDWP（而非 Frida / ptrace）

Dalvik/ART 内置 JDWP 支持，且 **JDWP `Location.index` 在 Android 上直接等于 dex 字节码偏移**（单位 code unit = 2 字节），不是 Java 源码行号：

```
Location = { byte typeTag, long classID, long methodID, long index }
                                                          └── dex_pc
```

这意味着可以在**任意一条 smali 指令**上下断点。ART 内部断点比对的就是 `dex_pc`，与调试信息无关。这是 Frida（只能方法级 hook）和纯 native ptrace（要自己解析解释器状态）都做不到的。指令级 = smali 级，这正是本项目的立身之本。

### 2.2 为什么必须是 dexlib2

JDWP 只给你 `(classID, methodID, dex_pc)` 这种运行时裸数据，要把它翻译成人能看的 smali、要推导寄存器类型、要算单步后继，全靠静态解析：

- **smali 反汇编 + dex_pc 偏移表**：把 JDWP 的 `index` 映射到具体 smali 行；
- **寄存器类型推导**（最关键）：JDWP `StackFrame.GetValues` 要求 `(slot, tag)` 二元组，但 **dex 寄存器是无类型的**——同一个 v0 上一条指令是 int、下一条可能是 object，tag 猜错会读到垃圾。dexlib2 的 `MethodAnalyzer` 做数据流分析，能给出每条指令处每个寄存器的推导类型，这是唯一正解；
- **控制流分析**：构建基本块、算指令后继，供指令级单步和 CFG 可视化使用。

Python 生态（androguard）解析慢且无等价 MethodAnalyzer；Rust/Go 的 dex 库残缺。**dexlib2 是唯一成熟选择，这决定了内核必须跑在 JVM 上。**

### 2.3 debuggable 启用（按新手友好度排优先级）

JDWP 只对「debuggable」的进程开放。**「开箱即用」的关键是：让工具自己探测环境、自动选最省事的路径，用户永远不接触 `ro.debuggable`、Magisk、adb 这些词。** 优先级从高到低：

- **P0 已 debuggable —— 零配置（主推新手路径）**：**模拟器（AVD 非 Play 镜像 / 多数三方模拟器）默认 `ro.debuggable=1`**，所有 App 天生可 JDWP 调，无需任何设置。这是唯一真正「开箱即用」的场景，应作为**新手默认推荐环境**，文档/首启向导直接引导用户用模拟器起步。真机若是 userdebug/eng ROM 同理。工具探测到此情况 → 跳过一切准备，直连调试。

- **P1 真机 root —— 工具自动准备，一次重启**：真机 root 但 `ro.debuggable=0` 时，**由工具（而非用户手动）**完成设置：先试免重启快路径（`resetprop` + 强杀目标 App 重启 + 探测 `adb jdwp` 是否出现目标）；快路径不生效则工具用 `magisk --install-module` **自动装入自带的持久化模块**，弹一句「需重启一次，现在重启？」→ `su -c reboot`。全程零手动刷入、不开 Magisk 面板。此后永久生效。
  - 进阶可选：Zygisk 模块只给目标 App fork 时打 `FLAG_DEBUGGABLE`，避免全局 `ro.debuggable` 被反检测读到（见 §12）。

- **P2 非 root —— 重打包回退（默认不走）**：ARSCLib 改 `android:debuggable` + apksig 重签名 + 安装。仅无 root 且非模拟器时降级至此，签名相关风险随之回归（见 §9 回退行）。

⚠️ 无论哪条路径，**JDWP 指令级断点内核（§4.3 起）完全一致**——路径只决定「怎么让进程可调」，不改变「怎么调」。

### 2.4 技术栈总览

| 层 | 选型 | 说明 |
|----|------|------|
| dex 解析 / 类型推导 / CFG | `com.android.tools.smali:smali-dexlib2` | 唯一选择，内核基石 |
| JDWP 协议引擎 | 自研（裸 NIO / Netty） | 约 1500 行，比套 JDI 更可控 |
| Java 视图 | `io.github.skylot:jadx-core` | 按需反编译，新手友好核心 |
| debuggable 启用（主） | Magisk `resetprop ro.debuggable 1`（经 `su`） | root 主路径，免重打包 |
| AXML 改造（回退） | `io.github.reandroid:ARSCLib` | 仅非 root 回退时用 |
| APK 签名（回退） | `com.android.tools.build:apksig` | 仅非 root 回退时用 |
| adb 通信 | 直连 `localhost:5037` adb server 协议 | 不用 `Runtime.exec`，稳定；含 `su`/`shell` 执行 |
| 内核语言 | Kotlin/JVM | dexlib2/jadx 都在 JVM |
| 前端 | 本地 Web UI（SVG/Canvas + WebSocket） | 见 §2.5 |

### 2.5 架构决策：JVM 内核 + 本地 Web 前端

内核必须在 JVM（dexlib2 约束）。前端有两个选项：

- **Compose Desktop**：单进程、部署简单，但 CFG/数据流/动画这类富可视化实现成本高；
- **本地 Web UI（推荐）**：Kotlin 内核起一个本地 HTTP + WebSocket 服务，前端用浏览器技术画可视化（SVG 画 CFG、Canvas 画数据流动画、CSS 过渡做寄存器高亮），**实时事件经 WebSocket 推流**。

鉴于本项目把「实时可视化」作为头号卖点，选**本地 Web 前端**——可视化生态成熟，且与参考文档（Passionfruit/Starfruit）的「后端 + Web 工作台」形态一致。打包时用 jpackage 内嵌 JRE，浏览器指向 `127.0.0.1`，对用户仍是「双击即用」的桌面应用体验。

---

## 3. 系统总体架构

```
┌──────────────────── 本地 Web 前端（浏览器 127.0.0.1）────────────────────┐
│  设备/应用选择  │  双视图（Java│smali）  │  寄存器面板  │  CFG/数据流画布  │
│                          ↑ WebSocket 实时事件流 ↓                          │
├──────────────────────────── Kotlin/JVM 内核 ─────────────────────────────┤
│  会话编排  ─────────────────────────────────────────────────────────────  │
│  ├── APK 改造流水线（ARSCLib patch → apksig 签名 → adb install）           │
│  ├── adb 连接层（adb server 协议：devices / transport / forward / shell）  │
│  ├── JDWP 协议引擎（握手 / packet 编解码 / 事件循环）                       │
│  ├── 断点引擎（pending 断点 + ClassPrepare 兜底 + 命中分发）               │
│  ├── 单步引擎（后继计算 + 临时断点 + step into/over/out）                  │
│  ├── 帧/寄存器/对象读取（GetValues + 类型推导 + 对象图展开）               │
│  └── 静态分析（dexlib2：反汇编 / dex_pc 偏移表 / CFG / MethodAnalyzer）    │
│  └── 反编译服务（jadx-core：按需 Java 视图 + 类树）                        │
├──────────────────────────────────────────────────────────────────────────┤
│                    adb（platform-tools）── USB ── 手机 ART                  │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 4. 核心子系统详细设计

### 4.1 首启向导与 debuggable 启用子系统（开箱即用核心）

用户视角只有「选设备 → 选应用 → 开始」三步，所有环境判断由**首启向导自动完成**，落地 §2.3 的优先级：

```
探测环境
  ├─ 是模拟器 / ro.debuggable 已为 1  ──────────────→ [P0] 直接进调试，零准备
  ├─ 真机 root：
  │    ├─ 试免重启快路径（resetprop + 强杀重启 + 探测 adb jdwp）
  │    │      命中 ──────────────────────────────→ 进调试，无重启
  │    └─ 未命中 → 自动 magisk --install-module 自带模块
  │                 → 弹「需重启一次」→ su -c reboot ──→ 重启后永久生效 [P1]
  └─ 非 root、非模拟器  ──────────────────────────→ [P2] 重打包回退（提示影响）
```

- **每次调试（P0/P1 就绪后）**：`su -c "pm path <pkg>"` 拿 `base.apk`（含 split）供 dexlib2 静态解析；`am set-debug-app -w <pkg>` 挂起启动 → attach。**原包不动，无改造、无签名、无重装。**
- **P2 回退**：`pm path` → `adb pull base.apk` → ARSCLib 写 `android:debuggable=true`（resource id `0x0101000f`）→ apksig 重签名 → `adb install -r`（签名变化时先卸载，见 §9 回退行）。

向导对用户只呈现自然语言状态（「正在准备设备…」「需要重启一次，现在重启？」），**不暴露 root/resetprop/Magisk/adb 术语**。P0 路径下从插入到断点，用户一个技术名词都不会遇到。

**顺带干掉的两个新手杀手**：

- **adb/USB 驱动地狱**（Windows 上最劝退）：模拟器走 `127.0.0.1` **完全不需要 USB 驱动**；真机在 root 下可由工具 `su -c "setprop service.adb.tcp.port 5555; stop adbd; start adbd"` 转 **无线 adb**，或用 Android 11+ 的无线调试配对码。adb 二进制由工具自带/自动下载，不让用户装 platform-tools。
- **找不到入口类**：预设断点模板（「断在应用入口」「断在所有 Activity.onCreate」）+ jadx Java 视图兜底，新手不必自己翻包名。

### 4.2 adb 连接层

直连 `localhost:5037`，走 adb server 二进制协议，避免 `Runtime.exec` 解析文本输出的脆弱性：

```
host:devices              → 设备清单
host:transport:<serial>   → 切到目标设备
shell:pm path <pkg>       → 执行 shell
host:forward:tcp:8700;jdwp:<pid>   → 端口转发到 JDWP socket
```

首次运行自动从官方下载 platform-tools（比内嵌小且始终最新）。

### 4.3 JDWP 协议引擎

**挂起启动**是新手能顺利断到 `onCreate` 的关键：

```
am set-debug-app -w <pkg>   # 应用启动时挂起，等待调试器
am start -n <pkg>/<activity>
adb jdwp                     # 输出可调试进程 pid
adb forward tcp:8700 jdwp:<pid>
```

握手为 14 字节 ASCII `JDWP-Handshake`，服务端原样回。之后是标准 packet：

```
Command: [length:4][id:4][flags:1][cmdSet:1][cmd:1][data…]
Reply:   [length:4][id:4][flags:1][errorCode:2][data…]
```

事件循环单独一条协程：解包 `Event.Composite`（cmdSet 64）→ 转成内核事件对象 → 经 Kotlin Flow 推给会话编排层 → 再经 WebSocket 推给前端。需实现的命令集见附录 A。

### 4.4 断点引擎

下断点分两种情况，**新手完全无感**：

- **类已加载**：直接 `EventRequest.Set(eventKind=BREAKPOINT, modifier=location)`；
- **类未加载**（新手最容易懵的场景）：内核自动转为 **pending 断点**——先注册 `CLASS_PREPARE`（按类名 pattern 过滤），在类加载回调里补下真实断点。用户只看到「断点已设置」，看不到这套机制。

⚠️ 一旦某方法被下断点，ART 会把它 deoptimize 回解释器执行，速度骤降——这是正常现象，UI 需明确标注（见 §9）。

### 4.5 单步引擎

JDWP 原生 `STEP` 事件按行走，无 line table 时退化为整个方法——**不用它**。自研指令级单步：

1. 解析当前 `dex_pc` 处指令，用 dexlib2 算出所有后继（顺序下一条 + 跳转目标 + 异常处理器入口）；
2. 在这些位置下临时断点；
3. Resume；
4. 命中后清掉全部临时断点。

- **step-over**：遇到 `invoke-*` 时，只在返回后的下一条设临时断点（不进方法体）；
- **step-into**：额外用 `METHOD_ENTRY` 事件（限定 class 范围，否则事件洪水）；
- **step-out**：在当前帧返回地址处设临时断点。

### 4.6 帧 / 寄存器 / 对象读取

- **调用栈**：`ThreadReference.Frames` 拿全部帧的 `(frameID, location)`；
- **寄存器**：对每个 vreg，用 dexlib2 `MethodAnalyzer.getPreInstructionRegisterType(reg)` 推导类型 → 转成对应 JDWP tag → `StackFrame.GetValues(slot, tag)`。slot 与 vreg 号在 ART 上基本对应，但不同 Android 版本有 `DemangleSlot` 差异，需针对目标版本实测校准；
- **对象展开**：object 型寄存器 → `ObjectReference.ReferenceType` → `ReferenceType.Fields` → `ObjectReference.GetValues` 读字段，递归构建对象图。

### 4.7 反编译服务

jadx-core **按需**反编译当前打开的类（全量在混淆大 App 上要几十秒）：

```kotlin
val jadx = JadxDecompiler(JadxArgs().apply { setInputFile(apk) }).also { it.load() }
val javaCode = jadx.classes.first { it.fullName == fqcn }.code
```

Java 视图只用来「看懂逻辑」，**断点仍设在 smali 侧**（Java 行↔dex_pc 映射对 MVP 是过度工程）。

---

## 5. 实时可视化设计（核心）

这是产品与所有现有工具拉开差距的地方。目标：**让新手把字节码执行「看成动画」**。每次 suspend（断点命中/单步），内核在一次往返内取齐当前帧的位置、寄存器、栈，经 WebSocket 推给前端，前端在同一帧内更新以下所有视图。

### 5.1 执行指针 + 寄存器实时变化高亮

- **执行指针**：当前 `dex_pc` 对应的 smali 行高亮 + 左侧箭头，随单步平滑滚动；
- **寄存器 diff 高亮**：单步前快照寄存器值，单步后 diff，**变化的寄存器用颜色脉冲一次**（CSS transition），新值旁显示旧值残影 0.5s。这一条是整个可视化里对新手价值最高的——亲眼看到 `const/4 v0, 0x1` 让 v0 由空变 1。

### 5.2 数据流箭头

对当前高亮指令，dexlib2 静态解析其操作数，画出「读→写」箭头：

```
add-int v0, v1, v2
        ↑   └─┬─┘
       写      读        →  面板上 v1、v2 高亮为「源」，v0 高亮为「汇」，箭头连接
```

配合 §5.1 的实时值，新手能同时看到「哪些寄存器参与运算」和「运算结果」。纯静态推导，零运行时开销。

### 5.3 控制流图（CFG）+ 执行路径

- dexlib2 构建当前方法的基本块，前端用 SVG 渲染 CFG（块 = 节点，分支 = 边）；
- **当前块高亮**；已执行过的块与边**着色为「走过的路」**，未走的保持灰；
- 分支指令命中时，能直观看到「走了 then 还是 else」。这把抽象的跳转变成可见的路径。

### 5.4 调用栈可视化

栈帧渲染为竖向卡片堆，栈顶在上；点任一帧切换其寄存器视图（`StackFrame.GetValues` 按帧取）；step-into 时新卡片压入、step-out 时弹出，带进出动画。

### 5.5 对象图展开

object 寄存器点开为树/图：节点 = 对象，子节点 = 字段，可逐层展开（懒加载，展开时才 `GetValues`）。基本类型字段直接显值，引用字段可继续下钻。

### 5.6 执行轨迹时间线（time-travel-lite）

单步模式下，每步记录一个快照（dex_pc + 全寄存器值 + 栈深）到时间线。用户可**拖动时间轴回看任意历史步的寄存器状态**——注意：这是**回放已记录的快照**，非真正反向执行（JDWP 不支持逆执行）。对新手理解「值是怎么一步步变来的」极有帮助。

### 5.7 实时性架构

```
suspend 事件 → 内核批量取（location + frames + registers 一次往返）
            → Flow → WebSocket 推 JSON → 前端 diff → 各视图响应式更新
```

所有视图共享一份「当前调试状态」响应式 store，一次推送触发全部视图刷新，保证同一帧内一致。

---

## 6. 新手友好设计

| 设计点 | 做法 |
|--------|------|
| 一键接入 | §4.1 四步全自动，用户只做「插 USB + 选应用」两个动作 |
| 双视图 | Java 看逻辑、smali 下断点，左右同步滚动 |
| 指令词典 | 鼠标悬停任一 smali 指令弹中文说明，内置 200+ 条指令词典（见附录 B） |
| 隐藏底层概念 | 用户永不接触 dex_pc / JDWP tag / slot / ClassPrepare，UI 只说「行/寄存器/类型」 |
| 预设断点模板 | 「断在所有 Activity.onCreate」「断在应用入口」等一键模板，免去手动找类 |
| 寄存器友好显示 | 自动带类型名 + 值；String 显字面量、对象显 `类名@toString`、数组显长度 |
| 中文错误引导 | 见 §9，每个失败都有下一步指引而非英文栈 |

---

## 7. UI 信息架构与页面详设

```
┌ 顶栏 ───────────────────────────────────────────────────────────┐
│ SmaliScope │ [设备▾] │ [应用▾] │ 会话:●已附加 │ [⚙]              │
├ 主工作区（三栏 + 底部）──────────────────────────────────────────┤
│ 左：类树        │ 中：Java │ smali 双视图（可下断点）│ 右：寄存器  │
│  ▸ com.x        │  行号+执行指针+断点红点                │ v0 int=1  │
│    ▸ MainActivity                                       │ v1 String │
├─────────────────┴──────────────────────────────────────┴──────────┤
│ 底部可切换：CFG 画布 │ 调用栈 │ 对象图 │ 执行时间线 │ 日志         │
├────────────────────────────────────────────────────────────────────┤
│ [▶继续] [↓步入] [→步过] [↑步出] [■停止]    deopt 提示：解释执行中  │
└────────────────────────────────────────────────────────────────────┘
```

页面清单（MVP）：① 接入向导（选设备/应用 → 自动改造进度）；② 调试主界面（上图）；③ 设置（platform-tools 路径、目标 Android 版本 slot 校准、主题）。

---

## 8. 数据模型

```
DebugSession { id, device, pkg, patchedApk, jdwpPort, state }
LoadedClass  { fqcn, dexlib2Ref, jadxCache?, methods[] }
MethodInfo   { classID, methodID, name, sig,
               instructions[ {dexPc, smali, reads[], writes[]} ],
               basicBlocks[], analyzer(MethodAnalyzer) }
Breakpoint   { id, fqcn, methodSig, dexPc, state(pending|active|hit), hitCount }
Frame        { frameID, location(classID,methodID,dexPc), registers[] }
Register     { name(vN/pN), inferredType, value, changed:boolean }
StepSnapshot { seq, dexPc, registers[], stackDepth }   # 时间线用
```

---

## 9. 失败路径与错误引导

新手向工具的产品质量 = 错误文案质量。四类高频失败，内核层能兜底的自动兜底，兜不了的给中文引导：

**root 主路径下，签名相关的两类失败直接消失**（不重打包）。剩余失败多为 JDWP 运行时固有：

| 现象 | 原因 | 处理 |
|------|------|------|
| 未检测到 root | 无 su / 授权被拒 | 引导授予 root；或降级到非 root 重打包回退路径 |
| 环境准备后仍不可调 | `ro.debuggable` 未生效 | 检查 Magisk 模块是否装好、是否已重启；提示「resetprop 需重启一次」 |
| attach 后断不到 | 断点所在类还没加载 | **内核自动转 pending 断点 + ClassPrepare 兜底，用户无感** |
| 命中后卡顿 | ART deopt 到解释器 | UI 常驻提示：「已切换解释执行，速度会变慢，属正常现象」 |
| slot 读出乱值 | 目标 Android 版本 slot 偏移差异 | 设置页提供「按当前设备版本校准」一键动作 |
| App 拒启动/行为异常 | 反检测读到 `ro.debuggable=1` 或 root | 建议改用 Zygisk 逐 App 打 flag（§2.3 进阶），避免全局属性 |
| 仅回退路径：`INSTALL_FAILED` / 签名校验闪退 | 非 root 重打包改了签名 | 卸载重装二次确认；App 有签名校验时诚实降级 |

---

## 10. 工程结构与构建发布

```
smaliscope/
  core/            # Kotlin 内核
    adb/           # adb server 协议
    jdwp/          # 握手 + packet 编解码 + 事件循环 + 命令集
    breakpoint/    # 断点引擎 + pending 兜底
    stepping/      # 指令级单步
    frame/         # 帧/寄存器/对象读取 + 类型推导桥接
    static/        # dexlib2：反汇编 / dex_pc 表 / CFG / analyzer
    decompile/     # jadx 服务
    repack/        # ARSCLib patch + apksig 签名
    server/        # HTTP + WebSocket
  web/             # 前端：双视图 / 寄存器 / CFG / 数据流 / 时间线
  dict/            # smali 指令中文词典
  packaging/       # jpackage 配置（.exe/.dmg/.deb，内嵌 JRE）
```

发布：jpackage 出三平台安装包，内嵌 JRE，首次运行自动下载 platform-tools。

---

## 11. 实施路线图

| 阶段 | 目标 | 验收（分水岭） |
|------|------|----------------|
| M1 | JDWP 握手 + `VirtualMachine.Version` | 能连上并打印 VM 信息 |
| M2 | dexlib2 加载 APK，列类/方法 + dex_pc 偏移表 | dump 出带偏移的 smali |
| M3 | root 启用（Magisk `ro.debuggable`）+ 挂起 attach | 「点应用 → 自动断在入口」全自动跑通（非 root 重打包回退可后置） |
| M4 | ClassPrepare + 单断点命中 | **断在指定 smali 行**（架构分水岭） |
| M5 | 帧 + 寄存器读取（含类型推导） | 看到 v0..vN 的值和类型 |
| M6 | 指令级单步 + 寄存器 diff 高亮 | step into/over/out + **变化寄存器高亮** |
| M7 | jadx 双视图 + 指令词典 | Java/smali 同步 + 悬浮解释 |
| M8 | CFG + 数据流 + 调用栈 + 对象图 + 时间线 | 五大可视化全部落地 |
| M9 | 预设断点模板 + 四类错误引导 + 打包发布 | 新手可下载即用 |

M3、M4 是两个关键分水岭：M3 通说明「一键」链路成立，M4 通说明调试内核成立。

---

## 12. 风险分析与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| slot↔vreg 在不同 Android 版本有偏移 | 寄存器读错 | 内置各版本校准表 + 设置页一键实测校准 |
| deopt 导致慢/行为差异 | 体验/正确性 | UI 明确标注；仅对下断点的方法 deopt，范围可控 |
| 混淆 App 类名不可读 | 新手难定位 | 靠 jadx Java 视图 + 预设入口模板兜底 |
| 全局 `ro.debuggable=1` 被反检测读到 | 部分 App 拒启动/改行为 | 进阶提供 Zygisk 逐 App 打 `FLAG_DEBUGGABLE`，只影响目标、无全局副作用 |
| 依赖 root | 无 root 设备不可用主路径 | 保留非 root 重打包回退（签名风险随之回归，仅回退时存在） |
| jadx 传递依赖使包体变大 | 安装包几十 MB | 可接受；JRE 才是大头，jpackage 已裁剪 |
| 加固壳（虽为非目标）用户误用 | attach 后无有效 dex | 检测到壳特征时明确提示「本工具不处理加固」 |

---

## 附录 A：JDWP 命令集映射（需实现）

| Command Set | 用途 | 关键命令 |
|-------------|------|----------|
| VirtualMachine (1) | 连接/枚举/挂起恢复 | Version / AllClasses / ClassesBySignature / IDSizes / Resume / Suspend |
| ReferenceType (2) | 类元信息 | Methods / Fields / SourceFile |
| Method (6) | 方法字节码 | Bytecodes / LineTable / VariableTable |
| ObjectReference (9) | 对象读取 | ReferenceType / GetValues |
| ThreadReference (11) | 线程/栈 | Frames / FrameCount / Status |
| StackFrame (16) | 帧内寄存器 | GetValues / SetValues(二期) / ThisObject |
| EventRequest (15) | 事件注册 | Set / Clear（BREAKPOINT / CLASS_PREPARE / METHOD_ENTRY / STEP） |
| Event (64) | 服务端推送 | Composite |

## 附录 B：smali 指令词典结构

```json
{
  "invoke-virtual": {
    "cn": "调用虚方法（按对象实际类型分派）",
    "format": "invoke-virtual {vRegs}, method@ref",
    "reads": "参数寄存器列表",
    "writes": "结果需下一条 move-result 取回",
    "note": "新手常见：返回值不在指令里，看下一条 move-result*"
  },
  "const/4": {
    "cn": "把 4 位有符号立即数存入寄存器",
    "format": "const/4 vA, #+B",
    "reads": "—", "writes": "vA"
  }
}
```

词典驱动 §6 的悬浮解释与 §5.2 的读写高亮（reads/writes 字段同时供数据流箭头使用）。

---

*本方案为 v1.0 设计基线（M1–M9），聚焦「指令级断点 + 实时可视化 + 新手友好」三条主线，不做平台化扩展。*
