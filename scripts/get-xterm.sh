#!/usr/bin/env bash
# 下载 xterm.js（内嵌终端用的终端模拟器）到前端资源目录。
# 这是唯一一个第三方前端库，刻意 vendored 进来，运行期零外网请求（和整个项目一致）。
# 用法： ./scripts/get-xterm.sh
set -euo pipefail
cd "$(dirname "$0")/.."

VER="5.3.0"
ADDON_FIT_VER="0.8.0"
OUT="src/main/resources/web/vendor"
mkdir -p "$OUT"

base="https://cdn.jsdelivr.net/npm/xterm@${VER}/lib/xterm.js"
css="https://cdn.jsdelivr.net/npm/xterm@${VER}/css/xterm.css"
fit="https://cdn.jsdelivr.net/npm/xterm-addon-fit@${ADDON_FIT_VER}/lib/xterm-addon-fit.js"

echo "下载 xterm.js ${VER} …"
curl -fsSL "$base" -o "$OUT/xterm.js"
curl -fsSL "$css"  -o "$OUT/xterm.css"
curl -fsSL "$fit"  -o "$OUT/addon-fit.js"

echo "完成："
ls -lh "$OUT" | awk 'NR>1 {print "  "$5"  "$9}'
echo "已放进 $OUT/，会随构建打进包，运行期不再联网。"
