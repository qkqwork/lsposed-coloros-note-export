package com.qkqwork.noteexport;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a {@link Doc} out as a .docx.
 *
 * <p>A .docx is an OPC package: a zip with a content-type map, a package
 * relationship part and one XML part per document, plus a relationship part and
 * one part per embedded picture. Only the parts Word actually requires are
 * emitted — there is no template engine and no third-party dependency.
 */
public final class DocxWriter {

    private static final String TAG = Main.TAG;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Font for Latin text; East Asian text is told to use the same family. */
    private static final String BODY_FONT = "Microsoft YaHei";
    private static final int BODY_SIZE_HALF_POINTS = 24;

    private DocxWriter() {
    }

    /**
     * Serialises {@code doc} into the .docx format.
     *
     * <p>Pictures are streamed straight out of the zip writer, so a docx with
     * hundreds of megabytes of images never needs an equally large heap.
     */
    public static void write(Doc doc, OutputStream out) throws Exception {
        List<Doc.Image> images = new ArrayList<>(doc.images);

        ZipOutputStream zip = new ZipOutputStream(out, UTF8);
        zip.setLevel(6);
        try {
            put(zip, "[Content_Types].xml", contentTypes(images).getBytes(UTF8));
            put(zip, "_rels/.rels", RELS.getBytes(UTF8));
            put(zip, "word/_rels/document.xml.rels",
                    documentRels(images).getBytes(UTF8));
            put(zip, "word/styles.xml", styles().getBytes(UTF8));
            put(zip, "docProps/core.xml", core().getBytes(UTF8));
            put(zip, "docProps/app.xml", app().getBytes(UTF8));

            // Word is tolerant of a streaming (unknown size) document part, so
            // the body never has to be buffered in full.
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            writeDocument(doc, zip);
            zip.closeEntry();

            for (Doc.Image image : images) {
                zip.putNextEntry(new ZipEntry(image.partName));
                zip.write(image.data);
                zip.closeEntry();
            }
        } finally {
            zip.finish();
            zip.close();
        }
    }

    /**
     * Serialises into a file. Word refuses a deflated zip whose directory uses
     * data descriptors, so the sizes are always known before anything is
     * written.
     */
    public static void writeToFile(Doc doc, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(target);
        try {
            write(doc, out);
        } finally {
            out.close();
        }
    }

    // ------------------------------------------------------------ document.xml

