import lk.manus.iptv.data.SeriesClassifier;
import lk.manus.iptv.data.SeriesClassifier.Parsed;
import lk.manus.iptv.meta.TitleCleaner;

/** T110a: series markers, S/E parsing, not-series cases, and the film/series exclusion. */
public class SeriesClassifierTest {
    static int fails;
    static void check(boolean ok, String m) { if (!ok) { fails++; System.out.println("FAIL " + m); } }

    static void se(String raw, String title, int s, int e) {
        Parsed p = SeriesClassifier.parse(raw);
        check(p.marker && p.title.equals(title) && p.season == s && p.episode == e, "[" + raw + "] -> [" + p.title + "|" + p.season + "|" + p.episode + "|" + p.marker + "] want [" + title + "|" + s + "|" + e + "]");
    }
    static void plain(String raw) {
        Parsed p = SeriesClassifier.parse(raw);
        check(!p.marker && p.season == 0 && p.episode == 0 && p.title.equals(raw), "plain [" + raw + "] -> [" + p.title + "|" + p.season + "|" + p.episode + "|" + p.marker + "]");
        check(!SeriesClassifier.isSeriesByGuide(raw), "not series [" + raw + "]");
    }

    static void titles(String raw, String... want) {
        java.util.List<String> got = SeriesClassifier.showTitles(SeriesClassifier.parse(raw).title);
        check(got.equals(java.util.Arrays.asList(want)), "showTitles [" + raw + "] -> " + got + " want " + java.util.Arrays.asList(want));
    }

    public static void main(String[] a) {
        se("Friends S01E02", "Friends", 1, 2);
        se("Friends S1 E2", "Friends", 1, 2);
        se("Friends s01e02 - The One", "Friends", 1, 2);
        se("The Office S2 E5", "The Office", 2, 5);
        se("The Office (S2 E5)", "The Office", 2, 5);
        se("Grey's Anatomy Season 2, Episode 5", "Grey's Anatomy", 2, 5);
        se("Grey's Anatomy Season 2 Episode 5", "Grey's Anatomy", 2, 5);
        se("Lost 2x05", "Lost", 2, 5);
        se("Midsomer Murders - Series 7", "Midsomer Murders", 7, 0);
        se("Taggart S3", "Taggart", 3, 0);
        se("Hercule Poirot Ep. 5", "Hercule Poirot", 0, 5);
        se("Hercule Poirot Ep 5", "Hercule Poirot", 0, 5);
        se("Hercule Poirot Episode 12", "Hercule Poirot", 0, 12);
        se("[HD] CSI - Miami S2E3", "CSI - Miami", 2, 3);
        se("Dr. House Saison 3 Episode 4", "Dr. House", 3, 4);
        se("Dr. House Staffel 3 Folge 4", "Dr. House", 3, 4);
        // Episode marker only when the guide gives it
        check(SeriesClassifier.parse("Dallas Season 3").episode == 0, "season only: episode stays 0");
        check(SeriesClassifier.parse("Dallas").season == 0, "no marker: season 0");

        plain("Seven");
        plain("24 Kitchen");
        plain("Se7en");
        plain("Step Up");
        plain("Sesame Street");
        plain("Episodes of Life");
        plain("Blade Runner 2049");
        plain("1917");
        plain("Die Hard 2");
        plain("The Expanse");

        // category-based
        check(SeriesClassifier.isSeriesByGuide("The Expanse", "Series"), "category Series");
        check(SeriesClassifier.isSeriesByGuide("The Expanse", "Drama / Episode"), "category Episode");
        check(!SeriesClassifier.isSeriesByGuide("The Expanse", "Movie"), "category Movie is not a series");
        check(!SeriesClassifier.isSeriesByGuide("The Expanse", null), "no category is not a series");
        check(!SeriesClassifier.isSeriesByGuide("The Expanse", ""), "empty category is not a series");
        check(!SeriesClassifier.isSeriesByGuide(null, null), "null title");
        check(SeriesClassifier.parse(null).title.isEmpty() && SeriesClassifier.parse("").title.isEmpty(), "empty titles stay empty");
        check(SeriesClassifier.parse("Episode 5").title.isEmpty(), "marker only: no show name");

        // film / series exclusion: Films.candidates drops isSeriesByGuide titles, so these never reach Movies on now,
        // even where TitleCleaner alone would still call them a film
        String[] stillFilmToCleaner = {"Dr. House Staffel 3 Folge 4", "Dr. House Saison 3 Episode 4"};
        for (String t : stillFilmToCleaner) {
            check(SeriesClassifier.isSeriesByGuide(t), "classified as series: " + t);
        }
        check(TitleCleaner.clean("Dr. House Staffel 3 Folge 4") != null, "precondition: cleaner alone lets the German form through");
        String[] films = {"Divergent (2014)", "Seven", "1917", "Blade Runner 2049", "The Matrix 1999"};
        for (String t : films) check(!SeriesClassifier.isSeriesByGuide(t), "film title stays a film: " + t);
        // whatever TitleCleaner calls a series episode, the classifier agrees (no gaps between the two paths)
        String[] cleanerSeries = {"Friends S01E02", "The Office S2 E5", "Hercule Poirot Ep. 5", "Lost 2x05", "Taggart S3"};
        for (String t : cleanerSeries) check(TitleCleaner.clean(t) == null && SeriesClassifier.isSeriesByGuide(t), "cleaner and classifier agree: " + t);

        // T110d: show names to try on TMDB, and the four titles that showed as letter tiles on the real TV
        titles("Homes Under The Hammer E4", "Homes Under The Hammer");
        titles("Police Interceptors E2", "Police Interceptors");
        titles("Avatar: The Last Airbender E5", "Avatar: The Last Airbender");
        titles("Dateline. Finding Venus S4 E66", "Dateline. Finding Venus", "Dateline");
        se("Dateline. Finding Venus S4 E66", "Dateline. Finding Venus", 4, 66);
        titles("Dr. House S3 E4", "Dr. House");
        titles("Taggart - The Killing Mind S2", "Taggart - The Killing Mind", "Taggart");
        check(SeriesClassifier.showTitles("").isEmpty(), "empty title: nothing to try");

        if (fails > 0) System.exit(1);
        System.out.println("SeriesClassifierTest ok");
    }
}
