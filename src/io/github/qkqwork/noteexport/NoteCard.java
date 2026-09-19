package io.github.qkqwork.noteexport;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Log;

/**
 * Wraps a note's long picture in the kind of card the Notes app shares.
 *
 * <p>The app stamps a watermark on a long picture it shares, and that watermark
 * lives in the card it builds around the note — a card this module never takes,
 * which is why the watermark setting reached the app's own share and not the
 * export. Drawing the card here is what lets a chosen watermark text appear in
 * an export at all: the card is ours, so its footer can carry whatever the
 * watermark setting says.
 *
 * <p>What is <em>not</em> copied is the app's own card down to the pixel. The
 * measurements are its own — a 1094 px card, the editor's pages are 1264 — and
 * the footer imitates its row of divider-then-name, but the font, the exact
 * shades and the spacing are this module's approximation. Anyone who wants the
 * app's card exactly should use the app's own "share as picture".
 */
final class NoteCard {

    private static final String TAG = Main.TAG;

    /** The width the app's own share card comes out at, measured on a device. */
    private static final int WIDTH = 1094;
    /** How far the note is inset from the card's edge. */
    private static final int MARGIN = 26;
    /** The gap between the note and the footer's divider, and the footer's own height. */
    private static final int FOOTER_GAP = 34;
    private static final int FOOTER_HEIGHT = 86;
    private static final int DIVIDER_HEIGHT = 2;
    /** The footer text's height as a fraction of the card's width. */
    private static final float TEXT_RATIO = 0.033f;

    private NoteCard() {
    }

    /**
     * The card around {@code picture}, or null when it could not be drawn.
     *
     * <p>The caller keeps ownership of {@code picture}: nothing here recycles it.
     */
    static Bitmap apply(Context context, Bitmap picture, int background) {
        if (picture == null || picture.isRecycled() || picture.getWidth() <= 0) {
            return null;
        }
        WatermarkSettings watermark = WatermarkSettings.read(context);
        boolean withText = watermark.active() && watermark.custom()
                && watermark.text.length() > 0;
        // "Leave it alone" cannot mean anything here: the thing left alone is the
        // app's own logo, which is not in this picture to begin with.
        boolean withFooter = withText
                || ConfigContract.WATERMARK_KEEP_SPACE.equals(watermark.mode);
        int inner = WIDTH - MARGIN * 2;
        int scaledHeight = Math.max(1,
                Math.round(picture.getHeight() * (inner / (float) picture.getWidth())));
        int footer = withFooter ? FOOTER_GAP + DIVIDER_HEIGHT + FOOTER_HEIGHT : 0;
        int height = MARGIN + scaledHeight + MARGIN + footer;

        boolean light = isLight(background);
        int card = light ? 0xFFF1F1F1 : 0xFF161616;
        int line = light ? 0x33000000 : 0x33FFFFFF;
        int ink = light ? 0xFF8A8A8A : 0xFFB4B4B4;

        Bitmap carded = null;
        Bitmap scaled = null;
        try {
            carded = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(carded);
            canvas.drawColor(card);
            scaled = Bitmap.createScaledBitmap(picture, inner, scaledHeight, true);
            canvas.drawBitmap(scaled, MARGIN, MARGIN, null);

            if (withFooter) {
                int top = MARGIN + scaledHeight + MARGIN;
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(line);
                canvas.drawRect(MARGIN, top, WIDTH - MARGIN, top + DIVIDER_HEIGHT, paint);
                if (withText) {
                    paint.setColor(ink);
                    paint.setTextSize(WIDTH * TEXT_RATIO);
                    paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
                    paint.setTextAlign(Paint.Align.CENTER);
                    float baseline = top + DIVIDER_HEIGHT + FOOTER_HEIGHT * 0.62f;
                    canvas.drawText(watermark.text, WIDTH / 2f, baseline, paint);
                }
            }
            Log.i(TAG, "note card: " + WIDTH + "x" + height + " around a "
                    + picture.getWidth() + "x" + picture.getHeight() + " note"
                    + (withText ? ", footer \"" + watermark.text + "\"" : withFooter
                            ? ", footer left blank" : ", no footer"));
            return carded;
        } catch (Throwable t) {
            Log.w(TAG, "note card: could not be drawn: " + t);
            if (carded != null) {
                carded.recycle();
            }
            return null;
        } finally {
            if (scaled != null) {
                scaled.recycle();
            }
        }
    }

    private static boolean isLight(int colour) {
        int luminance = (((colour >> 16) & 0xFF) * 299 + ((colour >> 8) & 0xFF) * 587
                + (colour & 0xFF) * 114) / 1000;
        return luminance >= 128;
    }
}
