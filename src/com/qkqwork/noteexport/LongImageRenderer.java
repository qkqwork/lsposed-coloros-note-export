package com.qkqwork.noteexport;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a note as one long picture, without a browser engine.
 *
 * <p>The first attempt rendered the note's HTML in a WebView. On paper that was
 * the cheap route — the note's rich text <em>is</em> HTML — but on this device it
 * never worked: the image export is triggered by a provider query that cold-starts
 * the Notes app, Chromium then has to start inside that process, and the WebView
 * constructor blocked the app's main thread for minutes without ever finishing.
 * Every note timed out and an export of 54 notes produced nothing at all.
 *
 * <p>So the picture is drawn directly instead. The note is parsed into the same
 * {@link Doc} model the Word export uses, and that model is painted here with
 * {@link StaticLayout} for text and {@link Canvas} for pictures. No browser, no
 * second process, no main-thread gymnastics — and a note lays out in
 * milliseconds instead of timing out.
 */
public final class LongImageRenderer {

    private static final String TAG = Main.TAG;

    /** CSS pixels wide, matching a comfortable reading width on a phone. */
    private static final int VIEWPORT_WIDTH_PX = 760;
    /** Drawn at twice that size so the result stays crisp. */
    private static final float SCALE = 2f;
    private static final float PADDING_PX = 16f;
    /** Body text: 16 CSS px, as a browser would render it. */
    private static final float BODY_PX = 16f;
    private static final float LINE_SPACING = 1.7f;
    /** Height cap; a note with hundreds of pictures would otherwise exhaust memory. */
    private static final int MAX_HEIGHT_PX = 40000;
    /** Word measures in twentieths of a point; a CSS pixel is 3/4 of a point. */
    private static final float TWIPS_TO_CSS_PX = 96f / 1440f;

    private LongImageRenderer() {
    }

    /** A rendered picture at {@link #SCALE} times the CSS size, or null. */
    public static Bitmap render(Context context, String html, File baseDir) {
        Doc doc = new Doc();
        HtmlToWord.convert(html, doc, baseDir);
        if (doc.paragraphs.isEmpty() && doc.images.isEmpty()) {
            Log.w(TAG, "render: the note has nothing to draw");
            return null;
        }

        float textWidth = VIEWPORT_WIDTH_PX - PADDING_PX * 2;
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF111111);
        paint.setTextSize(BODY_PX);

        // Laid out in full before anything is drawn, so the bitmap can be sized
        // exactly instead of guessed at and cropped.
        List<Block> blocks = new ArrayList<>();
        float bottom = PADDING_PX;
        for (Doc.Paragraph paragraph : doc.paragraphs) {
            float before = paragraph.spaceBefore * TWIPS_TO_CSS_PX;
            float after = paragraph.spaceAfter * TWIPS_TO_CSS_PX;
            float indent = Math.max(0f, paragraph.indentLeft * TWIPS_TO_CSS_PX);
            Block block = new Block();
            block.top = bottom + before;
            block.indent = indent;
            block.alignment = alignmentOf(paragraph.align);

            Bitmap picture = decode(paragraph);
            if (picture != null) {
                float available = Math.max(32f, textWidth - indent);
                float width = Math.min(available, picture.getWidth());
                block.bitmap = picture;
                block.width = width;
                block.height = picture.getHeight() * (width / picture.getWidth());
            } else {
                CharSequence text = spanned(paragraph, paint);
                if (text.length() == 0) {
                    block.height = BODY_PX;
                } else {
                    int available = (int) Math.max(32f, textWidth - indent);
                    block.layout = buildLayout(text, paint, available, block.alignment);
                    block.height = block.layout.getHeight();
                }
            }
            bottom = block.top + block.height + after;
            blocks.add(block);
            if (bottom > MAX_HEIGHT_PX) {
                Log.w(TAG, "render: the picture is capped at " + MAX_HEIGHT_PX + " px");
                break;
            }
        }

        int height = (int) Math.ceil(Math.min(bottom + PADDING_PX, MAX_HEIGHT_PX));
        Bitmap bitmap = Bitmap.createBitmap(
                (int) (VIEWPORT_WIDTH_PX * SCALE), (int) (height * SCALE),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(SCALE, SCALE);
        canvas.drawColor(Color.WHITE);

        for (Block block : blocks) {
            canvas.save();
            canvas.translate(PADDING_PX + block.indent, block.top);
            if (block.bitmap != null) {
                canvas.save();
                if (block.alignment == Layout.Alignment.ALIGN_CENTER) {
                    canvas.translate((textWidth - block.indent - block.width) / 2f, 0f);
                } else if (block.alignment == Layout.Alignment.ALIGN_OPPOSITE) {
                    canvas.translate(textWidth - block.indent - block.width, 0f);
                }
                canvas.drawBitmap(block.bitmap, null,
                        new RectF(0f, 0f, block.width, block.height), null);
                canvas.restore();
            } else if (block.layout != null) {
                canvas.save();
                if (block.alignment == Layout.Alignment.ALIGN_CENTER) {
                    canvas.translate((textWidth - block.indent - block.layout.getWidth()) / 2f, 0f);
                } else if (block.alignment == Layout.Alignment.ALIGN_OPPOSITE) {
                    canvas.translate(textWidth - block.indent - block.layout.getWidth(), 0f);
                }
                block.layout.draw(canvas);
                canvas.restore();
            }
            canvas.restore();
        }

        for (Block block : blocks) {
            if (block.bitmap != null) {
                block.bitmap.recycle();
            }
        }
        Log.i(TAG, "render: drew " + bitmap.getWidth() + "x" + height
                + " from " + blocks.size() + " blocks");
        return bitmap;
    }

