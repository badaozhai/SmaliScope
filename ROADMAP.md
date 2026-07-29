# 下一阶段计划

M1–M8 已实现并在模拟器上实测通过（见 README）。本文件记录接下来要做的事，按优先级排列。
每一项都写了**验收标准**，避免做完了却不知道算不算通过。

---

## 0. ~~量清楚真实 APK 上的寄存器可读率~~ ✅ 已完成

**结论：产品成立，不需要改方案。** 完整数据见 [docs/register-readability.md](docs/register-readability.md)。

局部寄存器 `vN` 在四种构建配置下都是 **100% 可读**——这正是当初悬而未决的问题，答案是有利的。
失败无一例外集中在**被复用的参数寄存器 `pN`** 上，最坏情况（R8 release 包）总可读率 **96.3%**，
寄存器面板不会大面积空白。

| 构建方式 | 总可读率 | `pN` | `vN` |
|---|---|---|---|
| `javac -g` | 100 % | 100 % | 100 % |
| `javac -g:none` | 100 % | 100 % | 100 % |
| `javac -g:lines,source`（javac 默认，典型 release） | 95.1 % | 90.1 % | 100 % |
| 再过 R8 `--release`（近似真实发布包） | 96.3 % | 92.4 % | 100 % |

反直觉的发现：完全没有调试信息反而 100% 可读（ART 没有声明类型可以否决），
而「只保留行号」这个最常见的 release 配置才是最差的一档。

产出：`smaliscope audit` 子命令、`testapp/build.sh` 的四种变体、
`RegisterView.error` 结构化失败原因。

---

## 1. ~~验证 P0「任意应用零配置可调」~~ ✅ 已完成（结论：此路不通）

**设计方案的 P0 前提被实测证伪**，完整实验见 [docs/p0-path-findings.md](docs/p0-path-findings.md)。

装了非 Play 的 android-34 google_apis 镜像（`ro.debuggable=1`、`userdebug`、`adb root` 可用），
拿未经改造的正规 release 包（Element X，R8 混淆）逐条排除：加 `ro.force.debuggable=1`、
卸载重装、`am set-debug-app -w`——**全部无效**，它始终不出现在 `adb jdwp` 里；
而同一台设备上自带 `android:debuggable="true"` 的应用立刻可调（对照组成立）。

**路线重排：**

- **P0**（靠系统属性零配置调任意应用）：此路不通，不再投入。
  仍然成立的是「调你自己的 debug 包零配置」——恰是新手最常见的场景。
- **P1**（root + `resetprop ro.debuggable 1`）：同样失效，根因不是这个属性。
  root 路径应改为 **Zygisk 逐应用在 fork 时打 `FLAG_DEBUGGABLE`**。
- **P2**（重打包 + 重签名）：**不采用**，理由见下一项。
  取而代之的是 Zygisk 模块，优先级提到 jpackage 之前。

产出：修正了 `EnvProbe` 的错误宣称（它以前会说「所有应用都能直接下断点」）。

---

## 2. Zygisk 模块：逐应用打 FLAG_DEBUGGABLE

第 1 项证伪了所有「靠系统属性免改造」的路子之后，让第三方应用可调只剩两个选择：
**改 APK**（重打包 + 重签名）或 **改进程启动时的 runtime flags**（Zygisk 模块）。
选后者。

### 为什么不走重签名

- **签名一变，应用自带的签名校验就失效**——国产 App 里这类校验极其普遍，
  改完往往直接闪退或走进降级逻辑，你调的已经不是原来那个程序了；
- **签名变了必须卸载重装，用户数据全丢**，无法在真实数据状态下复现问题；
- **它修改的是被研究对象本身**。逆向时这等于污染证据：你看到的 dex 偏移、
  类加载顺序、乃至反调试分支都可能因为改造而与原包不同；
- Play 集成 / Play Integrity 之类的链路一并失效，很多 App 会因此行为改变。

一句话：为了看清楚一个东西而先把它改掉，方法本身就不成立。

### 为什么 Zygisk 是对的

原包一字不动——签名、数据、更新链路全部保留，观察对象就是它本来的样子。
而且只对目标应用生效，不必动全局 `ro.debuggable`（那个属性既是反调试检测最爱读的，
第 1 项也已实测证明它根本不起作用）。

机制正好对上第 1 项的实测结论：**进程能否被 JDWP 调试，取决于 zygote fork 时的
runtime flags**，而 Zygisk 的 `preAppSpecialize` 拿到的 `AppSpecializeArgs` 里
`runtime_flags` 是可写的。对目标包置位即可：

```cpp
// com.android.internal.os.Zygote
//   DEBUG_ENABLE_JDWP      = 1        让 adbconnection 起来，进程才会出现在 adb jdwp
//   DEBUG_JAVA_DEBUGGABLE  = 1 << 8   让 ART 以可调试模式运行（断点所需的 deopt 靠它）
void preAppSpecialize(AppSpecializeArgs *args) {
    if (matchesTarget(args->nice_name)) {
        *args->runtime_flags |= DEBUG_ENABLE_JDWP | DEBUG_JAVA_DEBUGGABLE;
    }
}
```

