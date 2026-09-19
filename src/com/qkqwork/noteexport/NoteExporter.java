package com.qkqwork.noteexport;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the notes in the app's database into files in the shared Downloads
 * folder. Runs inside the Notes process, which is the only place where the
 * app's private database and attachments are readable.
 *
 * <p>Layout produced:
 * <pre>
 * Download/便签导出/&lt;timestamp&gt;/
 *   导出说明.txt
 *   便签汇总.docx                     (Word, single-file layout)
 *   &lt;分类&gt;/001_&lt;标题&gt;.docx          (Word, one-file-per-note layout)
 *   &lt;分类&gt;/001_&lt;标题&gt;.png            (image layout)
 *   &lt;分类&gt;/001_&lt;标题&gt;_附件/...       (always)
 * </pre>
 */
public final class NoteExporter {

    private static final String TAG = Main.TAG;

    private static final String ROOT_DIR = "便签导出";
    private static final String UNTITLED = "无标题";
    private static final String README = "导出说明.txt";
    private static final String SUMMARY_DOCX = "便签汇总.docx";
    /** MediaStore refuses names longer than this in practice. */
    private static final int MAX_NAME = 60;

    public static final class Result {
        public final boolean ok;
        public final String message;
        public final String path;
        /**
         * A small PNG of the top of the first exported note, or null.
         *
         * <p>Travels back to the settings screen inside the same cursor as the
         * message, which is how that screen can show what an export looks like
         * without any access to the export folder.
         */
        public final byte[] thumbnail;

        public Result(boolean ok, String message, String path) {
            this(ok, message, path, null);
        }

        public Result(boolean ok, String message, String path, byte[] thumbnail) {
            this.ok = ok;
            this.message = message;
            this.path = path;
            this.thumbnail = thumbnail;
        }
    }

    private NoteExporter() {
    }

    public static Result export(Context context, ExportOptions options) {
        if (context == null) {
            return new Result(false, "导出失败：没有可用的便签上下文", "");
        }
        try {
            return run(context, options);
        } catch (Throwable t) {
            Log.e(TAG, "export failed", t);
            return new Result(false, "导出失败：" + t, "");
        }
    }

