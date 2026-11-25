package lk.manus.iptv;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import lk.manus.iptv.data.Catchup;
import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Db;
import lk.manus.iptv.data.FilmResume;
import lk.manus.iptv.data.Library;
import lk.manus.iptv.data.LiveSource;
import lk.manus.iptv.data.Redact;
import lk.manus.iptv.data.SeriesLogic;
import lk.manus.iptv.data.XtreamSource;
import lk.manus.iptv.player.Engine;
import lk.manus.iptv.player.Media3Engine;
import lk.manus.iptv.player.MediaPlayerEngine;
import lk.manus.iptv.ui.Ui;

/**
 * Controller and UI for film playback, seek bar, resume progress,
 * and unsupported audio card (T83).
 */
final class FilmPlayer {
    private static final String TAG = "IPTV";

    private final PlayState ps;

    private boolean active;
    private Library.Item currentFilm;
    private boolean catchup;        // T95: playing an earlier programme of a live channel (no resume data, no next episode)
    private Channel catchCh;
    private boolean liveArmed;
    private TextView livePill;
    private long startPosMs;
    private boolean isPaused;
    private long lastSeekTime;

    // Seek bar UI
    private LinearLayout barBox;
    private TextView filmTitleTv;
    private TextView timeTv;
    private FrameLayout progressBar;
    private View progressFill;

    // Unsupported audio card UI
    private LinearLayout audioCard;
    private TextView audioMsgTv;
    private TextView otherAudioBtn;
    private TextView audioBackBtn;
    private boolean audioCardUp;

    // Next-episode pill UI & state (T93a)
    private TextView nextPill;
    private boolean nextPillUp;
    private boolean isStillWatching;
    private boolean pillDismissed;
    private boolean streamEnded;
    private int countdownSec = SeriesLogic.COUNTDOWN_START_SEC;
    private long nextEpisodeId = -1;
    private final SeriesLogic.StillWatchingTracker stillWatchingTracker = new SeriesLogic.StillWatchingTracker();

    private final Runnable countdownR = new Runnable() {
        @Override public void run() {
            if (!active || !nextPillUp || isStillWatching) return;
            countdownSec--;
            if (countdownSec > 0) {
                if (nextPill != null) {
                    nextPill.setText(SeriesLogic.formatNextEpisodeCountdown(countdownSec));
                }
                ps.h.postDelayed(this, 1000);
            } else {
                if (nextPill != null) {
                    nextPill.setText(SeriesLogic.formatNextEpisodeCountdown(0));
                }
                if (stillWatchingTracker.shouldPromptStillWatching()) {
                    isStillWatching = true;
                    if (nextPill != null) {
                        nextPill.setText(SeriesLogic.STILL_WATCHING_TEXT);
                    }
                } else {
                    autoPlayNextEpisode();
                }
            }
        }
    };

    private final Runnable hideBarR = new Runnable() {
        @Override public void run() {
            if (barBox != null) barBox.setVisibility(View.GONE);
        }
    };

    private final Runnable barTickR = new Runnable() {
        @Override public void run() {
            if (!active) return;
            updateBarUi();
            checkNextEpisodePill();
            ps.h.postDelayed(this, 500);
        }
    };

    private final Runnable progressSaveR = new Runnable() {
        @Override public void run() {
            if (!active) return;
            saveProgress(false);
            ps.h.postDelayed(this, 10000);
        }
    };

