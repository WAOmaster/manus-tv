import lk.manus.iptv.data.ShowGroups;
import lk.manus.iptv.data.ShowGroups.Rec;
import lk.manus.iptv.data.ShowGroups.Group;
import java.util.*;

public class ShowGroupsTest {
    static int fails;
    static void check(boolean ok, String m) { if (!ok) { fails++; System.out.println("FAIL " + m); } }
    static Rec r(String ref, int id, String t, boolean dead, boolean w, int q, long watched) { return new Rec(ref, id, t, dead, w, q, watched); }
    static Rec r(String ref, int id, String t) { return r(ref, id, t, false, false, 0, 0); }
    static List<Group> g(Rec... a) { return ShowGroups.group(Arrays.asList(a)); }
    static String rep(Group x) { return (String) x.rep.ref; }
    public static void main(String[] a) {
        List<Group> s = g(r("a", 7, "Avatar: The Last Airbender"), r("b", 7, "avatar the last airbender"), r("c", 9, "Other"));
        check(s.size() == 2 && s.get(0).size() == 2 && s.get(1).size() == 1, "same TMDB id merges, other stays apart");
        s = g(r("a", 0, "Café  Club!"), r("b", 0, "cafe club"), r("c", 0, "CAFE-CLUB"), r("d", 0, "Cafe Clubs"));
        check(s.size() == 2 && s.get(0).size() == 3, "unmatched cleaned title merges (case, accents, punctuation)");
        s = g(r("a", 0, "Friends"), r("b", 5, "Friends"), r("c", 0, "Friends"));
        check(s.size() == 1 && s.get(0).size() == 3, "unmatched joins the matched group with that title");
        s = g(r("a", 1, "Dune"), r("b", 2, "Dune"));
        check(s.size() == 2, "different TMDB ids with one title stay apart");
        s = g(r("a", 0, "!!!"), r("b", 0, "???"));
        check(s.size() == 2, "empty keys never merge");
        s = g(r("dead", 3, "X", true, false, 2160, 99), r("untested", 3, "X", false, false, 1080, 0), r("work", 3, "X", false, true, 480, 0));
        check(rep(s.get(0)).equals("work") && s.get(0).others.size() == 2 && s.get(0).others.get(0).ref.equals("untested") && s.get(0).others.get(1).ref.equals("dead"), "working > untested > dead");
        s = g(r("sd", 3, "X", false, true, 480, 50), r("hd", 3, "X", false, true, 1080, 0));
        check(rep(s.get(0)).equals("hd"), "quality beats recency");
        s = g(r("old", 3, "X", false, true, 720, 10), r("new", 3, "X", false, true, 720, 20), r("never", 3, "X", false, true, 720, 0));
        check(rep(s.get(0)).equals("new"), "recency breaks a quality tie");
        s = g(r("1", 3, "X"), r("2", 3, "X"), r("3", 3, "X"));
        check(rep(s.get(0)).equals("1") && s.get(0).others.get(0).ref.equals("2"), "full tie keeps input order");
        check(g(r("a", 1, "A"), r("b", 1, "A"), r("c", 1, "A"), r("d", 1, "A")).get(0).size() == 4, "chip count = group size");
        s = g(r("p", 1, "P"), r("q", 2, "Q"), r("p2", 1, "P"), r("r", 3, "R"));
        check(rep(s.get(0)).equals("p") && rep(s.get(1)).equals("q") && rep(s.get(2)).equals("r"), "groups keep first-appearance order");
        check(ShowGroups.qualityOf("1080p") == 1080 && ShowGroups.qualityOf("4K") == 2160 && ShowGroups.qualityOf("") == 0 && ShowGroups.qualityOf("HD") == 720, "qualityOf");
        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("ShowGroupsTest ok");
    }
}