    private static Result run(Context context, ExportOptions options) throws Exception {
        NoteStore.Snapshot snapshot = NoteStore.read(context, options.includeRecycled);
        if (snapshot.totalNotes == 0) {
            return new Result(false, "导出失败：便签数据库里没有读到任何便签，"
                    + "请把日志里的 schema 行反馈给开发者", "");
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        String root = options.timestampedFolder ? ROOT_DIR + "/" + stamp : ROOT_DIR;

        Stats stats = new Stats();
        stats.total = snapshot.totalNotes;
        stats.encrypted = snapshot.encrypted;
        stats.recycled = snapshot.recycled;
        stats.deleted = snapshot.deleted;
        stats.exported = snapshot.notes.size();

        // Filled in by whichever format draws a picture; the Word formats have
        // nothing worth showing, since their pages are text the screen cannot
        // reproduce faithfully in a preview.
        byte[] thumbnail = null;

        if (options.format == ExportOptions.Format.WORD) {
            exportWord(context, options, snapshot, root, stats);
        } else if (options.format == ExportOptions.Format.NATIVE) {
            // The app renders each note itself; see NativeImageExport.
            Result result = NativeImageExport.export(context, snapshot, options, root, stats);
            writeReadme(context, root, options, stats);
            return result;
        } else if (options.format == ExportOptions.Format.NATIVE_BATCH) {
            // Experimental: drive the app's own editor and keep what it draws.
            Result result = NativeBatchExport.export(context, snapshot, options, root, stats);
            writeReadme(context, root, options, stats);
            return result;
        } else {
            thumbnail = exportImages(context, options, snapshot, root, stats);
        }

        writeReadme(context, root, options, stats);
        String summary = "已导出 " + stats.notes + " 条便签到 " + root;
        if (stats.encrypted > 0) {
            summary += "（跳过 " + stats.encrypted + " 条加密便签）";
        }
        Log.i(TAG, summary);
        return new Result(true, summary, root, thumbnail);
    }

    // ------------------------------------------------------------------- word

    /**
     * The notes to export, grouped as the layout needs them.
     *
     * <p>{@code limit} cuts the list short so a format can be tried on a couple
     * of notes before it runs over the whole notebook. The order is the one the
     * export itself uses, so "the first two" means the same thing whichever
     * format is chosen; without this the Word path quietly exported everything
     * however small a limit was asked for.
     */
    static Map<String, List<Note>> groupsToExport(NoteStore.Snapshot snapshot,
            ExportOptions options) {
        Map<String, List<Note>> groups = groupedByCategory(snapshot);
        if (options.limit <= 0) {
            return groups;
        }
        Map<String, List<Note>> limited = new LinkedHashMap<>();
        int left = options.limit;
        for (Map.Entry<String, List<Note>> group : groups.entrySet()) {
            if (left <= 0) {
                break;
            }
            List<Note> notes = group.getValue();
            int take = Math.min(left, notes.size());
            limited.put(group.getKey(), new ArrayList<>(notes.subList(0, take)));
            left -= take;
        }
        return limited;
    }

    /** How many notes those groups hold. */
    static int countNotes(Map<String, List<Note>> groups) {
        int count = 0;
        for (List<Note> notes : groups.values()) {
            count += notes.size();
        }
        return count;
    }

    private static void exportWord(Context context, ExportOptions options,
            NoteStore.Snapshot snapshot, String root, Stats stats) throws Exception {
        Map<String, List<Note>> groups = groupsToExport(snapshot, options);
        stats.exported = countNotes(groups);
        if (options.wordLayout == ExportOptions.WordLayout.SINGLE) {
            Doc doc = new Doc();
            appendTitlePage(doc, snapshot, options);

            for (Map.Entry<String, List<Note>> group : groups.entrySet()) {
                String category = group.getKey();
                doc.add(heading(category, 1));
                int index = 0;
                for (Note note : group.getValue()) {
                    index++;
                    // A per-note heading makes the category's contents visible in
                    // Word's navigation pane and keeps Ctrl+F usable.
                    doc.add(heading(prefix(index) + titleOf(note), 2));
                    appendNoteBody(context, doc, note);
                    appendAttachments(doc, context, note, category, prefix(index), stats);
                }
            }

            if (doc.paragraphs.isEmpty()) {
                doc.add(paragraph("没有任何可导出的便签内容。"));
            }

            File temp = tempFile(context, "summary");
            try {
                DocxWriter.writeToFile(doc, temp);
                ExportSink sink = ExportSink.open(context, root, SUMMARY_DOCX,
                        MIME_DOCX);
                try {
                    copy(temp, sink.stream());
                    sink.finish();
                } catch (Throwable t) {
                    sink.abort();
                    throw t;
                }
                stats.notes = stats.exported;
                stats.files++;
                Log.i(TAG, "wrote " + sink.path());
            } finally {
                temp.delete();
            }
            return;
        }

        // One .docx per note, grouped into a directory per category.
        for (Map.Entry<String, List<Note>> group : groups.entrySet()) {
            String category = group.getKey();
            String dir = ExportSink.join(root, ExportSink.sanitize(category));
            int index = 0;
            for (Note note : group.getValue()) {
                index++;
                String base = prefix(index) + ExportSink.fileName(titleOf(note), UNTITLED);
                Doc doc = new Doc();
                doc.add(heading(titleOf(note), 1));
                doc.add(metadata(context, note, category));
                appendNoteBody(context, doc, note);
                appendAttachments(doc, context, note, category, prefix(index), stats);

                File temp = tempFile(context, "note");
                String name = truncate(base, MAX_NAME - 5) + ".docx";
                try {
                    DocxWriter.writeToFile(doc, temp);
                    ExportSink sink = ExportSink.open(context, dir, name, MIME_DOCX);
                    try {
                        copy(temp, sink.stream());
                        sink.finish();
                    } catch (Throwable t) {
                        sink.abort();
                        throw t;
                    }
                    stats.files++;
                    stats.notes++;
                } catch (Throwable t) {
                    // One bad note must not sink the whole run.
                    Log.w(TAG, "note " + note.id + " failed: " + t);
                    stats.failed++;
                } finally {
                    temp.delete();
                }
            }
        }
    }

    // ----------------------------------------------------------------- images

    /**
     * Draws one PNG per note, and keeps the first one as a preview.
     *
     * @return a small preview of the first note's picture, or null
     */
    private static byte[] exportImages(Context context, ExportOptions options,
            NoteStore.Snapshot snapshot, String root, Stats stats) {
        byte[] preview = null;
        for (Map.Entry<String, List<Note>> group
                : groupsToExport(snapshot, options).entrySet()) {
            String category = group.getKey();
            String dir = ExportSink.join(root, ExportSink.sanitize(category));
            int index = 0;
            for (Note note : group.getValue()) {
                index++;
                String base = prefix(index) + ExportSink.fileName(titleOf(note), UNTITLED);
                String name = truncate(base, MAX_NAME - 5) + ".png";
                ExportSink sink = null;
                try {
                    // The note's body goes in as it is; the renderer wraps and
                    // lays it out itself, with no browser engine involved.
                    sink = ExportSink.open(context, dir, name, "image/png");
                    Bitmap drawn = LongImageRenderer.renderTo(context, note.body(),
                            NoteStore.attachmentDir(context, note), sink.stream());
                    if (drawn != null) {
                        sink.finish();
                        stats.files++;
                        stats.notes++;
                        if (preview == null) {
                            preview = Thumbnail.of(drawn);
                        }
                        drawn.recycle();
                    } else {
                        sink.abort();
                        stats.failed++;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "note " + note.id + " failed: " + t);
                    if (sink != null) {
                        sink.abort();
                    }
                    stats.failed++;
                }
                copyAttachments(context, note, dir, base, stats);
            }
        }
        return preview;
    }

    // ------------------------------------------------------------ note pieces

    private static void appendTitlePage(Doc doc, NoteStore.Snapshot snapshot,
            ExportOptions options) {
        doc.add(heading("ColorOS 便签导出", 1));
        Doc.Paragraph meta = new Doc.Paragraph();
        meta.runs.add(new Doc.Run("导出时间：" + stamp()));
        meta.runs.add(new Doc.Run("　便签总数：" + snapshot.totalNotes));
        meta.runs.add(new Doc.Run("　可导出：" + snapshot.notes.size()));
        if (snapshot.encrypted > 0) {
            meta.runs.add(new Doc.Run("　加密便签（未导出）：" + snapshot.encrypted));
        }
        doc.add(meta);
        doc.add(paragraph("以下内容按便签分类组织，每条便签的标题为二级标题，"
                + "正文与图片均来自便签原件。"));
    }

    private static void appendNoteBody(Context context, Doc doc, Note note) {
        File baseDir = NoteStore.attachmentDir(context, note);
        if (TextUtils.isEmpty(note.html) && TextUtils.isEmpty(note.text)) {
            doc.add(paragraph("（这条便签没有正文）"));
            return;
        }
        HtmlToWord.convert(note.body(), doc, baseDir);
    }

    private static void appendAttachments(Doc doc, Context context, Note note,
            String category, String prefix, Stats stats) {
        List<File> files = NoteStore.attachmentFiles(context, note);
        if (files.isEmpty()) {
            return;
        }
        doc.add(paragraph("附件 " + files.size() + " 个："
                + ExportSink.join(ExportSink.sanitize(category),
                        prefix + ExportSink.fileName(titleOf(note), UNTITLED) + "_附件/")));
        for (File file : files) {
            doc.add(paragraph("　• " + file.getName()
                    + "（" + readableSize(file.length()) + "）"));
        }
        stats.attachments += files.size();
    }

    /** Copies files/<note id>/ verbatim, so nothing the note held is lost. */
    private static void copyAttachments(Context context, Note note, String dir,
            String base, Stats stats) {
        List<File> files = NoteStore.attachmentFiles(context, note);
        if (files.isEmpty()) {
            return;
        }
        String target = ExportSink.join(dir, truncate(base, MAX_NAME - 12) + "_附件");
        for (File file : files) {
            ExportSink sink = null;
            try {
                sink = ExportSink.open(context, target,
                        truncate(ExportSink.sanitize(file.getName()), MAX_NAME),
                        guessMime(file.getName()));
                copy(file, sink.stream());
                sink.finish();
                stats.attachments++;
            } catch (Throwable t) {
                Log.w(TAG, "attachment " + file + " failed: " + t);
                if (sink != null) {
                    sink.abort();
                }
            }
        }
    }

    private static Doc.Paragraph metadata(Context context, Note note, String category) {
        Doc.Paragraph paragraph = new Doc.Paragraph();
        paragraph.runs.add(new Doc.Run("分类：" + category
                + "　创建：" + time(note.createTime)
                + "　修改：" + time(note.updateTime)));
        if (note.recycled) {
            paragraph.runs.add(new Doc.Run("　（来自回收站）"));
        }
        return paragraph;
    }

    private static Doc.Paragraph heading(String text, int level) {
        Doc.Paragraph paragraph = new Doc.Paragraph();
        Doc.Run run = new Doc.Run(text);
        run.bold = true;
        run.sizeHalfPoints = level == 1 ? 36 : 28;
        paragraph.runs.add(run);
        paragraph.heading = level;
        paragraph.spaceBefore = level == 1 ? 360 : 240;
        paragraph.spaceAfter = 120;
        return paragraph;
    }

    private static Doc.Paragraph paragraph(String text) {
        Doc.Paragraph paragraph = new Doc.Paragraph();
        paragraph.runs.add(new Doc.Run(text));
        return paragraph;
    }

    // ------------------------------------------------------------------- misc

    /** The category a note belongs to, as the Notes app shows it. */
    static String categoryOf(Note note) {
        return TextUtils.isEmpty(note.folder) ? NoteStore.DIR_UNKNOWN : note.folder;
    }

    static Map<String, List<Note>> groupedByCategory(NoteStore.Snapshot snapshot) {
        Map<String, List<Note>> groups = new LinkedHashMap<>();
        for (Note note : snapshot.notes) {
            String key = categoryOf(note);
            List<Note> bucket = groups.get(key);
            if (bucket == null) {
                bucket = new java.util.ArrayList<>();
                groups.put(key, bucket);
            }
            bucket.add(note);
        }
        return groups;
    }

    private static void writeReadme(Context context, String root, ExportOptions options,
            Stats stats) {
        ExportSink sink = null;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("ColorOS 便签导出\r\n");
            sb.append("========================\r\n\r\n");
            sb.append("导出时间：").append(stamp()).append("\r\n");
            sb.append("导出方式：")
                    .append(options.format == ExportOptions.Format.WORD ? "Word 文档" : "图片")
                    .append(options.format == ExportOptions.Format.WORD
                            ? (options.wordLayout == ExportOptions.WordLayout.SINGLE
                                    ? "（单个汇总文档）" : "（每条便签一个文档）")
                            : "（每条便签一张长图）")
                    .append("\r\n\r\n");
            sb.append("便签总数：").append(stats.total).append("\r\n");
            sb.append("已导出：").append(stats.notes).append("\r\n");
            if (stats.encrypted > 0) {
                sb.append("跳过加密便签：").append(stats.encrypted)
                        .append(" 条（内容仍留在便签应用中）\r\n");
            }
            if (stats.recycled > 0) {
                sb.append("回收站便签：").append(stats.recycled).append(" 条\r\n");
            }
            sb.append("附件文件：").append(stats.attachments).append(" 个\r\n");
            if (stats.failed > 0) {
                sb.append("处理失败：").append(stats.failed).append(" 条（详见 logcat）\r\n");
            }
            sb.append("\r\n目录说明：\r\n");
            sb.append("  便签按分类分子目录，便签应用里的分类名就是这里的目录名。\r\n");
            sb.append("  便签在回收站中时归入「回收站」目录。\r\n");
            sb.append("  每条便签的图片等附件放在同名的「_附件」目录里。\r\n");

            sink = ExportSink.open(context, root, README, "text/plain");
            OutputStream out = sink.stream();
            out.write(sb.toString().getBytes("UTF-8"));
            sink.finish();
        } catch (Throwable t) {
            Log.w(TAG, "could not write the readme: " + t);
            if (sink != null) {
                sink.abort();
            }
        }
    }

