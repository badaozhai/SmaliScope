# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目是什么

面向新手的 DEX/smali **指令级**断点调试器：Kotlin/JVM 内核 + 本地 Web 工作台。
M1–M8 已全部实现并在模拟器上实测通过，M9 只差打包发布。设计基线见
`Smali断点调试器-系统设计方案.md`，实现现状与偏差见 `README.md`。

## 常用命令

```bash
./gradlew installDist                              # 构建（产物在 build/install/smaliscope/）
./gradlew packageApp                               # 打成自带 JRE 的本平台安装包（jpackage）
./zygisk/build.sh                                  # 编译 Zygisk 模块（需 NDK）
./gradlew test                                     # 单元测试（不需要设备）
./testapp/build.sh                                 # 构建自带的 debuggable 测试 APK
python3 scripts/e2e.py                             # Web 侧端到端回归（需设备 + 工作台已启动）
python3 scripts/mcp-e2e.py                         # MCP 侧端到端回归（需设备，自己拉起进程）
```

跑起来（**不要用 `./gradlew run`**，见下方「Gradle daemon 会继承沙箱网络限制」）：

```bash
./build/install/smaliscope/bin/smaliscope serve            # Web 工作台
./build/install/smaliscope/bin/smaliscope mcp              # MCP server（stdio）
./build/install/smaliscope/bin/smaliscope smoke            # JDWP 连通冒烟
./build/install/smaliscope/bin/smaliscope dump <包名> <类> <方法>
./build/install/smaliscope/bin/smaliscope debug <包名> <类> <方法> [步数] [into|over]
```

`debug` 子命令是改内核后最快的验证手段：一条命令走完「下断点 → 挂起启动 → 命中 → 单步打印寄存器」，
不必开浏览器。

## 平台事实（踩过坑，别重新推导）

这几条都是实测结论，改相关代码前先读：

- **Android 11+ 的 JDWP 是 OpenJDK 的 libjdwp，不是 ART 内置实现。** ART 自 R 起移除了内部 JDWP，
  改由 adbconnection 把 libjdwp 作为 JVMTI agent 注入目标进程（`VirtualMachine.Version` 的描述字段
  会写 "Java Debug Wire Protocol (Reference Implementation) version 1.8"）。
  对本项目的两个前提不变：`Location.index` 仍是 dex_pc，`StackFrame` 的 slot 仍是 dex 寄存器号。

- **读寄存器的 tag，ART 是拿 dex 调试信息里「声明」的类型来校验的，不是当前 dex_pc 上的实际类型。**
  d8 会把声明为 `int x` 的参数寄存器复用来存对象；此时按 MethodAnalyzer 推导出的引用类型去读，
  会被拒为 `TYPE_MISMATCH(34)`。`FrameReader.readOne` 用这个差异区分「确实读不了」和「类型对不上」，
  给出可理解的说明而不是垃圾值。

  **已用 `smaliscope audit` 实测量化，结论见 [docs/register-readability.md](docs/register-readability.md)：**
  局部寄存器 `vN` 在所有构建配置下 **100% 可读**；失败无一例外集中在被复用的参数寄存器 `pN` 上；
  最坏情况（R8 release 包）总可读率 96.3%。**产品成立，不需要改方案。**

  反直觉的一点：`-g:none`（完全无调试信息）反而 100% 可读，因为 ART 没有声明类型可以否决；
  而 `-g:lines,source`（**javac 默认**、也是多数 release 构建的选择，为了保留崩溃栈行号）
  会连带写入覆盖整个方法的参数类型，恰好落在最差的一档。改动读寄存器路径前先读那份实测报告。