    FilmPlayer(PlayState ps) {
        this.ps = ps;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isAudioCardUp() {
        return audioCardUp;
    }

    public Library.Item getCurrentFilm() {
        return currentFilm;
    }

    void buildUi(FrameLayout root) {
        Context c = ps.act;

        // 1. Film Seek Bar: Glass card at the bottom
        barBox = new LinearLayout(c);
        barBox.setOrientation(LinearLayout.VERTICAL);
        barBox.setBackground(Ui.card(0.95f, 14));
        barBox.setPadding(Ui.u(24), Ui.u(14), Ui.u(24), Ui.u(14));
        barBox.setVisibility(View.GONE);

        LinearLayout topRow = new LinearLayout(c);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        filmTitleTv = Ui.tv(c, Ui.BODY, Ui.PAPER, true);
        topRow.addView(filmTitleTv, new LinearLayout.LayoutParams(0, -2, 1));

        timeTv = Ui.tv(c, Ui.BODY, Ui.MUTED, false);
        timeTv.setGravity(Gravity.END);
        topRow.addView(timeTv, new LinearLayout.LayoutParams(-2, -2));

        barBox.addView(topRow);

        progressBar = new FrameLayout(c);
        progressBar.setBackground(Ui.rect(Ui.LINE, 3));
        progressFill = new View(c);
        progressFill.setBackgroundColor(Ui.SIGNAL);
        progressBar.addView(progressFill, new FrameLayout.LayoutParams(0, -1));

        LinearLayout.LayoutParams tkp = new LinearLayout.LayoutParams(-1, Ui.u(6));
        tkp.topMargin = Ui.u(10);
        barBox.addView(progressBar, tkp);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        int mx = Ui.marginX(), my = Ui.marginY();
        bp.setMargins(mx, 0, mx, my);
        root.addView(barBox, bp);

        // 2. Unsupported audio format card: Centered modal card
        audioCard = new LinearLayout(c);
        audioCard.setOrientation(LinearLayout.VERTICAL);
        audioCard.setGravity(Gravity.CENTER_HORIZONTAL);
        audioCard.setBackground(Ui.card(0.95f, 16));
        audioCard.setPadding(Ui.u(36), Ui.u(28), Ui.u(36), Ui.u(28));
        audioCard.setVisibility(View.GONE);

        audioMsgTv = Ui.tv(c, Ui.TITLE, Ui.PAPER, true);
        audioMsgTv.setSingleLine(false);
        audioMsgTv.setMaxLines(2);
        audioMsgTv.setEllipsize(null);
        audioMsgTv.setText("This film's sound can't play on this TV.");
        audioMsgTv.setGravity(Gravity.CENTER);
        audioCard.addView(audioMsgTv, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout btnRow = new LinearLayout(c);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.topMargin = Ui.u(24);

        otherAudioBtn = button(c, "Other audio");
        audioBackBtn = button(c, "Back");

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(-2, Ui.u(50));
        lp1.rightMargin = Ui.u(16);
        btnRow.addView(otherAudioBtn, lp1);

        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-2, Ui.u(50));
        btnRow.addView(audioBackBtn, lp2);

        audioCard.addView(btnRow, rp);

        otherAudioBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cycleOtherAudio();
            }
        });

        audioBackBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stop(true);
            }
        });

        root.addView(audioCard, new FrameLayout.LayoutParams(Ui.u(440), -2, Gravity.CENTER));

        // 3. Next-episode pill: Glass card bottom-right (T93a)
        nextPill = Ui.tv(c, Ui.BODY, Ui.PAPER, true);
        nextPill.setBackground(Ui.card(0.95f, 14));
        nextPill.setPadding(Ui.u(24), Ui.u(12), Ui.u(24), Ui.u(12));
        nextPill.setVisibility(View.GONE);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        np.setMargins(0, 0, Ui.marginX(), Ui.marginY() + Ui.u(130)); // above the playback bar
        root.addView(nextPill, np);

        livePill = Ui.tv(c, Ui.BODY, Ui.PAPER, true); // T95: Back to live
        livePill.setText("Back to live");
        livePill.setPadding(Ui.u(24), Ui.u(12), Ui.u(24), Ui.u(12));
        livePill.setVisibility(View.GONE);
        armLive(false);
        FrameLayout.LayoutParams lpp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lpp.setMargins(0, 0, Ui.marginX(), Ui.marginY() + Ui.u(130));
        root.addView(livePill, lpp);
        livePill.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (active && catchup) stop(true); } });

        nextPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!active || !nextPillUp) return;
                if (isStillWatching) {
                    continueStillWatching();
                } else {
                    playNextEpisodeNow();
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
        b.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN) {
                        if (v == otherAudioBtn && k == KeyEvent.KEYCODE_DPAD_RIGHT) audioBackBtn.requestFocus();
                        else if (v == audioBackBtn && k == KeyEvent.KEYCODE_DPAD_LEFT) otherAudioBtn.requestFocus();
                    }
                    return true;
                }
                return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
            }
        });
        return b;
    }

    void relayout() {
        if (barBox != null) {
            FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) barBox.getLayoutParams();
            bp.setMargins(Ui.marginX(), 0, Ui.marginX(), Ui.marginY());
            barBox.setLayoutParams(bp);
        }
        if (nextPill != null) {
            FrameLayout.LayoutParams np = (FrameLayout.LayoutParams) nextPill.getLayoutParams();
            np.setMargins(0, 0, Ui.marginX(), Ui.marginY() + Ui.u(130)); // above the playback bar
            nextPill.setLayoutParams(np);
        }
    }

    void play(final Library.Item item, final long startPos) {
        if (item == null) return;
        final Db db = Db.get();
        if (db == null) return;

        // Resolve stream URL via XtreamSource.playUrl (the ONLY place the login is joined)
        final String streamUrl = XtreamSource.playUrl(db, item.id);
        if (streamUrl == null) {
            ps.ui.showToast("Can't play this film", 2500);
            return;
        }
        start(item, streamUrl, startPos, false);
    }

    /** T95: an earlier programme of an Xtream live channel, through the same player (seek, pause, resume). */
    void playCatchup(Channel ch, Catchup.Prog p) {
        String url = LiveSource.catchupUrl(Db.get(), ch.xtItem, p); // the login is joined here, only for the request
        if (url == null) { ps.ui.showToast("Can't play this programme", 2500); return; }
        catchCh = ch;
        start(new Library.Item(-1, "catchup", p.title, 0, "", 0, ""), url, 0, true);
    }

    private void armLive(boolean on) {
        liveArmed = on;
        if (livePill != null) livePill.setBackground(on ? Ui.ring() : Ui.card(0.95f, 14));
    }

    private void start(final Library.Item item, final String streamUrl, final long startPos, boolean isCatchup) {
        final Db db = Db.get();
        catchup = isCatchup;
        armLive(false);
        if (livePill != null) livePill.setVisibility(isCatchup ? View.VISIBLE : View.GONE);

        // Hard rule: Log "film play host=<host>" only.
        Log.i(TAG, (isCatchup ? "catch-up play host=" : "film play host=") + Redact.host(streamUrl));
        Log.i(TAG, "film start pos=" + startPos + " (resuming near " + (startPos / 1000) + "s)");

        active = true;
        ps.message.setVisibility(View.GONE); // "Loading channels…" belongs to live TV, never over a film
        ps.guide.hideNow(); // the guide is fully gone as soon as a film / catch-up starts, not only once the picture is up
        currentFilm = item;
        startPosMs = startPos;
        isPaused = false;
        pillDismissed = false;
        streamEnded = false;
        hideNextPill();
        if ("episode".equals(item.kind)) {
            nextEpisodeId = SeriesLogic.nextEpisodeAfter(db, item.id);
        } else {
            nextEpisodeId = -1;
        }

        // Stop any running live channel playback and clear watchdogs/banners
        ps.h.removeCallbacks(ps.host.startR);
        ps.h.removeCallbacks(ps.fov.watchdog);
        ps.h.removeCallbacks(ps.fov.reconnectR);
        ps.h.removeCallbacks(ps.ui.connR);
        ps.fov.hideFail();
        ps.connBox.setVisibility(View.GONE);
        ps.banner.setVisibility(View.GONE);
        ps.tsh.tsReset();
        ps.playing = false;

        hideAudioFail();
        ps.ui.showToastNow(lk.manus.iptv.data.LoadText.film(item.title, startPos, isCatchup), 30000); // the screen was blank until the picture came: say what is opening
        openingUp = true;

        final int g = ++ps.gen;
        final Engine.Listener ls = new Engine.Listener() {
            @Override public void onPlaying() {
                if (g != ps.gen || !active) return;
                FilmPlayer.this.onPlaying();
            }
            @Override public void onBuffering(boolean start) {}
            @Override public void onError(String detail) {
                if (g != ps.gen || !active) return;
                if ("ended".equalsIgnoreCase(detail)) {
                    onFilmEnded();
                } else {
                    clearOpening(); // no "Opening…" pill left over a failed start
                    Log.i(TAG, "film playback error: " + detail);
                }
            }
            @Override public void onVideoSize(int w, int hh) {
                if (g != ps.gen) return;
                ps.host.fitTex();
            }
            @Override public void onTracks() {
                if (g != ps.gen || !active) return;
                applySavedTracks();
            }
            @Override public void onCues(String t) {
                if (g == ps.gen && active) {
                    ps.curCue = t;
                    ps.trCue = "";
                    if (ps.trOn && !t.isEmpty()) ps.subsCtl.translateCue(t);
                    ps.subsCtl.showCue();
                }
            }
            @Override public void onAudioDrop() {}
            @Override public void onUnsupportedAudio() {
                if (g != ps.gen || !active) return;
                showAudioFail();
            }
        };

        final Engine old = ps.eng;
        ps.eng = null;

        Runnable startPlay = new Runnable() {
            @Override public void run() {
                if (g != ps.gen || !active) return;
                try {
                    boolean m3 = ps.host.media3();
                    Engine e = m3 ? new Media3Engine(ps.act, ls) : new MediaPlayerEngine(ps.act, ls);
                    ps.eng = e;
                    e.setEven(ps.store.evenVolume());
                    e.setVolume(1f);
                    e.prepare(null, streamUrl, ps.store.quality(), ps.useTex ? null : ps.sv.getHolder(), ps.useTex ? ps.texSurface : null, startPos);

                    // DEBUG extra hook: filmfakeaudiofail=1 forces the audio fail card
                    if (ps.dbgFakeAudioFail) {
                        ps.h.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (active) showAudioFail();
                            }
                        }, 600);
                    }
                } catch (Exception ex) {
                    Log.w(TAG, "film engine prepare failed", ex);
                }
            }
        };

        if (old == null) {
            startPlay.run();
        } else {
            old.release(startPlay);
        }
    }

    private void onPlaying() {
        ps.playing = true;
        clearOpening();
        ps.guide.hideNow();
        filmTitleTv.setText(currentFilm.title);
        showBar(4000);

        ps.h.removeCallbacks(barTickR);
        ps.h.post(barTickR);

        ps.h.removeCallbacks(progressSaveR);
        ps.h.postDelayed(progressSaveR, 10000);

        applySavedTracks();
    }

    private void applySavedTracks() {
        if (ps.eng == null || currentFilm == null) return;
        Db db = Db.get();
        if (db == null) return;

        // 1. Saved audio choice
        String savedAudio = db.choice("audio", "film:" + currentFilm.id);
        if (!savedAudio.isEmpty()) {
            List<Engine.Track> aTracks = ps.eng.audioTracks();
            for (Engine.Track t : aTracks) {
                if (savedAudio.equalsIgnoreCase(t.label) || savedAudio.equalsIgnoreCase(t.lang) || savedAudio.equals(String.valueOf(t.id))) {
                    ps.eng.selectAudio(t.id);
                    break;
                }
            }
        }

        // 2. Saved subtitle choice
        String savedSubs = db.choice("subs", "film:" + currentFilm.id);
        if (!savedSubs.isEmpty()) {
            List<Engine.Track> sTracks = ps.eng.subtitleTracks();
            for (Engine.Track t : sTracks) {
                if (savedSubs.equalsIgnoreCase(t.label) || savedSubs.equalsIgnoreCase(t.lang) || savedSubs.equals(String.valueOf(t.id))) {
                    ps.eng.selectSubtitle(t.id);
                    break;
                }
            }
        }
    }

    void showBar(long autoHideMs) {
        if (barBox == null) return;
        updateBarUi();
        barBox.setVisibility(View.VISIBLE);
        ps.h.removeCallbacks(hideBarR);
        if (autoHideMs > 0) {
            ps.h.postDelayed(hideBarR, autoHideMs);
        }
    }

    void hideBar() {
        ps.h.removeCallbacks(hideBarR);
        if (barBox != null) barBox.setVisibility(View.GONE);
    }

    private void updateBarUi() {
        if (barBox == null || ps.eng == null) return;
        long pos = ps.eng.positionMs();
        long dur = ps.eng.durationMs();
        if (pos < 0) pos = 0;

        String status = isPaused ? "   ❚❚ Paused" : "";
        filmTitleTv.setText(currentFilm != null ? (currentFilm.title + status) : ("Film" + status));

        String posStr = FilmResume.formatTime(pos);
        String durStr = dur > 0 ? FilmResume.formatTime(dur) : "--:--";
        timeTv.setText(posStr + " / " + durStr);

        if (dur > 0 && progressBar != null && progressFill != null) {
            int totalW = progressBar.getWidth();
            if (totalW > 0) {
                float r = Math.min(1f, Math.max(0f, (float) pos / dur));
                int fillW = Math.round(totalW * r);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) progressFill.getLayoutParams();
                if (lp.width != fillW) {
                    lp.width = fillW;
                    progressFill.setLayoutParams(lp);
                }
            }
        }
    }

    void seekBy(long deltaMs) {
        if (!active || ps.eng == null) return;
        long cur = ps.eng.positionMs();
        long dur = ps.eng.durationMs();
        long target = Math.max(0, dur > 0 ? Math.min(cur + deltaMs, dur) : cur + deltaMs);
        ps.eng.seekTo(target);

        // Immediate feedback
        showBar(4000);
        updateBarUi();
        checkNextEpisodePill();

        // 95% completion check during seek
        if (dur > 0 && FilmResume.isFinished(target, dur)) {
            saveProgress(false);
        }
    }

    void togglePause() {
        if (!active || ps.eng == null) return;
        if (isPaused) {
            ps.eng.play();
            isPaused = false;
        } else {
            ps.eng.pause();
            isPaused = true;
            saveProgress(true); // save on pause (T103: on disk before the pause shows)
        }
        showBar(4000);
    }

    void play() {
        if (!active || ps.eng == null) return;
        ps.eng.play();
        isPaused = false;
        showBar(4000);
    }

    void pause() {
        if (!active || ps.eng == null) return;
        ps.eng.pause();
        isPaused = true;
        saveProgress(true);
        showBar(4000);
    }

    private void onFilmEnded() {
        if (!active) return;
        if (catchup) { stop(true); return; } // the programme is over: back to the live channel
        streamEnded = true;
        long dur = ps.eng != null ? ps.eng.durationMs() : -1;
        long pos = ps.eng != null ? ps.eng.positionMs() : dur;
        if (currentFilm != null) {
            Db db = Db.get();
            if (db != null) {
                db.saveProgressSync(currentFilm.id, pos > 0 ? pos : dur, dur, true);
                SeriesLogic.markWatched(db, currentFilm.id, true);
                Log.i(TAG, "film saved progress: pos=" + pos + " dur=" + dur + " watched=true (ended)");
            }
            if ("episode".equals(currentFilm.kind)) lk.manus.iptv.data.WatchNext.updateSeries(ps.act, currentFilm.parentId);
            else lk.manus.iptv.data.WatchNext.remove(ps.act, currentFilm.id);
        }

        if (nextEpisodeId > 0 && !pillDismissed) {
            if (!nextPillUp) {
                triggerNextEpisodePill();
            }
            return;
        }

        stop(true);
    }

    void saveProgress(boolean sync) {
        if (currentFilm == null || ps.eng == null || catchup) return;
        long pos = ps.eng.positionMs();
        long dur = ps.eng.durationMs();
        if (pos <= 0) return;

        boolean finished = FilmResume.isFinished(pos, dur);
        Db db = Db.get();
        if (db != null) {
            if (finished) { // 95%: progress cleared, watched set (T93b)
                if (sync) SeriesLogic.markWatched(db, currentFilm.id, true); else db.markWatchedAsync(currentFilm.id);
            } else if (sync) {
                db.saveProgressSync(currentFilm.id, pos, dur, false);
            } else {
                db.saveProgress(currentFilm.id, pos, dur, false);
            }
            Log.i(TAG, "film saved progress: pos=" + pos + " dur=" + dur + " watched=" + finished);
        }

        if ("episode".equals(currentFilm.kind)) {
            lk.manus.iptv.data.WatchNext.updateSeries(ps.act, currentFilm.parentId);
        } else if (finished) {
            lk.manus.iptv.data.WatchNext.remove(ps.act, currentFilm.id);
        } else if (pos > FilmResume.MIN_RESUME_MS) {
            lk.manus.iptv.data.WatchNext.update(ps.act, currentFilm, pos, dur);
        }
    }

    private boolean openingUp; // the "Opening X…" pill from start() is still up

    private void clearOpening() { if (openingUp) { openingUp = false; ps.ui.clearToast(); } }

    void stop(boolean returnToDetails) {
        if (!active) return;
        clearOpening();
        saveProgress(true); // synchronous save so Details can immediately read the updated state

        active = false;
        isPaused = false;
        audioCardUp = false;
        stillWatchingTracker.reset();
        hideNextPill();
        pillDismissed = false;
        streamEnded = false;
        nextEpisodeId = -1;

        ps.h.removeCallbacks(barTickR);
        ps.h.removeCallbacks(progressSaveR);
        ps.h.removeCallbacks(hideBarR);
        ps.h.removeCallbacks(countdownR);

        hideBar();
        hideAudioFail();

        if (ps.eng != null) {
            ps.eng.release(null);
            ps.eng = null;
        }

        Library.Item item = currentFilm;
        currentFilm = null;

        if (catchup) { // T95: leaving a catch-up programme returns to its live channel
            catchup = false;
            if (livePill != null) livePill.setVisibility(View.GONE);
            Channel ch = catchCh;
            catchCh = null;
            if (ch != null) ps.host.play(ch);
            return;
        }
        if (returnToDetails && item != null) {
            if (ps.guide.hasFilmOrigin()) ps.zapper.openGuide(); // started from a Home tile: Back is Home, tile lit
            else ps.guide.reopenLibDetails(item);
        }
    }

    void showAudioFail() {
        clearOpening();
        audioCardUp = true;
        hideBar();
        if (audioCard != null) {
            audioCard.setVisibility(View.VISIBLE);
            otherAudioBtn.requestFocus();
        }
    }

    void hideAudioFail() {
        audioCardUp = false;
        if (audioCard != null) {
            audioCard.setVisibility(View.GONE);
        }
    }

    void cycleOtherAudio() {
        if (ps.eng == null || currentFilm == null) return;
        List<Engine.Track> aTracks = ps.eng.audioTracks();
        if (aTracks.size() <= 1) {
            ps.ui.showToast("No other audio track", 2500);
            return;
        }

        int cur = -1;
        for (int i = 0; i < aTracks.size(); i++) {
            if (aTracks.get(i).sel) { cur = i; break; }
        }
        int next = (cur + 1) % aTracks.size();
        Engine.Track nt = aTracks.get(next);
        ps.eng.selectAudio(nt.id);

        String idStr = !nt.label.isEmpty() ? nt.label : (!nt.lang.isEmpty() ? nt.lang : String.valueOf(nt.id));
        Db db = Db.get();
        if (db != null) {
            db.setChoice("audio", "film:" + currentFilm.id, idStr, 200);
        }

        hideAudioFail();
        ps.eng.play();
        isPaused = false;
        showBar(4000);
        ps.ui.showToast("Audio: " + (nt.label.isEmpty() ? nt.lang : nt.label), 2000);
    }

    public boolean isNextPillUp() {
        return nextPillUp;
    }

    public boolean isStillWatching() {
        return isStillWatching;
    }

    public int getAutoPlayCount() {
        return stillWatchingTracker.getCount();
    }

    private void checkNextEpisodePill() {
        if (!active || ps.eng == null || currentFilm == null) return;
        if (nextEpisodeId <= 0 || pillDismissed || nextPillUp) return;

        long pos = ps.eng.positionMs();
        long dur = ps.eng.durationMs();
        if (dur <= 0 || pos <= 0) return;

        if (dur - pos <= SeriesLogic.NEXT_EPISODE_TRIGGER_MS) {
            triggerNextEpisodePill();
        }
    }

    private void triggerNextEpisodePill() {
        if (!active || nextEpisodeId <= 0 || nextPill == null) return;
        nextPillUp = true;
        ps.h.removeCallbacks(countdownR);

        if (stillWatchingTracker.shouldPromptStillWatching()) {
            isStillWatching = true;
            nextPill.setText(SeriesLogic.STILL_WATCHING_TEXT);
            nextPill.setVisibility(View.VISIBLE);
        } else {
            isStillWatching = false;
            countdownSec = SeriesLogic.COUNTDOWN_START_SEC;
            nextPill.setText(SeriesLogic.formatNextEpisodeCountdown(countdownSec));
            nextPill.setVisibility(View.VISIBLE);
            ps.h.postDelayed(countdownR, 1000);
        }
    }

    private void playNextEpisodeNow() {
        stillWatchingTracker.onKeyPress();
        playNextEpisode();
    }

    private void continueStillWatching() {
        stillWatchingTracker.reset();
        playNextEpisode();
    }

    private void autoPlayNextEpisode() {
        if (!active || nextEpisodeId <= 0) return;
        stillWatchingTracker.onAutoPlay();
        playNextEpisode();
    }

    private void playNextEpisode() {
        long nextId = nextEpisodeId;
        hideNextPill();
        if (nextId <= 0) {
            stop(true);
            return;
        }

        Db db = Db.get();
        if (db == null) {
            stop(true);
            return;
        }

        if (currentFilm != null) {
            long dur = ps.eng != null ? ps.eng.durationMs() : -1;
            long pos = ps.eng != null ? ps.eng.positionMs() : dur;
            db.saveProgressSync(currentFilm.id, pos > 0 ? pos : dur, dur, true);
            SeriesLogic.markWatched(db, currentFilm.id, true);
            if ("episode".equals(currentFilm.kind)) lk.manus.iptv.data.WatchNext.updateSeries(ps.act, currentFilm.parentId);
            else lk.manus.iptv.data.WatchNext.remove(ps.act, currentFilm.id);
        }

        Library.Item nextItem = Library.get(db, nextId);
        if (nextItem != null) {
            play(nextItem, 0L);
        } else {
            stop(true);
        }
    }

    private void dismissNextPill() {
        boolean wasStillWatching = isStillWatching;
        hideNextPill();
        pillDismissed = true;
        if (streamEnded || wasStillWatching) {
            stop(true);
        }
    }

    private void hideNextPill() {
        nextPillUp = false;
        isStillWatching = false;
        ps.h.removeCallbacks(countdownR);
        if (nextPill != null) {
            nextPill.setVisibility(View.GONE);
        }
    }

    boolean dispatch(KeyEvent e) {
        if (!active) return false;
        int k = e.getKeyCode();
        int a = e.getAction();

        if (audioCardUp) {
            // When audio format card is up, route Back and let buttons handle clicks
            if (k == KeyEvent.KEYCODE_BACK) {
                if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    stop(true);
                }
                return true;
            }
            return false;
        }

        if (nextPillUp) {
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    if (isStillWatching) {
                        continueStillWatching();
                    } else {
                        playNextEpisodeNow();
                    }
                }
                return true;
            }
            if (k == KeyEvent.KEYCODE_BACK) {
                if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    dismissNextPill();
                }
                return true;
            }
        }

        if (catchup && a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) { // T95: DOWN selects Back to live, OK on it goes there
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) { armLive(true); showBar(4000); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_UP && liveArmed) { armLive(false); return true; }
            if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && liveArmed) { stop(true); return true; }
        }

        // Any key press resets the count
        if (a == KeyEvent.ACTION_DOWN) {
            stillWatchingTracker.onKeyPress();
        }

        if (k == KeyEvent.KEYCODE_BACK) {
            if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                stop(true);
            }
            return true;
        }

        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                togglePause();
            }
            return true;
        }

        if (k == KeyEvent.KEYCODE_MEDIA_PLAY) {
            if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) play();
            return true;
        }

        if (k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) pause();
            return true;
        }

        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_MEDIA_REWIND) {
            if (a == KeyEvent.ACTION_DOWN) {
                long step = FilmResume.seekStep(e.getRepeatCount());
                seekBy(-step);
            }
            return true;
        }

        if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            if (a == KeyEvent.ACTION_DOWN) {
                long step = FilmResume.seekStep(e.getRepeatCount());
                seekBy(step);
            }
            return true;
        }

        if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                showBar(4000);
            }
            return true;
        }

        return false;
    }

    boolean keyDown(int k, KeyEvent e) {
        if (!active) return false;
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE
                || k == KeyEvent.KEYCODE_MEDIA_REWIND || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            return true;
        }
        return false;
    }
}
