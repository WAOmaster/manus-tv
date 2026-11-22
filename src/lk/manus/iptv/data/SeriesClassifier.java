package lk.manus.iptv.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java (no Android): does a guide title (and its optional guide category) describe a series episode?
 * parse() cuts the episode marker off, so the show title can be matched on its own: "Show S2 E5" -> ("Show", 2, 5).
 * Markers: S01E02, S2 E5, 2x05, "Season 2, Episode 5", "Season 2", "Ep. 5", "Episode 5", a few other languages. A bare number
 * in a title ("24 Kitchen", "Seven") is never a marker.
 */
public final class SeriesClassifier {
    private SeriesClassifier() {}

    public static final class Parsed {
        public final String title;      // show title with the marker, tags and prefixes removed
        public final int season, episode; // 0 = not given
        public final boolean marker;    // the title carried an explicit series marker
        Parsed(String t, int s, int e, boolean m) { title = t; season = s; episode = e; marker = m; }
    }

    private static final String SEASON = "(?:season|series|saison|staffel|temporada|stagione|sezon|sezona|série|serie|сезон|серия)";
    private static final String EPISODE = "(?:episode|episodio|épisode|ep|folge|odcinek|epizoda|díl|bölüm|серия)";
    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    // order matters: the most specific first. Each must start at a word edge and end at one.
    private static final Pattern SXE = Pattern.compile("(?<![A-Za-z0-9])s(\\d{1,2})[\\s._\\-]*e(\\d{1,3})(?![A-Za-z0-9])", F);
    private static final Pattern NXN = Pattern.compile("(?<![A-Za-z0-9])(\\d{1,2})x(\\d{2,3})(?![A-Za-z0-9])", F);
    private static final Pattern SEASON_EP = Pattern.compile("(?<![\\p{L}\\d])" + SEASON + "\\.?\\s*(\\d{1,2})[\\s,;.:\\-]*" + EPISODE + "\\.?\\s*(\\d{1,3})(?![\\p{L}\\d])", F);
    private static final Pattern SEASON_ONLY = Pattern.compile("(?<![\\p{L}\\d])" + SEASON + "\\.?\\s*(\\d{1,2})(?![\\p{L}\\d])", F);
    private static final Pattern EP_ONLY = Pattern.compile("(?<![\\p{L}\\d])" + EPISODE + "\\.?\\s*(\\d{1,3})(?![\\p{L}\\d])", F);
    private static final Pattern S_ONLY = Pattern.compile("(?<![A-Za-z0-9])s(\\d{1,2})(?![A-Za-z0-9])", F);
    private static final Pattern E_ONLY = Pattern.compile("(?<![A-Za-z0-9])e(\\d{1,3})(?![A-Za-z0-9])", F);
    private static final Pattern BRACKETS = Pattern.compile("\\s*[\\[({][^\\])}]*[\\])}]");
    private static final Pattern LEAD_TAG = Pattern.compile("^(?:\\|[^|]{1,6}\\||\\[[^\\]]{1,8}\\])\\s*");
    private static final Pattern CATEGORY = Pattern.compile("(?i)\\b(series|serial|serie|serien|episode|soap|sitcom|tv show)\\b");

    /** Is this guide category text a series category? null/empty = no category known. */
    public static boolean categoryIsSeries(String category) {
        return category != null && CATEGORY.matcher(category).find() && !category.toLowerCase(Locale.ROOT).contains("mini-series film");
    }

    /** True when the title has a series marker, or the guide category says series/episode. TMDB is not asked here. */
    public static boolean isSeriesByGuide(String rawTitle, String category) {
        return categoryIsSeries(category) || (rawTitle != null && parse(rawTitle).marker);
    }

    public static boolean isSeriesByGuide(String rawTitle) { return isSeriesByGuide(rawTitle, null); }

    /**
     * T110d: show names to try on TMDB, best first: the full title, then (guides write "Show. Episode title" / "Show - Episode title")
     * the part before the first ". " or " - ". A part must be at least 4 letters ("Dr. House" never tries "Dr").
     */
    public static List<String> showTitles(String title) {
        List<String> out = new ArrayList<>();
        if (title == null || title.trim().isEmpty()) return out;
        String t = title.trim();
        out.add(t);
        for (String sep : new String[]{". ", " - ", " – "}) {
            int i = t.indexOf(sep);
            String head = i > 0 ? t.substring(0, i).trim() : "";
            if (head.length() >= 4 && !out.contains(head)) out.add(head);
        }
        return out;
    }

    /** Splits a guide title into show title and S/E. Never null; an empty title stays empty. */
    public static Parsed parse(String raw) {
        String s = raw == null ? "" : LEAD_TAG.matcher(raw.trim()).replaceFirst(""); // not TitleCleaner prefixes: "CSI - Miami" must keep "CSI"
        int season = 0, ep = 0, cut = -1;
        boolean marker = true;
        Matcher m;
        if ((m = SXE.matcher(s)).find() || (m = NXN.matcher(s)).find() || (m = SEASON_EP.matcher(s)).find()) {
            season = Integer.parseInt(m.group(1)); ep = Integer.parseInt(m.group(2)); cut = m.start();
        } else if ((m = SEASON_ONLY.matcher(s)).find() || (m = S_ONLY.matcher(s)).find()) {
            season = Integer.parseInt(m.group(1)); cut = m.start();
            Matcher e = EP_ONLY.matcher(s);
            if (e.find()) ep = Integer.parseInt(e.group(1));
            else if ((e = E_ONLY.matcher(s)).find()) ep = Integer.parseInt(e.group(1));
        } else if ((m = EP_ONLY.matcher(s)).find() || (m = E_ONLY.matcher(s)).find()) {
            ep = Integer.parseInt(m.group(1)); cut = m.start();
        } else marker = false;
        if (cut >= 0 && cut > 0) s = s.substring(0, cut);
        else if (cut == 0) s = ""; // marker first: no show name (e.g. "Episode 5"); nothing to match
        s = BRACKETS.matcher(s).replaceAll("").replaceAll("\\s+", " ").replaceAll("^[\\s:;,.(\\[{\\-–—/]+|[\\s:;,.(\\[{\\-–—/]+$", "");
        return new Parsed(s, season, ep, marker);
    }
}