    private static File tempFile(Context context, String kind) throws Exception {
        File dir = new File(context.getCacheDir(), "export");
        dir.mkdirs();
        File file = File.createTempFile(kind + "-", ".docx", dir);
        Log.i(TAG, "staging " + file);
        return file;
    }

    private static void copy(File file, OutputStream out) throws Exception {
        InputStream in = new FileInputStream(file);
        try {
            copy(in, out);
        } finally {
            in.close();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[16384];
        int read;
        long total = 0;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            total += read;
        }
        out.flush();
        if (total == 0) {
            Log.w(TAG, "copied an empty stream");
        }
    }

    private static String prefix(int index) {
        return String.format(Locale.US, "%03d_", index);
    }

    static String titleOf(Note note) {
        if (!TextUtils.isEmpty(note.title) && note.title.trim().length() > 0) {
            return note.title.trim();
        }
        if (!TextUtils.isEmpty(note.text)) {
            for (String line : note.text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.length() > 0) {
                    return trimmed;
                }
            }
        }
        return UNTITLED;
    }

    static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit).trim();
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());
    }

    private static String time(long millis) {
        if (millis <= 0) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new Date(millis));
    }

    private static String readableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String guessMime(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".m4a") || lower.endsWith(".aac")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".amr")) {
            return "audio/amr";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        return "application/octet-stream";
    }

    private static final String MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** Running totals for the readme and the log. */
    static final class Stats {
        int total;
        int exported;
        int encrypted;
        int recycled;
        int deleted;
        int notes;
        int files;
        int attachments;
        int failed;
        /** Notes left alone because their file was already there. */
        int skipped;
    }
}
