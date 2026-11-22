package lk.manus.iptv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Thin programme progress bar: dim track with a SIGNAL fill. */
public final class NowBar extends View {
    private final Paint track = new Paint(), fill = new Paint();
    private float p;

    public NowBar(Context c) {
        super(c);
        track.setColor(Ui.LINE);
        fill.setColor(Ui.SIGNAL);
    }

    public void set(float progress) { p = progress; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        c.drawRect(0, 0, getWidth(), getHeight(), track);
        c.drawRect(0, 0, getWidth() * p, getHeight(), fill);
    }
}