- **「非 Play 镜像 → 任意应用可调」这个广为流传的说法在现代 Android 上是错的。**
  已逐条实测证伪（Android 14 google_apis userdebug 与 Android 16），见
  [docs/p0-path-findings.md](docs/p0-path-findings.md)：`ro.debuggable=1`、
  再加 `ro.force.debuggable=1`、重装应用、乃至 `am set-debug-app -w`，
  未带 `android:debuggable="true"` 的 release 包统统不出现在 `adb jdwp` 里；
  而同一台设备上带该标记的应用立刻可调。

  **判断能否调试的唯一可靠依据是「进程在不在 `adb jdwp` 列表里」，不是任何系统属性。**
  `EnvProbe.summary` 已按此改写——它以前会宣称「所有应用都能直接下断点」，那是对用户说假话。
  设计方案的 P0/P1 路径都不必再投入。要调未改造的第三方应用，选定的方案是 root 下用
  **Zygisk 模块在 fork 时置 `DEBUG_ENABLE_JDWP`**（ROADMAP 第 2 项），原包一字不动；
  **明确不采用重打包重签名**——改签名会让应用自带的签名校验失效、必须卸载重装丢数据，
  而且修改的是被研究对象本身。

- **`smali` 3.x 只发布在 Google Maven，Maven Central 上没有**（Central 上的 `org.smali:dexlib2` 停在 2.5.2）。
  `build.gradle.kts` 里的 `google()` 仓库是必需的。

- **`jadx-core` 单独用读不了 dex**：它只是反编译引擎，读 dex/apk 的能力在 `jadx-dex-input` 插件里。
  少了它 `jadx.classes` 是空表，且不报错——只表现为「找不到这个类」。

- **Gradle daemon 会继承启动时的沙箱网络限制**，导致 `./gradlew run` 起的子 JVM 连不上
  `127.0.0.1:5037`（表现为 `BindException: Can't assign requested address`）。
  用 `installDist` 生成脚本后直接跑，既避开这个问题也快得多。

## 架构

分层：浏览器前端 ─ SSE/HTTP ─ Kotlin 内核 ─ adb ─ 设备 ART。包划分见 README 的目录一节。

跨文件才能看明白的几处：

- **静态与运行时的桥在 `session/RuntimeIndex.kt`。** JDWP 只给 `(classID, methodID, dex_pc)`，
  静态侧只认 `(fqcn, 方法名, 签名, dex_pc)`，两边靠类签名和方法签名对上，结果必须缓存——每次单步都要翻译。

- **类型推导是地基，不是可选项。** dex 寄存器无类型，而 `StackFrame.GetValues` 要 `(slot, tag)`；
  tag 猜错不会报错、只会读到垃圾。`MethodModel.registerTypeAt` 经
  `MethodAnalyzer.getPreInstructionRegisterType` 拿到逐指令类型，`RegKind.jdwpTag` 决定读不读、怎么读。
  推不出类型的寄存器一律标为不可读，**不要为了「显示点什么」去猜 tag**。
  注意 `MethodAnalyzer` 会在真实指令前插一条虚拟的方法入口指令，`analyzedAt` 用长度差对齐。

- **单步是自研的，不用 JDWP 原生 STEP**（它按源码行走，无 line table 时退化成跨过整个方法）。
  `StepEngine` 用 dexlib2 算后继（顺序下一条 + 跳转目标 + 异常处理器入口）→ 下临时断点 → Resume →
  命中后清理。`StepPlan.accepts(depth)` 用栈深滤掉递归造成的误命中。
  step-into 直接在被调方法的 dex_pc 0 下断点（静态可解析），比 `METHOD_ENTRY` 干净且不会造成事件洪水。

- **JDWP 读线程绝不能发 JDWP 命令。** 回包要靠读线程自己读，在读线程里同步等回包会直接死锁。
  `DebugSession` 把事件一律转投单线程的 `eventExec` 处理。改事件路径时务必守住这条。

- **断点必须能在 attach 之前设好。** 用户是先选好位置再点「开始调试」的，那时还没有连接；
  `DebugSession.preSpecs` 先记下来，attach 时在 `vm.resume()` **之前**装上，否则应用早跑过断点位置了。
  类未加载时自动转 pending，靠 `CLASS_PREPARE` 在类加载回调里补下真实断点。

