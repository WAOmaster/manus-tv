package lk.manus.iptv;

import android.os.SystemClock;

/** Sleep timer: one Handler timer. 60 s before the end a pill asks "OK to keep watching"; at zero playback stops and the app closes (finish()) to the Home launcher. Lives while the activity lives. */
final class SleepCtl {
    private static final int LEAD_MS = 60000;
    static final String WARN = "Turning off in 1 minute. OK to keep watching.";
    private final PlayState ps;
    private int minutes;       // 0 = off (debug timers show as 1)
    private long warnUntil;    // uptime ms the warning pill is meant to show until

    SleepCtl(PlayState ps) { this.ps = ps; }

    private final Runnable warnR = new Runnable() { @Override public void run() {
        warnUntil = SystemClock.uptimeMillis() + LEAD_MS;
        ps.ui.showToastNow(WARN, LEAD_MS);
    } };
    private final Runnable endR = new Runnable() { @Override public void run() { end(); } };

    /** "Sleep timer: 60 min" / "Sleep timer: off". */
    String label() { return "Sleep timer: " + (minutes > 0 ? minutes + " min" : "off"); }
    int minutes() { return minutes; }

    void set(int min) { arm(min, min * 60000L); }

    /** DEBUG hook: stop after sec seconds (the warning still comes 60 s before, or at once when sec <= 60). */
    void debugSet(int sec) { arm(1, sec * 1000L); }

    private void arm(int min, long ms) {
        cancel();
        if (min <= 0) return;
        minutes = min;
        ps.h.postDelayed(warnR, Math.max(0, ms - LEAD_MS));
        ps.h.postDelayed(endR, ms);
    }

    void cancel() {
        ps.h.removeCallbacks(warnR);
        ps.h.removeCallbacks(endR);
        boolean was = warning();
        warnUntil = 0;
        minutes = 0;
        if (was) ps.ui.clearToast();
    }

    /** True while the warning pill is on screen. */
    boolean warning() { return warnUntil > SystemClock.uptimeMillis() && ps.ui.toastShows(WARN); }

    private void end() {
        warnUntil = 0;
        minutes = 0;
        if (ps.paused) return; // the app is already in the background: nothing playing to stop
        if (ps.filmPlayer != null && ps.filmPlayer.isActive()) ps.filmPlayer.stop(false); // saves the film position
        ps.host.stopAll();
        ps.act.finish(); // leaves the app for the Home launcher
    }
}
