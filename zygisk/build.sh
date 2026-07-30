#!/usr/bin/env bash
# 编译 Zygisk 模块并打成可刷入的 zip。
#
#   ./build.sh
#   → build/smaliscope-zygisk.zip
#
# 需要 Android NDK。默认从 ANDROID_NDK_HOME，或 SDK 下最新的一个版本里找。
set -euo pipefail
cd "$(dirname "$0")"

NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
    SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
    NDK=$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
fi
[ -n "$NDK" ] && [ -x "$NDK/ndk-build" ] || {
    echo "找不到 NDK。请设 ANDROID_NDK_HOME，或在 Android Studio 里装一个 NDK。" >&2
    exit 1
}

echo "[1/3] ndk-build（$(basename "$NDK")）"
rm -rf libs obj build
"$NDK/ndk-build" NDK_PROJECT_PATH=. NDK_APPLICATION_MK=./jni/Application.mk >/dev/null

echo "[2/3] 组装模块目录"
OUT=build/module
mkdir -p "$OUT/zygisk"
cp module.prop post-fs-data.sh "$OUT/"
# Zygisk 认的是 zygisk/<abi>.so 这个命名，不是 lib<name>.so
for abi in libs/*/; do
    abi=$(basename "$abi")
    cp "libs/$abi/libsmaliscope.so" "$OUT/zygisk/$abi.so"
    echo "     zygisk/$abi.so  $(stat -f%z "$OUT/zygisk/$abi.so" 2>/dev/null || stat -c%s "$OUT/zygisk/$abi.so") 字节"
done

echo "[3/3] 打包"
ZIP="$(cd build && pwd)/smaliscope-zygisk.zip"
(cd "$OUT" && zip -qr "$ZIP" .)
echo "完成: zygisk/build/smaliscope-zygisk.zip"
echo
echo "安装（KernelSU / APatch 用各自的应用刷入，Magisk 同理）:"
echo "  adb push zygisk/build/smaliscope-zygisk.zip /data/local/tmp/"
echo "  adb shell su -c 'ksud module install /data/local/tmp/smaliscope-zygisk.zip'   # KernelSU"
echo "  adb shell su -c 'magisk --install-module /data/local/tmp/smaliscope-zygisk.zip' # Magisk"
echo "然后重启一次，再用 smaliscope zygisk add <包名> 把目标加进名单。"
