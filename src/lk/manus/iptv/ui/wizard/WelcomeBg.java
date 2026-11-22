package lk.manus.iptv.ui.wizard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import lk.manus.iptv.ui.Ui;

/**
 * Welcome-screen backdrop: a slowly drifting wall of film posters, blurred (decoded to ~48 px wide, then scaled up) and dimmed.
 * Posters come from the poster disk cache already on the device (at most 12 tiny bitmaps). Nothing cached yet: LINE-coloured
 * tiles drift instead. Nothing is allocated per frame; the Low tier redraws less often.
 */
final class WelcomeBg extends View {
    private static final int MAX = 12, ROWS = 3;
    private final List<Bitmap> bmps = new ArrayList<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG), fill = new Paint(), dim = new Paint();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private float off;
    private long last;
    private volatile boolean gone;

    WelcomeBg(Context c) {
        super(c);
        fill.setColor(Ui.alpha(Ui.LINE, 0.55f));
        dim.setColor(Ui.alpha(Ui.INK, 0.74f));
        final File dir = new File(c.getCacheDir(), "posters");
        new Thread(new Runnable() {
            @Override public void run() {
                File[] fs = dir.listFiles();
                if (fs == null) return;
                final List<Bitmap> got = new ArrayList<>();
                for (File f : fs) {
                    if (got.size() >= MAX) break;
                    if (!f.getName().endsWith(".jpg")) continue;
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getPath(), o);
                    if (o.outWidth <= 0) continue;
                    o.inJustDecodeBounds = false;
                    o.inSampleSize = Math.max(1, o.outWidth / 48);
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
                    if (b != null) got.add(b);
                }
                if (gone) return;
                post(new Runnable() { @Override public void run() { bmps.addAll(got); } });
            }
        }, "welcome-bg").start();
    }

    void release() { gone = true; bmps.clear(); }

    @Override protected void onDraw(Canvas cv) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0 : Math.min(0.2f, (now - last) / 1e9f);
        last = now;
        int w = getWidth(), h = getHeight();
        float tw = w / 6f, th = tw * 1.5f, gap = tw * 0.08f, span = tw + gap;
        off += dt * Ui.u(10);
        cv.drawColor(Ui.INK);
        int n = bmps.size();
        for (int r = 0; r < ROWS; r++) {
            float y = r * (th + gap) - th * 0.35f;
            float total = off * (r % 2 == 0 ? 1 : -1) * (1 + r * 0.25f);
            int q = (int) Math.floor(total / span);
            float sh = total - q * span;
            for (int j = -1; sh + j * span < w; j++) {
                float x = sh + j * span;
                dst.set(x, y, x + tw, y + th);
                if (n == 0) { cv.drawRoundRect(dst, Ui.u(6), Ui.u(6), fill); continue; }
                Bitmap b = bmps.get(Math.floorMod(j - q + r * 5, n));
                src.set(0, 0, b.getWidth(), b.getHeight());
                cv.drawBitmap(b, src, dst, paint);
            }
        }
        cv.drawRect(0, 0, w, h, dim);
        postInvalidateDelayed(Ui.low() ? 100 : 40);
    }
}
