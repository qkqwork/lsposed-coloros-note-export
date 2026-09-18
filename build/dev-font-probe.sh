#!/system/bin/sh
# What font configuration does the Notes app's own Chromium (TBL WebView) read?
#
#   adb push build/dev-font-probe.sh /data/local/tmp/
#   adb shell su -c 'sh /data/local/tmp/dev-font-probe.sh'
#
# The library is searched as raw bytes, so no binary tooling is needed on the
# device. Any path ending in fonts.xml, or naming the framework's fallback
# configuration, is what the renderer will try to parse.

LIB=/data/data/com.coloros.note/app_files_tbl_64/700062/libtblwebviewchromium.so

echo "== library =="
ls -la "$LIB" 2>/dev/null || { echo "the WebView library is not at $LIB"; exit 1; }

echo
echo "== any path mentioning fonts =="
grep -aoE '[A-Za-z0-9_./-]*fonts[A-Za-z0-9_./-]*' "$LIB" | sort -u | head -40

echo
echo "== paths mentioning font_fallback or fontconfig =="
grep -aoE '[A-Za-z0-9_./-]*font[_A-Za-z0-9./-]*' "$LIB" | sort -u | head -40

echo
echo "== does it mention the modules' font file names =="
for name in SysFont PingFang Roboto NotoColorEmoji; do
    count=$(grep -ac "$name" "$LIB" 2>/dev/null || echo 0)
    echo "  $name: $count line(s)"
done
