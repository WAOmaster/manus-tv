package lk.manus.iptv.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * M3: search filters chosen with the remote: type (live / films / series), country, category, picture quality and
 * favourites only. With no words typed, an active filter browses the whole channel list (no keyboard needed).
 * Pure Java, tested in SearchFilterTest. Category labels are Index.groupLabel() labels ("Movies", "TV Series", "News").
 */
public final class SearchFilter {
    public static final int TYPE_ALL = 0, TYPE_LIVE = 1, TYPE_FILMS = 2, TYPE_SERIES = 3;
    static final String[] TYPES = {"All", "Live TV", "Films", "Series"};
    public static final int Q_ANY = 0, Q_HD = 1, Q_FHD = 2, Q_4K = 3;
    static final String[] QUALITIES = {"Any", "HD", "Full HD", "4K"};
    static final int[] MIN_LINES = {0, 720, 1080, 2160};
    public static final String FILMS_CAT = "Movies", SERIES_CAT = "TV Series";

    public int type = TYPE_ALL, quality = Q_ANY;
    public String country = "", category = ""; // "" = any
    public boolean favOnly;

    public boolean active() { return type != TYPE_ALL || quality != Q_ANY || !country.isEmpty() || !category.isEmpty() || favOnly; }
    public void clear() { type = TYPE_ALL; quality = Q_ANY; country = ""; category = ""; favOnly = false; }
    public SearchFilter copy() { SearchFilter f = new SearchFilter(); f.type = type; f.quality = quality; f.country = country; f.category = category; f.favOnly = favOnly; return f; }

    public static String typeLabel(int t) { return TYPES[t < 0 || t >= TYPES.length ? 0 : t]; }
    public static String qualityLabel(int q) { return QUALITIES[q < 0 || q >= QUALITIES.length ? 0 : q]; }
    public static int nextType(int t) { return (t + 1) % TYPES.length; }
    public static int nextQuality(int q) { return (q + 1) % QUALITIES.length; }

    /** Picture height a channel promises: its quality tag (720p, 1080i, 4K), else HD / FHD / UHD / 4K words in its name; 0 = unknown. */
    public static int lines(Channel c) {
        String q = c.quality == null ? "" : c.quality.trim().toLowerCase(Locale.ROOT);
        if (q.equals("4k") || q.equals("uhd")) return 2160;
        if (q.matches("\\d{3,4}[pi]")) { try { return Integer.parseInt(q.substring(0, q.length() - 1)); } catch (NumberFormatException ignored) { } }
        for (String w : Search.fold(c.name).split("[^a-z0-9]+")) {
            if (w.equals("4k") || w.equals("uhd")) return 2160;
            if (w.equals("fhd")) return 1080;
            if (w.equals("hd")) return 720;
        }
        return 0;
    }

    /** True when {@code c} is in category {@code label}. */
    public static boolean inCategory(Channel c, String label) {
        for (String g : c.groups) if (Index.groupLabel(g).equals(label)) return true;
        return false;
    }

    /** Whether one channel passes every filter. */
    public boolean matches(Channel c, Set<String> favs) {
        if (type == TYPE_FILMS && !inCategory(c, FILMS_CAT)) return false;
        if (type == TYPE_SERIES && !inCategory(c, SERIES_CAT)) return false;
        if (!country.isEmpty() && !country.equals(c.country)) return false;
        if (!category.isEmpty() && !inCategory(c, category)) return false;
        if (quality != Q_ANY && lines(c) < MIN_LINES[quality]) return false;
        if (favOnly && (favs == null || !favs.contains(c.url))) return false;
        return true;
    }

    /** {@code l} narrowed by every filter, in its order, at most {@code max} rows. */
    public List<Channel> apply(List<Channel> l, Set<String> favs, int max) {
        if (!active()) return l.size() <= max ? l : new ArrayList<>(l.subList(0, max));
        List<Channel> out = new ArrayList<>();
        for (Channel c : l) if (matches(c, favs)) { out.add(c); if (out.size() >= max) break; }
        return out;
    }

    /**
     * Values worth offering for a picker, most channels first, counted over {@code l} with every OTHER filter applied
     * (so a country list never offers a dead end). key = country code or category label, value = channel count.
     */
    public Map<String, Integer> options(List<Channel> l, Set<String> favs, boolean countries) {
        SearchFilter other = copy();
        if (countries) other.country = ""; else other.category = "";
        final Map<String, Integer> n = new LinkedHashMap<>();
        for (Channel c : l) {
            if (!other.matches(c, favs)) continue;
            if (countries) { if (!c.country.isEmpty()) n.merge(c.country, 1, Integer::sum); }
            else {
                java.util.HashSet<String> seen = new java.util.HashSet<>();
                for (String g : c.groups) { String lb = Index.groupLabel(g); if (seen.add(lb)) n.merge(lb, 1, Integer::sum); }
                if (c.groups.length == 0) n.merge("Other", 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> es = new ArrayList<>(n.entrySet());
        Collections.sort(es, new java.util.Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int c = Integer.compare(b.getValue(), a.getValue());
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            }
        });
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : es) out.put(e.getKey(), e.getValue());
        return out;
    }
}
