package com.qkqwork.noteexport;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Makes the small preview picture the settings screen shows after an export.
 *
 * <p>The picture is handed back through the same one row cursor the export
 * already answers with, and everything in that cursor travels over a binder
 * transaction, which is capped at about a megabyte for the whole reply. A
 * faithful scaled-down copy of a long note is not small — a ten page note stays
 * tens of thousands of pixels tall — so only the <em>top</em> of the picture is
 * kept: enough to judge the background, the text colour and the layout, which is
 * what the preview is for, and a fixed budget that no note can exceed.
 */
public final class Thumbnail {

    private static final String TAG = Main.TAG;

    /** How wide the preview is drawn; the notes themselves are about 1264 px. */
    private static final int WIDTH = 320;

    /**
     * How much of the note is kept, relative to its width.
     *
     * <p>Two widths' worth of height is a phone shaped window on the top of the
     * note.
     */
    private static final int HEIGHT_TO_WIDTH = 2;

    /** A reply larger than this is not worth the risk of a binder refusal. */
    private static final int MAX_BYTES = 384 * 1024;

    private Thumbnail() {
    }

    /**
     * The top of {@code picture} as a PNG, or null if no preview could be made.
     *
     * <p>The caller keeps ownership of {@code picture}; nothing here recycles it.
     */
    public static byte[] of(Bitmap picture) {
        if (picture == null || picture.isRecycled()
                || picture.getWidth() <= 0 || picture.getHeight() <= 0) {
            return null;
        }
        try {
            int slice = Math.min(picture.getHeight(), picture.getWidth() * HEIGHT_TO_WIDTH);
            Bitmap top = Bitmap.createBitmap(picture, 0, 0, picture.getWidth(), slice);
            byte[] bytes = null;
            try {
                // A very tall, very detailed note can still outgrow the budget at
                // this width, so it is offered a smaller one before it is dropped.
                int[] widths = {WIDTH, WIDTH / 2};
                for (int width : widths) {
                    bytes = encode(top, width);
                    if (bytes != null) {
                        break;
                    }
                }
            } finally {
                top.recycle();
            }
            return bytes;
        } catch (Throwable t) {
            Log.w(TAG, "thumbnail: could not be made: " + t);
            return null;
        }
    }

    /** Scales the slice to {@code width} and encodes it, or null if too large. */
    private static byte[] encode(Bitmap slice, int width) {
        int height = Math.max(1, Math.round(
                slice.getHeight() * (width / (float) slice.getWidth())));
        Bitmap scaled = Bitmap.createScaledBitmap(slice, width, height, true);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                return null;
            }
            byte[] bytes = out.toByteArray();
            Log.i(TAG, "thumbnail: " + width + "x" + height + ", "
                    + bytes.length + " bytes");
            if (bytes.length > MAX_BYTES) {
                return null;
            }
            return bytes;
        } catch (Throwable t) {
            Log.w(TAG, "thumbnail: encoding failed: " + t);
            return null;
        } finally {
            scaled.recycle();
        }
    }
}
