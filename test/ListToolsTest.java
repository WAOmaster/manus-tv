import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.ListTools;
import lk.manus.iptv.data.Ranker;

/** M2: sort, filter, keypad letter jump, row-number jump. */
public class ListToolsTest {
    private static int checks, failures;

    private static void eq(String what, Object expected, Object actual) {
        checks++;
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            failures++;
            System.out.println("FAIL " + what + ": expected [" + expected + "] got [" + actual + "]");
        }
    }

    static Channel ch(String name) { return new Channel("", name, "", "", new String[0], "lk", "http://x/" + name.hashCode(), "", ""); }

    static String names(List<Channel> l) { StringBuilder b = new StringBuilder(); for (Channel c : l) b.append(b.length() > 0 ? "|" : "").append(c.name); return b.toString(); }

    static List<String> ns(List<Channel> l) { List<String> o = new ArrayList<>(); for (Channel c : l) o.add(c.name); return o; }

    public static void main(String[] args) {
        List<Channel> base = Arrays.asList(ch("Sirasa TV"), ch("Ánimal Planet"), ch("+1 Movies"), ch("hiru TV"), ch("BBC News"), ch("Cartoon"), ch("Charlie"), ch("Derana"));
        // sort
        eq("num keeps order", names(base), names(ListTools.sort(base, ListTools.SORT_NUM, null)));
        List<Channel> az = ListTools.sort(base, ListTools.SORT_AZ, null);
        eq("az: digits first, accents + case folded", "+1 Movies|Ánimal Planet|BBC News|Cartoon|Charlie|Derana|hiru TV|Sirasa TV", names(az));
        eq("sort leaves base alone", "Sirasa TV", base.get(0).name);
        final Map<String, Integer> plays = new HashMap<>();
        plays.put(base.get(4).url, 5); plays.put(base.get(7).url, 9); plays.put(base.get(0).url, 5);
        final Map<String, Long> last = new HashMap<>();
        last.put(base.get(4).url, 200L); last.put(base.get(0).url, 100L);
        Ranker.Signals sig = new Ranker.Signals() {
            @Override public boolean isFavourite(String url) { return false; }
            @Override public int playCount(String url) { return plays.getOrDefault(url, 0); }
            @Override public long lastPlayedMs(String url) { return last.getOrDefault(url, 0L); }
            @Override public int health(String url) { return 0; }
            @Override public Set<String> pinnedCountries() { return Collections.emptySet(); }
        };
        List<Channel> w = ListTools.sort(base, ListTools.SORT_WATCHED, sig);
        eq("watched: count, then last played, then list order", "Derana|BBC News|Sirasa TV|Ánimal Planet", names(w.subList(0, 4)));
        // labels
        eq("label", "A–Z", ListTools.label(ListTools.SORT_AZ));
        eq("cycle", ListTools.SORT_NUM, ListTools.next(ListTools.SORT_WATCHED));
        eq("parse bad", ListTools.SORT_NUM, ListTools.parse("x"));
        eq("parse", ListTools.SORT_WATCHED, ListTools.parse("2"));
        // filter
        eq("filter word", "Sirasa TV|hiru TV", names(ListTools.filter(base, "tv")));
        eq("filter accents + words any order", "Ánimal Planet", names(ListTools.filter(base, "planet anim")));
        eq("filter empty = all", base.size(), ListTools.filter(base, "  ").size());
        eq("filter none", 0, ListTools.filter(base, "zzz").size());
        // keypad
        List<String> an = ns(az);
        ListTools.Jump j = ListTools.letter(an, 2, 0);
        eq("2 -> A", "1 A", j.row + " " + j.label);
        j = ListTools.letter(an, 2, 1);
        eq("2,2 -> B", "2 B", j.row + " " + j.label);
        j = ListTools.letter(an, 2, 2);
        eq("2,2,2 -> C (first C)", "3 C", j.row + " " + j.label);
        j = ListTools.letter(an, 2, 3);
        eq("wraps to A", "1 A", j.row + " " + j.label);
        j = ListTools.letter(an, 4, 0);
        eq("4 -> H (no G: skipped)", "6 H", j.row + " " + j.label);
        j = ListTools.letter(an, 1, 0);
        eq("1 -> 0-9", "0 0–9", j.row + " " + j.label);
        j = ListTools.letter(an, 9, 0);
        eq("9 -> none", "-1 WXYZ", j.row + " " + j.label);
        j = ListTools.letter(Arrays.asList(null, "Arts", null, "Bio"), 2, 1);
        eq("null rows skipped", "3 B", j.row + " " + j.label);
        eq("null rows not #", -1, ListTools.letter(Arrays.asList(null, "Arts"), 1, 0).row);
        j = ListTools.letter(an, 0, 0);
        eq("0 -> top", "0 Top", j.row + " " + j.label);
        eq("initial other script", '#', ListTools.initial("සිරස"));
        eq("initial skips leading symbol", 's', ListTools.initial("★ Star"));
        eq("initial letter after symbol", 's', ListTools.initial("'Sun"));
        // row
        eq("row 1", 0, ListTools.row("1", 8));
        eq("row 8", 7, ListTools.row("8", 8));
        eq("row too big", -1, ListTools.row("9", 8));
        eq("row 0", -1, ListTools.row("0", 8));
        eq("row leading zero", 4, ListTools.row("05", 8));
        eq("row junk", -1, ListTools.row("", 8));
        System.out.println("ListToolsTest: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
