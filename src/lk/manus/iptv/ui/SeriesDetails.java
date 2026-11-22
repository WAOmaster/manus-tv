package lk.manus.iptv.ui;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lk.manus.iptv.Config;
import lk.manus.iptv.data.Db;
import lk.manus.iptv.data.FilmResume;
import lk.manus.iptv.data.Library;
import lk.manus.iptv.data.SeriesLogic;
import lk.manus.iptv.data.SeriesSource;
import lk.manus.iptv.data.Store;
import lk.manus.iptv.meta.PosterLoader;

/**
 * Series Details screen (T92c): poster, title, meta, plot,
 * dynamic primary button ("Continue S2 E4" / "Start S1 E1" / "Watch again from S1 E1"),
 * season tabs as outline chips, 64dp episode rows with length, checkmark, and progress bar.
 */
public final class SeriesDetails extends FrameLayout {
    private static final String TAG = "IPTV";

    public interface Host {
        void close();
        void playFilm(Library.Item item, long startPosMs);
        void toast(String text);
    }

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ImageView backdrop, poster;
    private final TextView titleTv, metaTv, plotTv, primaryBtn, listBtn, backBtn, statusTv, plotBelowTv;
    private final LinearLayout buttonsRow, seasonTabsRow, episodeContainer;
    private final HorizontalScrollView seasonScroll;
    private final ScrollView episodeScroll;

    private Library.Item currentSeries;
    private int selectedSeason = 1;
    private final List<SeriesSource.Season> currentSeasons = new ArrayList<>();
    private final List<SeriesSource.Episode> allEpisodes = new ArrayList<>();
    private SeriesLogic.SeriesProgress currentProgress;
    private boolean isLoading, isFailed;
    private long openTimeMs;
    private SeriesSource.Episode focusedEpisode;

    public SeriesDetails(Context c, Host host) {
        super(c);
        this.host = host;
        setClickable(true);
        setBackgroundColor(Ui.alpha(Ui.INK, 0.8f));
        setVisibility(GONE);

        backdrop = new ImageView(c);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.28f);
        addView(backdrop, new LayoutParams(-1, -1));

        LinearLayout mainLayout = new LinearLayout(c);
        mainLayout.setOrientation(LinearLayout.VERTICAL);

        // 1. Header (poster + details + buttons)
        LinearLayout headerRow = new LinearLayout(c);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        poster = new ImageView(c);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setClipToOutline(true);
        poster.setBackground(Ui.rect(Ui.PANEL, 10));
        headerRow.addView(poster, new LinearLayout.LayoutParams(Ui.u(120), Ui.u(180)));

        LinearLayout headerCol = new LinearLayout(c);
        headerCol.setOrientation(LinearLayout.VERTICAL);
        headerCol.setPadding(Ui.u(24), 0, 0, 0);

        titleTv = Ui.tv(c, Ui.TITLE, Ui.PAPER, true);
        metaTv = Ui.tv(c, Ui.BODY, Ui.MUTED, false);
        metaTv.setPadding(0, Ui.u(4), 0, 0);

        plotTv = Ui.tv(c, Ui.BODY, Ui.PAPER, false);
        plotTv.setSingleLine(false);
        plotTv.setMaxLines(3);
        plotTv.setEllipsize(TextUtils.TruncateAt.END);
        plotTv.setPadding(0, Ui.u(8), 0, 0);

        buttonsRow = new LinearLayout(c);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(Gravity.CENTER_VERTICAL);
        buttonsRow.setPadding(0, Ui.u(12), 0, 0);

        primaryBtn = button(c, "Start S1 E1");
        listBtn = button(c, "Add to My list");
        backBtn = button(c, "Back");

        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(-2, Ui.u(44));
        pbp.rightMargin = Ui.u(14);
        buttonsRow.addView(primaryBtn, pbp);
        LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(-2, Ui.u(44));
        lbp.rightMargin = Ui.u(14);
        buttonsRow.addView(listBtn, lbp);
        buttonsRow.addView(backBtn, new LinearLayout.LayoutParams(-2, Ui.u(44)));

