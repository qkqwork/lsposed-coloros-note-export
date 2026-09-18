#!/system/bin/sh
# Full backup of everything the ColorOS Notes app keeps on this device.
#
# Run as root from the phone's shell (or over adb):
#   adb push tools/backup-notes.sh /data/local/tmp/
#   adb shell su -c 'sh /data/local/tmp/backup-notes.sh'
#
# What is backed up, and why:
#   databases/     nearme_note.db (+ -wal/-shm), the private/cloud databases
#   files/         one directory per note, holding its pictures and audio
#   shared_prefs/  the app's own settings
#   no_backup/     small odds and ends the app keeps outside its main store
#   capture/       the app's own screen captures, if any
#   /sdcard/Android/data/com.coloros.note/files  (external scratch, mostly logs)
#
# Deliberately left out, none of it user data:
#   app_files_tbl_64/  an extracted copy of the WebView native library (164 MB)
#   app_oms/splitApk   split APKs
#   cache, code_cache, app_webview*  caches
#
# The app is force-stopped first so that nothing is being written while its
# database and attachment tree are copied. tar runs with --selinux so each
# file's security label travels with it, and the uid/gid are recorded in the
# manifest because they change when the app is reinstalled.

set -e

APP=/data/data/com.coloros.note
EXT=/sdcard/Android/data/com.coloros.note
STAMP=$(date +%Y%m%d_%H%M%S)
DEST=/sdcard/NoteBackup_$STAMP

log() { echo "[backup] $*"; }

log "stopping the Notes app so the copy is consistent"
am force-stop com.coloros.note || true
sleep 1

mkdir -p "$DEST"
log "destination: $DEST"

# ------------------------------------------------------------------ manifest

{
    echo "ColorOS 便签备份清单"
    echo "备份时间： $(date '+%Y-%m-%d %H:%M:%S %z')"
    echo
    echo "== 设备 =="
    echo "型号：       $(getprop ro.product.model) ($(getprop ro.product.brand))"
    echo "Android：    $(getprop ro.build.version.release) (API $(getprop ro.build.version.sdk))"
    echo "ROM：        $(getprop ro.build.display.id)"
    echo "指纹：       $(getprop ro.build.fingerprint)"
    echo
    echo "== 便签应用 =="
    echo "包名：       com.coloros.note"
    echo "版本名：     $(dumpsys package com.coloros.note | grep -m1 versionName | sed 's/.*=//')"
    echo "版本号：     $(dumpsys package com.coloros.note | grep -m1 versionCode | sed 's/.*=//')"
    echo "数据目录：   $APP"
    echo "uid:gid：    $(stat -c %u $APP):$(stat -c %g $APP)"
    echo "SELinux：    $(ls -Zd $APP | awk '{print $1}')"
    echo
    echo "== 目录大小 (KB) =="
    du -sk $APP/databases $APP/files $APP/shared_prefs $APP/no_backup $APP/capture 2>/dev/null || true
    echo
    echo "== 文件统计 =="
    echo "便签附件目录数： $(ls $APP/files 2>/dev/null | wc -l)"
    echo "附件文件数：     $(find $APP/files -type f 2>/dev/null | wc -l)"
    echo "数据库文件："
    ls -la $APP/databases 2>/dev/null || true
} > "$DEST/manifest.txt"

# --------------------------------------------------------------------- data

log "archiving app data (this is the slow part)"
tar -cf "$DEST/app-data.tar" --selinux -C "$APP" \
    databases files shared_prefs no_backup capture

if [ -d "$EXT/files" ]; then
    log "archiving external scratch"
    tar -cf "$DEST/external-files.tar" --selinux -C "$EXT" files || true
fi

log "writing checksums"
( cd "$DEST" && md5sum *.tar > md5sums.txt && cat md5sums.txt )

# ------------------------------------------------------------ restore script

