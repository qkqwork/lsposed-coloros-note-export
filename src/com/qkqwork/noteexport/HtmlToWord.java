package com.qkqwork.noteexport;

import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts the Notes app's rich text into {@link Doc} paragraphs.
 *
 * <p>The app stores its rich text in {@code rich_notes.raw_text} already as
 * HTML, which is what makes a Word export possible without reverse engineering
 * any of the app's own classes. This converter understands the tags a note
 * editor realistically emits (paragraphs, spans with inline CSS, bold/italic,
 * headings, lists, table rows, pictures) and ignores everything else rather
 * than failing.
 *
 * <p>Lengths are converted to Word's units: half-points for fonts, EMU
 * (914400 per inch) for pictures.
 */
public final class HtmlToWord {

    private static final String TAG = Main.TAG;

    /** EMU per pixel at 96 dpi: 914400 / 96. */
    private static final int EMU_PER_PX = 9525;
    /** Usable text width of an A4 page with 2.54 cm margins, in EMU. */
    private static final int MAX_IMAGE_WIDTH_EMU = 5943600;
    private static final int DEFAULT_IMAGE_PX = 320;

    private static final Set<String> BLOCK_TAGS = new HashSet<>();
    private static final Set<String> SKIP_TAGS = new HashSet<>();
    private static final Set<String> LIST_TAGS = new HashSet<>();
    /**
     * Elements that never have content and never carry an end tag. They matter
     * to the skip logic below: a &lt;meta&gt; or &lt;link&gt; inside a &lt;head&gt;
     * would otherwise be mistaken for a container that is never closed, and the
     * rest of the document would be skipped.
     */
    private static final Set<String> VOID_TAGS = new HashSet<>();

    static {
        String[] block = {"p", "div", "section", "article", "blockquote", "li",
                "h1", "h2", "h3", "h4", "h5", "h6", "tr", "table", "ul", "ol",
                "pre", "figure", "figcaption", "header", "footer", "hr"};
        for (String tag : block) {
            BLOCK_TAGS.add(tag);
        }
        // Their content is markup, not note text, and must never reach the export.
        String[] skip = {"script", "style", "head", "title", "meta", "link"};
        for (String tag : skip) {
            SKIP_TAGS.add(tag);
        }
        String[] lists = {"ul", "ol"};
        for (String tag : lists) {
            LIST_TAGS.add(tag);
        }
        String[] voidTags = {"area", "base", "br", "col", "embed", "hr", "img",
                "input", "link", "meta", "source", "track", "wbr"};
        for (String tag : voidTags) {
            VOID_TAGS.add(tag);
        }
    }

    private static final Map<String, String> NAMED_COLORS = new HashMap<>();

    static {
        NAMED_COLORS.put("black", "000000");
        NAMED_COLORS.put("white", "FFFFFF");
        NAMED_COLORS.put("red", "FF0000");
        NAMED_COLORS.put("green", "008000");
        NAMED_COLORS.put("blue", "0000FF");
        NAMED_COLORS.put("yellow", "FFFF00");
        NAMED_COLORS.put("gray", "808080");
        NAMED_COLORS.put("grey", "808080");
        NAMED_COLORS.put("orange", "FFA500");
        NAMED_COLORS.put("purple", "800080");
        NAMED_COLORS.put("pink", "FFC0CB");
        NAMED_COLORS.put("brown", "A52A2A");
        NAMED_COLORS.put("cyan", "00FFFF");
        NAMED_COLORS.put("magenta", "FF00FF");
        NAMED_COLORS.put("silver", "C0C0C0");
        NAMED_COLORS.put("maroon", "800000");
        NAMED_COLORS.put("navy", "000080");
        NAMED_COLORS.put("teal", "008080");
        NAMED_COLORS.put("olive", "808000");
        NAMED_COLORS.put("lime", "00FF00");
        NAMED_COLORS.put("darkgray", "A9A9A9");
        NAMED_COLORS.put("darkgrey", "A9A9A9");
        NAMED_COLORS.put("lightgray", "D3D3D3");
        NAMED_COLORS.put("lightgrey", "D3D3D3");
    }

    /** Attributes one open tag contributes to the text inside it. */
    private static final class Format {
        boolean bold;
        boolean italic;
        boolean underline;
        boolean strike;
        String color;
        int sizeHalfPoints;

        Format copy() {
            Format f = new Format();
            f.bold = bold;
            f.italic = italic;
            f.underline = underline;
            f.strike = strike;
            f.color = color;
            f.sizeHalfPoints = sizeHalfPoints;
            return f;
        }
    }