        headerCol.addView(titleTv); headerCol.addView(metaTv); headerCol.addView(plotTv); headerCol.addView(buttonsRow);
        headerRow.addView(headerCol, new LinearLayout.LayoutParams(0, -2, 1f));
        mainLayout.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));

        // 2. Season tabs
        seasonScroll = new HorizontalScrollView(c);
        seasonScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        seasonScroll.setHorizontalScrollBarEnabled(false);
        seasonScroll.setFocusable(false);

        seasonTabsRow = new LinearLayout(c);
        seasonTabsRow.setOrientation(LinearLayout.HORIZONTAL);
        seasonTabsRow.setGravity(Gravity.CENTER_VERTICAL);
        seasonTabsRow.setPadding(Ui.u(4), Ui.u(4), Ui.u(4), Ui.u(4));
        seasonScroll.addView(seasonTabsRow, new LayoutParams(-2, -2));

        LinearLayout.LayoutParams ssp = new LinearLayout.LayoutParams(-1, -2);
        ssp.topMargin = Ui.u(12);
        mainLayout.addView(seasonScroll, ssp);

        // 3. Content box (episode list + status text)
        FrameLayout contentBox = new FrameLayout(c);
        episodeScroll = new ScrollView(c);
        episodeScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        episodeScroll.setVerticalScrollBarEnabled(false);
        episodeScroll.setFocusable(false);

        episodeContainer = new LinearLayout(c);
        episodeContainer.setOrientation(LinearLayout.VERTICAL);
        episodeScroll.addView(episodeContainer, new LayoutParams(-1, -2));
        contentBox.addView(episodeScroll, new LayoutParams(-1, -1));

        statusTv = Ui.tv(c, Ui.BODY, Ui.MUTED, false);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setVisibility(GONE);
        contentBox.addView(statusTv, new LayoutParams(-2, -2, Gravity.CENTER));

        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cbp.topMargin = Ui.u(8);
        mainLayout.addView(contentBox, cbp);

        // 4. Episode plot line of focused row below the list
        plotBelowTv = Ui.tv(c, Ui.CAPTION, Ui.PAPER, false);
        plotBelowTv.setSingleLine(false);
        plotBelowTv.setMaxLines(2);
        plotBelowTv.setEllipsize(TextUtils.TruncateAt.END);
        plotBelowTv.setPadding(Ui.u(6), Ui.u(6), Ui.u(6), Ui.u(4));
        plotBelowTv.setVisibility(INVISIBLE);

        LinearLayout.LayoutParams pbp2 = new LinearLayout.LayoutParams(-1, -2);
        pbp2.topMargin = Ui.u(4);
        mainLayout.addView(plotBelowTv, pbp2);

        LayoutParams mlp = new LayoutParams(-1, -1);
        mlp.setMargins(Ui.marginX(), Ui.marginY(), Ui.marginX(), Ui.marginY());
        addView(mainLayout, mlp);

        setupButtons();
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
        b.setClickable(true);
        b.setBackground(focusBg());
        return b;
    }

    private void setupButtons() {
        primaryBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (isLoading) return;
                if (isFailed) { loadEpisodes(true); return; }
                playTarget();
            }
        });
        primaryBtn.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { listBtn.requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) { focusCurrentSeasonTab(); return true; }
                return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_LEFT;
            }
        });
        listBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                Db db = Db.get();
                if (db == null || currentSeries == null) return;
                boolean on = !db.isInMyList(currentSeries.id);
                db.setMyList(currentSeries.id, on);
                listBtn.setText(on ? "Remove from My list" : "Add to My list");
                if (host != null) host.toast(on ? "Added to My list" : "Removed from My list");
            }
        });
        listBtn.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) { if (primaryBtn.getVisibility() == VISIBLE) primaryBtn.requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { backBtn.requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) { focusCurrentSeasonTab(); return true; }
                return k == KeyEvent.KEYCODE_DPAD_UP;
            }
        });
        backBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { hide(); if (host != null) host.close(); }
        });
        backBtn.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) { listBtn.requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) { focusCurrentSeasonTab(); return true; }
                return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_RIGHT;
            }
        });
    }

    private void focusCurrentSeasonTab() {
        for (int i = 0; i < seasonTabsRow.getChildCount(); i++) {
            View child = seasonTabsRow.getChildAt(i);
            if (child instanceof ChipView && ((ChipView) child).seasonNumber == selectedSeason) { child.requestFocus(); return; }
        }
        if (seasonTabsRow.getChildCount() > 0) seasonTabsRow.getChildAt(0).requestFocus();
    }

    public boolean isUp() { return getVisibility() == VISIBLE; }

    public void hide() {
        setVisibility(GONE);
        poster.setImageDrawable(null);
        backdrop.setImageDrawable(null);
        currentSeries = null;
        currentSeasons.clear();
        allEpisodes.clear();
        currentProgress = null;
        isLoading = false;
        isFailed = false;
        seasonTabsRow.removeAllViews();
        episodeContainer.removeAllViews();
        plotBelowTv.setText("");
        plotBelowTv.setVisibility(INVISIBLE);
        statusTv.setText("");
        statusTv.setVisibility(GONE);
    }

    public void show(final Library.Item seriesItem) {
        if (seriesItem == null || Config.PLAY) return;
        currentSeries = seriesItem;
        openTimeMs = SystemClock.elapsedRealtime();
        titleTv.setText(seriesItem.title);
        Db mdb = Db.get();
        listBtn.setText(mdb != null && mdb.isInMyList(seriesItem.id) ? "Remove from My list" : "Add to My list");

        Db db = Db.get();
        String plotText = "";
        double ratingVal = seriesItem.rating;
        String coverUrl = seriesItem.poster;
        if (db != null) {
            Cursor c = db.getReadableDatabase().rawQuery("SELECT cover, plot, rating FROM series_state WHERE item_id = ?", new String[]{String.valueOf(seriesItem.id)});
            try {
                if (c.moveToFirst()) {
                    String cov = c.getString(0);
                    if (cov != null && !cov.isEmpty()) coverUrl = cov;
                    String pl = c.getString(1);
                    if (pl != null) plotText = pl;
                    if (!c.isNull(2) && c.getDouble(2) > 0) ratingVal = c.getDouble(2);
                }
            } finally { c.close(); }
        }

        List<String> metaParts = new ArrayList<>();
        if (seriesItem.year > 0) metaParts.add(String.valueOf(seriesItem.year));
        if (ratingVal > 0) metaParts.add(String.format(Locale.US, "★ %.1f", ratingVal));
        if (seriesItem.groupName != null && !seriesItem.groupName.trim().isEmpty()) metaParts.add(seriesItem.groupName.trim().replace(", ", " · "));
        metaTv.setText(TextUtils.join(" · ", metaParts));
        metaTv.setVisibility(metaParts.isEmpty() ? GONE : VISIBLE);
        plotTv.setText(plotText);
        plotTv.setVisibility(!plotText.isEmpty() ? VISIBLE : GONE);

        PosterLoader pl = PosterLoader.get(getContext());
        poster.setImageDrawable(null);
        poster.setBackground(new LetterTile(seriesItem.title));
        if (!coverUrl.isEmpty()) pl.load(coverUrl, poster, Ui.u(120));
        backdrop.setImageDrawable(null);
        if (!Ui.low() && !coverUrl.isEmpty()) pl.loadBackdrop(coverUrl, backdrop);

        setVisibility(VISIBLE);
        loadEpisodes(false);
    }

    private void loadEpisodes(final boolean force) {
        if (currentSeries == null) return;
        final long seriesId = currentSeries.id;
        final Db db = Db.get();
        if (db == null) return;

        isLoading = true;
        isFailed = false;
        statusTv.setText("Loading…");
        statusTv.setVisibility(VISIBLE);
        seasonScroll.setVisibility(GONE);
        episodeScroll.setVisibility(GONE);
        plotBelowTv.setVisibility(INVISIBLE);
        primaryBtn.setText("Loading episodes…");
        primaryBtn.setVisibility(VISIBLE);
        primaryBtn.requestFocus();

        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = SeriesSource.ensureEpisodes(db, seriesId, force);
                final List<SeriesSource.Season> seasons = SeriesSource.seasons(db, seriesId);
                final List<SeriesSource.Episode> eps = SeriesSource.episodes(db, seriesId);
                final SeriesLogic.SeriesProgress sp = SeriesLogic.seriesProgress(db, seriesId);

                main.post(new Runnable() {
                    @Override public void run() {
                        if (currentSeries == null || currentSeries.id != seriesId || !isUp()) return;
                        isLoading = false;
                        if (!ok && eps.isEmpty()) {
                            isFailed = true;
                            statusTv.setText("Couldn't load episodes. Press OK to try again.");
                            statusTv.setVisibility(VISIBLE);
                            primaryBtn.setText("Retry");
                            primaryBtn.setVisibility(VISIBLE);
                            primaryBtn.requestFocus();
                            return;
                        }
                        if (eps.isEmpty()) {
                            statusTv.setText("No episodes found");
                            statusTv.setVisibility(VISIBLE);
                            primaryBtn.setVisibility(GONE);
                            backBtn.requestFocus();
                            return;
                        }
                        statusTv.setVisibility(GONE);
                        applyEpisodes(seasons, eps, sp);
                        if (openTimeMs > 0) {
                            long elapsed = SystemClock.elapsedRealtime() - openTimeMs;
                            Log.i(TAG, "series " + seriesId + " open to rows shown: " + elapsed + " ms");
                        }
                    }
                });
            }
        }, "series-episodes-load").start();
    }

    private void applyEpisodes(List<SeriesSource.Season> seasons, List<SeriesSource.Episode> eps, SeriesLogic.SeriesProgress sp) {
        currentSeasons.clear();
        currentSeasons.addAll(seasons);
        allEpisodes.clear();
        allEpisodes.addAll(eps);
        currentProgress = sp;

        if (currentSeasons.isEmpty() && !allEpisodes.isEmpty()) {
            Map<Integer, Integer> counts = new HashMap<>();
            for (SeriesSource.Episode e : allEpisodes) {
                int s = e.season > 0 ? e.season : 1;
                counts.put(s, counts.containsKey(s) ? counts.get(s) + 1 : 1);
            }
            List<Integer> sortedS = new ArrayList<>(counts.keySet());
            Collections.sort(sortedS);
            for (int s : sortedS) currentSeasons.add(new SeriesSource.Season(s, counts.get(s)));
        }

        primaryBtn.setText(SeriesLogic.buttonText(sp.target, sp.hasAnyProgress));
        primaryBtn.setVisibility(VISIBLE);
        if (!buttonsRow.hasFocus() && !seasonTabsRow.hasFocus() && !episodeContainer.hasFocus()) primaryBtn.requestFocus();

        int targetSeason = (sp.target != null && sp.target.season > 0) ? sp.target.season : (!currentSeasons.isEmpty() ? currentSeasons.get(0).seasonNumber : 1);
        selectedSeason = targetSeason;

        seasonTabsRow.removeAllViews();
        seasonScroll.setVisibility(VISIBLE);
        ChipView targetChip = null;
        for (int i = 0; i < currentSeasons.size(); i++) {
            final SeriesSource.Season season = currentSeasons.get(i);
            final int sNum = season.seasonNumber;
            final ChipView chip = new ChipView(getContext(), "Season " + sNum, sNum);
            boolean isSel = (sNum == selectedSeason);
            chip.setSelectedChip(isSel);
            if (isSel) targetChip = chip;
            setupSeasonChip(chip, i);
            seasonTabsRow.addView(chip);
        }

        if (targetChip != null) {
            final ChipView tc = targetChip;
            seasonScroll.post(new Runnable() {
                @Override public void run() { seasonScroll.smoothScrollTo(tc.getLeft(), 0); }
            });
        }

        populateEpisodesForSeason(selectedSeason);
        episodeScroll.setVisibility(VISIBLE);
    }

    private void setupSeasonChip(final ChipView chip, final int index) {
        chip.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { selectSeason(chip.seasonNumber); }
        });
        chip.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                chip.updateStyle();
                if (hasFocus) {
                    selectSeason(chip.seasonNumber);
                    seasonScroll.requestChildRectangleOnScreen(chip, new android.graphics.Rect(0, 0, chip.getWidth(), chip.getHeight()), false);
                }
            }
        });
        chip.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (index > 0) { View prev = seasonTabsRow.getChildAt(index - 1); if (prev != null) prev.requestFocus(); }
                    return true;
                } else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (index < seasonTabsRow.getChildCount() - 1) { View next = seasonTabsRow.getChildAt(index + 1); if (next != null) next.requestFocus(); }
                    return true;
                } else if (k == KeyEvent.KEYCODE_DPAD_UP) {
                    primaryBtn.requestFocus();
                    return true;
                } else if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (episodeContainer.getChildCount() > 0) episodeContainer.getChildAt(0).requestFocus();
                    return true;
                }
                return false;
            }
        });
    }

    private void selectSeason(int sNum) {
        if (selectedSeason == sNum && episodeContainer.getChildCount() > 0) return;
        selectedSeason = sNum;
        for (int i = 0; i < seasonTabsRow.getChildCount(); i++) {
            View child = seasonTabsRow.getChildAt(i);
            if (child instanceof ChipView) ((ChipView) child).setSelectedChip(((ChipView) child).seasonNumber == sNum);
        }
        populateEpisodesForSeason(sNum);
    }

    private void populateEpisodesForSeason(int seasonNum) {
        selectedSeason = seasonNum;
        episodeContainer.removeAllViews();
        final List<SeriesSource.Episode> sEps = new ArrayList<>();
        for (SeriesSource.Episode e : allEpisodes) { if (e.season == seasonNum) sEps.add(e); }
        if (sEps.isEmpty()) { plotBelowTv.setText(""); plotBelowTv.setVisibility(INVISIBLE); return; }
        for (int i = 0; i < sEps.size(); i++) episodeContainer.addView(createEpisodeRow(sEps.get(i), i, sEps.size()));
    }

    private View createEpisodeRow(final SeriesSource.Episode ep, final int pos, final int total) {
        final Context c = getContext();
        final FrameLayout row = new FrameLayout(c);
        row.setFocusable(true);
        row.setClickable(true);
        row.setTag(ep.itemId);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.u(64));
        lp.bottomMargin = Ui.u(4);
        row.setLayoutParams(lp);

        final Drawable unfocusedBg = Ui.rect(Ui.alpha(Ui.PANEL, 0.45f), 10);
        final Drawable focusedBg = Ui.ring();
        row.setBackground(unfocusedBg);

        LinearLayout inner = new LinearLayout(c);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setPadding(Ui.u(18), 0, Ui.u(18), 0);

        TextView numTv = Ui.tv(c, Ui.BODY, Ui.MUTED, true);
        numTv.setText(String.valueOf(ep.episode > 0 ? ep.episode : pos + 1));
        numTv.setMinWidth(Ui.u(44));
        inner.addView(numTv);

        TextView epTitleTv = Ui.tv(c, Ui.BODY, Ui.PAPER, false);
        epTitleTv.setText((ep.title != null && !ep.title.trim().isEmpty()) ? ep.title.trim() : ("Episode " + (ep.episode > 0 ? ep.episode : pos + 1)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.rightMargin = Ui.u(14);
        inner.addView(epTitleTv, tp);

        if (ep.durationSec > 0) {
            int mins = Math.round(ep.durationSec / 60f);
            if (mins > 0) {
                TextView durTv = Ui.tv(c, Ui.CAPTION, Ui.MUTED, false);
                durTv.setText(mins + " min");
                durTv.setPadding(0, 0, Ui.u(16), 0);
                inner.addView(durTv);
            }
        }

        boolean watched = SeriesLogic.isWatched(new SeriesLogic.Ep(ep));
        final TextView checkTv;
        if (watched) {
            checkTv = Ui.tv(c, Ui.BODY, Ui.MUTED, true);
            checkTv.setText("✓");
            inner.addView(checkTv);
        } else {
            checkTv = null;
        }
        row.addView(inner, new LayoutParams(-1, -1));

        boolean partial = FilmResume.canResume(ep.positionMs, ep.durationMs, ep.watched);
        if (partial && !watched) {
            float frac = (ep.durationMs > 0) ? ((float) ep.positionMs / (float) ep.durationMs) : ((ep.durationSec > 0) ? ((float) ep.positionMs / (ep.durationSec * 1000f)) : 0f);
            EpisodeProgressBar pb = new EpisodeProgressBar(c);
            pb.setProgress(frac);
            row.addView(pb, new LayoutParams(-1, Ui.u(3), Gravity.BOTTOM));
        }

        final boolean[] longHandled = new boolean[1];
        final Runnable longPressR = new Runnable() {
            @Override public void run() { longHandled[0] = true; toggleWatched(ep); }
        };

        row.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (longHandled[0]) { longHandled[0] = false; return; }
                long startPos = FilmResume.canResume(ep.positionMs, ep.durationMs, ep.watched) ? ep.positionMs : 0L;
                playEpisode(ep.itemId, startPos);
            }
        });
        row.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) { longHandled[0] = true; toggleWatched(ep); return true; }
        });
        row.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                row.setBackground(hasFocus ? focusedBg : unfocusedBg);
                if (checkTv != null) {
                    checkTv.setTextColor(hasFocus ? Ui.PAPER : Ui.MUTED);
                }
                if (hasFocus) {
                    focusedEpisode = ep;
                    updatePlotBelow(ep);
                }
            }
        });
        row.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN) {
                        if (e.getRepeatCount() == 0) {
                            longHandled[0] = false;
                            v.postDelayed(longPressR, ViewConfiguration.getLongPressTimeout());
                        } else if (e.getRepeatCount() >= 1 || e.isLongPress()) {
                            v.removeCallbacks(longPressR);
                            if (!longHandled[0]) { longHandled[0] = true; toggleWatched(ep); }
                            return true;
                        }
                    } else if (e.getAction() == KeyEvent.ACTION_UP) {
                        v.removeCallbacks(longPressR);
                        if (longHandled[0]) return true;
                    }
                }
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (k == KeyEvent.KEYCODE_DPAD_UP) {
                    if (pos == 0) { focusCurrentSeasonTab(); return true; }
                    View prev = episodeContainer.getChildAt(pos - 1);
                    if (prev != null) prev.requestFocus();
                    return true;
                } else if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (pos < total - 1) {
                        View next = episodeContainer.getChildAt(pos + 1);
                        if (next != null) next.requestFocus();
                        return true;
                    }
                    return true;
                }
                return k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
            }
        });

        return row;
    }

    private void playTarget() {
        if (currentProgress == null || allEpisodes.isEmpty()) return;
        long targetId = (currentProgress.target != null) ? currentProgress.target.itemId : allEpisodes.get(0).itemId;
        long startPos = (currentProgress.target != null && FilmResume.canResume(currentProgress.target.positionMs, currentProgress.target.durationMs, currentProgress.target.watched)) ? currentProgress.target.positionMs : 0L;
        playEpisode(targetId, startPos);
    }

    private void playEpisode(long episodeItemId, long startPosMs) {
        Db db = Db.get();
        if (db == null || host == null) return;
        Library.Item epItem = Library.get(db, episodeItemId);
        if (epItem != null) host.playFilm(epItem, startPosMs);
    }

    private void updatePlotBelow(SeriesSource.Episode ep) {
        if (ep == null) {
            plotBelowTv.setText("");
            plotBelowTv.setVisibility(INVISIBLE);
            return;
        }
        Store st = Store.get(getContext());
        boolean isWatched = SeriesLogic.isWatched(new SeriesLogic.Ep(ep));
        boolean inProgress = (ep.positionMs > 0 || FilmResume.canResume(ep.positionMs, ep.durationMs, ep.watched)) && !isWatched;
        if (st.hideUnwatchedPlots() && !isWatched && !inProgress) {
            plotBelowTv.setText("Plot hidden until you watch it");
            plotBelowTv.setVisibility(VISIBLE);
        } else if (ep.plot != null && !ep.plot.trim().isEmpty()) {
            plotBelowTv.setText(ep.plot.trim());
            plotBelowTv.setVisibility(VISIBLE);
        } else {
            plotBelowTv.setText("");
            plotBelowTv.setVisibility(INVISIBLE);
        }
    }

    private void toggleWatched(final SeriesSource.Episode ep) {
        final Db db = Db.get();
        if (db == null || ep == null || currentSeries == null) return;
        final boolean isW = SeriesLogic.isWatched(new SeriesLogic.Ep(ep));
        final boolean newWatched = !isW;
        SeriesLogic.markWatched(db, ep.itemId, newWatched);
        if (host != null) host.toast(newWatched ? "Marked watched" : "Marked not watched");
        final long seriesId = currentSeries.id;
        final List<SeriesSource.Season> seasons = SeriesSource.seasons(db, seriesId);
        final List<SeriesSource.Episode> eps = SeriesSource.episodes(db, seriesId);
        final SeriesLogic.SeriesProgress sp = SeriesLogic.seriesProgress(db, seriesId);
        final long targetFocusId = ep.itemId;
        applyEpisodes(seasons, eps, sp);
        SeriesSource.Episode targetEp = null;
        for (SeriesSource.Episode e : eps) {
            if (e.itemId == targetFocusId) { targetEp = e; break; }
        }
        for (int i = 0; i < episodeContainer.getChildCount(); i++) {
            View child = episodeContainer.getChildAt(i);
            if (child != null && child.getTag() instanceof Long && ((Long) child.getTag()) == targetFocusId) {
                child.requestFocus();
                break;
            }
        }
        if (targetEp != null) {
            focusedEpisode = targetEp;
            updatePlotBelow(targetEp);
        }
    }

    public void activate() {
        View f = findFocus();
        if (f != null) f.performClick();
    }

    private static final class ChipView extends TextView {
        final int seasonNumber;
        private boolean selected;

        ChipView(Context c, String text, int sNum) {
            super(c);
            this.seasonNumber = sNum;
            setText(text);
            setFocusable(true);
            setClickable(true);
            setGravity(Gravity.CENTER);
            setPadding(Ui.u(16), Ui.u(6), Ui.u(16), Ui.u(6));
            Ui.size(this, Ui.CAPTION);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.u(10);
            setLayoutParams(lp);
            updateStyle();
        }

        void setSelectedChip(boolean sel) { this.selected = sel; updateStyle(); }

        void updateStyle() {
            if (hasFocus()) {
                setBackground(Ui.ring());
                setTextColor(Ui.PAPER);
            } else if (selected) {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(Ui.u(12));
                g.setStroke(Math.max(1, Ui.u(1)), Ui.PAPER);
                setBackground(g);
                setTextColor(Ui.PAPER);
            } else {
                setBackground(Ui.chip());
                setTextColor(Ui.MUTED);
            }
        }
    }

    private static final class EpisodeProgressBar extends View {
        private float progress;
        private final Paint trackPaint = new Paint(), fillPaint = new Paint();

        EpisodeProgressBar(Context c) {
            super(c);
            trackPaint.setColor(Ui.LINE);
            fillPaint.setColor(Ui.SIGNAL);
        }

        void setProgress(float p) {
            this.progress = Math.max(0f, Math.min(1f, p));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            canvas.drawRect(0, 0, w, h, trackPaint);
            if (progress > 0f) canvas.drawRect(0, 0, w * progress, h, fillPaint);
        }
    }
}
