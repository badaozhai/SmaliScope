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

## 2. ~~Zygisk 模块：逐应用打 FLAG_DEBUGGABLE~~ ✅ 已完成并真机验证

在一台 Redmi（Android 14、`build.type=user`、`ro.debuggable=0`、KernelSU + ZygiskNext）上
端到端跑通：装模块 → 重启 → 把一个**未经任何改造的** release 包（Element X，官方签名、
manifest 里没有 DEBUGGABLE）加入名单 → 强杀重启 → 它出现在 `adb jdwp` 里 →
断点命中 `Application.onCreate` → 指令级单步 → 读到寄存器实时值
（`v0` = `AppInitializer@12`、`v1` = `Class@14`）。

验证「原包不动」：调试后该应用签名仍是官方的 `6A:2F:DC:…`，manifest 依旧无 DEBUGGABLE 标记——
可调试完全来自 Zygisk 在 fork 时置的 runtime flag，而非改 APK。这正是不走重签名的意义。

产出：
- `zygisk/`：模块 C++ 源码（约 160 行）+ `build.sh`，产物 5–7.5KB/ABI；
- `smaliscope zygisk <status|install|add|remove|list>`：状态探测、装模块、维护名单；
- `AdbClient.push` 与 `suShell`（适配 Magisk/KernelSU 的 `su -c` 与 AOSP 的 `su root sh -c`）。

---