    private final Doc doc;
    private final File baseDir;
    /** Format frames, index 0 is the root. Kept strictly balanced. */
    private final List<Format> frames = new ArrayList<>();
    /** Open element names, index 0 is the root. Kept strictly in step with frames. */
    private final List<String> openTags = new ArrayList<>();
    private final Set<String> seenImages = new HashSet<>();

    private Doc.Paragraph current = new Doc.Paragraph();
    private final StringBuilder pending = new StringBuilder();
    private boolean inPre;
    private boolean rowHasCell;
    private int listDepth;
    private int imageCount;
    private int skipDepth;
    /**
     * Marker to put in front of the next paragraph of text, such as "• " for a
     * list item or "☑ " for a checked one. The notes app stores its checklists
     * as {@code <li class="checked">}, which would otherwise lose the one thing
     * that makes the entry readable.
     */
    private String pendingMarker;
    /** Per nesting depth, how many ordered items have been written. */
    private final int[] orderedCounters = new int[16];

    private HtmlToWord(Doc doc, File baseDir) {
        this.doc = doc;
        this.baseDir = baseDir;
        frames.add(new Format());
        openTags.add("#root");
    }

    /**
     * Appends a converted note body to {@code doc}.
     *
     * @param baseDir directory the note's {@code <img src>} values are relative
     *                to, normally {@code files/<note id>}
     */
    public static void convert(String html, Doc doc, File baseDir) {
        if (html == null || html.trim().length() == 0) {
            return;
        }
        HtmlToWord converter = new HtmlToWord(doc, baseDir);
        // Picture part names must be unique across the whole document, and
        // several notes share one document in the single-file layout. Each
        // converter used to start counting at 1, so the second note's first
        // picture collided with the first note's and the zip writer rejected the
        // whole document with "duplicate entry".
        converter.imageCount = doc.images.size();
        try {
            converter.run(html);
        } catch (Throwable t) {
            // A partially converted note beats a failed export.
            Log.w(TAG, "html conversion stopped early: " + t);
        }
    }

    // ------------------------------------------------------------------ scan

    private void run(String html) {
        int i = 0;
        int length = html.length();
        while (i < length) {
            char c = html.charAt(i);

            if (c == '<') {
                if (html.startsWith("<!--", i)) {
                    // Comments are skipped whole: their text is not note content,
                    // and any markup inside one must not be interpreted either.
                    int close = html.indexOf("-->", i + 4);
                    if (close < 0) {
                        break;
                    }
                    i = close + 3;
                    continue;
                }
                int end = html.indexOf('>', i + 1);
                if (end < 0) {
                    appendText(html.substring(i));
                    break;
                }
                // Tags are dispatched even while a skipped region is open: that is
                // the only way its closing tag can be seen. Without this a single
                // <style> or <script> element would silently swallow every
                // remaining paragraph of the note.
                handleTag(html.substring(i + 1, end).trim());
                i = end + 1;
                continue;
            }

            int next = html.indexOf('<', i + 1);
            if (next < 0) {
                next = length;
            }
            if (skipDepth == 0) {
                appendText(html.substring(i, next));
            }
            i = next;
        }

        if (pending.toString().trim().length() > 0) {
            flush();
        }
        endParagraph();
    }

    private void appendText(String chunk) {
        if (chunk.length() == 0) {
            return;
        }
        // The note body is HTML, so its text is escaped; Word needs the
        // characters themselves, or every "&amp;" and "&nbsp;" in a note would
        // be exported literally.
        chunk = decodeEntities(chunk);
        if (!inPre && pending.length() > 0
                && pending.charAt(pending.length() - 1) != ' ') {
            // Keeps adjacent tokens from gluing together after an inline tag.
            pending.append(' ');
        }
        pending.append(chunk);
    }

    // ------------------------------------------------------------------ tags

