package com.dsh.noteexport;

/** One ColorOS note, as read straight out of the app's own database. */
public final class Note {

    /** rich_notes.local_id — also the name of the note's attachment directory. */
    public String id = "";

    /** Human readable category name, resolved from folders.guid. */
    public String folder = "";

    /** rich_notes.title, may be empty. */
    public String title = "";

    /** rich_notes.text — plain text. */
    public String text = "";

    /** rich_notes.raw_text — the app's own rich text, already HTML. */
    public String html = "";

    /** rich_notes.encrypted — encrypted notes are never exported. */
    public boolean encrypted;

    /** rich_notes.state == 2 means the note sits in the recycle bin. */
    public boolean recycled;

    /** rich_notes.create_time, epoch millis. */
    public long createTime;

    /** rich_notes.update_time, epoch millis. */
    public long updateTime;

    public String body() {
        // The HTML preserves formatting, so it wins whenever the app produced it.
        return html.trim().length() > 0 ? html : escapeToHtml(text);
    }

    private static String escapeToHtml(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '\n':
                    sb.append("<br>");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