### 待办

- 写 Zygisk 模块（C++），目标包名从一个配置文件读，避免给所有应用打标记；
- 工具侧：探测 Magisk/Zygisk 是否就位、模块是否安装、目标包是否已在名单里，
  没有就给出中文引导（安装模块 → 加包名 → 强杀应用重启），而不是丢一句「不可调试」；
- 验收：在 root 设备上，对一个**未经任何改造**的第三方 release 包完成
  「下断点 → 命中 → 读寄存器」，且该应用的签名与数据均未变动。

### 代价要说清楚

这条路**需要 root**。所以能力边界是：

- 调**你自己开发的** debug 包 → 零配置，不需要 root（新手最常见的场景，产品主线）；
- 调**别人的** release 包 → 需要 root + 本模块。

放弃重签名意味着放弃「非 root 真机也能调第三方应用」这个场景。这是有意识的取舍：
那条路能跑通，但跑通之后你研究的已经不是原来那个 App 了。

---

## 3. jpackage 打包

面向新手却要求先装 JDK 和 Gradle 是自相矛盾的。

### 怎么做

jpackage 出三平台安装包并内嵌 JRE；首次运行自动下载 platform-tools（比内嵌小且始终最新）。
需要处理：adb 路径探测（现在依赖用户已装 SDK）、缓存目录位置、无 GUI 时的降级。

### 验收

在一台没装 JDK / Android SDK 的干净机器上双击安装、双击运行，能连上模拟器。

---

## 4. AI 能力

### 已完成：标准 MCP server

`smaliscope mcp` 暴露 16 个工具供任何 MCP 客户端驱动调试，`smaliscope mcp-install` 一键注册。
刻意做成通用 MCP 而非某家 agent 的适配器——做一次就能被 grok-build、Claude Code、Cursor 共用。

这一步把 agent 从「读反编译代码猜」变成「下断点验证」。但要注意它**放大了第 0 项的风险**：
人类看到「此处不可用」会自己判断，agent 拿到空值容易当成事实继续推理。
目前的缓解是在返回文本里明确写出不可读的原因，但根子还在第 0 项。

### 以下未做

设计方案的非目标里写了「不做 MCP/Agent」。MCP 那步已是有意识的范围扩张（见 README 偏差一节）。
剩下的 AI 能力只应该出现在「让新手看懂」这条主线上，且不得触碰正确性与实时性。

### 4.1 离线生成完整指令词典（收益/风险比最高，建议先做这个）

`dict/SmaliDict.kt` 现在是手写的约 90 条（靠指令族前缀覆盖变体），完整 dex 指令有 200+ 条。
用模型**离线**批量生成中文解释 → 人工校对 → 固化成静态 JSON 随包发布。
**运行期零依赖、零网络**，完全不破坏「开箱即用」的定位。

验收：覆盖全部 dex opcode；抽样 30 条人工核对无错误；运行期不产生任何网络请求。

### 4.2 「这段 smali 在干什么」按需摘要

上下文条件很好：jadx 的 Java 视图 + 带 dex_pc 的 smali + 实时寄存器值 + 调用栈可以一起喂进去。
对着混淆过的 smali 发懵的新手，这是理解上最大的一块增量。

### 4.3 混淆 App 的寄存器语义命名

结合数据流和它调用的 framework API，推测「v3 看起来是个索引 / 是用户名 / 是校验结果」。
恰好补在工具当前最弱的地方（`README` 里 jadx 兜底并不足以解决 v0/v1 无意义的问题）。

### 明确不要做

- **不要进单步热路径**：实时性是卖点，设计方案要求同一帧内刷新所有视图，
  加一次网络往返就毁了。
- **不要替代寄存器类型推导**：那必须是确定性的，猜错会静默读出垃圾值，比读不出来更糟。
- **不要让模型决定断点位置或自动找漏洞**：越过项目边界，且新手最容易误信这类输出。

### 工程约束

新开 `explain/` 模块，只在用户主动触发时调用，按 `(方法, dex_pc)` 缓存，
没配 key 时整个功能在界面上消失。**绝不进 `session/` 的事件路径。**

隐私前提必须说清楚：用户调的往往是别人的 APK，把 smali 和反编译结果发给第三方 API
必须是显式 opt-in，并在界面上讲明白。

---

## 零散项

- `Main.kt` 里 `cmdServe` 之外的子命令各自 new 了一份 `ApkIndex`，重复拉包解析，可以共享缓存。
- 断点目前只能按 dex_pc 下；设计方案 §6 的「预设断点模板」（断在所有 Activity.onCreate）还没做。
- 前端的时间线只在切到该标签时刷新，单步过程中不是实时增长的。
- `DeviceApps.runningProcesses()` 解析 `ps -A -o PID,NAME`，不同 Android 版本输出格式有差异，未做兼容测试。
