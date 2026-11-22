package lk.manus.iptv.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** T95: Xtream catch-up, pure Java: archive window of a channel, the /timeshift/ address, which earlier programmes can be played. */
public final class Catchup {
    private Catchup() {}

    /** One programme of get_simple_data_table. start/stop = epoch seconds; startLocal = the provider's own "yyyy-MM-dd HH:mm:ss". */
    public static final class Prog {
        public final String title, startLocal;
        public final long start, stop;
        public final boolean hasArchive;
        public Prog(String title, String startLocal, long start, long stop, boolean hasArchive) {
            this.title = title != null ? title : ""; this.startLocal = startLocal != null ? startLocal : "";
            this.start = start; this.stop = stop; this.hasArchive = hasArchive;
        }
    }

    /** tv_archive (0/1) x tv_archive_duration (days) -> days of archive, 0 = none. */
    public static int archiveDays(int tvArchive, int durationDays) {
        return tvArchive == 1 && durationDays > 0 ? Math.min(durationDays, 60) : 0;
    }

    /** "2026-11-17 10:00:00" -> "2026-11-17:10-00" (the format of /timeshift/); null when it is not that shape. */
    public static String startStamp(String local) {
        if (local == null) return null;
        String s = local.trim();
        if (s.length() < 16 || s.charAt(4) != '-' || s.charAt(7) != '-' || s.charAt(10) != ' ' || s.charAt(13) != ':') return null;
        for (int i : new int[]{0, 1, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15}) if (!Character.isDigit(s.charAt(i))) return null;
        return s.substring(0, 10) + ":" + s.substring(11, 13) + "-" + s.substring(14, 16);
    }

    /** Whole minutes of the programme, rounded up, at least 1. */
    public static long minutes(long startSec, long stopSec) {
        return Math.max(1, (stopSec - startSec + 59) / 60);
    }

    /** Stream template; XtreamSource.joinUrl adds base, user and password at play time. Null when the programme has no usable start. */
    public static String template(Prog p, String streamId) {
        String stamp = startStamp(p.startLocal);
        if (stamp == null || streamId == null || streamId.isEmpty()) return null;
        return "{BASE}/timeshift/{USER}/{PASS}/" + minutes(p.start, p.stop) + "/" + stamp + "/" + streamId + ".ts";
    }

    /** Programmes that can be played now: archived, finished, inside the window; newest first. */
    public static List<Prog> window(List<Prog> all, long nowSec, int days) {
        List<Prog> out = new ArrayList<>();
        if (all == null || days <= 0) return out;
        long from = nowSec - days * 86400L;
        for (Prog p : all) if (p.hasArchive && p.stop <= nowSec && p.start >= from && p.stop > p.start) out.add(p);
        Collections.sort(out, new Comparator<Prog>() { @Override public int compare(Prog a, Prog b) { return Long.compare(b.start, a.start); } });
        return out;
    }
}
