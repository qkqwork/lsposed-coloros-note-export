#!/system/bin/sh
# Copies an export folder to an ASCII path so it can be pulled and measured.
#
#   adb push build/collect-export.sh /data/local/tmp/
#   adb shell su -c 'sh /data/local/tmp/collect-export.sh 20260918_213535'

STAMP="$1"
SRC="/sdcard/Download/便签导出/$STAMP"
OUT=/data/local/tmp/dsh-out

if [ -z "$STAMP" ] || [ ! -d "$SRC" ]; then
    echo "no export at $SRC"
    exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

index=0
find "$SRC" -name '*.png' | sort | while read -r file; do
    index=$((index + 1))
    name=$(printf '%03d.png' "$index")
    cp "$file" "$OUT/$name"
    echo "$name <- $(basename "$file")"
done

echo
ls -la "$OUT"