    private void handleTag(String token) {
        if (token.length() == 0 || token.startsWith("!") || token.startsWith("?")) {
            return;
        }

        if (token.charAt(0) == '/') {
            if (skipDepth > 0) {
                String name = nameOf(token.substring(1));
                if (skipDepth == 1 && SKIP_TAGS.contains(name)) {
                    skipDepth = 0;
                } else if (SKIP_TAGS.contains(name)) {
                    skipDepth--;
                }
                return;
            }
            closeTag(nameOf(token.substring(1)));
            return;
        }

        boolean selfClosing = token.endsWith("/");
        String body = selfClosing ? token.substring(0, token.length() - 1) : token;
        int space = firstSpace(body);
        String name = nameOf(space < 0 ? body : body.substring(0, space));
        if (name.length() == 0) {
            return;
        }
        Map<String, String> attributes = space < 0
                ? new HashMap<String, String>()
                : parseAttributes(body.substring(space + 1));

        // A tag can only open a region if it is neither self closing nor a void
        // element; only regions need their end tag to be waited for.
        boolean container = !selfClosing && !VOID_TAGS.contains(name);
        if (skipDepth > 0) {
            if (SKIP_TAGS.contains(name) && container) {
                skipDepth++;
            }
            return;
        }
        if (SKIP_TAGS.contains(name)) {
            if (container) {
                skipDepth = 1;
            }
            return;
        }

        if ("br".equals(name)) {
            lineBreak();
            return;
        }
        if ("img".equals(name) || "image".equals(name)) {
            appendImage(attributes);
            return;
        }
        if ("hr".equals(name)) {
            endParagraph();
            Doc.Paragraph rule = new Doc.Paragraph();
            rule.runs.add(new Doc.Run("————————————————"));
            rule.spaceBefore = 120;
            rule.spaceAfter = 120;
            doc.add(rule);
            return;
        }

        if ("td".equals(name) || "th".equals(name)) {
            if (rowHasCell) {
                appendText("\t");
            }
            rowHasCell = true;
        }

        boolean block = BLOCK_TAGS.contains(name);
        if (block) {
            if (pending.toString().trim().length() > 0) {
                flush();
            }
            endParagraph();
        }

        int heading;
        if (name.length() == 2 && name.charAt(0) == 'h' && Character.isDigit(name.charAt(1))) {
            heading = name.charAt(1) - '0';
        } else {
            // The Notes editor may mark a heading with a class rather than with
            // an <h1> element, so both spellings are honoured.
            heading = headingFromClass(attributes);
        }
        // A class only promotes the paragraph it starts: a paragraph that already
        // has content must not become a heading because of an inline span.
        boolean fresh = current.runs.isEmpty() && pending.toString().trim().length() == 0;
        if (heading > 0 && fresh) {
            current.heading = Math.min(3, Math.max(1, heading));
            current.spaceBefore = 240;
            current.spaceAfter = 120;
        } else if ("blockquote".equals(name)) {
            current.indentLeft = 480;
        } else if ("li".equals(name)) {
            current.indentLeft = 360 + 360 * Math.max(0, listDepth);
            current.bullet = true;
            pendingMarker = listMarker(attributes);
        } else if ("pre".equals(name)) {
            inPre = true;
        } else if ("tr".equals(name)) {
            rowHasCell = false;
        }

        // Alignment belongs to the block, and a note's centred line would
        // otherwise come out left aligned. It is only taken from a block element
        // or from a tag that starts a paragraph, never from a span in the middle
        // of one.
        int align = alignmentOf(attributes);
        if (align != Doc.ALIGN_LEFT && (block || fresh)) {
            current.align = align;
        }

        if (LIST_TAGS.contains(name)) {
            listDepth++;
        }

        pushFrame(name, attributes);
        if (selfClosing) {
            closeTag(name);
        }
    }

    /**
     * Reads a heading level out of a {@code class} attribute, for note bodies
     * that mark headings with a class instead of an {@code <h1>} element.
     * Understands {@code h1..h6} and {@code heading1} / {@code heading-1} style
     * names, and returns 0 when the class says nothing about headings.
     */
    private static int headingFromClass(Map<String, String> attributes) {
        String classes = attributes.get("class");
        if (classes == null || classes.length() == 0) {
            return 0;
        }
        for (String token : classes.split("\\s+")) {
            String value = token.toLowerCase(Locale.US);
            if (value.length() == 2 && value.charAt(0) == 'h'
                    && value.charAt(1) >= '1' && value.charAt(1) <= '6') {
                return value.charAt(1) - '0';
            }
            if (value.startsWith("heading")) {
                String digits = value.substring("heading".length())
                        .replace("-", "").replace("_", "");
                if (digits.length() == 1 && digits.charAt(0) >= '1' && digits.charAt(0) <= '6') {
                    return digits.charAt(0) - '0';
                }
            }
        }
        return 0;
    }

