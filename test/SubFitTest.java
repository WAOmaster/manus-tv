import lk.manus.iptv.player.SubFit;

/** Subtitle sizing: short cues full size, long cues shrink, nothing is ever dropped. */
public class SubFitTest {
    private static int checks, failures;

    private static void eq(String what, Object expected, Object actual) {
        checks++;
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            failures++;
            System.out.println("FAIL " + what + ": expected [" + expected + "] got [" + actual + "]");
        }
    }

    static String rep(char c, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(c); return b.toString(); }

    public static void main(String[] args) {
        eq("clean trims + drops blanks", "Hello\nWorld", SubFit.clean("  Hello  \n\n   World \r\n"));
        eq("clean collapses spaces", "a b", SubFit.clean("a \t   b"));
        eq("clean null", "", SubFit.clean(null));
        eq("lines empty", 0, SubFit.lines("", 40));
        eq("lines one", 1, SubFit.lines("short", 40));
        eq("lines wrap", 3, SubFit.lines(rep('x', 81), 40));
        eq("lines hard breaks", 2, SubFit.lines("a\nb", 40));
        eq("short full size", 1f, SubFit.scale("- Where are you?\n- Home.", 40));
        eq("4 lines full size", 1f, SubFit.scale("a\nb\nc\nd", 40));
        float s = SubFit.scale(rep('x', 200), 40); // 5 lines at full size
        eq("long shrinks", true, s < 1f);
        eq("long fits budget", true, SubFit.lines(rep('x', 200), 40 / s) <= SubFit.BUDGET);
        eq("6 hard lines -> smallest step", 0.7f, SubFit.scale("1\n2\n3\n4\n5\n6", 40));
        eq("two speakers translated", true, SubFit.scale("- " + rep('w', 90) + "\n- " + rep('v', 90), 40) < 1f);
        System.out.println("SubFitTest: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
