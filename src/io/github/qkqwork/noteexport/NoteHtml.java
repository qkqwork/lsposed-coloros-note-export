package io.github.qkqwork.noteexport;

import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * Turns the image references a note actually stores into paths a renderer can
 * open.
 *
 * <p>This is not cosmetic: on ColorOS 16 the editor writes
 *
 * <pre>
 *   &lt;img src="ee0d6e1e-7df1-4d6f-b6ad-d0745110cc48" width="1264" height="2780"&gt;
 * </pre>
 *
 * <p>— an attachment id, not a path. The bytes live next to the note, in
 * {@code files/&lt;note id&gt;/&lt;attachment id&gt;_thumb.png} (the file the app calls a
 * thumbnail is the full size picture; a {@code _placeholder.png} beside it is
 * only a blurred stand-in). Neither the Word converter nor the WebView renderer
 * can guess that, so without this step every picture is silently dropped from
 * both exports.
 *
 * <p>The rewrite happens once, while the note body is read, so that every
 * consumer downstream sees ordinary paths.
 */
final class NoteHtml {

    private static final String TAG = Main.TAG;

    /** Extensions to try, in the order they are preferred. */
    private static final String[] EXTENSIONS = {"", ".png", ".jpg", ".jpeg", ".webp", ".gif"};

    private NoteHtml() {
    }

    /**
     * Rewrites relative {@code <img src>} values into absolute {@code file://}
     * URIs for the files that exist. References that already carry a scheme, and
     * ones whose file cannot be found, are left untouched — the converter logs
     * those and carries on.
     */
    static String resolveImages(String html, File baseDir) {
        if (html == null || html.indexOf('<') < 0 || baseDir == null) {
            return html;
        }
        StringBuilder out = new StringBuilder(html.length() + 64);
        int i = 0;
        int resolved = 0;
        while (i < html.length()) {
            int at = indexOfTag(html, i);
            if (at < 0) {
                out.append(html, i, html.length());
                break;
            }
            out.append(html, i, at);
            int end = html.indexOf('>', at + 1);
            if (end < 0) {
                out.append(html, at, html.length());
                break;
            }
            String tag = html.substring(at, end + 1);
            String rewritten = rewriteTag(tag, baseDir);
            if (rewritten != null) {
                out.append(rewritten);
                resolved++;
            } else {
                out.append(tag);
            }
            i = end + 1;
        }
        if (resolved > 0) {
            Log.i(TAG, "resolved " + resolved + " image reference(s) in "
                    + baseDir.getName());
        }
        return out.toString();
    }

    private static int indexOfTag(String html, int from) {
        int img = html.indexOf("<img", from);
        int image = html.indexOf("<image", from);
        if (img < 0) {
            return image;
        }
        if (image < 0) {
            return img;
        }
        return Math.min(img, image);
    }

    /** Returns the rewritten tag, or null when nothing needs changing. */
    private static String rewriteTag(String tag, File baseDir) {
        String lower = tag.toLowerCase(Locale.US);
        if (!lower.startsWith("<img") && !lower.startsWith("<image")) {
            return null;
        }
        int srcAt = indexOfAttribute(lower, "src");
        if (srcAt < 0) {
            return null;
        }
        int valueAt = lower.indexOf('=', srcAt);
        if (valueAt < 0) {
            return null;
        }
        int quote = valueAt + 1;
        while (quote < tag.length() && Character.isWhitespace(tag.charAt(quote))) {
            quote++;
        }
        if (quote >= tag.length()) {
            return null;
        }
        char delimiter = tag.charAt(quote);
        String value;
        int valueEnd;
        if (delimiter == '"' || delimiter == '\'') {
            valueEnd = tag.indexOf(delimiter, quote + 1);
            if (valueEnd < 0) {
                return null;
            }
            value = tag.substring(quote + 1, valueEnd);
        } else {
            valueEnd = quote;
            while (valueEnd < tag.length() && !Character.isWhitespace(tag.charAt(valueEnd))
                    && tag.charAt(valueEnd) != '>') {
                valueEnd++;
            }
            value = tag.substring(quote, valueEnd);
        }

        File file = locate(value, baseDir);
        if (file == null) {
            return null;
        }
        String absolute = "file://" + file.getAbsolutePath();
        return tag.substring(0, quote + 1) + absolute + tag.substring(valueEnd);
    }

    /** Finds the attribute at a token boundary, so "src" cannot match "data-src". */
    private static int indexOfAttribute(String lowerTag, String attribute) {
        int from = 0;
        while (true) {
            int at = lowerTag.indexOf(attribute, from);
            if (at < 0) {
                return -1;
            }
            char before = at == 0 ? ' ' : lowerTag.charAt(at - 1);
            int after = at + attribute.length();
            char next = after >= lowerTag.length() ? ' ' : lowerTag.charAt(after);
            boolean boundaryBefore = Character.isWhitespace(before);
            boolean boundaryAfter = next == '=' || Character.isWhitespace(next);
            if (boundaryBefore && boundaryAfter) {
                return at;
            }
            from = at + 1;
        }
    }

    /** The file a reference points at, or null when it is not a local one. */
    private static File locate(String value, File baseDir) {
        String path = value == null ? "" : value.trim();
        if (path.length() == 0) {
            return null;
        }
        String lower = path.toLowerCase(Locale.US);
        if (lower.startsWith("http:") || lower.startsWith("https:")
                || lower.startsWith("data:") || lower.startsWith("content:")) {
            return null;
        }
        String bare = path;
        if (lower.startsWith("file://")) {
            bare = path.substring("file://".length());
        }
        File direct = new File(bare);
        if (direct.isFile() && direct.length() > 0) {
            return direct;
        }
        if (direct.isAbsolute()) {
            // An absolute path that is not there; nothing to fall back to.
            return null;
        }

        // The editor's own naming: <attachment id>_thumb.png next to the note,
        // with the un-suffixed name tried first in case a release stores that.
        for (String extension : EXTENSIONS) {
            File candidate = new File(baseDir, bare + extension);
            if (candidate.isFile() && candidate.length() > 0) {
                return candidate;
            }
        }
        for (String extension : EXTENSIONS) {
            File candidate = new File(baseDir, bare + "_thumb" + extension);
            if (candidate.isFile() && candidate.length() > 0) {
                return candidate;
            }
        }
        return null;
    }
}
