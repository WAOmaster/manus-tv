package lk.manus.iptv;

import android.os.SystemClock;

import java.util.List;

import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Keys;
import lk.manus.iptv.data.Reminders;

/** Film reminders on screen: one Handler timer to the next due reminder (foreground only), one pill at a time (20 s), OK on the pill tunes the channel. No alarms, no notifications, no services. */
final class RemindCtl {
    private static final int PILL_MS = 20000;
    private final PlayState ps;
    private String offerText;      // the pill that is on screen for a reminder (null = none)
    private Channel offerCh;
    private long offerUntil;       // uptime ms

    RemindCtl(PlayState ps) { this.ps = ps; }

    private final Runnable tickR = new Runnable() { @Override public void run() { fire(); } };

    /** (Re)aims the single timer at the next due reminder. Call on resume and whenever reminders change. */
    void arm() {
        ps.h.removeCallbacks(tickR);
        if (ps.paused) return;
        long d = Reminders.nextDelay(Reminders.list(), System.currentTimeMillis());
        if (d >= 0) ps.h.postDelayed(tickR, d);
    }

    void stop() { ps.h.removeCallbacks(tickR); offerText = null; offerCh = null; }

    private void fire() {
        long now = System.currentTimeMillis();
        if (Config.DEBUG) android.util.Log.i("IPTV", "reminder tick: " + Reminders.list().size() + " stored");
        if (ps.idx == null || ps.list == null) { ps.h.postDelayed(tickR, 5000); return; } // channels not loaded yet
        long left = offerUntil - SystemClock.uptimeMillis();
        if (offerText != null && left > 0 && ps.ui.toastShows(offerText)) { ps.h.postDelayed(tickR, left + 200); return; } // one pill at a time
        offerText = null; offerCh = null;
        List<Reminders.Item> l = Reminders.list();
        for (Reminders.Item r : l) {
            if (Reminders.expired(r.startSec, now)) { Reminders.remove(r.key, r.startSec); ps.guide.onRemindersChanged(); continue; } // too late: dropped silently
            if (!Reminders.due(r.startSec, now)) continue;
            Reminders.remove(r.key, r.startSec); // shown (or pointless) once
            ps.guide.onRemindersChanged();
            Channel ch = find(r.key);
            if (ch == null || Keys.of(ps.zapper.cur().url).equals(r.key)) continue; // gone, or already watching it: no pill
            offerCh = ch;
            offerText = r.title + " is starting on " + ch.name + ". OK to watch.";
            offerUntil = SystemClock.uptimeMillis() + PILL_MS;
            ps.ui.showToastNow(offerText, PILL_MS);
            if (Config.DEBUG) android.util.Log.i("IPTV", "reminder pill shown");
            break;
        }
        arm();
    }

    private Channel find(String key) {
        for (Channel c : ps.idx.all) if (Keys.of(c.url).equals(key)) return c;
        return null;
    }

    /** True while the reminder pill is on screen. */
    boolean offering() { return offerCh != null && offerText != null && SystemClock.uptimeMillis() < offerUntil && ps.ui.toastShows(offerText); }

    /** OK while the pill shows: tune that channel and drop the pill. */
    void accept() {
        Channel ch = offerCh;
        offerText = null; offerCh = null;
        ps.ui.clearToast();
        if (ch == null || ps.idx == null) return;
        List<Channel> src = ps.idx.list("cat:Movies", ps.store);
        int pos = src.indexOf(ch);
        String id = "cat:Movies", name = "Movies";
        if (pos < 0) { src = ps.idx.list("all", ps.store); pos = src.indexOf(ch); id = "all"; name = ps.idx.title("all"); }
        if (pos < 0) return;
        ps.zapper.commit(id, name, src, pos);
        if (ps.guide.isOpen()) ps.guide.hide();
    }

    /** DEBUG hook: a reminder for the first other movie channel, due in sec seconds. */
    void debugAdd(int sec, String title) {
        if (ps.idx == null) return;
        String cur = ps.zapper.cur().url;
        for (Channel c : ps.idx.list("cat:Movies", ps.store)) if (!c.url.equals(cur)) {
            Reminders.add(new Reminders.Item(Keys.of(c.url), title, c.name, System.currentTimeMillis() / 1000 + sec));
            arm();
            return;
        }
    }
}
