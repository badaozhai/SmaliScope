#!/system/bin/sh
# 建好名单文件与目录。只有 root 可写——否则任何应用都能把自己加进去开调试。
MODDIR=${0%/*}
DIR=/data/adb/smaliscope
[ -d "$DIR" ] || mkdir -p "$DIR"
[ -f "$DIR/targets" ] || {
    echo "# 一行一个进程名（通常就是包名），# 起头为注释。" > "$DIR/targets"
    echo "# 改完下次启动该应用即生效，不必重启手机。" >> "$DIR/targets"
}
chmod 700 "$DIR"
chmod 600 "$DIR/targets"
