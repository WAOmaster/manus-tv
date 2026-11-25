package lk.manus.iptv;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Db;
import lk.manus.iptv.data.Dedup;
import lk.manus.iptv.data.CloudData;
import lk.manus.iptv.data.FavGuard;
import lk.manus.iptv.data.Health;
import lk.manus.iptv.data.Prober;
import lk.manus.iptv.data.Rank;
import lk.manus.iptv.data.Index;
import lk.manus.iptv.data.Store;
import lk.manus.iptv.ui.Ui;
import lk.manus.iptv.data.Sources;

/** Thin coordinator: owns the Android lifecycle and the playlist load; everything else lives in the parts held by {@link PlayState}. */
public class PlayerActivity extends Activity {
    private static final String TAG = "IPTV";
    final PlayState ps = new PlayState(this);

    private final Runnable retryLoad = new Runnable() { @Override public void run() { load(); } };

    @Override protected void onCreate(Bundle b) {
        setTheme(R.style.Theme_Manus); // T105: leave the opening-logo theme
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ps.prefs = getSharedPreferences("iptv", MODE_PRIVATE);
        ps.store = new Store(ps.prefs);
        lk.manus.iptv.ui.wizard.Wizard.maybeStart(this);
        Ui.init(this);
        FavGuard.init(getFilesDir());
        // hidden test hooks: only honoured in a DEBUG build (Config.DEBUG); release ignores every extra
        final android.content.Intent in = getIntent();
        if (Config.DEBUG) {
            ps.spike = in.getIntExtra("spike", 0);
            ps.simDead = in.getBooleanExtra("simdead", false); // drop after 20 s / fail every reconnect / fake buffering
            ps.simDrop = ps.simDead || in.getBooleanExtra("simdrop", false);
            ps.simStall = in.getBooleanExtra("simstall", false);
            ps.simFailFirst = in.getBooleanExtra("failfirst", false); // first stream of a channel with duplicates fails once
            ps.simFailNext = in.getIntExtra("failnext", 0);           // the next N starts fail; health is not recorded
            ps.bgSkipSec = in.getIntExtra("bgskip", 0);               // N s after the guide opens, the playing stream "fails to start"
            if (in.hasExtra("autoskip")) ps.dbgAutoSkip = in.getBooleanExtra("autoskip", false);
            if (in.hasExtra("quality")) ps.store.setQuality(in.getStringExtra("quality")); // writes the real prefs
            if (in.hasExtra("mirror")) ps.store.setMirror(in.getBooleanExtra("mirror", false));
            if (in.hasExtra("engine")) ps.prefs.edit().putString("engine", in.getStringExtra("engine")).apply(); // media3 | mediaplayer
            if (in.hasExtra("demo_blur")) ps.prefs.edit().putBoolean("demo_blur", in.getBooleanExtra("demo_blur", false)).apply(); // demo recordings: blur the video, keep the UI sharp
            lk.manus.iptv.ui.Ui.demoBlur = ps.prefs.getBoolean("demo_blur", false);
            if (lk.manus.iptv.ui.Ui.demoBlur) ps.store.setMirror(true); // the blur needs the TextureView
            ps.dbgSub = in.getIntExtra("sub", -1);
            if (in.hasExtra("subsdef")) ps.store.setSubsDefault(in.getBooleanExtra("subsdef", false)); // writes the real prefs
            if (in.getBooleanExtra("dbreset", false)) { ps.prefs.edit().remove("db_migrated").commit(); deleteDatabase("catalogue.db"); } // re-run the import from the old preferences
            Db.forceFail = in.getBooleanExtra("dbfail", false);       // the first database import throws after writing: must roll back and keep the old preferences
            Log.i(TAG, "DEBUG hooks on: failnext=" + ps.simFailNext + " simdrop=" + ps.simDrop + " sub=" + ps.dbgSub);
        }
        Db.start(this, ps.prefs); // opens catalogue.db (importing the old preference strings once) on a worker thread; Store waits for it on first use
        lk.manus.iptv.meta.MetaStore.get(this);
        if (Config.DEBUG) handleDebugIntent(in);
        ps.health = Health.get(this);
        CloudData.get(this).start(new Runnable() {
            @Override public void run() { // worker thread: new snapshot -> re-rank next time a list opens, refresh the now/next views
                ps.h.post(new Runnable() { @Override public void run() { if (isFinishing()) return; if (ps.idx != null) ps.idx.markDirty(); if (ps.list != null) ps.ui.refreshNow(); ps.guide.onCloudData(); } });
            }
        });
        lk.manus.iptv.data.LiveSource.onSynced = new Runnable() { // an Xtream sync finished: show its live channels now, same path as adding a playlist
            @Override public void run() { ps.h.post(new Runnable() { @Override public void run() { if (!isFinishing() && ps.sources != null) { Log.i(TAG, "live channels synced: reloading the channel list"); load(); } } }); }
        };
        ps.prober = Prober.get(this);
        ps.prober.setListener(new Prober.Listener() { @Override public void onProbed(String url, boolean ok) { if (ps.idx != null) ps.idx.markDirty(); } });
        ps.ui.buildUi();
        ps.sources = new Sources(ps.prefs, getFilesDir(), Config.DEBUG ? in.getStringExtra("src") : null); // "src" extra: DEBUG-only test hook for the built-in source
        ps.message.setText(""); // T105b: the logo screen (ps.boot) carries the LoadingLine
        load();
    }

