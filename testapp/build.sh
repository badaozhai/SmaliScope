#!/usr/bin/env bash
# 手工构建 debuggable 测试 APK：aapt2 → javac → d8 → zipalign → apksigner
# 不依赖 AGP / Gradle，避免为了一个演示 App 拖进整条 Android 构建链。
set -euo pipefail

cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BT="$SDK/build-tools/36.1.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
KS="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$ANDROID_JAR" "$KS"; do
    [ -e "$f" ] || { echo "缺少: $f" >&2; exit 1; }
done

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "[1/5] aapt2 link (仅清单，无资源)"
"$BT/aapt2" link \
    --manifest AndroidManifest.xml \
    -I "$ANDROID_JAR" \
    --min-sdk-version 24 \
    --target-sdk-version 34 \
    -o "$OUT/base.apk"

echo "[2/5] javac"
# -XDstringConcat=inline: 让 "a"+b 编译成 StringBuilder 链而非 invokedynamic，
# 生成的 smali 对新手可读得多（indy 在 smali 里是一坨 invoke-custom）。
#
# -g 很关键，不只是「方便点」：ART 校验读寄存器用的 tag 时，依据的是 dex 调试信息里
# 该 slot 声明的类型。没有 -g 时 d8 只能按方法签名给出覆盖整个方法的参数类型，
# 一旦寄存器被复用来存别的类型，按真实类型读就会被拒为 TYPE_MISMATCH(34)。
# 有了 -g，d8 会按作用域分段记录局部变量类型，复用段也能读出来。
javac \
    --release 11 \
    -g \
    -XDstringConcat=inline \
    -nowarn \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    $(find src -name '*.java')

echo "[3/5] d8"
"$BT/d8" \
    --lib "$ANDROID_JAR" \
    --min-api 24 \
    --output "$OUT" \
    $(find "$OUT/classes" -name '*.class')

echo "[4/5] 打入 classes.dex"
(cd "$OUT" && zip -q base.apk classes.dex)

echo "[5/5] zipalign + apksigner"
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign \
    --ks "$KS" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out "$OUT/smaliscope-test.apk" \
    "$OUT/aligned.apk"

rm -f "$OUT/aligned.apk" "$OUT/aligned.apk.idsig"
echo "完成: $OUT/smaliscope-test.apk"
