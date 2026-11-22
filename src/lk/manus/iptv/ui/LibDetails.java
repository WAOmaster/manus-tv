package lk.manus.iptv.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import lk.manus.iptv.Config;
import lk.manus.iptv.data.Db;
import lk.manus.iptv.data.FilmResume;
import lk.manus.iptv.data.Library;
import lk.manus.iptv.data.XtreamSource;
import lk.manus.iptv.meta.MetaStore;
import lk.manus.iptv.meta.PosterLoader;
import lk.manus.iptv.meta.Tmdb;

/**
 * Dedicated Details panel for a movie in the Library (T82, T83).
 * Provider details fetched first; TMDB only fills empty gaps when film info is enabled.
 */
public final class LibDetails extends FrameLayout {
    public interface Host {
        void close();
        void trailer(String ytKey);
        void toast(String text);
        void playFilm(Library.Item item, long startPosMs);
    }

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ImageView backdrop, poster;
    private final TextView title, meta, rating, genres, plot, cast;
    private final LinearLayout buttons;
    private final TextView watchBtn, resumeBtn, startOverBtn, listBtn, trailerBtn, backBtn;
    private Library.Item currentItem;
    private String currentTrailer = "";
    private long currentSavedPosition = 0;

    public LibDetails(Context c, Host host) {
        super(c);
        this.host = host;
        setClickable(true);
        setBackgroundColor(Ui.alpha(Ui.INK, 0.8f));
        setVisibility(GONE);

        backdrop = new ImageView(c);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.28f);
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
        plot.setMaxLines(5);
        plot.setEllipsize(TextUtils.TruncateAt.END);

        cast = Ui.tv(c, Ui.CAPTION, Ui.MUTED, false);
        cast.setSingleLine(false);
        cast.setMaxLines(2);

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

        buttons = new LinearLayout(c);
        buttons.setGravity(Gravity.CENTER_VERTICAL);

        watchBtn = button(c, "Watch");
        resumeBtn = button(c, "Resume");
        startOverBtn = button(c, "Start over");
        listBtn = button(c, "Add to My list");
        trailerBtn = button(c, "Trailer");
        backBtn = button(c, "Back");

