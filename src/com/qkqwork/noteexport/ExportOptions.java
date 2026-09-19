package com.qkqwork.noteexport;

import java.util.ArrayList;
import java.util.List;

/** What the user picked in the module's settings screen. */
public final class ExportOptions {

    public enum Format {
        /** Long PNG per note, drawn by this module. */
        IMAGE,
        /** Long PNG per note, drawn by the Notes app itself — its own look. */
        NATIVE,
        /** Experimental: the app's editor draws every note, driven from here. */
        NATIVE_BATCH,
        /** Word documents. */
        WORD
    }

    /** Word layout, ignored for the image format. */
    public enum WordLayout {
        /** One .docx holding every category, images embedded. */
        SINGLE,
        /** One .docx per note, grouped into a directory per category. */
        PER_NOTE
    }

    /** What a long picture is drawn on. */
    public enum Background {
        /** Work it out per note from the colour its text is drawn in. */
        AUTO,
        /** Always white; text that was drawn white is repainted black. */
        WHITE,
        /** Always the near-black the app itself uses; dark text is repainted white. */
        DARK
    }

    public Format format = Format.WORD;
    public WordLayout wordLayout = WordLayout.SINGLE;

    /**
     * The backing of a long picture.
     *
     * <p>Automatic, and that is the only thing the settings screen asks for: the
     * Notes app draws these pictures in the colours of the phone's theme, so the
     * module reads the backing off the page it was handed instead of imposing
     * one and repainting the glyphs to suit. A request that arrives with no
     * colour of its own therefore means "the same as the note".
     */
    public Background background = Background.AUTO;

    /** Include notes sitting in the recycle bin. */
    public boolean includeRecycled = true;

    /**
     * The notes to export, as note ids; empty means every one of them.
     *
     * <p>Ticked in the picker, which can only see titles — the ids are what
     * survives the trip into the Notes process and what the export matches its
     * notes against.
     */
    public final List<String> guids = new ArrayList<>();

    /** Export the .docx / .png files into a timestamped subdirectory. */
    public boolean timestampedFolder = true;

    /**
     * Export only this many notes; 0 means all of them. Used to try a format on
     * a couple of notes instead of a hundred, which is how the native renderer
     * was tested before it ran over the whole notebook.
     */
    public int limit;

    /** Put a number in front of each file name, so the order is the note order. */
    public boolean numberedNames = true;

    /** Keep each note's files in a folder of its category. */
    public boolean categoryFolders = true;

    /**
     * Leave a note alone when its file is already there.
     *
     * <p>Combined with a fixed output folder rather than a timestamped one, this
     * is what makes a long export resumable: run it again and it picks up where
     * the interrupted one stopped instead of drawing everything from the top.
     */
    public boolean skipExisting;

    /**
     * Install the diagnostic probes and log their detail.
     *
     * <p>Off by default: they hook whole families of the app's methods and print
     * inventories of its classes, which is worth having while something is being
     * investigated and is only noise the rest of the time.
     */
    public boolean debug;

    /** Whether the picker narrowed this export down to particular notes. */
    public boolean hasSelection() {
        return guids != null && !guids.isEmpty();
    }

    public ExportOptions() {
    }

    public ExportOptions(Format format, WordLayout wordLayout) {
        this.format = format;
        this.wordLayout = wordLayout;
    }
}
