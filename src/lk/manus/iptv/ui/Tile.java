package lk.manus.iptv.ui;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import lk.manus.iptv.data.Channel;
import lk.manus.iptv.meta.PosterLoader;

/** One focusable tile shared by the film grid and Home: a rounded picture (poster, channel logo or plain text) with the red focus ring, and an optional small line under it. */
final class Tile extends LinearLayout {
    static final int POSTER = 0, LOGO = 1, TEXT = 2;
    /** Height of the caption line, px. */
    static int captionH() { return Ui.u(22); }

    /** Poster picture; the guide title on the letter tile shows only while there is no poster. */
    private static final class Pic extends ImageView {
        TextView label;
        Pic(Context c) { super(c); }
        @Override public void setImageDrawable(Drawable d) {
            super.setImageDrawable(d);
            if (label != null) label.setVisibility(d == null ? VISIBLE : GONE);
        }
    }

    final TextView label, caption, badge, rate, more;
    Object item;
    private final Pic pic;
    private final LinearLayout progTrack;
    private final View progFill;
    private final View progEmpty;

    private final boolean reserve;   // the caption's height is kept even when it has no text, so every tile in a row is the same height

    Tile(Context c, int mode, boolean reserve) {
        super(c);
        this.reserve = reserve;
        setOrientation(VERTICAL);
        setFocusable(true);
        FrameLayout ring = new FrameLayout(c); // takes the tile's focused state, so the red ring surrounds the picture only
        int r = Ui.u(2);
        ring.setPadding(r, r, r, r);
        ring.setDuplicateParentStateEnabled(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, Ui.rect(Ui.SIGNAL, 10));
        bg.addState(new int[]{}, new ColorDrawable(0));
        ring.setBackground(bg);
        FrameLayout card = new FrameLayout(c);
        card.setClipToOutline(true);
        final float rad = Ui.u(8);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad); }
        });
        pic = new Pic(c);
        pic.setScaleType(mode == LOGO ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        if (mode == LOGO) { pic.setPadding(Ui.u(10), Ui.u(8), Ui.u(10), Ui.u(8)); pic.setBackgroundColor(Ui.PANEL); }
        card.addView(pic, new FrameLayout.LayoutParams(-1, -1));
        label = Ui.tv(c, Ui.CAPTION, Ui.PAPER, true);
        label.setSingleLine(false);
        label.setMaxLines(3);
        label.setPadding(Ui.u(6), Ui.u(5), Ui.u(6), Ui.u(5));
        if (mode == TEXT) { label.setGravity(Gravity.CENTER); card.addView(label, new FrameLayout.LayoutParams(-1, -1)); card.setBackground(Ui.rect(Ui.PANEL, 8)); }
        else {
            label.setBackgroundColor(Ui.alpha(Ui.INK, 0.7f));
            card.addView(label, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            if (mode == LOGO) label.setVisibility(GONE); else pic.label = label;
        }

        progTrack = new LinearLayout(c);
        progTrack.setOrientation(LinearLayout.HORIZONTAL);
        progFill = new View(c);
        progFill.setBackgroundColor(Ui.SIGNAL);
        progEmpty = new View(c);
        progEmpty.setBackgroundColor(Ui.alpha(Ui.INK, 0.6f));
        progTrack.addView(progFill, new LinearLayout.LayoutParams(0, -1, 0f));
        progTrack.addView(progEmpty, new LinearLayout.LayoutParams(0, -1, 1f));
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(-1, Ui.u(4), Gravity.BOTTOM);
        card.addView(progTrack, pbLp);
        progTrack.setVisibility(GONE);

        badge = Ui.tv(c, Ui.MICRO, Ui.PAPER, true);
        badge.setText("Series");
        GradientDrawable bgb = (GradientDrawable) Ui.chip();
        bgb.setColor(Ui.alpha(Ui.INK, 0.85f));
        badge.setBackground(bgb);
        badge.setPadding(Ui.u(6), Ui.u(2), Ui.u(6), Ui.u(2));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        blp.setMargins(Ui.u(6), Ui.u(6), 0, 0);
        card.addView(badge, blp);
        badge.setVisibility(GONE);

        rate = Ui.tv(c, Ui.MICRO, Ui.PAPER, true);
        GradientDrawable bgr = (GradientDrawable) Ui.chip();
        bgr.setColor(Ui.alpha(Ui.INK, 0.85f));
        rate.setBackground(bgr);
        rate.setPadding(Ui.u(6), Ui.u(2), Ui.u(6), Ui.u(2));
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rlp.setMargins(0, Ui.u(6), Ui.u(6), 0);
        card.addView(rate, rlp);
        rate.setVisibility(GONE);

        more = Ui.tv(c, Ui.MICRO, Ui.PAPER, true); // T110e: group size ("×4"), top-right under the rating chip
        GradientDrawable bgm = (GradientDrawable) Ui.chip();
        bgm.setColor(Ui.alpha(Ui.INK, 0.85f));
        more.setBackground(bgm);
        more.setPadding(Ui.u(6), Ui.u(2), Ui.u(6), Ui.u(2));
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        mlp.setMargins(0, Ui.u(6), Ui.u(6), 0);
        card.addView(more, mlp);
        more.setVisibility(GONE);

        addView(ring, new LinearLayout.LayoutParams(-1, 0, 1));
        ring.addView(card, new FrameLayout.LayoutParams(-1, -1));
        caption = Ui.tv(c, Ui.CAPTION, Ui.MUTED, false);
        caption.setPadding(Ui.u(4), Ui.u(3), Ui.u(2), 0);
        caption.setVisibility(reserve ? INVISIBLE : GONE);
        addView(caption, new LinearLayout.LayoutParams(-1, -2));
    }

    /** A film: its guide title on a letter tile until the poster arrives. */
    void poster(String title, String posterPath, int widthPx) {
        label.setText(title);
        pic.setBackground(new LetterTile(title));
        pic.setImageDrawable(null); // label on until a poster arrives
        if (!posterPath.isEmpty()) PosterLoader.get(getContext()).load(posterPath, pic, widthPx);
    }

    /** A channel: its logo (letter tile first). */
    void logo(Channel ch) { lk.manus.iptv.ui.LogoLoader.get(getContext()).load(ch.logo, ch.name, pic); }

    /** The small line under the picture; null or empty hides it. */
    void caption(String s) {
        boolean on = s != null && !s.isEmpty();
        caption.setVisibility(on ? VISIBLE : reserve ? INVISIBLE : GONE);
        if (on && !s.contentEquals(caption.getText())) caption.setText(s);
    }

    /** Small outline badge at top-left of the card (e.g. "Series" in search results); null or empty hides it. */
    void badge(String s) {
        boolean on = s != null && !s.isEmpty();
        badge.setVisibility(on ? VISIBLE : GONE);
        if (on && !s.contentEquals(badge.getText())) badge.setText(s);
    }

    /** Small chip at top-right of the card (a rating); null or empty hides it. */
    void rating(String s) {
        boolean on = s != null && !s.isEmpty();
        rate.setVisibility(on ? VISIBLE : GONE);
        if (on && !s.contentEquals(rate.getText())) rate.setText(s);
        chipPos();
    }

    /** Group size chip ("×4", top-right, under the rating when there is one); fewer than 2 hides it. */
    void more(int n) {
        more.setVisibility(n > 1 ? VISIBLE : GONE);
        if (n > 1) more.setText("×" + n);
        chipPos();
    }

    private void chipPos() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) more.getLayoutParams();
        int top = rate.getVisibility() == VISIBLE ? Ui.u(32) : Ui.u(6);
        if (lp.topMargin != top) { lp.topMargin = top; more.setLayoutParams(lp); }
    }

    /** Sets progress bar (0.0 to 1.0); 0 or negative hides it. */
    void progress(float fraction) {
        if (fraction <= 0f) {
            progTrack.setVisibility(GONE);
        } else {
            progTrack.setVisibility(VISIBLE);
            fraction = Math.max(0.01f, Math.min(1f, fraction));
            ((LinearLayout.LayoutParams) progFill.getLayoutParams()).weight = fraction;
            ((LinearLayout.LayoutParams) progEmpty.getLayoutParams()).weight = 1f - fraction;
            progTrack.requestLayout();
        }
    }

    /** A text-only tile (mode TEXT). */
    void text(String s) { label.setText(s); }
}
