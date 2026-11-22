package lk.manus.iptv.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import lk.manus.iptv.meta.MetaStore;
import lk.manus.iptv.meta.PosterLoader;
import lk.manus.iptv.ui.Films.Entry;

/** Full-screen Details page for a film on a channel (NEXT-GEN 6.3), over the dimmed live picture. Keys reach its buttons through the guide's raw-key path; Back is the guide's. */
public final class FilmDetails extends FrameLayout {
    public interface Host {
        void watch(Entry e);          // tune the channel
        void trailer(String ytKey);   // hand off to the YouTube app
        void notThisFilm(Entry e);    // forget the match
        boolean reminded(Entry e);    // a reminder is set for this showing
        void remind(Entry e);         // OK on the reminder button: set it, or ask to cancel it
    }

    private final Host host;
    private final ImageView backdrop, poster;
    private final TextView title, meta, rating, genres, plot, cast, listed, starts;
    private final LinearLayout buttons;
    private final TextView watch, trailerBtn, notThis, remindBtn;
    private Entry entry;
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);

    public FilmDetails(Context c, Host host) {
        super(c);
        this.host = host;
        setClickable(true);
        setBackgroundColor(Ui.alpha(Ui.INK, 0.8f));
        setVisibility(GONE);
        backdrop = new ImageView(c);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.28f); // dimmed
        addView(backdrop, new LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        poster = new ImageView(c);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setClipToOutline(true);
        poster.setBackground(Ui.rect(Ui.PANEL, 10));
        row.addView(poster, new LinearLayout.LayoutParams(Ui.u(190), Ui.u(285)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.u(28), 0, 0, 0);
        title = Ui.tv(c, Ui.DISPLAY, Ui.PAPER, true);
        meta = Ui.tv(c, Ui.BODY, Ui.MUTED, false);
        rating = Ui.tv(c, Ui.BODY, Ui.GOLD, true);
        genres = Ui.tv(c, Ui.BODY, Ui.MUTED, false);
        plot = Ui.tv(c, Ui.BODY, Ui.PAPER, false);
        plot.setSingleLine(false);
        plot.setMaxLines(4);
        cast = Ui.tv(c, Ui.CAPTION, Ui.MUTED, false);
        cast.setSingleLine(false);
        cast.setMaxLines(2);
        listed = Ui.tv(c, Ui.CAPTION, Ui.MUTED, false);
        listed.setAlpha(0.7f);
        listed.setPadding(0, Ui.u(6), 0, 0);
        meta.setPadding(0, Ui.u(6), 0, 0);
        rating.setPadding(0, Ui.u(8), 0, 0);
        genres.setPadding(0, Ui.u(4), 0, 0);
        plot.setPadding(0, Ui.u(14), 0, 0);
        cast.setPadding(0, Ui.u(12), 0, 0);
        col.addView(title);
        col.addView(meta);
        col.addView(rating);
        col.addView(genres);
        col.addView(plot);
        col.addView(cast);
        col.addView(listed);
        buttons = new LinearLayout(c);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        starts = Ui.tv(c, Ui.BODY, Ui.MUTED, true);
        starts.setPadding(0, 0, Ui.u(24), 0);
        remindBtn = button(c, "Remind me");
        watch = button(c, "Watch");
        trailerBtn = button(c, "Trailer");
        notThis = button(c, "Not this film");
        buttons.addView(starts);
        for (TextView b : new TextView[]{remindBtn, watch, trailerBtn, notThis}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.u(50));
            lp.rightMargin = Ui.u(14);
            buttons.addView(b, lp);
        }
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = Ui.u(22);
        col.addView(buttons, bp);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        LayoutParams rp = new LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
        rp.setMargins(Ui.marginX(), Ui.marginY(), Ui.marginX(), Ui.marginY());
        addView(row, rp);

        remindBtn.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (entry != null) FilmDetails.this.host.remind(entry); } });
        watch.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (entry != null) FilmDetails.this.host.watch(entry); } });
        trailerBtn.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (entry != null && entry.film != null) FilmDetails.this.host.trailer(entry.film.trailer); } });
        notThis.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (entry != null) FilmDetails.this.host.notThisFilm(entry); } });
    }

    private Drawable focusBg() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Ui.ring());
        s.addState(new int[]{}, Ui.rect(Ui.alpha(Ui.PANEL, 0.6f), 14));
        return s;
    }

    private TextView button(Context c, String t) {
        TextView b = Ui.tv(c, Ui.BODY, Ui.PAPER, true);
        b.setText(t);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.u(24), 0, Ui.u(24), 0);
        b.setFocusable(true);
        b.setBackground(focusBg());
        b.setOnKeyListener(new OnKeyListener() { // keep focus on the buttons; nothing leaks to the guide
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN) step(v, k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    return true;
                }
                return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
            }
        });
        return b;
    }

    private void step(View from, int d) {
        List<View> v = new ArrayList<>();
        for (int i = 0; i < buttons.getChildCount(); i++) { View b = buttons.getChildAt(i); if (b.isFocusable() && b.getVisibility() == VISIBLE) v.add(b); }
        int i = v.indexOf(from) + d;
        if (i >= 0 && i < v.size()) v.get(i).requestFocus();
    }

    public boolean isUp() { return getVisibility() == VISIBLE; }

    /** Opens the page for a matched film. Watch (or the "Starts" text) first; focus on Watch when there is one. */
    public void show(Entry e) {
        entry = e;
        MetaStore.Film f = e.film;
        title.setText(f.title);
        listed.setText("Listed as: " + e.clean.title);
        listed.setVisibility(lk.manus.iptv.meta.TitleCleaner.fold(e.clean.title).equals(lk.manus.iptv.meta.TitleCleaner.fold(f.title)) ? GONE : VISIBLE);
        meta.setText(Films.join(f.releaseYear > 0 ? String.valueOf(f.releaseYear) : "", Films.runtime(f.runtime)));
        meta.setVisibility(meta.getText().length() == 0 ? GONE : VISIBLE);
        rating.setText(f.rating > 0 ? Films.rating(f) + " TMDB" : "");
        rating.setVisibility(f.rating > 0 ? VISIBLE : GONE);
        genres.setText(Films.genres(f, 4));
        genres.setVisibility(genres.getText().length() == 0 ? GONE : VISIBLE);
        plot.setText(f.plot);
        plot.setEllipsize(TextUtils.TruncateAt.END);
        plot.setVisibility(f.plot.isEmpty() ? GONE : VISIBLE);
        cast.setText(f.cast.isEmpty() ? "" : f.cast.replace(", ", " · "));
        cast.setVisibility(f.cast.isEmpty() ? GONE : VISIBLE);
        long startMs = e.slot.start * 1000 + (Films.debugFuture ? 3 * 3600000L : 0);
        boolean notYet = startMs > System.currentTimeMillis();
        starts.setText("Starts " + hm.format(new Date(startMs)));
        starts.setVisibility(notYet ? VISIBLE : GONE);
        watch.setVisibility(notYet ? GONE : VISIBLE);
        remindBtn.setVisibility(notYet && e.slot.start * 1000 > System.currentTimeMillis() ? VISIBLE : GONE); // a reminder needs a real future start
        refresh();
        trailerBtn.setVisibility(f.trailer.isEmpty() ? GONE : VISIBLE);
        PosterLoader pl = PosterLoader.get(getContext());
        poster.setImageDrawable(null);
        poster.setBackground(new LetterTile(f.title));
        if (!f.posterPath.isEmpty()) pl.load(f.posterPath, poster, Ui.u(190));
        backdrop.setImageDrawable(null);
        if (!Ui.low() && !f.backdropPath.isEmpty()) pl.loadBackdrop(f.backdropPath, backdrop); // no backdrop on the Low tier
        setVisibility(VISIBLE);
        (notYet ? (remindBtn.getVisibility() == VISIBLE ? remindBtn : trailerBtn.getVisibility() == VISIBLE ? trailerBtn : notThis) : watch).requestFocus();
    }

    /** The reminder button reads "Reminder set" while one exists for this showing. */
    public void refresh() { if (entry != null) remindBtn.setText(host.reminded(entry) ? "Reminder set" : "Remind me"); }

    public void hide() {
        setVisibility(GONE);
        poster.setImageDrawable(null);
        backdrop.setImageDrawable(null);
        entry = null;
    }
}