    private static void writeDocument(Doc doc, OutputStream out) throws Exception {
        // Relationship ids are assigned up front so the relationship part and
        // the document body cannot disagree about which picture is which.
        int relCounter = 0;
        for (Doc.Image image : doc.images) {
            relCounter++;
            image.relId = "rIdImg" + relCounter;
        }

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"");
        sb.append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"");
        sb.append(" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"");
        sb.append(" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"");
        sb.append(" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">");
        sb.append("<w:body>");

        int chunk = 0;
        for (Doc.Paragraph paragraph : doc.paragraphs) {
            appendParagraph(sb, paragraph);
            chunk++;
            // Flushed in blocks so a very long note never needs a huge buffer.
            if (chunk >= 512) {
                out.write(sb.toString().getBytes(UTF8));
                sb.setLength(0);
                chunk = 0;
            }
        }

        // The document body must end with a paragraph, otherwise Word repairs
        // the file instead of opening it cleanly.
        sb.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>");
        sb.append("<w:pgMar w:top=\"1418\" w:right=\"1418\" w:bottom=\"1418\" w:left=\"1418\"");
        sb.append(" w:header=\"851\" w:footer=\"992\" w:gutter=\"0\"/></w:sectPr>");
        sb.append("</w:body></w:document>");
        out.write(sb.toString().getBytes(UTF8));
    }

    private static void appendParagraph(StringBuilder sb, Doc.Paragraph paragraph) {
        sb.append("<w:p>");
        appendParagraphProperties(sb, paragraph);
        for (Doc.Run run : paragraph.runs) {
            if (run.image != null) {
                appendDrawing(sb, run.image);
            } else {
                appendRun(sb, run, paragraph.heading);
            }
        }
        sb.append("</w:p>");
    }

    private static void appendParagraphProperties(StringBuilder sb,
            Doc.Paragraph paragraph) {
        boolean any = paragraph.align != Doc.ALIGN_LEFT
                || paragraph.heading > 0
                || paragraph.spaceBefore > 0
                || paragraph.spaceAfter > 0
                || paragraph.indentLeft > 0
                || paragraph.bullet;
        if (!any) {
            return;
        }
        sb.append("<w:pPr>");
        if (paragraph.bullet) {
            // A literal bullet keeps the package free of a numbering part while
            // still looking like a list in Word.
            sb.append("<w:ind w:left=\"").append(paragraph.indentLeft)
                    .append("\" w:hanging=\"200\"/>");
        } else if (paragraph.indentLeft > 0) {
            sb.append("<w:ind w:left=\"").append(paragraph.indentLeft).append("\"/>");
        }
        if (paragraph.heading > 0) {
            sb.append("<w:outlineLvl w:val=\"").append(paragraph.heading - 1).append("\"/>");
        }
        if (paragraph.align != Doc.ALIGN_LEFT) {
            sb.append("<w:jc w:val=\"")
                    .append(paragraph.align == Doc.ALIGN_CENTER ? "center" : "right")
                    .append("\"/>");
        }
        if (paragraph.spaceBefore > 0) {
            sb.append("<w:spacing w:before=\"").append(paragraph.spaceBefore).append("\"/>");
        }
        if (paragraph.spaceAfter > 0) {
            sb.append("<w:spacing w:after=\"").append(paragraph.spaceAfter).append("\"/>");
        }
        sb.append("</w:pPr>");
    }

    /**
     * Writes one text run.
     *
     * <p>{@code heading} is the level of the paragraph the run belongs to, and is
     * what makes a heading look like a heading: the outline level alone puts the
     * paragraph in Word's navigation pane but leaves it the same size as body
     * text, which is not what a reader expects. A note that carried its own font
     * size keeps it.
     */
    private static void appendRun(StringBuilder sb, Doc.Run run, int heading) {
        if (run.text.length() == 0) {
            return;
        }
        boolean headingRun = heading > 0;
        int size = run.sizeHalfPoints;
        boolean bold = run.bold;
        if (headingRun) {
            if (size <= 0) {
                size = headingSize(heading);
            }
            bold = true;
        }

        sb.append("<w:r>");
        boolean props = bold || run.italic || run.underline || run.strike
                || notEmpty(run.color) || size > 0;
        if (props) {
            sb.append("<w:rPr>");
            if (bold) {
                sb.append("<w:b/>");
            }
            if (run.italic) {
                sb.append("<w:i/>");
            }
            if (run.underline) {
                sb.append("<w:u w:val=\"single\"/>");
            }
            if (run.strike) {
                sb.append("<w:strike/>");
            }
            if (notEmpty(run.color)) {
                sb.append("<w:color w:val=\"").append(run.color).append("\"/>");
            }
            if (size > 0) {
                sb.append("<w:sz w:val=\"").append(size).append("\"/>");
                sb.append("<w:szCs w:val=\"").append(size).append("\"/>");
            }
            sb.append("</w:rPr>");
        }
        sb.append("<w:t xml:space=\"preserve\">").append(escape(run.text)).append("</w:t>");
        sb.append("</w:r>");
    }

    /** Heading sizes in half-points: 16 pt, 14 pt, 13 pt. */
    private static int headingSize(int heading) {
        switch (heading) {
            case 1:
                return 32;
            case 2:
                return 28;
            default:
                return 26;
        }
    }

    private static void appendDrawing(StringBuilder sb, Doc.Image image) {
        String relId = image.relId == null ? "rIdImg1" : image.relId;
        long id = Math.abs((long) image.partName.hashCode());
        sb.append("<w:r><w:drawing>");
        sb.append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">");
        sb.append("<wp:extent cx=\"").append(image.widthEmu)
                .append("\" cy=\"").append(image.heightEmu).append("\"/>");
        sb.append("<wp:docPr id=\"").append((id % 100000) + 1).append("\" name=\"Picture ")
                .append(escapeAttr(image.partName)).append("\"/>");
        sb.append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">");
        sb.append("<pic:pic>");
        sb.append("<pic:nvPicPr><pic:cNvPr id=\"0\" name=\"")
                .append(escapeAttr(image.partName)).append("\"/><pic:cNvPicPr/></pic:nvPicPr>");
        sb.append("<pic:blipFill><a:blip r:embed=\"").append(relId)
                .append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>");
        sb.append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"")
                .append(image.widthEmu).append("\" cy=\"").append(image.heightEmu)
                .append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>");
        sb.append("</pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline>");
        sb.append("</w:drawing></w:r>");
    }

    // ------------------------------------------------------------- fixed parts

    private static String contentTypes(List<Doc.Image> images) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        // Only the extensions actually present may be declared as defaults.
        boolean png = false;
        boolean jpg = false;
        boolean gif = false;
        boolean bmp = false;
        for (Doc.Image image : images) {
            String name = image.partName.toLowerCase(Locale.US);
            if (name.endsWith(".png")) {
                png = true;
            } else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                jpg = true;
            } else if (name.endsWith(".gif")) {
                gif = true;
            } else if (name.endsWith(".bmp")) {
                bmp = true;
            }
        }
        if (png) {
            sb.append("<Default Extension=\"png\" ContentType=\"image/png\"/>");
        }
        if (jpg) {
            sb.append("<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>");
        }
        if (gif) {
            sb.append("<Default Extension=\"gif\" ContentType=\"image/gif\"/>");
        }
        if (bmp) {
            sb.append("<Default Extension=\"bmp\" ContentType=\"image/bmp\"/>");
        }
        sb.append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>");
        sb.append("<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>");
        sb.append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>");
        sb.append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>");
        sb.append("</Types>");
        return sb.toString();
    }

    private static final String RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
            + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
            + "</Relationships>";

    private static String documentRels(List<Doc.Image> images) {
        StringBuilder sb = new StringBuilder(256 + images.size() * 160);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        sb.append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        int index = 0;
        for (Doc.Image image : images) {
            index++;
            String relId = image.relId == null ? "rIdImg" + index : image.relId;
            // Targets are relative to word/, and embedded pictures live in word/media.
            String target = image.partName.startsWith("word/")
                    ? image.partName.substring("word/".length())
                    : image.partName;
            sb.append("<Relationship Id=\"").append(relId)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"")
                    .append(escapeAttr(target)).append("\"/>");
        }
        sb.append("</Relationships>");
        return sb.toString();
    }

    private static String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:docDefaults><w:rPrDefault><w:rPr>"
                + "<w:rFonts w:ascii=\"" + BODY_FONT + "\" w:hAnsi=\"" + BODY_FONT
                + "\" w:eastAsia=\"" + BODY_FONT + "\" w:cs=\"" + BODY_FONT + "\"/>"
                + "<w:sz w:val=\"" + BODY_SIZE_HALF_POINTS + "\"/>"
                + "<w:szCs w:val=\"" + BODY_SIZE_HALF_POINTS + "\"/>"
                + "</w:rPr></w:rPrDefault>"
                + "<w:pPrDefault><w:pPr><w:spacing w:line=\"300\" w:lineRule=\"auto\"/>"
                + "<w:jc w:val=\"left\"/></w:pPr></w:pPrDefault></w:docDefaults>"
                + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
                + "<w:name w:val=\"Normal\"/></w:style>"
                + "</w:styles>";
    }

    private static String core() {
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .format(new java.util.Date());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:dcterms=\"http://purl.org/dc/terms/\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<dc:title>ColorOS 便签导出</dc:title>"
                + "<dc:creator>lsposed-coloros-note-export</dc:creator>"
                + "<cp:lastModifiedBy>lsposed-coloros-note-export</cp:lastModifiedBy>"
                + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + stamp + "</dcterms:created>"
                + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + stamp + "</dcterms:modified>"
                + "</cp:coreProperties>";
    }

    private static String app() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\""
                + " xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">"
                + "<Application>lsposed-coloros-note-export</Application></Properties>";
    }

    // ------------------------------------------------------------- primitives

    private static void put(ZipOutputStream zip, String name, byte[] body)
            throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body);
        zip.closeEntry();
    }

    static String escape(String value) {
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
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    // Characters Word cannot store (control codes and the two
                    // non-characters) are dropped, and lone surrogates are
                    // replaced, rather than making the whole document unopenable.
                    if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
                        if (c < 0xD800 || c > 0xDFFF) {
                            sb.append(c);
                        } else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                                && Character.isLowSurrogate(value.charAt(i + 1))) {
                            sb.append(c).append(value.charAt(i + 1));
                            i++;
                        } else {
                            sb.append('\uFFFD');
                        }
                    }
            }
        }
        return sb.toString();
    }

    static String escapeAttr(String value) {
        return escape(value);
    }

    private static boolean notEmpty(String value) {
        return value != null && value.length() > 0;
    }
}
