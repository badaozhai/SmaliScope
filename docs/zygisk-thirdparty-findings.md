# Zygisk 方案实测：未改造的第三方 release 包成功调试

> 这份是 ROADMAP 第 2 项验收标准的**可复现实证**，不是提交信息里的一句声称。
> 环境：Redmi Note 13（Android 14，`build.type=user`、`ro.debuggable=0`，KernelSU + ZygiskNext）。
> 被试：Element X（`io.element.android.x`），官方签名、R8 混淆、manifest 无 `debuggable` 标记——
> 一个货真价实、未经任何改造的正规发布包。

## 结论

**成立。** 把该包加入名单后，它从「不可调试」变为「可 JDWP 调试」，并完成了
断点命中 → 单步 → 读寄存器；全程 APK 一字未动，签名与数据均未变。

## 实测四步

**一、加入名单前——不可调试（对照）**

```
$ adb shell am force-stop io.element.android.x && 启动
$ adb jdwp
  Element X pid=16707；在 jdwp 列表: 否
```

**二、加入名单 → 强杀重启 → 变为可调试**

```
$ smaliscope zygisk add io.element.android.x
$ adb shell am force-stop io.element.android.x && 启动
$ adb jdwp
  Element X pid=16868；在 jdwp 列表: 是
```

同一个未改造的包，加入名单前后唯一的差别就是 Zygisk 在 fork 时置的 runtime flag。

**三、下断点 → 命中 → 单步 → 读寄存器**

```
$ smaliscope debug io.element.android.x io.element.android.x.ElementXApplication onCreate 3 over

断点: io.element.android.x.ElementXApplication.onCreate()V @ dex_pc 0
  · 已连接 pid 17036（Dalvik 8）
▸ 断点命中  ElementXApplication.onCreate  dex_pc=0  栈深=12
    invoke-super {p0}, Landroid/app/Application;->onCreate()V
    p0    引用    ElementXApplication@11 (this)
▸ 单步完成  dex_pc=7  const-class v1, Lio/element/android/x/initializer/CrashInitializer;
    v0    引用    AppInitializer@12 ←变化
    p0    引用    ElementXApplication@11 (this)
```

`set-debug-app -w` 挂起启动这条既有路径，在 Zygisk 把进程变可调试之后照常工作——
无需为第三方应用另写 attach 流程。

**四、原包不动的证据**

```
$ adb shell dumpsys package io.element.android.x | grep flags=
    flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA LARGE_HEAP ]      # 仍无 DEBUGGABLE

$ apksigner verify --print-certs base.apk
    Signer #1 certificate DN: CN=element.io, OU=New Vector Ltd. ...
    SHA-256: 6a2fdc3148049ce0d5c6e85010723b83fb207d20c7477f5c22ac53c877e92d47
```

签名仍是 element.io 官方证书（未重签），manifest 依旧没有 `debuggable` 标记。
可调试完全来自运行时 flag，而非改 APK——这正是不走重打包重签名的意义
（见 [p0-path-findings.md](p0-path-findings.md)）。

## 复现

```bash
./zygisk/build.sh                                  # 需 NDK；产物 build/smaliscope-zygisk.zip
smaliscope zygisk install zygisk/build/smaliscope-zygisk.zip   # 装完重启一次
smaliscope zygisk add <未改造的第三方包名>
adb shell am force-stop <包名>                      # 强杀后重开即生效
smaliscope debug <包名> <Application 类> onCreate 3 over
```

名单在 `/data/adb/smaliscope/targets`，只有 root 能写——否则任何应用都能给自己开调试。
用完 `smaliscope zygisk remove <包名>` 移出即可，无需重启。