    void load() {
        ps.sources.load(new Sources.Callback() {
            @Override public void onLoaded(final Sources.Loaded ld) {
                if (isFinishing()) return;
                final List<Channel> a = ld.merged;
                ps.all = a;
                if (ps.spike > 0) { if (ps.spikeIdx == null) ps.host.startSpike(); return; }
                if (ps.bootLine != null) ps.bootLine.set(lk.manus.iptv.data.LoadText.sorting(a.size()));
                new Thread(new Runnable() {
                    @Override public void run() {
                        long t = SystemClock.elapsedRealtime();
                        final List<Channel> fy = Index.forYou(a, ps.store);
                        final Index ix = Index.build(ld, fy);
                        Log.i(TAG, "index built in " + (SystemClock.elapsedRealtime() - t) + " ms: " + ix.byUrl.size()
                                + " channels, " + ix.cats.size() + " categories, " + ix.countries.size() + " countries");
                        ps.h.post(new Runnable() { @Override public void run() { if (!isFinishing()) applyIndex(ix); } });
                    }
                }, "index").start();
            }
            @Override public void onStage(String text) { if (ps.bootLine != null) ps.bootLine.set(text); }
            @Override public void onError() {
                if (isFinishing() || ps.list != null) return;
                hideBoot();
                if (ps.filmPlayer == null || !ps.filmPlayer.isActive()) ps.message.setVisibility(View.VISIBLE);
                ps.message.setText("Can't load channels — retrying…");
                ps.h.removeCallbacks(retryLoad);
                ps.h.postDelayed(retryLoad, 20000);
            }
        });
    }

    private void hideBoot() { if (ps.boot != null) { ps.boot.setVisibility(View.GONE); ps.boot = null; ps.bootLine = null; } }

    private void applyIndex(Index ix) {
        hideBoot();
        ps.idx = ix;
        String kill = Config.DEBUG ? getIntent().getStringExtra("kill") : null; // DEBUG: record 3 failed starts (= DEAD) for lk channels whose name contains one of these (comma separated)
        if (kill != null) for (Channel c : ix.all) {
            if (!c.country.equals("lk")) continue;
            for (String t : kill.toLowerCase(Locale.ROOT).split(",")) if (!t.isEmpty() && c.name.toLowerCase(Locale.ROOT).contains(t)) { for (int i = 0; i < 3; i++) ps.health.record(c.url, false); Log.i(TAG, "kill " + c.name); }
            ps.health.flushAsync();
        }
        Map<String, String> fnames = new HashMap<>(); // favourites' names for the safety snapshots (T33)
        for (String fu : ps.store.favs()) { Channel fc = ix.byUrl.get(fu); if (fc != null) fnames.put(fu, fc.name); }
        FavGuard.rememberNames(fnames);
        ix.setSignals(new Rank(ps.store, ps.health));
        ix.refresh(ps.store);
        ps.guide.onIndexChanged();
        ps.remind.arm();
        String sec = Config.DEBUG && getIntent().hasExtra("sec") && ps.list == null ? getIntent().getStringExtra("sec") : ps.store.section(); // DEBUG: open on this section without saving it
        List<Channel> nl = ix.list(sec, ps.store);
        if (nl.isEmpty()) { sec = "foryou"; nl = ix.forYou; }
        if (nl.isEmpty()) {
            if (ps.filmPlayer == null || !ps.filmPlayer.isActive()) ps.message.setVisibility(View.VISIBLE);
            ps.message.setText(ix.all.isEmpty() ? (Config.PLAY ? "No channels yet\n\nAdd your own M3U playlist in Settings \u2192 Sources\nPress OK to open Settings" : "No channels yet \u2014 add a playlist in Settings \u2192 Sources\n\nPress OK to open Settings") : "No channels found");
            return;
        }
        if (ps.list == null) {
            ps.zapSection = sec;
            ps.zapName = ix.title(sec);
            ps.list = nl;
            String last = ps.prefs.getString("last", "");
            String tune = Config.DEBUG ? getIntent().getStringExtra("tune") : null; // DEBUG: start on the first channel whose name contains this
            if (tune != null) {
                List<Channel> al = ix.list("all", ps.store);
                for (Channel c : al) if (c.name.toLowerCase(Locale.ROOT).contains(tune.toLowerCase(Locale.ROOT))) {
                    last = c.url; sec = "all"; nl = al; ps.zapSection = sec; ps.zapName = ix.title(sec); ps.list = nl;
                    String dup = getIntent().getStringExtra("dupalt"); // DEBUG: pretend this other channel is a second stream of the tuned one
                    if (dup != null) for (Channel d : ix.all) if (d.name.toLowerCase(Locale.ROOT).contains(dup.toLowerCase(Locale.ROOT))) { Dedup.register(c, d); break; }
                    break;
                }
            }
            ps.index = 0;
            for (int i = 0; i < nl.size(); i++) if (nl.get(i).url.equals(last)) { ps.index = i; break; }
            ps.message.setVisibility(View.GONE);
            ps.host.ensurePlaying();
            ps.zapper.openPendingGuide();
        } else { // refreshed playlist: keep the current channel and section
            String curUrlNow = ps.list.get(ps.index).url;
            if (ps.zapSection.equals("search")) return; // a search result list is not part of the index
            nl = ix.list(ps.zapSection, ps.store);
            if (nl.isEmpty()) { ps.zapSection = "foryou"; ps.zapName = "For You"; nl = ix.forYou; }
            ps.list = nl;
            ps.index = Math.min(ps.index, nl.size() - 1);
            for (int i = 0; i < nl.size(); i++) if (nl.get(i).url.equals(curUrlNow)) { ps.index = i; break; }
        }
    }

    @Override public void onBackPressed() { } // exit only through Settings > Exit

    @Override public boolean onSearchRequested() { ps.zapper.openSearch(); return true; }

    @Override protected void onResume() {
        super.onResume();
        if (ps.idx != null) lk.manus.iptv.ui.wizard.Wizard.sync(ps.store, ps.idx);
        if (ps.sources != null && ps.list == null && ps.idx != null && ps.idx.all.isEmpty()) { ps.sources.reread(); load(); } // the wizard may have added a playlist while we waited
        ps.paused = false;
        ps.remind.arm();
        ps.sessionT0 = ps.lastSummary = SystemClock.elapsedRealtime();
        ps.stallsTotal = 0; ps.reconnectsTotal = 0; ps.statSamples = 0; ps.mbpsSum = 0; ps.lastRxT = 0;
        ps.h.removeCallbacks(ps.host.tickR);
        ps.h.postDelayed(ps.host.tickR, 1000);
        ps.host.ensurePlaying();
    }

