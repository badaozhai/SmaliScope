# P0「零配置调试任意应用」路径实测：前提不成立

> 结论：设计方案 §2.3 的 P0 假设在现代 Android 上已经失效。
> 实测环境 Android 14（API 34，google_apis userdebug）与 Android 16（API 36）。

## 设计方案原本的假设

> **P0 已 debuggable —— 零配置（主推新手路径）**：模拟器（AVD 非 Play 镜像 / 多数三方模拟器）
> 默认 `ro.debuggable=1`，所有 App 天生可 JDWP 调，无需任何设置。

这在 Android 10 及更早是成立的，也是网上大量教程仍在这么写的原因。**它现在不成立了。**

## 实测过程

装了非 Play 的 `system-images;android-34;google_apis;arm64-v8a`（`ro.build.type=userdebug`，
`ro.debuggable=1`，`adb root` 可用），拿一个**未经任何改造的正规 release 包**
（Element X，110 MB，R8 混淆，`flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA LARGE_HEAP ]`——
注意其中没有 `DEBUGGABLE`）做被试，逐条排除：

| 尝试 | 结果 |
|---|---|
| `ro.debuggable=1`（镜像默认） | ❌ 进程不出现在 `adb jdwp` |
| 追加 `ro.force.debuggable=1` 到 `/system/build.prop` 并重启 | ❌ 仍然不出现 |
| 属性生效后卸载重装该应用（排除扫包时机问题） | ❌ 仍然不出现 |
| `am set-debug-app -w <pkg>` 挂起启动（工具自身用的路径） | ❌ 仍然不出现 |
| **对照组**：自带 `android:debuggable="true"` 的应用 | ✅ 立刻出现在 `adb jdwp` |

对照组证明 JDWP 基础设施本身完全正常，问题精确地落在
「应用清单里有没有 `android:debuggable="true"`」这一件事上。

## 含义

**判断一个应用能不能调，唯一可靠的依据是它的进程在不在 `adb jdwp` 列表里**，
而不是任何系统属性。工具的环境探测已按此改写——之前它会宣称
「设备已全局可调试，所有应用都能直接下断点」，那是错的，属于对用户说假话。

对路线的影响，按设计方案的编号重排：

- **P0**（靠系统属性零配置调任意应用）：**此路不通**，不必再投入。
  仍然成立的部分是：调试**你自己开发的** debug 包时确实零配置——
  而这恰恰是新手最常见的场景。
- **P1**（root + `resetprop ro.debuggable 1`）：**同样失效**，因为根因不是这个属性。
  root 路径要改成 **Zygisk 逐应用在 fork 时打 `FLAG_DEBUGGABLE`**——
  设计方案把它列为「进阶可选」，现在它是 root 下唯一可行的干净方案。
- **P2**（非 root 重打包 + 重签名）：**不采用**。它确实能跑通，但改签名会让应用自带的
  签名校验失效、必须卸载重装导致数据全丢，而且修改的是被研究对象本身——
  为了看清楚一个东西而先把它改掉，方法本身就不成立。

结论：「想调别人的 App」在现代 Android 上**没有免改造的捷径**，
而唯一不污染观察对象的改造方式是在进程启动时改 runtime flags，即 Zygisk 模块。
详见 ROADMAP 第 2 项。这一点应当在产品里对用户直说，
而不是让他换了非 Play 镜像之后发现还是不行。

## 复现

```bash
# 装非 Play 镜像并建 AVD
sdkmanager --install "system-images;android-34;google_apis;arm64-v8a"
avdmanager create avd -n P0_API34_NoPlay -k "system-images;android-34;google_apis;arm64-v8a"
emulator -avd P0_API34_NoPlay -no-snapshot -writable-system

# 确认属性
adb shell getprop ro.debuggable        # 1
adb shell getprop ro.build.type        # userdebug

# 装一个未改造的 release 包并启动，然后看它在不在 jdwp 列表里
adb install -r <某个正规 release 包>.apk
adb shell dumpsys package <pkg> | grep 'flags=\['   # 应当没有 DEBUGGABLE
adb shell am start -n <pkg>/<activity>
adb jdwp                                            # 它不会出现
```