- **`session/Debugger.kt` 是与协议无关的门面，Web 工作台和 MCP server 都是它的薄壳。**
  两者共用同一份会话状态——一个进程里不能出现两套互不知情的调试状态。
  新增对外接口时，逻辑放 `Debugger`，`server/` 和 `mcp/` 只做格式转换。

- **MCP 的 stdout 是协议通道**，除 JSON-RPC 消息外不能往里写任何东西，日志一律走 stderr。
  另外 MCP 侧没有事件流，所以 `Debugger.actAndWait` 用 `stopSeq` 序号区分
  「新的一次停下」与「本来就停着」，让 agent 一次请求就能拿到结果而不必轮询。
  给 agent 的文本里必须写明寄存器为何不可读——模型把「不可读」当成「值是 0」会让后续推理全错。

- **断点编号由 `DebugSession` 统一发放**（`bpIdGen`），`BreakpointEngine.add` 接收外部 ID。
  因为断点可以在 attach 之前设下，两边各自发号会让用户先拿到的编号在连接建立后失效。

- **读寄存器一次往返批量取**（`FrameCmds.getValues` 收一批 slot），保证前端各视图同一帧内一致；
  批量失败才退化为逐个读，免得一个坏寄存器把整个面板打空。

## 约定

- **文档与所有用户可见文案用中文**；UI 层不暴露 dex_pc / JDWP tag / slot / ClassPrepare 这些词，
  只说「行 / 寄存器 / 类型」。错误提示给中文的下一步指引，不是英文异常栈。
- **`su` 的语法不统一，别硬编码 `su -c`**：Magisk / KernelSU / APatch 认 `su -c '<cmd>'`，
  而 AOSP userdebug 自带的 su 是 `su [用户] [命令]`，喂 `-c` 会报 invalid uid/gid。
  统一走 `AdbClient.suShell`（探测一次并缓存，兜底用两边都认的 `su root sh -c`）。
  另外**探测 root 方案与 Zygisk 必须经 su**：`/data/adb` 只有 root 能读，
  普通 shell 会静默拿到空结果从而误判成「没装」。
- **可选的大模型功能必须保持「没配 key 就不存在」**：`config/Settings.kt` 读
  `~/.smaliscope/config.properties`（环境变量优先），`llm.enabled` 为假时
  Web 界面不显示标签页、MCP 不注册 `explain_code` / `suggest_register_names`。
  边界写在 `explain/Explainer.kt` 的注释里：不进单步热路径、不替代类型推导、不做安全判断。
  改这块前先想清楚是否越界。
- **第三方依赖只有 dexlib2 / baksmali / jadx / kotlinx-coroutines**。HTTP、SSE、JSON 都是手写的极简实现，
  刻意不引序列化框架和 Web 框架——少一个依赖少一层版本纠纷。加依赖前先想清楚是否真的必要。
- 前端无框架，原生 JS + CSS + SVG。列表项用 `clickable()` 挂 `role`/`tabindex`，键盘和读屏可用。
- 里程碑落地后同步更新 `README.md` 的「已实现 / 尚未实现 / 与设计方案的偏差」三节。

## 明确的非目标（别顺手加）

不处理加固壳；不做 Frida 式 hook、内存 patch、流量抓包；不做多设备集控、Web 研究平台；
第一版不做寄存器写入、条件断点、表达式求值。设计原则是**只把「断点单步」这一件事做到新手能用、能看懂**。

设计方案原本还把「MCP/Agent 化」列为非目标，**这一条已被有意识地突破**：`mcp/` 提供了标准 MCP server。
理由是它没有动主线（与 Web 工作台共用同一个 `Debugger` 门面），却让 agent 能「下断点验证」而不是
「读反编译代码猜」。但要清楚这确实把服务对象从新手扩展到了 agent——
再往 agent 方向扩（自动找漏洞、让模型决定断点位置）就越界了，别顺手加。
