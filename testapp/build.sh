#!/usr/bin/env bash
# 手工构建 debuggable 测试 APK：aapt2 → javac → d8/R8 → zipalign → apksigner
# 不依赖 AGP / Gradle，避免为了一个演示 App 拖进整条 Android 构建链。
#
# 用法： ./build.sh [debug|nodebug|r8]
#
#   debug   （默认）javac -g，保留完整局部变量表
#   lines   javac 默认（-g:lines,source）：有行号、无局部变量表——最典型的 release 构建
#   nodebug javac -g:none，完全没有调试信息
#   r8      javac -g 后再过一遍 R8 --release——近似真实发布包
#
# 三种变体是为了量清楚「ART 按声明类型校验读寄存器」这件事在真实包上的影响，
# 见 ROADMAP 第 0 项。用 `smaliscope audit` 跑数字。
set -euo pipefail

cd "$(dirname "$0")"

VARIANT="${1:-debug}"
case "$VARIANT" in
    debug|lines|nodebug|r8) ;;
    *) echo "未知变体: $VARIANT（可选 debug / lines / nodebug / r8）" >&2; exit 2 ;;
esac

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BT="$SDK/build-tools/36.1.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
KS="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$ANDROID_JAR" "$KS"; do
    [ -e "$f" ] || { echo "缺少: $f" >&2; exit 1; }
done

OUT="build/$VARIANT"
APK="build/smaliscope-test-$VARIANT.apk"
[ "$VARIANT" = "debug" ] && APK="build/smaliscope-test.apk"   # 默认变体保持原名

rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "[1/5] aapt2 link (仅清单，无资源)"
"$BT/aapt2" link \
    --manifest AndroidManifest.xml \
    -I "$ANDROID_JAR" \
    --min-sdk-version 24 \
    --target-sdk-version 34 \
    -o "$OUT/base.apk"

echo "[2/5] javac（变体 $VARIANT）"
# -XDstringConcat=inline: 让 "a"+b 编译成 StringBuilder 链而非 invokedynamic，
# 生成的 smali 对新手可读得多（indy 在 smali 里是一坨 invoke-custom）。
#
# -g 很关键，不只是「方便点」：ART 校验读寄存器用的 tag 时，依据的是 dex 调试信息里
# 该 slot 声明的类型。没有 -g 时 d8 只能按方法签名给出覆盖整个方法的参数类型，
# 一旦寄存器被复用来存别的类型，按真实类型读就会被拒为 TYPE_MISMATCH(34)。
GFLAG="-g"
[ "$VARIANT" = "nodebug" ] && GFLAG="-g:none"
[ "$VARIANT" = "lines" ] && GFLAG="-g:lines,source"

javac \
    --release 11 \
    $GFLAG \
    -XDstringConcat=inline \
    -nowarn \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    $(find src -name '*.java')

if [ "$VARIANT" = "r8" ]; then
    echo "[3/5] R8 --release（剥掉调试信息，近似真实发布包）"
    # 保留类名与方法名：混淆改名不影响寄存器可读性，留着名字才好按同一套方法名对比。
    cat > "$OUT/rules.pro" <<'EOF'
-keep class com.smaliscope.testapp.** { *; }
-dontwarn **
EOF
    java -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 \
        --release \
        --min-api 24 \
        --lib "$ANDROID_JAR" \
        --pg-conf "$OUT/rules.pro" \
        --output "$OUT" \
        $(find "$OUT/classes" -name '*.class')
else
    echo "[3/5] d8"
    "$BT/d8" \
        --lib "$ANDROID_JAR" \
        --min-api 24 \
        --output "$OUT" \
        $(find "$OUT/classes" -name '*.class')
fi

echo "[4/5] 打入 classes.dex"
(cd "$OUT" && zip -q base.apk classes.dex)

echo "[5/5] zipalign + apksigner"
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign \
    --ks "$KS" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out "$APK" \
    "$OUT/aligned.apk"

rm -f "$OUT/aligned.apk" "$OUT/aligned.apk.idsig"
echo "完成: $APK"
