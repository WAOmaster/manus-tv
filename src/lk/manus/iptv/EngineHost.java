package lk.manus.iptv;

import android.net.TrafficStats;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Locale;
import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Dedup;
import lk.manus.iptv.ui.Ui;
import lk.manus.iptv.data.Redact;
import lk.manus.iptv.player.AudioGuard;
import lk.manus.iptv.player.Engine;
import lk.manus.iptv.player.Hls;
import lk.manus.iptv.player.MediaPlayerEngine;
import lk.manus.iptv.player.Media3Engine;

/** Owns the playback engine: surfaces, starting and stopping a channel, buffering, quality steps, speed meter and the hidden spike test mode. */
final class EngineHost implements SurfaceHolder.Callback {
    final PlayState ps;

    EngineHost(PlayState ps) { this.ps = ps; }

    private static final String TAG = "IPTV";
    private static final String NET = "IPTV-net";
    private static final int TIMEOUT_MS = 15000;
    private static final int SPIKE_TIMEOUT_MS = 12000;

    private SurfaceHolder holder;
    private boolean surfaceReady;
    private int vw;
    private int vh;
    private Object[] pendingStart;
    private boolean m3Broken;
    private boolean hintShown;
    private boolean simFailDone;
    private final AudioGuard aGuard = new AudioGuard();
    private int audioTrips;
    private int spikeI;
    private boolean curDone;
    private File spikeFile;
    private boolean simDropDone;
    private boolean simStallDone;
    private String quality = "auto";
    private List<Hls.Variant> variants;
    private final ArrayList<Long> stallTimes = new ArrayList<>();
    private long lastNetEvent;
    private long lastRx;
    private double mbps;

    final Runnable showBufR = new Runnable() { @Override public void run() { if (ps.buffering && ps.playing) ps.ui.setPill("Buffering…"); } };

    final Runnable longBufR = new Runnable() { @Override public void run() { if (ps.buffering && ps.playing) { Log.i(NET, "buffering > 10 s"); ps.fov.fail("error", "buffer>10s"); } } };

    final Runnable tickR = new Runnable() { @Override public void run() { tick(); ps.h.postDelayed(this, 1000); } };

    final Runnable startR = new Runnable() { @Override public void run() { play(ps.zapper.cur()); } };

    private final Runnable spikeNextR = new Runnable() { @Override public void run() { spikeNext(); } };

    void ensurePlaying() {
        if (ps.list == null || ps.list.isEmpty() || !(ps.useTex ? ps.texSurface != null : surfaceReady) || ps.paused || ps.eng != null || ps.gaveUp || ps.userPaused) return;
        if (ps.h.hasCallbacks(startR) || ps.h.hasCallbacks(ps.fov.skipR)) return;
        if (ps.spike > 0) { spikeNext(); return; }
        ps.ui.updateBanner();
        play(ps.zapper.cur());
    }

    void play(Channel c) { play(c, null, false); }