    @Override protected void onPause() {
        ps.paused = true;
        if (ps.filmPlayer != null && ps.filmPlayer.isActive()) ps.filmPlayer.saveProgress(true); // T103: position on disk when the app leaves the screen
        ps.remind.stop();
        ps.h.removeCallbacks(ps.host.tickR);
        ps.guide.hideNow();
        ps.previewCh = null;
        ps.tsh.clearPause();
        ps.fov.hideFail();
        ps.h.removeCallbacks(ps.ui.connR);
        ps.h.removeCallbacks(ps.fov.bgSkipR);
        ps.connBox.setVisibility(View.GONE);
        ps.prober.setPaused(true);
        ps.menus.closeMenu();
        ps.host.stopAll();
        super.onPause();
    }

    @Override protected void onDestroy() {
        lk.manus.iptv.data.LiveSource.onSynced = null;
        ps.h.removeCallbacksAndMessages(null);
        if (ps.tr != null) ps.tr.close();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // the parts call these two to reach the default behaviour of Activity.dispatchKeyEvent / onKeyDown
    boolean superDispatch(KeyEvent e) { return super.dispatchKeyEvent(e); }
    boolean superKeyDown(int k, KeyEvent e) { return super.onKeyDown(k, e); }

    /** OK needs press/long-press tracking, so keys are intercepted before the views see them. */
    @Override public boolean dispatchKeyEvent(KeyEvent e) { return ps.router.dispatch(e); }

    @Override public boolean onKeyDown(int k, KeyEvent e) { return ps.router.keyDown(k, e); }

    /** e.g. "4.2 Mbps ●●●" (good >= 3, fair 1-3, poor < 1). Also meant for the guide preview card. */
    public String speedText() { return ps.host.speedText(); }

    /** Programme title for the watermark; null = show the channel name. */
    public void setNowTitle(String t) { ps.ui.setNowTitle(t); }

    @Override protected void onNewIntent(android.content.Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        if (Config.DEBUG) handleDebugIntent(in);
    }

    /** DEBUG only (T72 tests): "favs:A~B~C" sets favourites by channel name, "recent:A~B", "rmfav:N" removes the first N, "addsrc:url~name", "rmsrc:name", "dump" logs names (never addresses). */
    private void t72Debug(final String cmd) {
        String[] kv = cmd.split(":", 2);
        String arg = kv.length > 1 ? kv[1] : "";
        if (kv[0].equals("favs") || kv[0].equals("recent")) {
            List<String> urls = new java.util.ArrayList<>();
            for (String n : arg.split("~")) for (Channel c : ps.idx.all) if (c.name.equalsIgnoreCase(n.trim())) { urls.add(c.url); break; }
            if (kv[0].equals("favs")) { ps.store.setFavs(urls); ps.guide.onFavChanged(); } else for (String u : urls) ps.store.pushRecent(u);
        } else if (kv[0].equals("rmfav")) {
            List<String> f = ps.store.favs();
            for (int i = 0; i < Integer.parseInt(arg) && i < f.size(); i++) { Channel c = ps.idx.byUrl.get(f.get(i)); ps.store.toggleFav(f.get(i), c == null ? "" : c.name); }
            ps.guide.onFavChanged();
        } else if (kv[0].equals("addsrc")) {
            final String[] nu = arg.split("~", 2);
            new Thread(new Runnable() { @Override public void run() {
                final Sources.Probe pr = ps.sources.probe(nu[0]);
                ps.h.post(new Runnable() { @Override public void run() { if (pr.error == null) { ps.sources.add(nu.length > 1 ? nu[1] : "", pr); load(); } else Log.i(TAG, "DEBUG addsrc failed: " + pr.error); } });
            } }, "t72-addsrc").start();
        } else if (kv[0].equals("quality")) { ps.store.setQuality(arg);
        } else if (kv[0].equals("edge")) { ps.store.setEdge(Integer.parseInt(arg));
        } else if (kv[0].equals("hide")) { ps.store.hide(arg);
        } else if (kv[0].equals("rmsrc")) {
            for (Sources.Source s : ps.sources.list()) if (s.name.equals(arg)) ps.sources.remove(s.id);
            load();
        }
        StringBuilder b = new StringBuilder("DEBUG state favs=[");
        for (String u : ps.store.favs()) { Channel c = ps.idx == null ? null : ps.idx.byUrl.get(u); b.append(c == null ? "?" : c.name).append(" | "); }
        b.append("] recents=[");
        for (String u : ps.store.recents()) { Channel c = ps.idx == null ? null : ps.idx.byUrl.get(u); b.append(c == null ? "?" : c.name).append(" | "); }
        b.append("] sources=[");
        for (Sources.Source s : ps.sources.list()) b.append(s.name).append(s.enabled ? "" : " (off)").append(" | ");
        b.append("] reminders=").append(lk.manus.iptv.data.Reminders.list().size()).append(" quality=").append(ps.store.quality()).append(" edge=").append(ps.store.edge()).append(" hidden=").append(ps.store.hidden().size());
        Log.i(TAG, b.toString());
    }

    /** DEBUG (T110a): classifies the guide's current programmes, looks up to max distinct shows on TMDB TV, logs counts and the final order. */
    private void seriesDebug(final int max) {
        ps.h.post(new Runnable() { @Override public void run() {
            if (ps.idx == null || ps.list == null) { ps.h.postDelayed(this, 1000); return; }
            final java.util.List<lk.manus.iptv.ui.SeriesNow.Entry> all = lk.manus.iptv.ui.SeriesNow.candidates(PlayerActivity.this, ps.idx, ps.store, ps.idx.all);
            new Thread(new Runnable() { @Override public void run() {
                lk.manus.iptv.ui.SeriesNow.loadCached(PlayerActivity.this, all);
                ps.h.post(new Runnable() { @Override public void run() {
                    final java.util.Set<String> titles = new java.util.HashSet<>();
                    int n = 0;
                    java.util.List<lk.manus.iptv.ui.SeriesNow.Entry> order = new java.util.ArrayList<>(all); // marked titles first: they are series for sure, TMDB only adds poster / year / rating
                java.util.Collections.sort(order, new java.util.Comparator<lk.manus.iptv.ui.SeriesNow.Entry>() { @Override public int compare(lk.manus.iptv.ui.SeriesNow.Entry x, lk.manus.iptv.ui.SeriesNow.Entry y) { return (y.parsed.marker ? 1 : 0) - (x.parsed.marker ? 1 : 0); } });
                    for (final lk.manus.iptv.ui.SeriesNow.Entry e : order) {
                        if (e.film != null || n >= max || !titles.add(e.parsed.title)) continue;
                        n++;
                        lk.manus.iptv.ui.SeriesNow.ask(PlayerActivity.this, e, new Runnable() { @Override public void run() { } });
                    }
                    Log.i(TAG, "series dbg: asking TMDB TV for " + n + " distinct titles");
                    ps.h.postDelayed(new Runnable() { @Override public void run() { seriesReport(all); } }, 4000L + n * 600L);
                } });
            } }, "series-dbg").start();
        } });
    }

    /** DEBUG (T110d): for each raw guide title: parse, search TMDB TV, log candidates, pick, and the resolveShow outcome. */
    private void showTest(final String raws) {
        if (raws == null) return;
        new Thread(new Runnable() { @Override public void run() {
            for (String raw : raws.split("[|]")) {
                lk.manus.iptv.data.SeriesClassifier.Parsed p = lk.manus.iptv.data.SeriesClassifier.parse(raw);
                Log.i(TAG, "showtest raw=[" + raw + "] -> show=[" + p.title + "] S" + p.season + " E" + p.episode + " marker=" + p.marker);
                java.util.List<lk.manus.iptv.meta.Matcher.Candidate> cs = lk.manus.iptv.meta.Tmdb.get(PlayerActivity.this).searchTv(p.title);
                if (cs == null) { Log.i(TAG, "showtest search null (no key / network)"); continue; }
                for (int i = 0; i < Math.min(5, cs.size()); i++) { lk.manus.iptv.meta.Matcher.Candidate c = cs.get(i); Log.i(TAG, "showtest  cand " + c.id + " [" + c.title + "] y" + c.year + " v" + c.voteCount + " poster=" + (c.posterPath != null && !c.posterPath.isEmpty())); }
                lk.manus.iptv.meta.Matcher.Candidate pick = lk.manus.iptv.meta.Matcher.pick(p.title, 0, cs);
                Log.i(TAG, "showtest  pick=" + (pick == null ? "NONE (of " + cs.size() + ")" : pick.id + " " + pick.title));
                final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
                Log.i(TAG, "showtest  variants=" + lk.manus.iptv.data.SeriesClassifier.showTitles(p.title));
                lk.manus.iptv.ui.SeriesNow.resolve(lk.manus.iptv.meta.MetaStore.get(PlayerActivity.this), lk.manus.iptv.data.SeriesClassifier.showTitles(p.title), 0, new java.util.function.Consumer<lk.manus.iptv.meta.MetaStore.Film>() {
                    @Override public void accept(lk.manus.iptv.meta.MetaStore.Film f) {
                        Log.i(TAG, "showtest  resolved -> " + (f.isMatched() ? "MATCH " + f.title + " id" + f.tmdbId + " y" + f.releaseYear + " r" + f.rating + " v" + f.votes + " poster=" + f.posterPath : "no-match"));
                        l.countDown();
                    } });
                try { l.await(20, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { return; }
            }
            Log.i(TAG, "showtest done");
        } }, "showtest").start();
    }

    private void seriesReport(java.util.List<lk.manus.iptv.ui.SeriesNow.Entry> all) {
        int marked = 0, maybe = 0, asked = 0, matchedTv = 0, shown = 0;
        for (lk.manus.iptv.ui.SeriesNow.Entry e : all) {
            if (e.parsed.marker) marked++; else maybe++;
            if (e.film != null) asked++;
            if (e.matched()) matchedTv++;
        }
        java.util.List<lk.manus.iptv.ui.SeriesNow.Entry> list = lk.manus.iptv.ui.SeriesNow.shown(all);
        shown = list.size();
        lk.manus.iptv.ui.SeriesNow.sort(list, ps.store);
        Log.i(TAG, "series counts: guide entries=" + all.size() + " marked-by-title=" + marked + " unmarked-short=" + maybe + " with-TMDB-answer=" + asked + " matched-TV=" + matchedTv + " series-shown=" + shown);
        for (int i = 0; i < Math.min(20, list.size()); i += 4) Log.i(TAG, "series order: " + lk.manus.iptv.ui.SeriesNow.describe(list.subList(i, Math.min(i + 4, list.size())), 4));
    }

    private void handleDebugIntent(android.content.Intent in) {
        if (in == null) return;
        Log.i(TAG, "handleDebugIntent: " + in.getExtras());
        if (in.hasExtra("t72") && ps.sources != null && ps.idx != null) t72Debug(in.getStringExtra("t72"));
        if (in.hasExtra("sleepsec")) ps.sleep.debugSet(in.getIntExtra("sleepsec", 70)); // DEBUG: sleep timer that ends after N s
        if (in.hasExtra("remindin")) ps.remind.debugAdd(in.getIntExtra("remindin", 40), in.hasExtra("remindtitle") ? in.getStringExtra("remindtitle") : "Divergent"); // DEBUG: a reminder due in N s
        if (in.hasExtra("filmsfuture")) lk.manus.iptv.ui.Films.debugFuture = in.getBooleanExtra("filmsfuture", false); // Details: every film "starts in 3 h"
        if (in.getBooleanExtra("metawipe", false)) {
            lk.manus.iptv.meta.MetaStore.get(this).wipe();
        }
        if (in.hasExtra("tmdb_base")) {
            String tb = in.getStringExtra("tmdb_base");
            lk.manus.iptv.meta.Tmdb.setBaseUrl(tb);
            ps.prefs.edit().putString("tmdb_base", tb).apply();
        }
        if (in.hasExtra("tmdb_img_base")) {
            String tib = in.getStringExtra("tmdb_img_base");
            lk.manus.iptv.meta.PosterLoader.setImgBaseUrl(tib);
            ps.prefs.edit().putString("tmdb_img_base", tib).apply();
        }
        if (in.hasExtra("toastdbg")) { // DEBUG (T110b): open the guide, then fire the subtitle-download pill over it
            ps.h.post(new Runnable() { @Override public void run() {
                if (ps.idx == null || ps.list == null) { ps.h.postDelayed(this, 1000); return; }
                ps.zapper.prepGuide(); ps.guide.open();
                ps.ui.showToast("Downloading Polish→English subtitles (once, ~30 MB)…", 0);
            } });
        }
        if (in.hasExtra("showtest")) showTest(in.getStringExtra("showtest")); // DEBUG (T110d): resolve show titles "A|B|C" through the real path
        if (in.hasExtra("seriesdbg")) seriesDebug(in.getIntExtra("seriesdbg", 60)); // DEBUG (T110a): log Series on now from the real guide
        if (in.hasExtra("openrail")) {
            final String rid = in.getStringExtra("openrail");
            ps.h.post(new Runnable() {
                @Override public void run() {
                    if (ps.idx == null || ps.list == null) return;
                    ps.zapper.prepGuide();
                    ps.guide.openSection(rid);
                    ps.ui.wmUpdate();
                }
            });
        }
        if (in.hasExtra("metatest")) {
            final String raw = in.getStringExtra("metatest");
            lk.manus.iptv.meta.MetaStore.get(this).resolve(raw, new lk.manus.iptv.meta.MetaStore.Callback() {
                @Override public void onResolved(lk.manus.iptv.meta.TitleCleaner.Cleaned clean, lk.manus.iptv.meta.MetaStore.Film film) {
                    if (clean != null && film != null && film.isMatched()) {
                        Log.i(TAG, String.format(java.util.Locale.US, "IPTV meta: %s|%d -> %d|%.2f|%s",
                                clean.title, clean.year, film.tmdbId, film.matchScore, film.posterPath));
                    } else if (clean != null) {
                        Log.i(TAG, String.format(java.util.Locale.US, "IPTV meta: %s|%d -> no match",
                                clean.title, clean.year));
                    } else {
                        Log.i(TAG, "IPTV meta: no match");
                    }
                }
            });
        }
        if (in.hasExtra("xtreamphase")) {
            final String raw = in.getStringExtra("xtreamphase");
            if (raw != null) {
                final String[] parts = raw.split("\\|", 3);
                if (parts.length >= 3) {
                    new Thread(new Runnable() {
                        @Override public void run() {
                            lk.manus.iptv.data.XtreamSource.measurePhases(PlayerActivity.this, parts[0], parts[1], parts[2]);
                        }
                    }, "t81-xtream-phase").start();
                }
            }
        }
        if (in.hasExtra("xtream")) {
            final String raw = in.getStringExtra("xtream");
            if (raw != null) {
                final String[] parts = raw.split("\\|", 3);
                if (parts.length >= 3) {
                    if (in.getBooleanExtra("phase", false)) {
                        new Thread(new Runnable() {
                            @Override public void run() {
                                lk.manus.iptv.data.XtreamSource.measurePhases(PlayerActivity.this, parts[0], parts[1], parts[2]);
                            }
                        }, "t81-xtream-phase").start();
                        return;
                    }
                    new Thread(new Runnable() {
                        @Override public void run() {
                            lk.manus.iptv.data.XtreamSource.Account acc = lk.manus.iptv.data.XtreamSource.verify(parts[0], parts[1], parts[2]);
                            if (acc.state != lk.manus.iptv.data.XtreamSource.Account.OK) {
                                Log.i(TAG, "IPTV xtream: " + acc.stateName());
                                return;
                            }
                            Log.i(TAG, "IPTV xtream: ok");
                            Db db = Db.get();
                            if (db == null) return;
                            String norm = lk.manus.iptv.data.XtreamSource.normaliseAddress(parts[0]);
                            long existingId = -1;
                            android.database.Cursor cur = db.getReadableDatabase().rawQuery(
                                    "SELECT id FROM source WHERE kind = 'xtream' AND address = ?",
                                    new String[]{norm != null ? norm : parts[0]});
                            try {
                                if (cur.moveToFirst()) existingId = cur.getLong(0);
                            } finally {
                                cur.close();
                            }
                            final long sourceId = existingId > 0 ? existingId : lk.manus.iptv.data.XtreamSource.add(db, "Xtream Demo", parts[0], parts[1], parts[2]);
                            if (sourceId < 0) return;
                            final long t0 = SystemClock.elapsedRealtime();
                            lk.manus.iptv.data.XtreamSource.sync(PlayerActivity.this, sourceId, new lk.manus.iptv.data.XtreamSource.Listener() {
                                @Override public void onProgress(int films) {}
                                @Override public void onDone(int films, String errorOrNull) {
                                    long ms = SystemClock.elapsedRealtime() - t0;
                                    Log.i(TAG, "xtream sync: " + films + " films in " + ms + " ms");
                                }
                            });
                        }
                    }, "t81-xtream-debug").start();
                }
            }
        }
        if (in.hasExtra("xtreamplay")) {
            long itemIdRaw = -1;
            Object obj = in.getExtras() != null ? in.getExtras().get("xtreamplay") : null;
            if (obj instanceof Number) {
                itemIdRaw = ((Number) obj).longValue();
            } else if (obj != null) {
                try {
                    itemIdRaw = Long.parseLong(obj.toString().trim());
                } catch (Exception ignored) {}
            }
            final long itemId = itemIdRaw;
            new Thread(new Runnable() {
                @Override public void run() {
                    Db db = Db.get();
                    if (db == null) return;
                    String url = lk.manus.iptv.data.XtreamSource.playUrl(db, itemId);
                    if (url != null) {
                        Log.i(TAG, "xtream play host=" + lk.manus.iptv.data.Redact.host(url));
                    }
                }
            }, "t81-xtreamplay-debug").start();
        }
        if (in.hasExtra("xtreamremove")) {
            long sidRaw = -1;
            Object obj = in.getExtras() != null ? in.getExtras().get("xtreamremove") : null;
            if (obj instanceof Number) {
                sidRaw = ((Number) obj).longValue();
            } else if (obj != null) {
                try {
                    sidRaw = Long.parseLong(obj.toString().trim());
                } catch (Exception ignored) {}
            }
            final long sid = sidRaw;
            final boolean delProg = in.getBooleanExtra("deleteprogress", true);
            new Thread(new Runnable() {
                @Override public void run() {
                    Db db = Db.get();
                    if (db != null) {
                        lk.manus.iptv.data.XtreamSource.remove(db, sid, delProg);
                        Log.i(TAG, "xtream remove: done");
                    }
                }
            }, "t81-xtreamremove-debug").start();
        }
        if (Config.DEBUG && in.hasExtra("serieslist")) {
            long sidRaw = -1;
            Object obj = in.getExtras() != null ? in.getExtras().get("serieslist") : null;
            if (obj instanceof Number) sidRaw = ((Number) obj).longValue();
            else if (obj != null) {
                try { sidRaw = Long.parseLong(obj.toString().trim()); } catch (Exception ignored) {}
            }
            final long sid = sidRaw;
            new Thread(new Runnable() {
                @Override public void run() {
                    Db db = Db.get();
                    if (db == null) return;
                    android.database.sqlite.SQLiteDatabase d = db.getReadableDatabase();
                    String q = sid > 0 ? "SELECT id, title FROM item WHERE kind = 'series' AND source_id = ? ORDER BY id ASC" : "SELECT id, title FROM item WHERE kind = 'series' ORDER BY id ASC";
                    String[] args = sid > 0 ? new String[]{String.valueOf(sid)} : null;
                    android.database.Cursor c = d.rawQuery(q, args);
                    List<String> titles = new java.util.ArrayList<>();
                    int total = c.getCount();
                    try {
                        while (c.moveToNext() && titles.size() < 5) titles.add(c.getString(1));
                    } finally { c.close(); }
                    Log.i(TAG, "serieslist: count=" + total + " first 5: " + titles);
                }
            }, "t91-serieslist-debug").start();
        }
        if (Config.DEBUG && in.hasExtra("seriesepisodes")) {
            String pidRaw = null;
            Object obj = in.getExtras() != null ? in.getExtras().get("seriesepisodes") : null;
            if (obj != null) pidRaw = obj.toString().trim();
            final String providerId = pidRaw;
            final boolean force = in.getBooleanExtra("force", false);
            new Thread(new Runnable() {
                @Override public void run() {
                    Db db = Db.get();
                    if (db == null || providerId == null) return;
                    android.database.sqlite.SQLiteDatabase d = db.getReadableDatabase();
                    long seriesItemId = -1;
                    android.database.Cursor c = d.rawQuery("SELECT id FROM item WHERE kind = 'series' AND provider_id = ? LIMIT 1", new String[]{providerId});
                    try { if (c.moveToFirst()) seriesItemId = c.getLong(0); } finally { c.close(); }
                    if (seriesItemId < 0) {
                        Log.w(TAG, "seriesepisodes: series not found for provider_id=" + providerId);
                        return;
                    }
                    long t0 = SystemClock.elapsedRealtime();
                    lk.manus.iptv.data.SeriesSource.ensureEpisodes(db, seriesItemId, force);
                    long elapsed = SystemClock.elapsedRealtime() - t0;
                    List<lk.manus.iptv.data.SeriesSource.Season> seasons = lk.manus.iptv.data.SeriesSource.seasons(db, seriesItemId);
                    List<lk.manus.iptv.data.SeriesSource.Episode> eps = lk.manus.iptv.data.SeriesSource.episodes(db, seriesItemId);
                    Log.i(TAG, "seriesepisodes: providerId=" + providerId + " seasons=" + seasons.size() + " episodes=" + eps.size() + " in " + elapsed + " ms");
                }
            }, "t91-seriesepisodes-debug").start();
        }
        if (Config.DEBUG && in.hasExtra("seriesplay")) {
            long itemIdRaw = -1;
            Object obj = in.getExtras() != null ? in.getExtras().get("seriesplay") : null;
            if (obj instanceof Number) itemIdRaw = ((Number) obj).longValue();
            else if (obj != null) {
                try { itemIdRaw = Long.parseLong(obj.toString().trim()); } catch (Exception ignored) {}
            }
            final long rawId = itemIdRaw;
            new Thread(new Runnable() {
                @Override public void run() {
                    final Db db = Db.get();
                    if (db == null) return;
                    long targetItemId = rawId;
                    android.database.sqlite.SQLiteDatabase d = db.getReadableDatabase();
                    android.database.Cursor cur = d.rawQuery("SELECT id FROM item WHERE id = ?", new String[]{String.valueOf(rawId)});
                    try {
                        if (!cur.moveToFirst()) {
                            android.database.Cursor curPid = d.rawQuery("SELECT id FROM item WHERE kind = 'episode' AND provider_id = ? LIMIT 1", new String[]{String.valueOf(rawId)});
                            try { if (curPid.moveToFirst()) targetItemId = curPid.getLong(0); } finally { curPid.close(); }
                        }
                    } finally { cur.close(); }
                    final long finalItemId = targetItemId;
                    String url = lk.manus.iptv.data.XtreamSource.playUrl(db, finalItemId);
                    if (url != null) {
                        Log.i(TAG, "xtream play host=" + lk.manus.iptv.data.Redact.host(url));
                    }
                    ps.h.post(new Runnable() {
                        @Override public void run() {
                            if (ps.filmPlayer != null) {
                                lk.manus.iptv.data.Library.Item item = lk.manus.iptv.data.Library.get(db, finalItemId);
                                if (item != null) {
                                    ps.filmPlayer.play(item, 0L);
                                }
                            }
                        }
                    });
                }
            }, "t91-seriesplay-debug").start();
        }
        if (Config.DEBUG && in.hasExtra("seriesnext")) {
            String pidRaw = null;
            Object obj = in.getExtras() != null ? in.getExtras().get("seriesnext") : null;
            if (obj != null) pidRaw = obj.toString().trim();
            final String providerId = pidRaw;
            new Thread(new Runnable() {
                @Override public void run() {
                    Db db = Db.get();
                    if (db == null || providerId == null) return;
                    android.database.sqlite.SQLiteDatabase d = db.getReadableDatabase();
                    long seriesItemId = -1;
                    android.database.Cursor c = d.rawQuery("SELECT id FROM item WHERE kind = 'series' AND provider_id = ? LIMIT 1", new String[]{providerId});
                    try { if (c.moveToFirst()) seriesItemId = c.getLong(0); } finally { c.close(); }
                    if (seriesItemId < 0) {
                        Log.w(TAG, "seriesnext: series not found for provider_id=" + providerId);
                        return;
                    }
                    lk.manus.iptv.data.SeriesLogic.SeriesProgress sp = lk.manus.iptv.data.SeriesLogic.seriesProgress(db, seriesItemId);
                    String btn = lk.manus.iptv.data.SeriesLogic.buttonText(sp.target, sp.hasAnyProgress);
                    String tgtStr = sp.target != null ? ("S" + sp.target.season + " E" + sp.target.episode) : "null";
                    Log.i(TAG, "seriesnext: providerId=" + providerId + " target=" + tgtStr + " button=" + btn);
                }
            }, "t92b-seriesnext-debug").start();
        }
        if (Config.DEBUG && in.hasExtra("seriesmark")) {
            final String pid = in.getStringExtra("seriesmark");
            final int s = in.getIntExtra("season", 0);
            final int epFrom = in.getIntExtra("ep_from", 0);
            final int epTo = in.getIntExtra("ep_to", 0);
            final int ep = in.getIntExtra("ep", 0);
            final boolean watched = in.getBooleanExtra("watched", true);
            final long pos = in.getLongExtra("pos", 0L);
            final long dur = in.getLongExtra("dur", 0L);
            new Thread(new Runnable() {
                @Override public void run() {
                    Db db = Db.get();
                    if (db == null || pid == null) return;
                    android.database.sqlite.SQLiteDatabase d = db.getWritableDatabase();
                    long seriesItemId = -1;
                    android.database.Cursor c = d.rawQuery("SELECT id FROM item WHERE kind = 'series' AND provider_id = ? LIMIT 1", new String[]{pid});
                    try { if (c.moveToFirst()) seriesItemId = c.getLong(0); } finally { c.close(); }
                    if (seriesItemId < 0) return;
                    int from = epFrom > 0 ? epFrom : (ep > 0 ? ep : 1);
                    int to = epTo > 0 ? epTo : (ep > 0 ? ep : from);
                    long now = System.currentTimeMillis();
                    if (s <= 0 && pos <= 0) {
                        if (watched) {
                            d.execSQL("INSERT OR REPLACE INTO progress(profile_id, item_id, position_ms, duration_ms, watched, updated_at) " +
                                    "SELECT 1, id, 0, 0, 1, ? FROM item WHERE parent_id = ? AND kind = 'episode'",
                                    new Object[]{now, seriesItemId});
                        } else {
                            d.execSQL("DELETE FROM progress WHERE profile_id = 1 AND item_id IN " +
                                    "(SELECT id FROM item WHERE parent_id = ? AND kind = 'episode')",
                                    new Object[]{seriesItemId});
                        }
                    } else if (pos > 0) {
                        d.execSQL("INSERT OR REPLACE INTO progress(profile_id, item_id, position_ms, duration_ms, watched, updated_at) " +
                                "SELECT 1, id, ?, ?, ?, ? FROM item WHERE parent_id = ? AND season = ? AND episode >= ? AND episode <= ? AND kind = 'episode'",
                                new Object[]{pos, dur, watched ? 1 : 0, now, seriesItemId, s, from, to});
                    } else if (watched) {
                        d.execSQL("INSERT OR REPLACE INTO progress(profile_id, item_id, position_ms, duration_ms, watched, updated_at) " +
                                "SELECT 1, id, 0, 0, 1, ? FROM item WHERE parent_id = ? AND season = ? AND episode >= ? AND episode <= ? AND kind = 'episode'",
                                new Object[]{now, seriesItemId, s, from, to});
                    } else {
                        d.execSQL("DELETE FROM progress WHERE profile_id = 1 AND item_id IN " +
                                "(SELECT id FROM item WHERE parent_id = ? AND season = ? AND episode >= ? AND episode <= ? AND kind = 'episode')",
                                new Object[]{seriesItemId, s, from, to});
                    }
                    Log.i(TAG, "seriesmark: provider=" + pid + " S" + s + " E" + from + ".." + to + " watched=" + watched + " pos=" + pos);
                }
            }, "t92b-seriesmark-debug").start();
        }
        if (Config.DEBUG && in.hasExtra("backupdebug")) {
            final String pass = in.getStringExtra("backupdebug");
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        for (int i = 0; i < 50 && (ps.sources == null || ps.store == null); i++) {
                            try { Thread.sleep(100); } catch (Exception ignored) {}
                        }
                        android.content.SharedPreferences p = getSharedPreferences("iptv", MODE_PRIVATE);
                        Store st = ps.store;
                        Sources src = ps.sources;
                        char[] pc = pass != null ? pass.toCharArray() : "correcthorse".toCharArray();
                        lk.manus.iptv.data.AddServer.Download dl = lk.manus.iptv.data.BackupOps.backup(PlayerActivity.this, p, st, src, pc);
                        Log.i(TAG, "backupdebug: done " + dl.name + " " + dl.bytes.length + " B");
                    } catch (Exception e) {
                        Log.e(TAG, "backupdebug: fail " + e.getClass().getSimpleName());
                    }
                }
            }, "backupdebug").start();
        }
        if (Config.DEBUG && in.hasExtra("restoredebug")) {
            final String raw = in.getStringExtra("restoredebug");
            Log.i(TAG, "restoredebug extra received: " + raw);
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Log.i(TAG, "restoredebug thread started");
                        for (int i = 0; i < 50 && (ps.sources == null || ps.store == null); i++) {
                            try { Thread.sleep(100); } catch (Exception ignored) {}
                        }
                        String pass = raw, filename = null;
                        if (raw != null && raw.contains("|")) {
                            String[] sp = raw.split("\\|", 2);
                            pass = sp[0]; filename = sp[1];
                        }
                        android.content.SharedPreferences p = getSharedPreferences("iptv", MODE_PRIVATE);
                        Store st = ps.store;
                        Sources src = ps.sources;
                        byte[] fileBytes;
                        if (filename != null && !filename.isEmpty()) {
                            java.io.File f = new java.io.File(filename);
                            if (f.isAbsolute()) {
                                fileBytes = java.nio.file.Files.readAllBytes(f.toPath());
                            } else {
                                fileBytes = lk.manus.iptv.data.BackupOps.readLocal(PlayerActivity.this, filename);
                            }
                        } else {
                            List<java.io.File> copies = lk.manus.iptv.data.BackupOps.localCopies(PlayerActivity.this);
                            if (copies.isEmpty()) throw new IllegalStateException("no local copies");
                            fileBytes = lk.manus.iptv.data.BackupOps.readLocal(PlayerActivity.this, copies.get(0).getName());
                        }
                        char[] pc = pass != null ? pass.toCharArray() : "correcthorse".toCharArray();
                        lk.manus.iptv.data.Backup.Data d = lk.manus.iptv.data.BackupOps.open(fileBytes, pc);
                        lk.manus.iptv.data.BackupOps.restore(PlayerActivity.this, p, st, src, d);
                        Log.i(TAG, "restoredebug: done");
                    } catch (Throwable e) {
                        Log.e(TAG, "restoredebug: fail " + e.getClass().getSimpleName(), e);
                    }
                }
            }, "restoredebug").start();
        }
        if (in.hasExtra("filmfakeaudiofail")) {
            ps.dbgFakeAudioFail = in.getBooleanExtra("filmfakeaudiofail", false);
            if (ps.dbgFakeAudioFail && ps.filmPlayer != null) {
                ps.filmPlayer.showAudioFail();
            }
        }
        if (in.hasExtra("filmplay")) {
            long itemIdRaw = in.getLongExtra("filmplay", -1L);
            if (itemIdRaw <= 0) {
                Object obj = in.getExtras() != null ? in.getExtras().get("filmplay") : null;
                if (obj instanceof Number) itemIdRaw = ((Number) obj).longValue();
                else if (obj != null) {
                    try { itemIdRaw = Long.parseLong(obj.toString().trim()); } catch (Exception ignored) {}
                }
            }
            final long itemId = itemIdRaw;
            final long startPos = in.getLongExtra("filmpos", 0L);
            Log.i(TAG, "filmplay intent parsed: itemId=" + itemId + " startPos=" + startPos);
            final Runnable playR = new Runnable() {
                int attempts = 0;
                @Override public void run() {
                    Db db = Db.get();
                    if (db == null && attempts++ < 10) {
                        ps.h.postDelayed(this, 200);
                        return;
                    }
                    if (db != null && ps.filmPlayer != null) {
                        lk.manus.iptv.data.Library.Item item = lk.manus.iptv.data.Library.get(db, itemId);
                        Log.i(TAG, "filmplay found item: " + (item != null ? item.title : "null"));
                        if (item != null) {
                            ps.filmPlayer.play(item, startPos);
                        }
                    }
                }
            };
            ps.h.post(playR);
        }
        if (in.hasExtra("filmopen")) {
            long itemIdRaw = in.getLongExtra("filmopen", -1L);
            if (itemIdRaw <= 0) {
                Object obj = in.getExtras() != null ? in.getExtras().get("filmopen") : null;
                if (obj instanceof Number) itemIdRaw = ((Number) obj).longValue();
                else if (obj != null) {
                    try { itemIdRaw = Long.parseLong(obj.toString().trim()); } catch (Exception ignored) {}
                }
            }
            final long itemId = itemIdRaw;
            Log.i(TAG, "filmopen intent parsed: itemId=" + itemId);
            final Runnable openR = new Runnable() {
                int attempts = 0;
                @Override public void run() {
                    Db db = Db.get();
                    if (db == null && attempts++ < 10) {
                        ps.h.postDelayed(this, 200);
                        return;
                    }
                    if (db != null && ps.guide != null) {
                        lk.manus.iptv.data.Library.Item item = lk.manus.iptv.data.Library.get(db, itemId);
                        Log.i(TAG, "filmopen found item: " + (item != null ? item.title : "null"));
                        if (item != null) {
                            ps.guide.reopenLibDetails(item);
                        }
                    }
                }
            };
            ps.h.post(openR);
        }
        if (Config.DEBUG && in.hasExtra("audiotrip")) {
            ps.h.post(new Runnable() {
                @Override public void run() {
                    if (ps.host != null) ps.host.triggerAudioTrip();
                }
            });
        }
    }
}
