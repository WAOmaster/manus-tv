package lk.manus.iptv;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import java.util.Locale;
import lk.manus.iptv.ui.Ui;
import lk.manus.iptv.player.Engine;

/** Pause / rewind live TV and the remote Pause / Play handling. */
final class Timeshift {
    final PlayState ps;

    Timeshift(PlayState ps) { this.ps = ps; }

    private static final String TAG = "IPTV";

    private long tsHideAt;

    private final Runnable tsR = new Runnable() { @Override public void run() { tsUpdate(); } };

    private boolean tsOk() { return ps.eng != null && ps.eng.smart() && ps.playing && ps.previewCh == null && !ps.guide.isOpen(); }

    void tsReset() {
        ps.h.removeCallbacks(tsR);
        ps.tsPaused = false; tsHideAt = 0;
        ps.tsBox.setVisibility(View.GONE);
    }

    /** Bar visible = Left / Right rewind and fast-forward instead of opening the guide / options. */
    boolean tsBar() { return ps.tsBox.getVisibility() == View.VISIBLE && ps.tsBox.getChildAt(1).getVisibility() == View.VISIBLE; }

    private static String mmss(long ms) { long s = ms / 1000; return String.format(Locale.US, "%d:%02d", s / 60, s % 60); }

    /** Shows the full bar for ms (0 = until resumed). */
    private void tsShow(long ms) {
        tsHideAt = ms == 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() + ms;
        tsUpdate();
    }

    void tsUpdate() {
        ps.h.removeCallbacks(tsR);
        Engine e = ps.eng;
        if (!tsOk() || e == null) { ps.tsBox.setVisibility(View.GONE); return; }
        int was = ps.tsBox.getVisibility();
        long behind = e.behindMs(), win = e.windowMs(), now = SystemClock.elapsedRealtime();
        boolean full = ps.tsPaused || now < tsHideAt;
        if (full || behind >= 3000) {
            String t = behind >= 1500 ? "\u2212" + mmss(behind) + " behind live" + (full ? "   \u00b7   OK: Go live" : "") : "LIVE";
            ps.tsChip.setText((ps.tsPaused ? "\u275a\u275a  " : "") + t);
            ps.tsChip.setTextColor(Ui.PAPER);
            ps.tsTrack.setVisibility(full ? View.VISIBLE : View.GONE);
            if (full && win > 0) {
                int w = Math.round(Ui.u(360) * Math.min(1f, Math.max(0f, e.positionMs() / (float) win)));
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ps.tsFill.getLayoutParams();
                if (lp.width != w) { lp.width = w; ps.tsFill.setLayoutParams(lp); }
            }
            ps.tsBox.setVisibility(View.VISIBLE);
        } else ps.tsBox.setVisibility(View.GONE);
        if (was != ps.tsBox.getVisibility()) ps.ui.wmUpdate();
        ps.h.postDelayed(tsR, 500);
    }

    /** Left / Right / FF / Rew. Returns false when the engine can't (the caller falls back to its old meaning). */
    boolean seekRel(long ms) {
        if (!tsOk()) return false;
        long win = ps.eng.windowMs(), pos = ps.eng.positionMs();
        if (win <= 0) { ps.ui.showToast("Rewind isn't available on this channel", 2000); return true; }
        if (ms < 0 && pos < 10000) { // how much rewind this stream really allows
            if (pos < 1000) { ps.ui.showToast("Rewind isn't available on this channel", 2000); tsShow(2500); return true; }
            ps.ui.showToast("This channel allows " + pos / 1000 + " s rewind", 3000);
        }
        long before = ps.eng.behindMs();
        ps.eng.seekBy(ms);
        Log.i(TAG, "seek " + ms / 1000 + " s, behind live " + before + " -> " + ps.eng.behindMs() + " ms, window " + win + " ms, was at " + pos + " ms");
        tsShow(4000);
        return true;
    }

    void goLive() {
        if (!tsOk()) return;
        ps.eng.goLive();
        ps.tsPaused = false;
        Log.i(TAG, "go live");
        tsShow(2500);
    }

    /** Media3: a real pause (the buffer keeps filling, Play resumes at the same point). MediaPlayer: pause stops the video, Play starts the channel again. */
    void pauseVideo() {
        if (ps.userPaused || ps.tsPaused || ps.list == null) return;
        if (tsOk()) {
            ps.eng.pause();
            ps.tsPaused = true;
            Log.i(TAG, "paused (live window " + ps.eng.windowMs() + " ms, pos " + ps.eng.positionMs() + " ms)");
            tsShow(0);
            return;
        }
        ps.userPaused = true;
        ps.h.removeCallbacks(ps.host.startR); ps.h.removeCallbacks(ps.fov.skipR); ps.h.removeCallbacks(ps.fov.altR); ps.h.removeCallbacks(ps.fov.reconnectR); ps.h.removeCallbacks(ps.ui.connR);
        ps.fov.hideFail();
        ps.connBox.setVisibility(View.GONE);
        ps.host.stopAll();
        ps.gaveUp = false;
        ps.ui.setState("");
        ps.message.setText("Paused — press Play");
        ps.message.setBackgroundColor(Ui.alpha(Ui.INK, 0.6f));
        ps.message.setVisibility(View.VISIBLE);
        Log.i(TAG, "paused by remote");
        ps.ui.wmUpdate();
    }

    void resumeVideo() {
        if (ps.tsPaused && ps.eng != null) {
            ps.tsPaused = false;
            ps.eng.play();
            Log.i(TAG, "resumed, behind live " + ps.eng.behindMs() + " ms");
            tsShow(4000);
            return;
        }
        if (!ps.userPaused) return;
        clearPause();
        ps.fails = 0;
        ps.gaveUp = false;
        Log.i(TAG, "resumed by remote");
        ps.ui.updateBanner();
        ps.host.play(ps.zapper.cur());
    }

    void clearPause() {
        if (!ps.userPaused) return;
        ps.userPaused = false;
        ps.message.setBackgroundColor(0);
        ps.message.setVisibility(View.GONE);
    }
}