cat > "$DEST/restore.sh" <<'RESTORE'
#!/system/bin/sh
# Restores a backup made by backup-notes.sh onto this device.
#
#   adb push <backup dir> /sdcard/        (or copy it there by hand)
#   adb shell su -c 'sh /sdcard/NoteBackup_XXXX/restore.sh'
#
# Needs root: /data/data belongs to the Notes app, not to the shell.
set -e

SRC=$(cd "$(dirname "$0")" && pwd)
APP=/data/data/com.coloros.note
EXT=/sdcard/Android/data/com.coloros.note

[ -f "$SRC/app-data.tar" ] || { echo "app-data.tar not found in $SRC"; exit 1; }

echo "[restore] verifying the archive first"
( cd "$SRC" && md5sum -c md5sums.txt ) || { echo "checksum mismatch, refusing to restore"; exit 1; }

echo "[restore] stopping the Notes app"
am force-stop com.coloros.note || true
sleep 1

# The uid is taken from the current install: it changes on reinstall, and a
# wrong owner leaves the app unable to read its own database.
UID_APP=$(stat -c %u "$APP")
GID_APP=$(stat -c %g "$APP")
echo "[restore] app uid:gid = $UID_APP:$GID_APP"

# Old state is moved aside rather than deleted, so a bad restore is undoable.
ASIDE="${APP}.before_restore_$(date +%Y%m%d_%H%M%S)"
if [ -d "$APP" ]; then
    echo "[restore] moving the current data to $ASIDE"
    mv "$APP" "$ASIDE"
fi

mkdir -p "$APP"
echo "[restore] unpacking"
tar -xf "$SRC/app-data.tar" -C "$APP"
chown -R "$UID_APP:$GID_APP" "$APP"
restorecon -R "$APP" 2>/dev/null || true

if [ -f "$SRC/external-files.tar" ]; then
    echo "[restore] unpacking external scratch"
    mkdir -p "$EXT"
    tar -xf "$SRC/external-files.tar" -C "$EXT" || true
    chown -R "$UID_APP:$GID_APP" "$EXT" 2>/dev/null || true
    restorecon -R "$EXT" 2>/dev/null || true
fi

echo
echo "[restore] done. Open the Notes app and check that the notes are back."
echo "[restore] the previous data is still at: $ASIDE"
echo "[restore] delete it once you are satisfied:  rm -rf $ASIDE"
RESTORE
chmod 755 "$DEST/restore.sh"

cat > "$DEST/RESTORE.md" <<'DOC'
# 如何恢复

## 前提

- 手机已 root（KernelSU / Magisk 均可），因为 `/data/data` 只有 root 能写。
- 便签应用**版本不低于**备份时的版本（版本号见 `manifest.txt`），
  跨大版本恢复可能因为数据库升级过而失败。

## 步骤

```sh
# 1. 把整个备份目录放回手机（用 adb 或 MT 管理器都行）
adb push NoteBackup_XXXX /sdcard/

# 2. 执行恢复（会先校验 md5，再动手）
adb shell su -c 'sh /sdcard/NoteBackup_XXXX/restore.sh'

# 3. 打开便签应用确认内容回来了
```

恢复脚本做的事：

1. 用 `md5sum -c` 校验压缩包，坏包直接拒绝执行；
2. 强制停止便签应用；
3. 把现有数据目录**改名**保留（`com.coloros.note.before_restore_<时间>`），不是删除；
4. 解包、按当前安装的 uid/gid 重新 chown、`restorecon` 修正 SELinux 标签。

确认没问题后再删掉旧目录：`rm -rf /data/data/com.coloros.note.before_restore_*`

## 注意

- 恢复后如果便签里开着 **OPPO 云同步**，应用可能把云端数据合并回来，
  覆盖掉刚恢复的内容。恢复完先断网打开看一眼，确认条数对了再联网。
- `app-data.tar` 用 `--selinux` 保存了安全标签，但解包后仍建议 `restorecon`，
  所以脚本里已经带上了。
DOC

log "done"
echo "BACKUP_DIR=$DEST"
ls -la "$DEST"
