package lk.manus.iptv.ui.wizard;

import android.content.Context;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import lk.manus.iptv.ui.Ui;

/** Wizard UI kit: UX-SPEC scaling (baseline 960x540dp, s = clamp(min(w/960, h/540), 0.85, 1.6), font scale up to 1.3) and the few widgets the steps share. */
final class Wiz {
        int wPx, hPx;

    Wiz(Context c) { update(c); }

    /** Re-read the canvas (the scale, safe area and font rules live in {@link Ui}). */
    void update(Context c) {
        Ui.init(c);
        wPx = Ui.screenW();
        hPx = Ui.screenH();
    }

    int u(float dp) { return Ui.u(dp); }

    TextView tv(Context c, CharSequence t, float dp, int color, boolean bold) {
        TextView v = Ui.tv(c, dp, color, bold);
        v.setSingleLine(false);
        v.setEllipsize(null);
        v.setText(t);
        return v;
    }

    /** A focusable tile: title (+ optional caption). setOn() shows a tick and a tinted fill, so state never relies on colour alone. */
    final class Tile extends LinearLayout {
        final TextView title, cap;
        private final String label;
        private boolean on;

        Tile(Context c, String label, String caption, boolean center) {
            super(c);
            this.label = label;
            setOrientation(VERTICAL);
            setFocusable(true);
            setClickable(true);
            setGravity(center ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
            setPadding(u(12), u(8), u(12), u(8));
            setMinimumHeight(u(50));
            title = tv(c, label, 18, Ui.PAPER, true);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            addView(title, new LayoutParams(center ? -2 : -1, -2));
            cap = tv(c, caption == null ? "" : caption, 14, Ui.MUTED, false);
            cap.setSingleLine(true);
            cap.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (caption != null) addView(cap, new LayoutParams(center ? -2 : -1, -2));
            paint();
        }

        void setOn(boolean v) { on = v; paint(); }
        boolean isOn() { return on; }

        private void paint() {
            title.setText(on ? "✓ " + label : label);
            StateListDrawable d = new StateListDrawable();
            d.addState(new int[]{android.R.attr.state_focused}, Ui.ring());
            d.addState(new int[]{}, on ? Ui.rect(Ui.RAISED, 14) : Ui.card(1f, 14));
            setBackground(d);
        }
    }

    Tile button(Context c, String text, boolean primary) {
        Tile t = new Tile(c, text, null, true);
        t.setMinimumWidth(u(150));
        if (primary) { t.setOn(false); t.title.setTextColor(Ui.PAPER); }
        return t;
    }
}