    /**
     * Renders and streams the result straight into {@code out} as a PNG.
     *
     * <p>Answers with the picture that was drawn — already compressed, but still
     * usable as the source of a preview — or null if nothing was written. The
     * caller owns it and should recycle it.
     */
    public static Bitmap renderTo(Context context, String html, File baseDir,
            OutputStream out) {
        Bitmap bitmap = render(context, html, baseDir);
        if (bitmap == null) {
            return null;
        }
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                Log.w(TAG, "render: png compression reported failure");
                bitmap.recycle();
                return null;
            }
            Log.i(TAG, "render: wrote " + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " picture");
            return bitmap;
        } catch (Throwable t) {
            Log.e(TAG, "render: writing the picture failed", t);
            bitmap.recycle();
            return null;
        }
    }

    /** Debug helper: renders straight to a file. */
    public static boolean renderToFile(Context context, String html, File baseDir,
            File target) {
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(target);
            Bitmap rendered = renderTo(context, html, baseDir, out);
            if (rendered == null) {
                return false;
            }
            rendered.recycle();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "renderToFile failed", t);
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    // -------------------------------------------------------------- the pieces

    /** One laid-out paragraph, plus where it goes on the picture. */
    private static final class Block {
        StaticLayout layout;
        Bitmap bitmap;
        Layout.Alignment alignment = Layout.Alignment.ALIGN_NORMAL;
        float top;
        float height;
        float width;
        float indent;
    }

    private static Layout.Alignment alignmentOf(int align) {
        if (align == Doc.ALIGN_CENTER) {
            return Layout.Alignment.ALIGN_CENTER;
        }
        if (align == Doc.ALIGN_RIGHT) {
            return Layout.Alignment.ALIGN_OPPOSITE;
        }
        return Layout.Alignment.ALIGN_NORMAL;
    }

    private static StaticLayout buildLayout(CharSequence text, TextPaint paint, int width,
            Layout.Alignment alignment) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(alignment)
                .setLineSpacing(0f, LINE_SPACING)
                .setIncludePad(false)
                .build();
    }

    /** The paragraph's runs as one styled sequence. */
    private static CharSequence spanned(Doc.Paragraph paragraph, TextPaint paint) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        for (Doc.Run run : paragraph.runs) {
            if (run.isImage() || run.text == null || run.text.length() == 0) {
                continue;
            }
            int start = builder.length();
            builder.append(run.text);
            int end = builder.length();
            int flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
            if (run.bold || paragraph.heading > 0) {
                builder.setSpan(new StyleSpan(Typeface.BOLD), start, end, flags);
            }
            if (run.italic) {
                builder.setSpan(new StyleSpan(Typeface.ITALIC), start, end, flags);
            }
            if (run.underline) {
                builder.setSpan(new UnderlineSpan(), start, end, flags);
            }
            if (run.strike) {
                builder.setSpan(new StrikethroughSpan(), start, end, flags);
            }
            if (run.color != null && run.color.length() > 0) {
                try {
                    builder.setSpan(new ForegroundColorSpan(
                                    (int) Long.parseLong(run.color, 16) | 0xFF000000),
                            start, end, flags);
                } catch (Throwable ignored) {
                    // an unparseable colour simply stays at the default
                }
            }
            int halfPoints = run.sizeHalfPoints > 0 ? run.sizeHalfPoints
                    : (paragraph.heading > 0 ? headingHalfPoints(paragraph.heading) : 0);
            if (halfPoints > 0) {
                // Word's half-points to the CSS pixels a browser would use.
                builder.setSpan(new AbsoluteSizeSpan(Math.round(halfPoints / 2f * 4f / 3f)),
                        start, end, flags);
            }
        }
        return builder;
    }

    /** Matches the Word export: 16 pt, 14 pt, 13 pt, in half-points. */
    private static int headingHalfPoints(int heading) {
        if (heading <= 1) {
            return 32;
        }
        return heading == 2 ? 28 : 26;
    }

    private static Bitmap decode(Doc.Paragraph paragraph) {
        for (Doc.Run run : paragraph.runs) {
            if (run.isImage() && run.image != null && run.image.data != null) {
                try {
                    return BitmapFactory.decodeByteArray(
                            run.image.data, 0, run.image.data.length);
                } catch (Throwable t) {
                    Log.w(TAG, "render: a picture could not be decoded: " + t);
                    return null;
                }
            }
        }
        return null;
    }
}