        for (TextView b : new TextView[]{watchBtn, resumeBtn, startOverBtn, listBtn, trailerBtn, backBtn}) {
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

        watchBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (LibDetails.this.host != null && currentItem != null) {
                    LibDetails.this.host.playFilm(currentItem, 0L);
                }
            }
        });

        resumeBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (LibDetails.this.host != null && currentItem != null) {
                    LibDetails.this.host.playFilm(currentItem, currentSavedPosition);
                }
            }
        });

        startOverBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (LibDetails.this.host != null && currentItem != null) {
                    LibDetails.this.host.playFilm(currentItem, 0L);
                }
            }
        });

        listBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Db db = Db.get();
                if (db == null || currentItem == null) return;
                boolean on = !db.isInMyList(currentItem.id);
                db.setMyList(currentItem.id, on);
                listBtn.setText(on ? "Remove from My list" : "Add to My list");
                if (LibDetails.this.host != null) LibDetails.this.host.toast(on ? "Added to My list" : "Removed from My list");
            }
        });

        backBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                hide();
                if (LibDetails.this.host != null) LibDetails.this.host.close();
            }
        });

        trailerBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (LibDetails.this.host != null && !currentTrailer.isEmpty()) {
                    LibDetails.this.host.trailer(currentTrailer);
                }
            }
        });
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
        b.setOnKeyListener(new OnKeyListener() {
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
        for (int i = 0; i < buttons.getChildCount(); i++) {
            View b = buttons.getChildAt(i);
            if (b.isFocusable() && b.getVisibility() == VISIBLE) v.add(b);
        }
        int i = v.indexOf(from) + d;
        if (i >= 0 && i < v.size()) v.get(i).requestFocus();
    }

    public boolean isUp() {
        return getVisibility() == VISIBLE;
    }

    public void show(final Library.Item item) {
        currentItem = item;
        currentTrailer = "";

        title.setText(item.title);
        meta.setText(item.year > 0 ? String.valueOf(item.year) : "");
        meta.setVisibility(meta.getText().length() > 0 ? VISIBLE : GONE);

        if (item.rating > 0) {
            rating.setText(String.format(Locale.US, "★ %.1f/10", item.rating));
            rating.setVisibility(VISIBLE);
        } else {
            rating.setVisibility(GONE);
        }

        genres.setText(item.groupName);
        genres.setVisibility(!item.groupName.isEmpty() ? VISIBLE : GONE);

        plot.setVisibility(GONE);
        cast.setVisibility(GONE);
        trailerBtn.setVisibility(GONE);

        PosterLoader pl = PosterLoader.get(getContext());
        poster.setImageDrawable(null);
        poster.setBackground(new LetterTile(item.title));
        if (!item.poster.isEmpty()) pl.load(item.poster, poster, Ui.u(190));
        backdrop.setImageDrawable(null);

        setVisibility(VISIBLE);

        Db ldb = Db.get();
        listBtn.setText(ldb != null && ldb.isInMyList(item.id) ? "Remove from My list" : "Add to My list");
        listBtn.setVisibility(Config.PLAY || ldb == null ? GONE : VISIBLE);

        currentSavedPosition = 0;
        if (Config.PLAY) {
            watchBtn.setVisibility(GONE);
            resumeBtn.setVisibility(GONE);
            startOverBtn.setVisibility(GONE);
            backBtn.requestFocus();
        } else {
            Db db = Db.get();
            Db.Progress prog = db != null ? db.progress(item.id) : null;
            if (prog != null && FilmResume.canResume(prog.positionMs, prog.durationMs, prog.watched)) {
                currentSavedPosition = prog.positionMs;
                watchBtn.setVisibility(GONE);
                resumeBtn.setText(FilmResume.resumeButtonText(prog.positionMs));
                resumeBtn.setVisibility(VISIBLE);
                startOverBtn.setVisibility(VISIBLE);
                resumeBtn.requestFocus();
            } else {
                watchBtn.setVisibility(VISIBLE);
                resumeBtn.setVisibility(GONE);
                startOverBtn.setVisibility(GONE);
                watchBtn.requestFocus();
            }
        }

        // 1. Fetch provider vod info first
        final Context ctx = getContext();
        XtreamSource.vodInfo(Db.get(), item.id, new XtreamSource.VodInfoCallback() {
            @Override public void onInfo(final XtreamSource.VodInfo info) {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (currentItem != item || !isUp()) return;
                        applyVodInfo(item, info);
                    }
                });
            }
        });
    }

    private void applyVodInfo(final Library.Item item, final XtreamSource.VodInfo info) {
        String p = (info != null && !info.plot.isEmpty()) ? info.plot : "";
        String g = (info != null && !info.genres.isEmpty()) ? info.genres : item.groupName;
        int rt = info != null ? info.runtime : 0;
        String c = (info != null && !info.cast.isEmpty()) ? info.cast : "";
        String bd = (info != null && !info.backdrop.isEmpty()) ? info.backdrop : "";
        long tmdbId = info != null ? info.tmdbId : 0;
        double r = (info != null && info.rating > 0) ? info.rating : item.rating;

        // Update UI
        updateFields(item.year, rt, r, g, p, c, bd);

        // 2. TMDB only fills empty gaps (plot/rating/poster/trailer still empty)
        // only when viewer has a key and film information is not Off (Films.enabled)
        final Context ctx = getContext();
        if (!Films.enabled(ctx)) return;

        final boolean needPlot = p.isEmpty();
        final boolean needRating = r == 0;
        final boolean needTrailer = currentTrailer.isEmpty();
        final boolean needBackdrop = bd.isEmpty();

        if (!needPlot && !needRating && !needTrailer && !needBackdrop) return;

        new Thread(new Runnable() {
            @Override public void run() {
                Tmdb tmdb = Tmdb.get(ctx);
                Tmdb.Details d = null;
                if (tmdbId > 0) {
                    d = tmdb.details((int) tmdbId);
                }
                if (d == null) {
                    MetaStore.Film f = MetaStore.get(ctx).get(item.title, item.year);
                    if (f != null && f.tmdbId > 0) {
                        d = tmdb.details((int) f.tmdbId);
                    }
                }
                if (d != null) {
                    final Tmdb.Details td = d;
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (currentItem != item || !isUp()) return;
                            fillTmdbGaps(td);
                        }
                    });
                }
            }
        }, "tmdb-gap-fill").start();
    }

    private void updateFields(int year, int runtimeMins, double rate, String genreStr, String plotStr, String castStr, String backdropUrl) {
        StringBuilder mb = new StringBuilder();
        if (year > 0) mb.append(year);
        if (runtimeMins > 0) {
            if (mb.length() > 0) mb.append(" · ");
            mb.append(runtimeMins).append(" min");
        }
        meta.setText(mb.toString());
        meta.setVisibility(mb.length() > 0 ? VISIBLE : GONE);

        if (rate > 0) {
            rating.setText(String.format(Locale.US, "★ %.1f/10", rate));
            rating.setVisibility(VISIBLE);
        } else {
            rating.setVisibility(GONE);
        }

        genres.setText(genreStr.replace(", ", " · "));
        genres.setVisibility(!genreStr.isEmpty() ? VISIBLE : GONE);

        plot.setText(plotStr);
        plot.setVisibility(!plotStr.isEmpty() ? VISIBLE : GONE);

        cast.setText(castStr.replace(", ", " · "));
        cast.setVisibility(!castStr.isEmpty() ? VISIBLE : GONE);

        if (!Ui.low() && !backdropUrl.isEmpty()) {
            PosterLoader.get(getContext()).loadBackdrop(backdropUrl, backdrop);
        }
    }

    private void fillTmdbGaps(Tmdb.Details d) {
        if (d == null) return;
        // Never overwrite a non-empty provider field
        if (plot.getVisibility() != VISIBLE && d.overview != null && !d.overview.trim().isEmpty()) {
            plot.setText(d.overview.trim());
            plot.setVisibility(VISIBLE);
        }
        if (rating.getVisibility() != VISIBLE && d.voteAverage > 0) {
            rating.setText(String.format(Locale.US, "★ %.1f/10", d.voteAverage));
            rating.setVisibility(VISIBLE);
        }
        if (genres.getVisibility() != VISIBLE && d.genres != null && !d.genres.isEmpty()) {
            genres.setText(d.genres.replace(", ", " · "));
            genres.setVisibility(VISIBLE);
        }
        if (cast.getVisibility() != VISIBLE && d.cast != null && !d.cast.isEmpty()) {
            cast.setText(d.cast.replace(", ", " · "));
            cast.setVisibility(VISIBLE);
        }
        if (!Ui.low() && backdrop.getDrawable() == null && d.backdropPath != null && !d.backdropPath.isEmpty()) {
            PosterLoader.get(getContext()).loadBackdrop(d.backdropPath, backdrop);
        }
        if (d.trailer != null && !d.trailer.isEmpty()) {
            currentTrailer = d.trailer;
            trailerBtn.setVisibility(VISIBLE);
        }
    }

    public void hide() {
        setVisibility(GONE);
        poster.setImageDrawable(null);
        backdrop.setImageDrawable(null);
        currentItem = null;
        currentTrailer = "";
    }
}
