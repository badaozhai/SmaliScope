# SmaliScope

面向新手的 DEX/smali 指令级断点调试器。核心卖点：单步时实时看到寄存器变化、执行路径、数据流。

内核：JDWP 指令级断点（`Location.index = dex_pc`）+ dexlib2 静态解析与类型推导 + jadx Java 视图。

## 它解决什么问题

现有工具对新手都不友好：smalidea 配置繁琐、报错晦涩；JADX/JEB 只能看静态；Frida 是方法级 hook，
看不到「一条 smali 指令执行前后寄存器怎么变」。

SmaliScope 只做一件事，做到新手能用：**在任意一条 smali 指令上下断点，单步，然后亲眼看着寄存器的值变化。**

下面是真实输出（`compute(3, 4)`，循环累加 `a*i`）。注意 `v0` 是怎么从 0 变成 1 的：

```
▸ 断点命中  Calc.compute  dex_pc=0  栈深=21
    nop
    p0    引用    Calc@15 (this)
    p1    int    3
    p2    int    4

▸ 单步完成  Calc.compute  dex_pc=8  栈深=21
    add-int/lit8 v0, v0, 1
    ↳ 整数加法
    v0    int    0
    v1    int    0
    ...

▸ 单步完成  Calc.compute  dex_pc=10  栈深=21
    goto :pc_3
    ↳ 无条件跳转。执行会直接跳到目标行，不会走到下一行
    v0    int    1 ←变化
    v1    int    0
```

循环 4 次之后 `v1`（也就是源码里的 `sum`）累加到 18，`18 <= 10` 为假，
于是走进 `sum - 1` 分支得到 **17** —— 与应用日志里打印的 `compute=17` 一致。

Web 工作台把同样的过程做成可视化：执行指针跟着指令走，变化的寄存器脉冲高亮，
CFG 上「走过的路」着色，另有调用栈、对象图和可拖动回看的执行时间线。

---

## 跑起来

### 1. 准备设备

启动一个 Android 模拟器（或连一台手机），确认 adb 能看到它：

```bash
adb devices
```

**关于「哪些应用能调」**：JDWP 只对 debuggable 的进程开放，有两条路——

- 设备 `ro.debuggable=1`（AVD 的**非 Play 镜像**、userdebug/eng ROM）→ 所有应用都能调，真正的零配置；
- 设备 `ro.debuggable=0`（Play 商店镜像、市售手机）→ 只有自身带 `android:debuggable="true"` 的应用能调。

工具会自动探测并在界面上说明当前属于哪种情况。

### 2. 构建并安装自带的测试应用

仓库自带一个测试 App，覆盖了算术、循环、分支、数组、对象、字符串各类指令，专门用来演示调试过程。它带 debuggable 标记，因此在任何镜像上都能调：

```bash
./testapp/build.sh && adb install -r testapp/build/smaliscope-test.apk
```

### 3. 启动工作台

```bash
./gradlew installDist && ./build/install/smaliscope/bin/smaliscope serve
```

浏览器打开 `http://127.0.0.1:8080`，然后：

1. 顶栏选择应用（带 ● 的是当前可调试的），点「载入应用」；
2. 左栏选类 → 选方法，中间会显示带 `dex_pc` 的 smali；
3. 在某条指令左侧点圆点下断点；
4. 点「开始调试」——工具会挂起启动应用并自动 attach；
5. 命中后用「步入 / 步过 / 步出」单步，右侧寄存器面板里**变化的寄存器会高亮**。

## 命令行

工作台之外，同一套内核也有纯文本入口，适合快速验证或写脚本：

```bash
smaliscope smoke                                  # 连通冒烟：握手并打印 VM 信息
smaliscope apps                                   # 列出设备上的应用与环境探测结果
smaliscope dump <包名> [类名] [方法名]              # dump 带 dex_pc 的 smali、CFG、类型推导
smaliscope debug <包名> <类名> <方法名> [步数] [into|over]
                                                  # 下断点 → 挂起启动 → 命中 → 单步打印寄存器
```

例如：

```bash
./build/install/smaliscope/bin/smaliscope debug com.smaliscope.testapp Calc compute 10 over
```

