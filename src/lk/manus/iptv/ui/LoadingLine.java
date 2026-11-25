package lk.manus.iptv.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The one loading look: a status line in PAPER/MUTED that names what is loading, over a hairline LINE track with a
 * short SIGNAL segment sweeping across (static, centred segment on low-tier TVs). Text changes cross-fade.
 */
public final class LoadingLine extends LinearLayout {
    private final TextView text;
    private final Track track;

    public LoadingLine(Context c, int widthPx) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        text = Ui.tv(c, Ui.BODY, Ui.MUTED, false);
        text.setGravity(Gravity.CENTER);
        addView(text, new LayoutParams(-2, -2));
        track = new Track(c);
        LayoutParams tp = new LayoutParams(widthPx, Math.max(2, Ui.u(3)));
        tp.topMargin = Ui.u(14);
        addView(track, tp);
    }

    /** Shows {@code s}; a different text fades in (no fade on low-tier TVs). */
    public void set(String s) {
        if (s == null || s.contentEquals(text.getText())) return;
        text.setText(s);
        if (Ui.anim()) { text.animate().cancel(); text.setAlpha(0.35f); text.animate().alpha(1f).setDuration(220).start(); }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); track.start(); }
    @Override protected void onDetachedFromWindow() { track.stop(); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View v, int vis) { super.onVisibilityChanged(v, vis); if (vis == VISIBLE && isAttachedToWindow()) track.start(); else track.stop(); }

    private static final class Track extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator a;
        private float x = -1f; // segment start as a fraction of the width; -1 = static

        Track(Context c) { super(c); bg.setColor(Ui.LINE); fg.setColor(Ui.SIGNAL); }

        void start() {
            if (!Ui.anim() || (a != null && a.isRunning())) return;
            a = ValueAnimator.ofFloat(-0.3f, 1f);
            a.setDuration(1200);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(Ui.DEC);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { @Override public void onAnimationUpdate(ValueAnimator v) { x = (Float) v.getAnimatedValue(); invalidate(); } });
            a.start();
        }

        void stop() { if (a != null) { a.cancel(); a = null; } }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            c.drawRoundRect(0, 0, w, h, r, r, bg);
            float seg = w * 0.3f, s = x < -0.9f ? (w - seg) / 2f : x * w;
            c.drawRoundRect(Math.max(0, s), 0, Math.min(w, s + seg), h, r, r, fg);
        }
    }
}
