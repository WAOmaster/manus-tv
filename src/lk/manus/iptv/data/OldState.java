package lk.manus.iptv.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The user state exactly as the old SharedPreferences strings hold it, parsed (pure Java, no SQLite) so the migration mapping can be tested.
 * Parsing follows the rules Store used: newline-joined lines, empty lines skipped, plays = url TAB count TAB last-played (base 36 seconds),
 * subs/audio/vols = url TAB value (first line for a url wins), vers = key TAB url (last wins). Choices keyed by url are re-keyed by {@link Keys#of}.
 */
public final class OldState {
    /** Order of {@link #counts()} and of the raw strings given to {@link #parse} / {@link #backupText}. */
    public static final String[] NAMES = {"favs", "recents", "plays", "subs", "audio", "vols", "vers"};

    public final List<String> favs = new ArrayList<>(), recents = new ArrayList<>();
    public final Map<String, long[]> plays = new LinkedHashMap<>();                       // url -> {count, lastPlayedMs}
    public final Map<String, String> subs = new LinkedHashMap<>(), audio = new LinkedHashMap<>(), vols = new LinkedHashMap<>(); // Keys.of(url) -> value
    public final Map<String, String> vers = new LinkedHashMap<>();                        // Dedup choice key -> stream url
    /** Lines dropped while parsing (malformed or duplicate): reported, never silent. */
    public int dropped;

    private OldState() {}

    /** raw order = {@link #NAMES}; null counts as empty. */
    public static OldState parse(String... raw) {
        OldState o = new OldState();
        String[] r = new String[NAMES.length];
        for (int i = 0; i < r.length; i++) r[i] = i < raw.length && raw[i] != null ? raw[i] : "";
        o.list(r[0], o.favs);
        o.list(r[1], o.recents);
        for (String line : lines(r[2])) {
            String[] f = line.split("\t");
            if (f.length < 3) { o.dropped++; continue; }
            try { o.plays.put(f[0], new long[]{Long.parseLong(f[1]), Long.parseLong(f[2], 36) * 1000}); } catch (NumberFormatException e) { o.dropped++; }
        }
        o.kv(r[3], o.subs, true, true);
        o.kv(r[4], o.audio, true, true);
        o.kv(r[5], o.vols, true, true);
        o.kv(r[6], o.vers, false, false);
        return o;
    }

    private static List<String> lines(String s) {
        List<String> l = new ArrayList<>();
        if (!s.isEmpty()) for (String u : s.split("\n")) if (!u.isEmpty()) l.add(u);
        return l;
    }

    private void list(String s, List<String> out) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String u : lines(s)) if (!seen.add(u)) dropped++;
        out.addAll(seen);
    }

    private void kv(String s, Map<String, String> out, boolean hashKey, boolean firstWins) {
        for (String line : lines(s)) {
            int t = line.indexOf('\t');
            if (t <= 0) { dropped++; continue; }
            String k = line.substring(0, t), v = line.substring(t + 1);
            if (hashKey) k = Keys.of(k);
            if (firstWins && out.containsKey(k)) { dropped++; continue; }
            out.put(k, v);
        }
    }

    /** Entry counts in {@link #NAMES} order: what the database must hold after the import. */
    public int[] counts() { return new int[]{favs.size(), recents.size(), plays.size(), subs.size(), audio.size(), vols.size(), vers.size()}; }

    /** The backup file body: every raw old string, verbatim, under a "[name]" line (no stream url is ever shortened or masked - it is a backup). */
    public static String backupText(String stamp, String... raw) {
        StringBuilder b = new StringBuilder("# manus-iptv user state before the database move, ").append(stamp).append('\n');
        for (int i = 0; i < NAMES.length; i++) b.append('[').append(NAMES[i]).append("]\n").append(i < raw.length && raw[i] != null ? raw[i] : "").append('\n');
        return b.toString();
    }
}
