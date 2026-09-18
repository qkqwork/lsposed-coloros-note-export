#!/system/bin/sh
# Removes the copies this investigation left on the shared storage.
#
#   adb push build/cleanup-device-temp.sh /data/local/tmp/
#   adb shell su -c 'sh /data/local/tmp/cleanup-device-temp.sh'
#
# Only files with the dsh- prefix are touched, plus fonts.fixed.xml; the backups
# of the user's own data and the notes exports are left alone.

for name in dsh-cap0.png dsh-cap1.png dsh-cap2.png dsh-cap3.png dsh-cap4.png \
            dsh-SysFont-Regular.ttf dsh-fonts.xml dsh-font_fallback.xml \
            dsh-Roboto-Regular.ttf fonts.fixed.xml; do
    if [ -f "/sdcard/$name" ]; then
        rm -f "/sdcard/$name" && echo "removed /sdcard/$name"
    fi
done

echo
echo "== anything of mine left in /sdcard =="
ls -la /sdcard/dsh-* /sdcard/fonts.fixed.xml 2>/dev/null || echo "nothing left"
