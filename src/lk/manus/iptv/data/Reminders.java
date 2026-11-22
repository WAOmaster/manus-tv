package lk.manus.iptv.data;

import java.util.ArrayList;
import java.util.List;

/** "Remind me" for films that have not started: the due / late / next-timer arithmetic (pure) and the stored list (catalogue.db table "reminder"). Times: start in epoch seconds. */
public final class Reminders {
    private Reminders() {}

    /** A pill shows when the start is this close (or past, up to LATE_MS). */
    public static final long LEAD_MS = 30000, LATE_MS = 600000;

    public static final class Item {
        public final String key, title, channel; // key = Keys.of(channel url)
        public final long startSec;
        public Item(String key, String title, String channel, long startSec) { this.key = key; this.title = title; this.channel = channel; this.startSec = startSec; }
    }

    /** Shows now: the start is 30 s away or already passed by less than 10 minutes. */
    public static boolean due(long startSec, long nowMs) { return startSec * 1000 - nowMs <= LEAD_MS && nowMs - startSec * 1000 < LATE_MS; }

    /** More than 10 minutes late: dropped without showing. */
    public static boolean expired(long startSec, long nowMs) { return nowMs - startSec * 1000 >= LATE_MS; }

    /** Milliseconds until the next reminder becomes due (0 = one is due now); -1 = nothing to wait for. Expired ones do not count. */
    public static long nextDelay(List<Item> l, long nowMs) {
        long best = -1;
        for (Item i : l) {
            if (expired(i.startSec, nowMs)) continue;
            long d = Math.max(0, i.startSec * 1000 - LEAD_MS - nowMs);
            if (best < 0 || d < best) best = d;
        }
        return best;
    }

    // ---- stored list ----

    /** Soonest first (a copy). Empty when the database is unavailable. */
    public static List<Item> list() { Db d = Db.get(); return d == null ? new ArrayList<Item>() : d.reminders(); }

    public static boolean has(String key, long startSec) {
        for (Item i : list()) if (i.startSec == startSec && i.key.equals(key)) return true;
        return false;
    }

    public static void add(Item i) { Db d = Db.get(); if (d != null) d.reminderAdd(i); }

    public static void remove(String key, long startSec) { Db d = Db.get(); if (d != null) d.reminderRemove(key, startSec); }
}
