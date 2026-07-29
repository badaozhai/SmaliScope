# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目是什么

面向新手的 DEX/smali **指令级**断点调试器：Kotlin/JVM 内核 + 本地 Web 工作台。
M1–M8 已全部实现并在模拟器上实测通过，M9 只差打包发布。设计基线见
`Smali断点调试器-系统设计方案.md`，实现现状与偏差见 `README.md`。

## 常用命令

```bash
./gradlew installDist                              # 构建（产物在 build/install/smaliscope/）
./gradlew test                                     # 单元测试（不需要设备）
./testapp/build.sh                                 # 构建自带的 debuggable 测试 APK
python3 scripts/e2e.py                             # 端到端回归（需设备 + 工作台已在 8777 端口跑）
```

跑起来（**不要用 `./gradlew run`**，见下方「Gradle daemon 会继承沙箱网络限制」）：

```bash
./build/install/smaliscope/bin/smaliscope serve            # Web 工作台
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
  会被拒为 `TYPE_MISMATCH(34)`，而按声明的 int 读则能成功（但拿到的是无意义的堆内偏移）。
  `FrameReader.readOne` 用这个差异来区分「确实读不了」和「类型对不上」，给出可理解的说明而不是垃圾值。
  测试 APK 用 `javac -g` 构建正是为了避开这个问题——有作用域的局部变量表后，d8 不再复用参数寄存器。
  **含义**：对没有调试信息的 release APK，部分寄存器会读不出来，这是平台限制，不是 bug。

- **本机模拟器镜像是 Play 商店镜像**：`ro.debuggable=0` 且 `adb root` 不可用，
  因此只有自带 `android:debuggable="true"` 的应用（即 `testapp/`）能调。
  要验证「任意应用零配置可调」的 P0 路径，需要另外装一个非 Play 系统镜像。

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

- **读寄存器一次往返批量取**（`FrameCmds.getValues` 收一批 slot），保证前端各视图同一帧内一致；
  批量失败才退化为逐个读，免得一个坏寄存器把整个面板打空。

## 约定

- **文档与所有用户可见文案用中文**；UI 层不暴露 dex_pc / JDWP tag / slot / ClassPrepare 这些词，
  只说「行 / 寄存器 / 类型」。错误提示给中文的下一步指引，不是英文异常栈。
- **第三方依赖只有 dexlib2 / baksmali / jadx / kotlinx-coroutines**。HTTP、SSE、JSON 都是手写的极简实现，
  刻意不引序列化框架和 Web 框架——少一个依赖少一层版本纠纷。加依赖前先想清楚是否真的必要。
- 前端无框架，原生 JS + CSS + SVG。列表项用 `clickable()` 挂 `role`/`tabindex`，键盘和读屏可用。
- 里程碑落地后同步更新 `README.md` 的「已实现 / 尚未实现 / 与设计方案的偏差」三节。

## 明确的非目标（别顺手加）

不处理加固壳；不做 Frida 式 hook、内存 patch、流量抓包；不做多设备集控、Web 研究平台、MCP/Agent 化；
第一版不做寄存器写入、条件断点、表达式求值。设计原则是**只把「断点单步」这一件事做到新手能用、能看懂**。
