package com.qkqwork.noteexport;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately tiny document model — just enough to represent what the Notes
 * app produces, and enough for {@link DocxWriter} to emit WordprocessingML.
 */
public final class Doc {

    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;

    /** A run of text sharing one set of attributes, or an inline picture. */
    public static final class Run {
        public String text = "";
        public boolean bold;
        public boolean italic;
        public boolean underline;
        public boolean strike;
        public String color;
        /** Half-points: Word's unit for font size. */
        public int sizeHalfPoints;
        /** When set, this run is a picture and {@link #text} is ignored. */
        public Image image;

        public Run() {
        }

        public Run(String text) {
            this.text = text;
        }

        public boolean isImage() {
            return image != null;
        }
    }

    public static final class Paragraph {
        public final List<Run> runs = new ArrayList<>();
        public int align = ALIGN_LEFT;
        /** 0 = body text, 1..3 = heading levels. */
        public int heading;
        /** Extra space above/below, in twentieths of a point. */
        public int spaceBefore;
        public int spaceAfter;
        public int indentLeft;
        public boolean bullet;

        public boolean isEmpty() {
            if (runs.isEmpty()) {
                return true;
            }
            for (Run run : runs) {
                if (run.isImage() || run.text.trim().length() > 0) {
                    return false;
                }
            }
            return true;
        }
    }

    /** One embedded picture: the bytes plus the target path inside the package. */
    public static final class Image {
        public final String partName;
        public final byte[] data;
        public final int widthEmu;
        public final int heightEmu;
        /** Filled in by the writer, which owns relationship ids. */
        String relId;

        public Image(String partName, byte[] data, int widthEmu, int heightEmu) {
            this.partName = partName;
            this.data = data;
            this.widthEmu = widthEmu;
            this.heightEmu = heightEmu;
        }
    }

    public final List<Paragraph> paragraphs = new ArrayList<>();
    public final List<Image> images = new ArrayList<>();

    public void add(Paragraph paragraph) {
        paragraphs.add(paragraph);
    }

    public void addImage(Image image) {
        images.add(image);
    }
}