## 测试

```bash
./gradlew test
```

单元测试覆盖不需要设备的部分（签名换算、类型映射、指令词典、JDWP 编解码、JSON 转义）。

需要设备的端到端回归（载入 → 下断点 → 命中 → 单步 → 时间线 → 对象图 → Java 视图），在工作台已启动的前提下跑：

```bash
python3 scripts/e2e.py
```

---

## 已实现

| 阶段 | 内容 | 状态 |
|------|------|------|
| M1 | adb server 二进制协议 + JDWP 握手 + VM 信息 | ✅ |
| M2 | dexlib2 加载 APK、类/方法索引、`dex_pc` 偏移表、CFG、读写集 | ✅ |
| M3 | 环境探测 + `am set-debug-app -w` 挂起启动 + 自动 attach | ✅ |
| M4 | 断点引擎 + 类未加载时自动转 pending（CLASS_PREPARE 兜底） | ✅ |
| M5 | 调用栈 / 寄存器读取 + MethodAnalyzer 类型推导 + 对象图 | ✅ |
| M6 | 指令级单步（自研后继计算，不用 JDWP 原生 STEP）+ 寄存器 diff | ✅ |
| M7 | jadx Java 视图 + smali 指令中文词典悬浮解释 | ✅ |
| M8 | 执行指针、寄存器高亮、数据流、CFG 走过路径、调用栈、对象图、时间线 | ✅ |
| M9 | 中文错误引导 ✅ / jpackage 三平台打包 ❌ | 部分 |

## 尚未实现

- **P1 / P2 接入路径**：目前只做**探测与说明**（告诉你当前设备属于哪种情况、为什么某些应用不可调），没有实现「root 下自动 resetprop / 装 Magisk 模块」和「非 root 重打包 + 重签名」这两条自动准备路径。因此在 `ro.debuggable=0` 的设备上，只能调试自身带 debuggable 标记的应用。
- 寄存器写入、条件断点、表达式求值（设计方案里本就列为二期）。
- jpackage 打包与 platform-tools 自动下载。

## 与设计方案的偏差

- **SSE 代替 WebSocket**：状态推送本来就是单向的（内核 → 前端），命令走普通 HTTP。SSE 在 JDK 自带的 `HttpServer` 上二十行就能实现，而手写 RFC 6455 的分帧、掩码、心跳和关闭握手要多几百行，对本项目没有额外收益。
- **数据流用「数据流条」而非跨栏连线**：代码区与寄存器面板紧贴，中间没有横向空间画曲线，连线会糊在分栏边界上。改成显式的一行 `v1=0、p1=3 ──▶ v2`，同样表达「谁参与运算、结果去哪」，而且带上了实时值。
- 内核包名用 `analysis/` 而非设计方案里的 `static/`（`static` 是 Java 关键字，留着容易在互操作时踩坑）。

## 目录

```
src/main/kotlin/com/smaliscope/
  adb/          直连 adb server 二进制协议：devices / jdwp / forward / shell / sync 拉包
  jdwp/         握手、packet 编解码、事件循环、各命令集（cmdSet 1/2/3/6/9/10/11/13/15/16/64）
  analysis/     dexlib2：APK 索引、反汇编、dex_pc 表、CFG、读写集、寄存器类型推导
  breakpoint/   断点引擎 + pending 兜底 + 单步用的临时断点
  stepping/     指令级单步：后继计算 + 栈深校验
  frame/        帧 / 寄存器 / 对象图读取
  session/      会话编排、设备与应用探测、推给前端的视图模型
  decompile/    jadx 按需反编译
  dict/         smali 指令中文词典
  server/       本地 HTTP + SSE 工作台
src/main/resources/web/    前端（无框架，原生 JS/CSS/SVG）
testapp/                   自带的 debuggable 测试应用（aapt2 + javac + d8 手工构建）
scripts/e2e.py             需要设备的端到端回归
```

设计细节见 [`Smali断点调试器-系统设计方案.md`](Smali断点调试器-系统设计方案.md)，
下一阶段计划见 [`ROADMAP.md`](ROADMAP.md)。
