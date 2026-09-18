#!/system/bin/sh
# Puts the ColorOS 16 font module's font configuration back the way it was.
#
#   adb push build/restore-font-config.sh /data/local/tmp/
#   adb shell su -c 'sh /data/local/tmp/restore-font-config.sh'
#
# The file lives in the module and is bind-mounted over /system/etc/fonts.xml, so
# copying over it in place (rather than replacing it) is what makes the change
# visible immediately, with no reboot.

MODULE=/data/adb/modules/FontColorOS16/system/etc
ORIGINAL=$MODULE/fonts.xml.dsh-original

if [ ! -f "$ORIGINAL" ]; then
    echo "no backup at $ORIGINAL, nothing to restore"
    exit 1
fi

echo "restoring $MODULE/fonts.xml from the backup"
cat "$ORIGINAL" > "$MODULE/fonts.xml"
echo
echo "== checksums =="
md5sum "$MODULE/fonts.xml" "$ORIGINAL" /system/etc/fonts.xml
echo
echo "expected original: e21b12f8b3cff8a9e38f8321a607788e"