    /** keep = reconnect or variant switch of the same channel: keeps the outage/variant state and stays quiet. */
    void play(Channel c, String url, boolean keep) {
        ps.h.removeCallbacks(startR);
        ps.h.removeCallbacks(ps.fov.watchdog);
        ps.h.removeCallbacks(ps.fov.reconnectR);
        ps.h.removeCallbacks(showBufR);
        ps.h.removeCallbacks(longBufR);
        ps.h.removeCallbacks(ps.ui.connR);
        ps.fov.hideFail();
        ps.connBox.setVisibility(View.GONE);
        ps.playing = false;
        ps.buffering = false;
        curDone = false;
        ps.playCh = c;
        if (!keep && !ps.failover) ps.altFor = null;
        ps.prober.setPaused(true); // probes wait while a channel is starting
        ps.ui.wmUpdate();
        ps.subsCtl.clearSubs();
        final int g = ++ps.gen;
        ps.t0 = SystemClock.elapsedRealtime();
        ps.tsh.tsReset();
        if (!keep) {
            ps.reconnecting = false; ps.rc = 0; variants = null; ps.curVar = -1; ps.curUrl = null; stallTimes.clear();
            lastNetEvent = ps.t0;
            aGuard.reset(); audioTrips = 0; ps.infoUp = false;
            quality = ps.store.quality();
            ps.ui.hidePill();
        }
        // mirror mode: swap the video target between channel starts; the unused view is hidden
        boolean want = ps.store.mirror();
        if (want != ps.useTex) {
            ps.useTex = want;
            ps.tex.setVisibility(want ? View.VISIBLE : View.GONE);
            ps.sv.setVisibility(want ? View.GONE : View.VISIBLE);
            Log.i(TAG, "mirror mode " + (want ? "on" : "off"));
        }
        Log.i(TAG, "start #" + (ps.index + 1) + " " + c.name + " " + Redact.host(c.url) + (keep ? " (retry)" : ""));
        if (ps.spike == 0 && !ps.guide.isOpen() && !keep) { ps.ui.setState(lk.manus.iptv.data.LoadText.tuning()); ps.ui.showBanner(); }
        ps.h.postDelayed(ps.fov.watchdog, ps.spike > 0 ? SPIKE_TIMEOUT_MS : TIMEOUT_MS);
        if (!keep && ps.spike == 0) ps.h.postDelayed(ps.ui.connR, 1500);
        final Engine old = ps.eng;
        ps.eng = null;
        final Channel cc = c;
        final String u = url;
        final boolean k = keep;
        if (old == null) { begin(cc, u, k, g); return; }
        // the old player must be fully released before a new one can attach to the same surface
        old.release(new Runnable() { @Override public void run() { if (g == ps.gen) begin(cc, u, k, g); } });
    }

    boolean media3() { return !m3Broken && !"mediaplayer".equals(ps.prefs.getString("engine", "media3")); }

    /** MediaPlayer path: auto: start on the master at once and look at its variants in the background; best/saver: wait for the playlist. */
    /** Media3 picks variants itself (quality limits); only MediaPlayer needs the master playlist juggling below. */
    private void begin(final Channel c, String url, boolean keep, final int g) {
        if (url != null || keep || ps.spike > 0 || media3() || !wantsHls(c.url)) { startPlayer(c, url != null ? url : c.url, g); return; }
        final boolean auto = !"best".equals(quality) && !"saver".equals(quality);
        if (auto) startPlayer(c, c.url, g);
        new Thread(new Runnable() {
            @Override public void run() {
                Hls.Fetched f = null;
                try { f = Hls.fetch(c.url, c.userAgent, c.referrer); } catch (Exception e) { Log.i(NET, "master fetch failed " + e.getClass().getSimpleName()); }
                final List<Hls.Variant> v = f == null ? null : Hls.parseMaster(f.body, f.url);
                ps.h.post(new Runnable() {
                    @Override public void run() {
                        if (g != ps.gen) return;
                        if (v != null && !v.isEmpty()) {
                            variants = v;
                            StringBuilder b = new StringBuilder();
                            for (Hls.Variant x : v) b.append(' ').append(x.label());
                            Log.i(NET, "master " + Redact.host(c.url) + " variants:" + b);
                        }
                        if (auto) return;
                        Hls.Variant pick = v == null ? null : Hls.pick(v, quality);
                        if (pick != null) { ps.curVar = v.indexOf(pick); ps.curUrl = pick.url; Log.i(NET, quality + " -> " + pick.label() + " " + Redact.host(pick.url)); }
                        else Log.i(NET, quality + " -> no master, playing as is");
                        ps.h.removeCallbacks(ps.fov.watchdog);
                        ps.h.postDelayed(ps.fov.watchdog, TIMEOUT_MS);
                        startPlayer(c, pick != null ? pick.url : c.url, g);
                    }
                });
            }
        }, "hls").start();
    }