    /**
     * Reads paragraph alignment from the legacy {@code align} attribute or from
     * {@code text-align} in a style. Justified text is left aligned because the
     * document model only carries left, centre and right.
     */
    private static int alignmentOf(Map<String, String> attributes) {
        String value = attributes.get("align");
        if (value == null) {
            String style = attributes.get("style");
            if (style != null) {
                value = valueOf(style.toLowerCase(Locale.US), "text-align");
            }
        }
        if (value == null) {
            return Doc.ALIGN_LEFT;
        }
        String normalized = value.trim().toLowerCase(Locale.US);
        if ("center".equals(normalized) || "centre".equals(normalized)) {
            return Doc.ALIGN_CENTER;
        }
        if ("right".equals(normalized) || "end".equals(normalized)) {
            return Doc.ALIGN_RIGHT;
        }
        return Doc.ALIGN_LEFT;
    }

    /**
     * What goes in front of a list entry.
     *
     * <p>The editor writes checklists as {@code <li class="checked">} and
     * {@code <li class="unchecked">}, and those markers are the whole point of
     * the entry; numbered lists are counted per nesting depth so a nested list
     * starts at one again.
     */
    private String listMarker(Map<String, String> attributes) {
        String classes = attributes.get("class");
        if (classes != null) {
            String lower = classes.toLowerCase(Locale.US);
            if (lower.contains("unchecked")) {
                return "☐ ";
            }
            if (lower.contains("checked")) {
                return "☑ ";
            }
        }
        if (insideOrderedList()) {
            int depth = Math.min(listDepth, orderedCounters.length - 1);
            orderedCounters[depth]++;
            return orderedCounters[depth] + ". ";
        }
        return "• ";
    }

    /** Whether the innermost list the parser is inside is an {@code <ol>}. */
    private boolean insideOrderedList() {
        for (int i = openTags.size() - 1; i >= 0; i--) {
            String tag = openTags.get(i);
            if ("ol".equals(tag)) {
                return true;
            }
            if ("ul".equals(tag)) {
                return false;
            }
        }
        return false;
    }

    private void closeTag(String name) {
        if (name.length() == 0 || name.equals("br") || SKIP_TAGS.contains(name)) {
            return;
        }

        int at = openTags.lastIndexOf(name);
        if (at <= 0) {
            // Close tag without an open one: ignore it rather than unbalancing
            // the format stack.
            return;
        }
        while (openTags.size() > at) {
            openTags.remove(openTags.size() - 1);
            if (frames.size() > 1) {
                frames.remove(frames.size() - 1);
            }
        }

        if (LIST_TAGS.contains(name) && listDepth > 0) {
            listDepth--;
        }
        if ("pre".equals(name)) {
            inPre = false;
        }
        if ("li".equals(name)) {
            boolean empty = pending.toString().trim().length() == 0 && current.runs.isEmpty();
            endParagraph();
            if (empty && pendingMarker != null) {
                // An empty checklist entry is still an entry, and its marker is
                // the only thing it has to say.
                Doc.Paragraph item = new Doc.Paragraph();
                item.bullet = true;
                item.indentLeft = 360 + 360 * Math.max(0, listDepth);
                item.runs.add(new Doc.Run(pendingMarker.trim()));
                doc.add(item);
                pendingMarker = null;
            }
            return;
        }
        if ("tr".equals(name) || BLOCK_TAGS.contains(name)) {
            endParagraph();
        }
    }

    private void pushFrame(String name, Map<String, String> attributes) {
        Format parent = frames.get(frames.size() - 1);
        Format format = parent.copy();

        String style = attributes.get("style");
        if (style != null) {
            applyStyle(format, style);
        }
        String color = attributes.get("color");
        if (color != null) {
            String parsed = parseColor(color);
            if (parsed != null) {
                format.color = parsed;
            }
        }
        String size = attributes.get("size");
        if (size != null) {
            try {
                format.sizeHalfPoints = legacyFontSize(Integer.parseInt(size.trim()));
            } catch (NumberFormatException ignored) {
                // not a plain number, keep the inherited size
            }
        }

        frames.add(format);
        openTags.add(name);
    }

    // ---------------------------------------------------------------- format

    private static int legacyFontSize(int level) {
        switch (level) {
            case 1:
                return 16;
            case 2:
                return 20;
            case 3:
                return 24;
            case 4:
                return 28;
            case 5:
                return 36;
            case 6:
                return 48;
            case 7:
                return 72;
            default:
                return 24;
        }
    }

