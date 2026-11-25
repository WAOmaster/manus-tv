package lk.manus.iptv.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Finding things in a channel list with a TV remote (M2): sort modes, a name filter, the phone-keypad letter jump
 * (A-Z lists) and the row-number jump (other lists). Pure Java, no Android types: tested in ListToolsTest.
 */
public final class ListTools {
    public static final int SORT_NUM = 0, SORT_AZ = 1, SORT_WATCHED = 2;
    private static final String[] LABELS = {"Number", "A–Z", "Most watched"};
    /** Phone keypad: index = key. 1 = names that start with a digit or symbol; 0 = back to the top. */
    static final String[] KEYPAD = {"", "#", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};

    private ListTools() {}

    public static String label(int mode) { return mode >= 0 && mode < LABELS.length ? LABELS[mode] : LABELS[0]; }
    public static int next(int mode) { return (Math.max(0, mode) + 1) % LABELS.length; }
    public static int parse(String s) { for (int i = 0; i < LABELS.length; i++) if (String.valueOf(i).equals(s)) return i; return SORT_NUM; }

    /** Name key for A-Z: folded (lowercase, no accents) with leading symbols dropped ("+1 TV" -> "1 tv", "'Ayu" -> "ayu"). */
    public static String key(String name) {
        String f = Search.fold(name == null ? "" : name);
        int i = 0;
        while (i < f.length() && !Character.isLetterOrDigit(f.charAt(i))) i++;
        return f.substring(i);
    }

    /** The keypad group a name falls in: 'a'..'z', or '#' for anything not starting with a latin letter. */
    public static char initial(String name) {
        String k = key(name);
        char c = k.isEmpty() ? '#' : k.charAt(0);
        return c >= 'a' && c <= 'z' ? c : '#';
    }

    /** A new list in {@code mode} order; ties (and SORT_NUM) keep the order of {@code base}. Never changes base. */
    public static List<Channel> sort(List<Channel> base, int mode, final Ranker.Signals sig) {
        List<Channel> out = new ArrayList<>(base);
        if (mode == SORT_AZ) {
            final java.util.Map<Channel, String> keys = new java.util.IdentityHashMap<>();
            for (Channel c : out) keys.put(c, key(c.name));
            Collections.sort(out, new Comparator<Channel>() {
                @Override public int compare(Channel a, Channel b) {
                    String ka = keys.get(a), kb = keys.get(b);
                    boolean la = !ka.isEmpty() && ka.charAt(0) >= 'a' && ka.charAt(0) <= 'z', lb = !kb.isEmpty() && kb.charAt(0) >= 'a' && kb.charAt(0) <= 'z';
                    if (la != lb) return la ? 1 : -1; // '#' group (digits, other scripts) first, as key 1 is first on the keypad
                    return ka.compareTo(kb);
                }
            });
        } else if (mode == SORT_WATCHED && sig != null) {
            Collections.sort(out, new Comparator<Channel>() {
                @Override public int compare(Channel a, Channel b) {
                    int c = Integer.compare(sig.playCount(b.url), sig.playCount(a.url));
                    if (c != 0) return c;
                    c = Long.compare(sig.lastPlayedMs(b.url), sig.lastPlayedMs(a.url));
                    if (c != 0) return c;
                    return Integer.compare(sig.popularity(b.url), sig.popularity(a.url));
                }
            });
        }
        return out;
    }

    /** Channels whose name holds every word of {@code q} (case and accent insensitive), in list order. Empty q = the whole list. */
    public static List<Channel> filter(List<Channel> l, String q) {
        String f = Search.fold(q == null ? "" : q).trim();
        if (f.isEmpty()) return l;
        String[] toks = f.split("\\s+");
        List<Channel> out = new ArrayList<>();
        for (Channel c : l) {
            String n = Search.fold(c.name);
            boolean ok = true;
            for (String t : toks) if (!n.contains(t)) { ok = false; break; }
            if (ok) out.add(c);
        }
        return out;
    }

    /** Result of a keypad press: the row to jump to and what to show ("B"), or row -1 with nothing to show. */
    public static final class Jump {
        public final int row; public final String label;
        Jump(int r, String l) { row = r; label = l; }
    }

    /**
     * Phone-keypad letter jump in an A-Z list. {@code press} counts repeats of the same key (0 = first press): each repeat
     * moves to the key's next letter that has channels, wrapping. Key 0 goes to the top; key 1 to names starting with a digit or symbol.
     * A null name is a row that cannot be landed on (a divider or header) and is skipped.
     */
    public static Jump letter(List<String> names, int key, int press) {
        if (key == 0) return names.isEmpty() ? new Jump(-1, "") : new Jump(0, "Top");
        if (key < 1 || key > 9) return new Jump(-1, "");
        String letters = KEYPAD[key];
        List<Character> have = new ArrayList<>();
        List<Integer> first = new ArrayList<>();
        for (int i = 0; i < letters.length(); i++) {
            char want = letters.charAt(i);
            for (int r = 0; r < names.size(); r++) if (names.get(r) != null && initial(names.get(r)) == want) { have.add(want); first.add(r); break; }
        }
        if (have.isEmpty()) return new Jump(-1, letters.equals("#") ? "0–9" : letters.toUpperCase(java.util.Locale.ROOT));
        int j = Math.floorMod(press, have.size());
        char c = have.get(j);
        return new Jump(first.get(j), c == '#' ? "0–9" : String.valueOf(Character.toUpperCase(c)));
    }

    /** Row for a typed number (1-based as shown in the list), or -1 when there is no such row. */
    public static int row(String digits, int size) {
        if (digits == null || digits.isEmpty() || digits.length() > 6) return -1;
        int n;
        try { n = Integer.parseInt(digits); } catch (NumberFormatException e) { return -1; }
        return n >= 1 && n <= size ? n - 1 : -1;
    }
}