    /** Plain video endpoints are never probed: a second connection can cost a login slot. */
    private static boolean wantsHls(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        return u.startsWith("http") && !(u.endsWith(".ts") || u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".flv") || u.endsWith(".mp3") || u.endsWith(".aac"));
    }

    /** Mirror mode: size the TextureView to the video's aspect inside the screen (bars instead of stretching). */
    void fitTex() {
        View root = (View) ps.tex.getParent();
        int W = root.getWidth(), H = root.getHeight();
        if (W == 0 || H == 0) return;
        int w = W, hh = H;
        if (vw > 0 && vh > 0) { hh = (int) ((long) W * vh / vw); if (hh > H) { hh = H; w = (int) ((long) H * vw / vh); } }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ps.tex.getLayoutParams();
        if (lp.width != w || lp.height != hh) { lp.width = w; lp.height = hh; ps.tex.setLayoutParams(lp); }
    }

    private boolean targetReady() { return ps.useTex ? ps.texSurface != null : surfaceReady; }

    void tryPending() {
        Object[] p = pendingStart;
        if (p == null || !targetReady()) return;
        pendingStart = null;
        if ((Integer) p[2] == ps.gen) startPlayer((Channel) p[0], (String) p[1], ps.gen);
    }

    private void startPlayer(Channel c, String url, final int g) {
        if (!targetReady()) { pendingStart = new Object[]{c, url, g}; return; } // the switched view has no surface yet
        ps.curUrl = url.equals(c.url) ? null : url;
        if (ps.simDead && ps.reconnecting) { ps.h.post(new Runnable() { @Override public void run() { if (g == ps.gen) ps.fov.fail("error", "simdead"); } }); return; }
        if (ps.simFailFirst && !simFailDone && ps.previewCh == null && !Dedup.alternates(c).isEmpty() && c == ps.list.get(ps.index)) {
            ps.h.post(new Runnable() { @Override public void run() { if (g == ps.gen) { simFailDone = true; ps.fov.fail("error", "simfailfirst"); } } }); // DEBUG: pretend the first stream of a duplicated channel never starts
            return;
        }
        if (ps.simFailNext > 0 && ps.previewCh == null) {
            ps.h.post(new Runnable() { @Override public void run() { if (g == ps.gen && ps.simFailNext > 0) { ps.simFailNext--; ps.fov.fail("error", "simfailnext"); } } }); // DEBUG: this start "fails"; health is not recorded
            return;
        }
        final Engine.Listener ls = new Engine.Listener() {
            @Override public void onPlaying() { if (g == ps.gen) EngineHost.this.onPlaying(); }
            @Override public void onBuffering(boolean start) { if (g != ps.gen) return; if (start) onBufferStart(); else onBufferEnd(); }
            @Override public void onError(String detail) { if (g == ps.gen) ps.fov.fail("error", detail); }
            @Override public void onVideoSize(int w, int hh) { if (g == ps.gen) { vw = w; vh = hh; fitTex(); Log.i(TAG, "video " + w + "x" + hh); } }
            @Override public void onTracks() { if (g == ps.gen && ps.playing) ps.subsCtl.scanTracks(); }
            @Override public void onCues(String t) { if (g == ps.gen) { ps.curCue = t; ps.trCue = ""; if (ps.trOn && !t.isEmpty()) ps.subsCtl.translateCue(t); ps.subsCtl.showCue(); } }
            @Override public void onAudioDrop() { if (g == ps.gen) audioDrop(); }
        };
        try {
            Engine e;
            try { e = media3() ? new Media3Engine(ps.act, ls) : new MediaPlayerEngine(ps.act, ls); ps.eng = e; prepareEngine(e, c, url); }
            catch (LinkageError le) { // Media3 classes missing in this build: fall back for good
                Log.w(TAG, "Media3 unavailable, using MediaPlayer: " + le);
                m3Broken = true;
                if (ps.eng != null) ps.eng.release(null);
                e = new MediaPlayerEngine(ps.act, ls); ps.eng = e; prepareEngine(e, c, url);
            }
            Log.i(TAG, "engine " + e.name());
        } catch (Exception e) {
            Log.w(TAG, "play failed", e); ps.fov.fail("error", e.getClass().getSimpleName());
        }
    }

    private void prepareEngine(Engine e, Channel c, String url) throws Exception {
        String q = "auto".equals(quality) && Ui.low() && !ps.store.qualityChosen() ? "auto720" : quality; // Low-tier default: 720p cap until the user picks a quality
        e.setEven(ps.store.evenVolume());
        e.setVolume(ps.previewCh == null ? ps.store.volume(c.url) : 1f);
        e.prepare(c, url, q, ps.useTex ? null : holder, ps.useTex ? ps.texSurface : null);
    }

    private void onPlaying() {
        final boolean wasReconnect = ps.reconnecting;
        ps.playing = true;
        ps.fails = 0;
        ps.h.removeCallbacks(ps.ui.connR);
        ps.connBox.setVisibility(View.GONE);
        ps.prober.setPaused(false);
        if (ps.playCh != null && ps.spike == 0) {
            ps.health.record(ps.playCh.url, true);
            ps.health.flushAsync();
            if (ps.idx != null) ps.idx.markDirty();
        }
        if (!ps.reconnecting) ps.ui.hidePill();
        if (ps.rc > 0) Log.i(NET, "reconnected after " + ps.rc + " tries");
        if (ps.reconnecting) { ps.reconnecting = false; ps.rc = 0; ps.ui.hidePill(); }
        ps.h.removeCallbacks(ps.fov.watchdog);
        final int g = ps.gen;
        if (ps.simDrop && !simDropDone && ps.spike == 0 && ps.previewCh == null) {
            simDropDone = true;
            ps.h.postDelayed(new Runnable() { @Override public void run() { if (g == ps.gen) ps.fov.fail("error", "simdrop"); } }, 20000);
        }
        if (ps.simStall && !simStallDone && ps.spike == 0 && ps.previewCh == null) {
            simStallDone = true;
            ps.h.postDelayed(new Runnable() {
                @Override public void run() { if (g == ps.gen) { Log.i(NET, "simstall: fake buffering 5 s"); onBufferStart(); } }
            }, 8000);
            ps.h.postDelayed(new Runnable() { @Override public void run() { if (g == ps.gen) onBufferEnd(); } }, 13000);
        }
        Log.i(TAG, "playing after " + (SystemClock.elapsedRealtime() - ps.t0) + " ms");
        if (ps.spike > 0) { spikeRecord("ok", ""); return; }
        if (ps.previewCh == null) {
            ps.prefs.edit().putString("last", ps.list.get(ps.index).url).apply();
            ps.store.pushRecent(ps.list.get(ps.index).url);
            if (!wasReconnect && ps.playCh != null) { ps.store.recordPlay(ps.playCh.url); if (ps.idx != null) ps.idx.markDirty(); }
            ps.wmArm = true;
        }
        if (ps.eng != null && ps.eng.smart()) ps.tsh.tsUpdate();
        ps.subsCtl.scanTracks();
        ps.h.removeCallbacks(ps.subsCtl.rescanR);
        ps.h.postDelayed(ps.subsCtl.rescanR, 2500);
        ps.ui.setState("");
        if (!hintShown && ps.previewCh == null && ps.store.recents().size() > 1) { hintShown = true; ps.bHint.setText("0 = previous channel"); ps.bHint.setVisibility(View.VISIBLE); }
        ps.ui.showBanner();
        ps.ui.wmUpdate();
    }

    private void onBufferStart() {
        if (!ps.playing) return; // start-up buffering is covered by "Tuning in…"
        ps.buffering = true;
        ps.prober.setPaused(true);
        recordStall();
        ps.h.removeCallbacks(showBufR);
        ps.h.removeCallbacks(longBufR);
        ps.h.postDelayed(showBufR, 400);
        ps.h.postDelayed(longBufR, 10000);
    }

    private void onBufferEnd() {
        ps.buffering = false;
        ps.prober.setPaused(false);
        ps.h.removeCallbacks(showBufR);
        ps.h.removeCallbacks(longBufR);
        if (!ps.reconnecting) ps.ui.hidePill();
    }

    /** 3 stalls in 60 s on auto: one variant down. */
    private void recordStall() {
        long now = SystemClock.elapsedRealtime();
        ps.stallsTotal++;
        Log.i(NET, "stall " + ps.stallsTotal);
        lastNetEvent = now;
        stallTimes.add(now);
        while (!stallTimes.isEmpty() && now - stallTimes.get(0) > 60000) stallTimes.remove(0);
        if (stallTimes.size() >= 3 && ps.previewCh == null && variants != null && variants.size() > 1 && quality.equals("auto")) {
            stallTimes.clear();
            int to = step(ps.curVar == -1 ? variants.size() - 1 : ps.curVar, -1);
            if (to >= 0) switchVariant(to, "Lower quality — slow connection");
        }
    }

    /** Nearest variant in direction d (+1/-1) with a different bandwidth than variants[from]; -1 if none. */
    private int step(int from, int d) {
        int bw = variants.get(from).bandwidth;
        for (int i = from + d; i >= 0 && i < variants.size(); i += d) if (variants.get(i).bandwidth != bw) return i;
        return -1;
    }

    private void switchVariant(int to, String msg) {
        ps.curVar = to;
        Hls.Variant v = to < 0 ? null : variants.get(to);
        lastNetEvent = SystemClock.elapsedRealtime();
        Log.i(NET, "variant -> " + (v == null ? "master" : v.label() + " " + Redact.host(v.url)));
        ps.ui.showToast(msg, 3500);
        ps.reconnecting = true; // a failed start of the new variant retries instead of skipping the channel
        play(ps.playCh != null ? ps.playCh : ps.zapper.cur(), v == null ? null : v.url, true);
    }

    private void tick() {
        if (ps.eng != null) ps.eng.setEven(ps.store.evenVolume()); // Settings toggle applies within a second
        long now = SystemClock.elapsedRealtime(), rx = TrafficStats.getUidRxBytes(Process.myUid());
        if (rx != TrafficStats.UNSUPPORTED && ps.lastRxT > 0 && now > ps.lastRxT) {
            double inst = (rx - lastRx) * 8.0 / ((now - ps.lastRxT) / 1000.0) / 1e6;
            mbps = mbps == 0 ? inst : mbps * 0.7 + inst * 0.3;
            if (ps.playing) { ps.mbpsSum += mbps; ps.statSamples++; }
        }
        lastRx = rx; ps.lastRxT = now;
        if (ps.bSpeed != null && ps.banner.getVisibility() == View.VISIBLE) ps.bSpeed.setText(speedText());
        if (ps.playing && ps.previewCh == null && variants != null && ps.curVar != -1 && quality.equals("auto") && now - lastNetEvent >= 180000) {
            int to = step(ps.curVar, 1);
            switchVariant(to, "Trying higher quality"); // no higher variant left = back to the master
        }
        if (now - ps.lastSummary >= 300000) {
            ps.lastSummary = now;
            double mins = Math.max(1, (now - ps.sessionT0) / 60000.0);
            Log.i(NET, String.format(Locale.US, "stalls/min=%.2f reconnects=%d avgMbps=%.1f", ps.stallsTotal / mins, ps.reconnectsTotal, ps.statSamples == 0 ? 0 : ps.mbpsSum / ps.statSamples));
        }
    }

    /** e.g. "4.2 Mbps ●●●" (good >= 3, fair 1-3, poor < 1). Also meant for the guide preview card. */
    public String speedText() {
        if (mbps <= 0) return "";
        return String.format(Locale.US, "%.1f Mbps %s", mbps, mbps >= 3 ? "●●●" : mbps >= 1 ? "●●○" : "●○○");
    }

    void releasePlayer() {
        Engine old = ps.eng;
        ps.eng = null;
        if (old != null) old.release(null);
    }

    void stopAll() {
        ps.h.removeCallbacks(startR);
        ps.h.removeCallbacks(ps.fov.skipR);
        ps.h.removeCallbacks(ps.fov.altR);
        ps.h.removeCallbacks(ps.fov.watchdog);
        ps.h.removeCallbacks(spikeNextR);
        ps.h.removeCallbacks(ps.fov.reconnectR);
        ps.h.removeCallbacks(showBufR);
        ps.h.removeCallbacks(longBufR);
        ps.h.removeCallbacks(ps.ui.connR);
        ps.gen++;
        releasePlayer();
        ps.tsh.tsReset();
        ps.playing = false;
        ps.reconnecting = false;
        ps.buffering = false;
        ps.ui.hidePill();
    }

    /** More than 3 underruns in 30 s: first time step the video down quietly (auto quality); if it persists, say so. */
    private void audioDrop() {
        if (!ps.playing || ps.previewCh != null || !aGuard.hit(SystemClock.elapsedRealtime())) return;
        audioTrips++;
        boolean lowered = audioTrips == 1 && "auto".equals(quality) && ps.eng != null && ps.eng.lowerQuality();
        Log.i(NET, "audio drops: trip " + audioTrips + (lowered ? ", video stepped down" : ""));
        if (audioTrips >= 2 && (ps.ui == null || !ps.ui.isGuideOpen())) ps.ui.showToast("Audio unstable on this channel", 4000);
    }

    void triggerAudioTrip() {
        audioTrips = 2;
        boolean guideOpen = ps.ui != null && ps.ui.isGuideOpen();
        Log.i("IPTV", "triggerAudioTrip: guideOpen=" + guideOpen);
        if (audioTrips >= 2 && !guideOpen) {
            ps.ui.showToast("Audio unstable on this channel", 4000);
        }
    }

    @Override public void surfaceCreated(SurfaceHolder sh) {
        holder = sh;
        surfaceReady = true;
        tryPending();
        ensurePlaying();
    }

    @Override public void surfaceChanged(SurfaceHolder sh, int f, int w, int hh) { }

    @Override public void surfaceDestroyed(SurfaceHolder sh) {
        surfaceReady = false;
        if (!ps.useTex) stopAll(); // in mirror mode the hidden SurfaceView going away is expected
    }

    void startSpike() {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < ps.all.size(); i++) ids.add(i);
        Collections.shuffle(ids, new Random(42));
        ps.spikeIdx = ids.subList(0, Math.min(ps.spike, ids.size()));
        spikeFile = new File(ps.act.getExternalFilesDir(null), "spike.csv");
        spikeFile.delete();
        ps.list = ps.all;
        ps.message.setVisibility(View.VISIBLE);
        ensurePlaying();
    }

    private void spikeNext() {
        if (spikeI >= ps.spikeIdx.size()) { ps.act.finish(); return; }
        ps.index = ps.spikeIdx.get(spikeI);
        ps.message.setText("Spike " + (spikeI + 1) + "/" + ps.spikeIdx.size() + "\n" + ps.all.get(ps.index).name);
        play(ps.all.get(ps.index));
    }

    private static String q(String s) { return '"' + s.replace("\"", "\"\"") + '"'; }

    void spikeRecord(String result, String detail) {
        if (curDone) return;
        curDone = true;
        Channel c = ps.all.get(ps.index);
        long ms = SystemClock.elapsedRealtime() - ps.t0;
        try (FileWriter w = new FileWriter(spikeFile, true)) {
            w.write(ps.index + "," + q(c.name) + "," + q(c.url) + "," + result + "," + ms + "," + q(detail)
                    + "," + q(c.userAgent) + "," + q(c.referrer) + "\n");
        } catch (Exception e) {
            Log.w(TAG, "spike write failed: " + e);
        }
        Log.i(TAG, "spike " + (spikeI + 1) + " " + result + " " + detail);
        spikeI++;
        ps.h.removeCallbacks(ps.fov.watchdog);
        ps.gen++;
        releasePlayer();
        ps.h.postDelayed(spikeNextR, 500);
    }
}
