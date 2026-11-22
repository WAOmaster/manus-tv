import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lk.manus.iptv.data.Catchup;
import lk.manus.iptv.data.XtreamSource;

/** T95: catch-up address, archive window and which earlier programmes are offered (pure Java). */
public final class CatchupTest {
    static int fails, checks;

    static void eq(String what, Object got, Object want) {
        checks++;
        if (!Objects.equals(want, got)) { fails++; System.out.println("FAIL " + what + ": got [" + got + "], want [" + want + "]"); }
    }

    static Catchup.Prog p(String t, long start, long stop, boolean arch) {
        return new Catchup.Prog(t, "2026-11-17 10:00:00", start, stop, arch);
    }

    public static void main(String[] args) {
        eq("archive 1 x 3 days", Catchup.archiveDays(1, 3), 3);
        eq("archive off", Catchup.archiveDays(0, 3), 0);
        eq("archive no days", Catchup.archiveDays(1, 0), 0);
        eq("archive absurd days capped", Catchup.archiveDays(1, 999), 60);

        eq("stamp", Catchup.startStamp("2026-11-17 10:05:00"), "2026-11-17:10-05");
        eq("stamp short", Catchup.startStamp("2026-11-17"), null);
        eq("stamp bad", Catchup.startStamp("2026/11/17 10:05:00"), null);
        eq("stamp null", Catchup.startStamp(null), null);

        eq("minutes exact", Catchup.minutes(0, 3600), 60L);
        eq("minutes rounds up", Catchup.minutes(0, 3601), 61L);
        eq("minutes at least 1", Catchup.minutes(100, 100), 1L);

        Catchup.Prog one = p("News", 1000, 1000 + 3600, true);
        eq("template", Catchup.template(one, "201"), "{BASE}/timeshift/{USER}/{PASS}/60/2026-11-17:10-00/201.ts");
        eq("template no stream", Catchup.template(one, ""), null);
        eq("template bad start", Catchup.template(new Catchup.Prog("x", "soon", 0, 60, true), "201"), null);
        eq("login joined only at the end", XtreamSource.joinUrl(Catchup.template(one, "201"), "http://10.0.2.2:8081", "demo", "p w"),
                "http://10.0.2.2:8081/timeshift/demo/p%20w/60/2026-11-17:10-00/201.ts");

        long now = 1_000_000;
        List<Catchup.Prog> all = new ArrayList<>();
        all.add(p("old", now - 4 * 86400L, now - 4 * 86400L + 3600, true));  // outside a 3-day window
        all.add(p("a", now - 2 * 86400L, now - 2 * 86400L + 3600, true));
        all.add(p("b", now - 7200, now - 3600, true));
        all.add(p("noarch", now - 5000, now - 4000, false));                  // provider says not archived
        all.add(p("live", now - 600, now + 3000, true));                      // still running
        all.add(p("future", now + 3600, now + 7200, true));
        all.add(p("empty", now - 100, now - 100, true));                      // zero length
        List<Catchup.Prog> w = Catchup.window(all, now, 3);
        eq("window size", w.size(), 2);
        eq("window newest first", w.get(0).title + "," + w.get(1).title, "b,a");
        eq("no archive channel", Catchup.window(all, now, 0).size(), 0);
        eq("null list", Catchup.window(null, now, 3).size(), 0);

        System.out.println(checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
