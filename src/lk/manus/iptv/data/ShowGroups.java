package lk.manus.iptv.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * T110e: one tile per show or film. Channels airing the same thing are one group: same TMDB id, or (when not matched) the same cleaned title
 * (case, accents and punctuation ignored); an unmatched title also joins a matched group that carries that title. The representative is the
 * best channel: working over untested over dead, then higher quality, then watched most recently, then the earlier one in the input.
 * Groups keep the order of their first member, so a stable sort afterwards stays stable. No Android here.
 */
public final class ShowGroups {
    private ShowGroups() {}

    /** One channel airing the thing. ref is the caller's own object (returned as is). */
    public static final class Rec {
        public final Object ref;
        public final int tmdbId;       // 0 = not matched
        final String title;            // folded key
        public final boolean dead, working;
        public final int quality;      // see qualityOf
        public final long watched;     // last watched at (or a rank: larger = more recent), 0 = never
        public Rec(Object ref, int tmdbId, String title, boolean dead, boolean working, int quality, long watched) {
            this.ref = ref; this.tmdbId = tmdbId; this.title = titleKey(title); this.dead = dead; this.working = working; this.quality = quality; this.watched = watched;
        }
        int health() { return dead ? 0 : working ? 2 : 1; }
    }

    public static final class Group {
        public final Rec rep;
        public final List<Rec> others;  // best first
        Group(Rec r, List<Rec> o) { rep = r; others = o; }
        public int size() { return 1 + others.size(); }
    }

    /** Lower case, accents and punctuation gone, spaces collapsed. */
    public static String titleKey(String s) {
        if (s == null) return "";
        return Search.fold(s).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** "1080p" 1080, "4K" / "UHD" 2160, "HD" 720, "SD" 480, anything else 0. */
    public static int qualityOf(String q) {
        if (q == null) return 0;
        String s = q.trim().toLowerCase(Locale.ROOT);
        if (s.contains("4k") || s.contains("uhd")) return 2160;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3,4})\\s*[pi]?").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        if (s.equals("hd")) return 720;
        if (s.equals("sd")) return 480;
        return 0;
    }

    private static final Comparator<Rec> BEST = new Comparator<Rec>() {
        @Override public int compare(Rec a, Rec b) {
            if (a.health() != b.health()) return b.health() - a.health();
            if (a.quality != b.quality) return b.quality - a.quality;
            return Long.compare(b.watched, a.watched);
        }
    };

    public static List<Group> group(List<Rec> in) {
        Map<String, String> byTitle = new HashMap<>(); // folded title -> the matched group that carries it
        for (Rec r : in) if (r.tmdbId > 0 && !r.title.isEmpty() && !byTitle.containsKey(r.title)) byTitle.put(r.title, "i" + r.tmdbId);
        Map<String, List<Rec>> by = new LinkedHashMap<>();
        int solo = 0;
        for (Rec r : in) {
            String k = r.tmdbId > 0 ? "i" + r.tmdbId : r.title.isEmpty() ? "s" + solo++ : byTitle.containsKey(r.title) ? byTitle.get(r.title) : "n" + r.title;
            List<Rec> l = by.get(k);
            if (l == null) by.put(k, l = new ArrayList<>());
            l.add(r);
        }
        List<Group> out = new ArrayList<>();
        for (List<Rec> l : by.values()) {
            List<Rec> s = new ArrayList<>(l);
            Collections.sort(s, BEST); // stable: ties keep the input order
            out.add(new Group(s.get(0), new ArrayList<>(s.subList(1, s.size()))));
        }
        return out;
    }
}
