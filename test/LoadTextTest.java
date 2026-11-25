import lk.manus.iptv.data.LoadText;

/** Every wait names what it waits for; long names are trimmed, never a bare "Loading…". */
public class LoadTextTest {
    private static int checks, failures;

    private static void eq(String what, Object expected, Object actual) {
        checks++;
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            failures++;
            System.out.println("FAIL " + what + ": expected [" + expected + "] got [" + actual + "]");
        }
    }

    public static void main(String[] args) {
        eq("reading 1", "Reading your channel list…", LoadText.reading(1));
        eq("reading 3", "Reading your 3 channel lists…", LoadText.reading(3));
        eq("downloading", "Downloading “My TV”…", LoadText.downloading("  My   TV "));
        eq("downloading blank", "Downloading your channel list…", LoadText.downloading(null));
        eq("sorting", "Sorting 4,210 channels…", LoadText.sorting(4210));
        eq("sorting 1", "Sorting 1 channel…", LoadText.sorting(1));
        eq("sorting 0", "Getting your guide ready…", LoadText.sorting(0));
        eq("film new", "Opening Inception…", LoadText.film("Inception", 0, false));
        eq("film resume", "Resuming Inception at 42:10…", LoadText.film("Inception", 2530000, false));
        eq("film resume hours", "Resuming Dune at 1:02:03…", LoadText.film("Dune", 3723000, false));
        eq("film blank", "Opening your film…", LoadText.film("", 0, false));
        eq("catch-up", "Opening News at 8 from the archive…", LoadText.film("News at 8", 5000, true));
        String longT = "The Extraordinarily Long Title Of A Film That Keeps Going On";
        String f = LoadText.film(longT, 0, false);
        eq("long trimmed", true, f.length() < 60 && f.contains("…"));
        eq("name trim limit", 40, LoadText.name(longT).length());
        eq("earlier", "Getting earlier programmes on Sirasa TV…", LoadText.earlier("Sirasa TV"));
        eq("tuning", "Tuning in…", LoadText.tuning());
        System.out.println("LoadTextTest: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