    private static void applyStyle(Format format, String style) {
        String lower = style.toLowerCase(Locale.US);
        String weight = valueOf(lower, "font-weight");
        if (weight != null && (weight.contains("bold") || numericAtLeast(weight, 600))) {
            format.bold = true;
        }
        String slant = valueOf(lower, "font-style");
        if (slant != null && slant.contains("italic")) {
            format.italic = true;
        }
        String decoration = valueOf(lower, "text-decoration");
        if (decoration != null) {
            if (decoration.contains("underline")) {
                format.underline = true;
            }
            if (decoration.contains("line-through")) {
                format.strike = true;
            }
        }
        String colour = valueOf(lower, "color");
        if (colour != null) {
            String parsed = parseColor(colour);
            if (parsed != null) {
                format.color = parsed;
            }
        }
        String fontSize = valueOf(lower, "font-size");
        if (fontSize != null) {
            int halfPoints = parseFontSize(fontSize);
            if (halfPoints > 0) {
                format.sizeHalfPoints = halfPoints;
            }
        }
    }

    private static boolean numericAtLeast(String value, int threshold) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0) {
            return false;
        }
        try {
            return Integer.parseInt(digits.toString()) >= threshold;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Extracts one CSS declaration value. Matching must start at a property
     * boundary, otherwise {@code color} would also match inside
     * {@code background-color}.
     */
    private static String valueOf(String lowerStyle, String property) {
        int index = 0;
        while (index < lowerStyle.length()) {
            int at = lowerStyle.indexOf(property, index);
            if (at < 0) {
                return null;
            }
            boolean boundaryStart = at == 0
                    || lowerStyle.charAt(at - 1) == ';'
                    || lowerStyle.charAt(at - 1) == ' ';
            int after = at + property.length();
            while (after < lowerStyle.length()
                    && (lowerStyle.charAt(after) == ' ' || lowerStyle.charAt(after) == '\t')) {
                after++;
            }
            if (boundaryStart && after < lowerStyle.length()
                    && lowerStyle.charAt(after) == ':') {
                int start = after + 1;
                int end = start;
                while (end < lowerStyle.length() && lowerStyle.charAt(end) != ';') {
                    end++;
                }
                return lowerStyle.substring(start, end).trim();
            }
            index = at + property.length();
        }
        return null;
    }

    /** CSS font-size to half-points: 1pt = 2 half-points, 1px = 0.75pt. */
    private static int parseFontSize(String value) {
        String trimmed = value.trim().toLowerCase(Locale.US).replace(" ", "");
        try {
            if (trimmed.endsWith("pt")) {
                return (int) Math.round(Double.parseDouble(
                        trimmed.substring(0, trimmed.length() - 2)) * 2);
            }
            if (trimmed.endsWith("px")) {
                return (int) Math.round(Double.parseDouble(
                        trimmed.substring(0, trimmed.length() - 2)) * 1.5);
            }
            if (trimmed.endsWith("em")) {
                return (int) Math.round(Double.parseDouble(
                        trimmed.substring(0, trimmed.length() - 2)) * 24);
            }
            if (trimmed.endsWith("%")) {
                return (int) Math.round(Double.parseDouble(
                        trimmed.substring(0, trimmed.length() - 1)) * 0.24);
            }
        } catch (NumberFormatException ignored) {
            // unparseable size, keep the inherited one
        }
        return 0;
    }

    /** CSS colour to RRGGBB, or null when it cannot be understood. */
    private static String parseColor(String value) {
        String trimmed = value.trim().toLowerCase(Locale.US);
        if (trimmed.length() == 0 || trimmed.contains("transparent")
                || trimmed.contains("inherit") || trimmed.contains("currentcolor")) {
            return null;
        }
        if (trimmed.charAt(0) == '#') {
            String hex = trimmed.substring(1);
            if (hex.length() == 3) {
                StringBuilder sb = new StringBuilder(6);
                for (int i = 0; i < 3; i++) {
                    sb.append(hex.charAt(i)).append(hex.charAt(i));
                }
                return sb.toString().toUpperCase(Locale.US);
            }
            if (hex.length() == 6) {
                return hex.toUpperCase(Locale.US);
            }
            if (hex.length() == 8) {
                // AARRGGBB as some editors write it; Word wants RGB.
                return hex.substring(2).toUpperCase(Locale.US);
            }
            return null;
        }
        if (trimmed.startsWith("rgb")) {
            int open = trimmed.indexOf('(');
            int close = trimmed.indexOf(')', open + 1);
            if (open < 0 || close < 0) {
                return null;
            }
            String[] parts = trimmed.substring(open + 1, close).split(",");
            if (parts.length < 3) {
                return null;
            }
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 3; i++) {
                try {
                    int channel = (int) Math.round(Double.parseDouble(parts[i].trim()));
                    channel = Math.max(0, Math.min(255, channel));
                    sb.append(String.format(Locale.US, "%02X", channel));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return sb.toString();
        }
        return NAMED_COLORS.get(trimmed);
    }

    // ------------------------------------------------------------------ text

    private void lineBreak() {
        flush();
        endParagraph();
    }

    /** Emits the accumulated text as runs, splitting on embedded newlines. */
    private void flush() {
        String raw = inPre ? pending.toString() : collapse(pending.toString());
        pending.setLength(0);
        if (raw.length() == 0) {
            // Nothing to write yet: a list marker waits for the entry's own text
            // rather than turning into a paragraph of its own.
            return;
        }
        int start = 0;
        boolean first = true;
        while (true) {
            int nl = raw.indexOf('\n', start);
            String part = nl < 0 ? raw.substring(start) : raw.substring(start, nl);
            if (part.length() > 0) {
                if (first && pendingMarker != null) {
                    appendRun(pendingMarker + part);
                    pendingMarker = null;
                } else {
                    appendRun(part);
                }
                first = false;
            }
            if (nl < 0) {
                break;
            }
            // A newline inside <pre> is a paragraph break, not a lost character.
            endParagraph();
            start = nl + 1;
        }
    }

    private void appendRun(String text) {
        Format format = frames.get(frames.size() - 1);
        Doc.Run run = new Doc.Run(text);
        run.bold = format.bold;
        run.italic = format.italic;
        run.underline = format.underline;
        run.strike = format.strike;
        run.color = format.color;
        run.sizeHalfPoints = format.sizeHalfPoints;
        current.runs.add(run);
    }

    private static String collapse(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        boolean lastSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean isSpace = c == ' ' || c == '\t' || c == '\r' || c == '\n'
                    || c == '\u00a0';
            if (isSpace) {
                if (!lastSpace) {
                    sb.append(' ');
                }
                lastSpace = true;
            } else {
                sb.append(c);
                lastSpace = false;
            }
        }
        return sb.toString();
    }

    private void endParagraph() {
        // Text belongs to the block it was collected in, so it is flushed here
        // first. Without this the paragraph carrying a heading level, a list
        // marker or an alignment is dropped as empty, and the text is emitted
        // later into a fresh paragraph that has none of those attributes.
        flush();
        // A paragraph with only whitespace and an explicit heading is still
        // worth keeping; an entirely empty one is not.
        if (!current.isEmpty()) {
            doc.add(current);
        }
        current = new Doc.Paragraph();
        applySurroundingBlock(current);
    }

    /**
     * Carries the enclosing list or quote context into a paragraph that starts
     * inside one, so a {@code <p>} nested in an {@code <li>} keeps the item's
     * marker instead of turning into plain text.
     */
    private void applySurroundingBlock(Doc.Paragraph paragraph) {
        if (openTags.contains("li")) {
            paragraph.bullet = true;
            paragraph.indentLeft = 360 + 360 * Math.max(0, listDepth);
        } else if (openTags.contains("blockquote")) {
            paragraph.indentLeft = 480;
        }
    }

    // ---------------------------------------------------------------- images

    private void appendImage(Map<String, String> attributes) {
        String src = firstNonNull(attributes.get("src"), attributes.get("data-src"),
                attributes.get("href"));
        if (TextUtils.isEmpty(src)) {
            return;
        }
        File file = resolve(src);
        if (file == null || !file.isFile() || file.length() == 0) {
            Log.w(TAG, "image not found: " + src);
            return;
        }
        if (!seenImages.add(file.getAbsolutePath())) {
            return;
        }
        byte[] data = readAll(file);
        if (data == null || data.length == 0) {
            return;
        }

        int[] size = imageSize(data, attributes);
        int width = size[0];
        int height = size[1];
        // The editor marks pictures that it shows two per row with
        // class="size-half inline-1"/"inline-2"; honouring that keeps a note's
        // side-by-side groupings looking the way they do in the app.
        int limit = isHalfWidth(attributes)
                ? MAX_IMAGE_WIDTH_EMU / 2 : MAX_IMAGE_WIDTH_EMU;
        int widthEmu = width * EMU_PER_PX;
        if (widthEmu > limit) {
            double scale = (double) limit / widthEmu;
            widthEmu = limit;
            height = (int) Math.max(1, Math.round(height * scale));
        }
        int heightEmu = height * EMU_PER_PX;

        imageCount++;
        String partName = "word/media/" + String.format(Locale.US, "note_image%d.%s",
                imageCount, imageExtension(data, file.getName()));
        Doc.Image image = new Doc.Image(partName, data, widthEmu, heightEmu);
        doc.addImage(image);

        // Pictures get their own paragraph: a note's pictures are attachments,
        // and an inline drawing mixed into running text reads badly.
        flush();
        endParagraph();
        Doc.Paragraph paragraph = new Doc.Paragraph();
        Doc.Run run = new Doc.Run();
        run.image = image;
        paragraph.runs.add(run);
        paragraph.spaceBefore = 60;
        paragraph.spaceAfter = 60;
        doc.add(paragraph);
    }

    /** Whether the editor asked for this picture to take half the text width. */
    private static boolean isHalfWidth(Map<String, String> attributes) {
        String classes = attributes.get("class");
        return classes != null
                && classes.toLowerCase(Locale.US).contains("size-half");
    }

    /**
     * The extension a picture must be stored under, taken from its bytes rather
     * than from its file name.
     *
     * <p>The Notes app is not consistent about this: some attachments are called
     * {@code <id>_thumb.png} while actually holding JPEG data. The package's
     * content type is derived from the part name, so trusting the name makes
     * Word declare the part as a PNG it cannot decode and draw an empty frame —
     * the file is intact, the picture is simply invisible.
     */
    private static String imageExtension(byte[] data, String fileName) {
        String sniffed = sniffImageType(data);
        return sniffed != null ? sniffed : extensionOf(fileName);
    }

    /** "png", "jpg", "gif", "bmp" or "webp" from the leading bytes, else null. */
    private static String sniffImageType(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        boolean png = (data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N'
                && data[3] == 'G';
        if (png) {
            return "png";
        }
        if ((data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8
                && (data[2] & 0xff) == 0xff) {
            return "jpg";
        }
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return "gif";
        }
        if (data[0] == 'B' && data[1] == 'M') {
            return "bmp";
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F'
                && data[3] == 'F' && data[8] == 'W' && data[9] == 'E'
                && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "png";
        if ("jpeg".equals(extension)) {
            return "jpg";
        }
        if ("png".equals(extension) || "jpg".equals(extension) || "gif".equals(extension)
                || "bmp".equals(extension)) {
            return extension;
        }
        return "png";
    }

    private File resolve(String src) {
        String path = decodeEntities(src.trim());
        if (path.startsWith("file://")) {
            path = path.substring("file://".length());
        }
        if (path.startsWith("content:") || path.startsWith("http:")
                || path.startsWith("https:") || path.startsWith("data:")) {
            Log.i(TAG, "skipping non-file image: " + src);
            return null;
        }
        try {
            path = URLDecoder.decode(path, "UTF-8");
        } catch (Throwable ignored) {
            // keep the raw path
        }
        File file = new File(path);
        if (!file.isAbsolute() && baseDir != null) {
            file = new File(baseDir, path);
        }
        if (!file.isFile() && baseDir != null) {
            // Some notes record only the file name.
            file = new File(baseDir, new File(path).getName());
        }
        return file;
    }

    private static int[] imageSize(byte[] data, Map<String, String> attributes) {
        int width = parsePixels(attributes.get("width"));
        int height = parsePixels(attributes.get("height"));
        if (width <= 0 || height <= 0) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try {
                BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (options.outWidth > 0 && options.outHeight > 0) {
                    width = options.outWidth;
                    height = options.outHeight;
                }
            } catch (Throwable t) {
                Log.w(TAG, "could not read image size: " + t);
            }
        }
        if (width <= 0 || height <= 0) {
            width = DEFAULT_IMAGE_PX;
            height = DEFAULT_IMAGE_PX;
        }
        return new int[] {width, height};
    }

    private static int parsePixels(String value) {
        if (value == null) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static byte[] readAll(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    (int) Math.min(file.length(), 1 << 20));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "could not read image " + file + ": " + t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    // ------------------------------------------------------------- utilities

    private static int firstSpace(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                return i;
            }
        }
        return -1;
    }

    private static String nameOf(String value) {
        return value.trim().toLowerCase(Locale.US);
    }

    private static Map<String, String> parseAttributes(String input) {
        Map<String, String> result = new HashMap<>();
        int i = 0;
        int length = input.length();
        while (i < length) {
            while (i < length && Character.isWhitespace(input.charAt(i))) {
                i++;
            }
            int nameStart = i;
            while (i < length && !Character.isWhitespace(input.charAt(i))
                    && input.charAt(i) != '=') {
                i++;
            }
            String name = input.substring(nameStart, i).toLowerCase(Locale.US);
            while (i < length && Character.isWhitespace(input.charAt(i))) {
                i++;
            }
            String value = "";
            if (i < length && input.charAt(i) == '=') {
                i++;
                while (i < length && Character.isWhitespace(input.charAt(i))) {
                    i++;
                }
                if (i < length && (input.charAt(i) == '"' || input.charAt(i) == '\'')) {
                    char quote = input.charAt(i);
                    i++;
                    int start = i;
                    while (i < length && input.charAt(i) != quote) {
                        i++;
                    }
                    value = input.substring(start, Math.min(i, length));
                    if (i < length) {
                        i++;
                    }
                } else {
                    int start = i;
                    while (i < length && !Character.isWhitespace(input.charAt(i))) {
                        i++;
                    }
                    value = input.substring(start, i);
                }
            }
            if (name.length() > 0) {
                result.put(name, value);
            }
        }
        return result;
    }

    /** The note editor escapes entities; Word needs the real characters. */
    static String decodeEntities(String value) {
        if (value == null || value.indexOf('&') < 0) {
            return value == null ? "" : value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = value.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 12) {
                sb.append(c);
                i++;
                continue;
            }
            String replacement = entityValue(value.substring(i + 1, semi));
            if (replacement == null) {
                sb.append(c);
                i++;
            } else {
                sb.append(replacement);
                i = semi + 1;
            }
        }
        return sb.toString();
    }

    /**
     * The named entities a note can realistically contain, plus the typographic
     * ones editors insert when text is pasted from a web page. Anything missing
     * here is left as written rather than dropped, so an unknown entity is
     * visible in the export instead of vanishing.
     */
    private static final Map<String, String> NAMED_ENTITIES = new HashMap<>();

    static {
        NAMED_ENTITIES.put("amp", "&");
        NAMED_ENTITIES.put("lt", "<");
        NAMED_ENTITIES.put("gt", ">");
        NAMED_ENTITIES.put("quot", "\"");
        NAMED_ENTITIES.put("apos", "'");
        NAMED_ENTITIES.put("nbsp", " ");
        NAMED_ENTITIES.put("ensp", " ");
        NAMED_ENTITIES.put("emsp", " ");
        NAMED_ENTITIES.put("thinsp", " ");
        NAMED_ENTITIES.put("mdash", "—");
        NAMED_ENTITIES.put("ndash", "–");
        NAMED_ENTITIES.put("hellip", "…");
        NAMED_ENTITIES.put("middot", "·");
        NAMED_ENTITIES.put("bull", "•");
        NAMED_ENTITIES.put("lsquo", "\u2018");
        NAMED_ENTITIES.put("rsquo", "\u2019");
        NAMED_ENTITIES.put("ldquo", "\u201c");
        NAMED_ENTITIES.put("rdquo", "\u201d");
        NAMED_ENTITIES.put("laquo", "«");
        NAMED_ENTITIES.put("raquo", "»");
        NAMED_ENTITIES.put("copy", "©");
        NAMED_ENTITIES.put("reg", "®");
        NAMED_ENTITIES.put("trade", "™");
        NAMED_ENTITIES.put("deg", "°");
        NAMED_ENTITIES.put("plusmn", "±");
        NAMED_ENTITIES.put("times", "×");
        NAMED_ENTITIES.put("divide", "÷");
        NAMED_ENTITIES.put("ne", "≠");
        NAMED_ENTITIES.put("le", "≤");
        NAMED_ENTITIES.put("ge", "≥");
        NAMED_ENTITIES.put("larr", "←");
        NAMED_ENTITIES.put("rarr", "→");
        NAMED_ENTITIES.put("euro", "€");
        NAMED_ENTITIES.put("pound", "£");
        NAMED_ENTITIES.put("yen", "¥");
        NAMED_ENTITIES.put("sect", "§");
        NAMED_ENTITIES.put("para", "¶");
    }

    private static String entityValue(String entity) {
        if (entity.length() == 0) {
            return null;
        }
        if (entity.charAt(0) == '#') {
            try {
                int code;
                if (entity.length() > 1
                        && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')) {
                    code = Integer.parseInt(entity.substring(2), 16);
                } else {
                    code = Integer.parseInt(entity.substring(1));
                }
                return String.valueOf((char) code);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String lower = entity.toLowerCase(Locale.US);
        return NAMED_ENTITIES.get(lower);
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && value.trim().length() > 0) {
                return value;
            }
        }
        return null;
    }
}
