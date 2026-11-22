package lk.manus.iptv.meta;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lk.manus.iptv.data.Search;

/**
 * Pure Java title cleaner for guide and VOD programme titles.
 * Extracts clean title and release year, or returns null for series episodes.
 */
public final class TitleCleaner {

    public static final class Cleaned {
        public final String title;
        public final int year;

        public Cleaned(String title, int year) {
            this.title = title;
            this.year = year;
        }

        public String foldedKey() {
            return fold(title);
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Cleaned)) return false;
            Cleaned c = (Cleaned) o;
            return year == c.year && Objects.equals(title, c.title);
        }

        @Override public int hashCode() {
            return Objects.hash(title, year);
        }

        @Override public String toString() {
            return title + (year > 0 ? " (" + year + ")" : "");
        }
    }

    // Trailing series episode marks: S01E02, S1 E2, 1x05, Season 1 Episode 2, Ep 05, etc.
    private static final Pattern SERIES_EPISODE = Pattern.compile(
        "(?i)(?:^|[\\s._\\-\\[(])(?:s\\d{1,2}[\\s._\\-]*e\\d{1,3}|s\\d{1,2}|e\\d{1,3}|\\d{1,2}x\\d{1,3}|(?:season|series)\\s*\\d+(?:\\s*(?:episode|ep|ep\\.)\\s*\\d+)?|(?:episode|ep|ep\\.)\\s*\\d+)(?:[\\s._\\-\\])].*|\\s*$)"
    );

    // Common prefixes to strip: country tags, pipes, classifications
    private static final Pattern PREFIX_CLASSIFICATION = Pattern.compile(
        "^(?i)(?:Film|Movie|Movies|New|Cinema|Premiere|VOD|PPV)\\s*[:\\-]\\s*"
    );
    private static final Pattern PREFIX_COUNTRY = Pattern.compile(
        "^(?i)[A-Z]{2,3}\\s*[-:|]\\s*"
    );
    private static final Pattern PREFIX_PIPE = Pattern.compile(
        "^\\|[^|]+\\|\\s*"
    );

    // Cyrillic programme-type prefixes: х/ф, д/ф, м/ф (film: strip); т/с, м/с (series: null)
    private static final Pattern PREFIX_CYRILLIC_FILM = Pattern.compile(
        "^(?:[хx]/[фf]|[дd]/[фf]|[мm]/[фf])[.\\s]*",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern PREFIX_CYRILLIC_SERIES = Pattern.compile(
        "^(?:[тt]/[сc]|[мm]/[сc])[.\\s]*",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // Year range for movies: 1888 to 2030. 2049 (Blade Runner) is excluded and survives as title.
    private static final Pattern BRACKETED_YEAR = Pattern.compile(
        "[\\[({](19\\d{2}|20[0-2]\\d)[\\s)}\\]]"
    );

    private static final Pattern YEAR_TOKEN = Pattern.compile(
        "\\b(19\\d{2}|20[0-2]\\d)\\b"
    );

    private TitleCleaner() {}

    /** Lower-case, accent-folded comparison key reusing data/Search.fold. */
    public static String fold(String s) {
        return Search.fold(s != null ? s : "");
    }

    /**
     * Cleans a raw title into {cleanTitle, year}.
     * Returns null if the title represents a TV series episode or is empty.
     */
    public static Cleaned clean(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // Strip leading prefixes first (e.g. "EN - ", "|UK|", "Film:")
        s = stripPrefixes(s);
        if (s.isEmpty()) return null;

        // Check if item is a Cyrillic series or TV series episode
        if (PREFIX_CYRILLIC_SERIES.matcher(s).find() || SERIES_EPISODE.matcher(s).find()) {
            return null;
        }

        // Check for bracketed year: e.g. "Divergent (2014) [HD]" or "1917 (2019)"
        int year = 0;
        int lastBracketYearStart = -1, lastBracketYearEnd = -1;
        Matcher ym = BRACKETED_YEAR.matcher(s);
        while (ym.find()) {
            String inside = ym.group(1);
            if (s.length() == ym.group(0).length()) {
                // Whole title is in brackets, e.g. (1917) -> title is 1917, not year
                break;
            }
            year = Integer.parseInt(inside);
            lastBracketYearStart = ym.start();
            lastBracketYearEnd = ym.end();
        }

        if (year > 0 && lastBracketYearStart >= 0) {
            // Cut before bracketed year if it was followed by tags, or remove bracketed year
            s = s.substring(0, lastBracketYearStart) + " " + s.substring(lastBracketYearEnd);
        }

        // Strip bracketed/parenthesised tags: [HD], [1080p], (4K), {MULTI}, etc.
        s = stripBracketedTags(s);

        // Replace dots and underscores with spaces (e.g. "The.Matrix.1999.1080p")
        s = s.replace('.', ' ').replace('_', ' ');

        // Strip prefixes again in case dots or brackets revealed new prefixes
        s = stripPrefixes(s);

        // If year was not found in brackets, find the last standalone year token
        if (year == 0) {
            String trimmed = s.trim();
            Matcher tm = YEAR_TOKEN.matcher(trimmed);
            int lastYearStart = -1, lastYearEnd = -1;
            int foundYear = 0;
            while (tm.find()) {
                foundYear = Integer.parseInt(tm.group(1));
                lastYearStart = tm.start();
                lastYearEnd = tm.end();
            }

            if (foundYear > 0 && lastYearStart >= 0) {
                // Check if this year token is the whole title (e.g. "1917", "2012")
                if (trimmed.length() == (lastYearEnd - lastYearStart)) {
                    // Whole title is the year: it survives as title, year is 0
                    s = trimmed;
                    year = 0;
                } else if (lastYearStart > 0) {
                    // Not the whole title: everything before the year token is the title
                    s = trimmed.substring(0, lastYearStart).trim();
                    year = foundYear;
                }
            } else {
                // No year token found: strip trailing quality tags if any
                s = stripTrailingTags(s);
            }
        } else {
            // Year was extracted from brackets: strip trailing quality tags
            s = stripTrailingTags(s);
        }

        // Clean up title whitespace and dangling punctuation
        s = cleanPunctuation(s);
        if (s.isEmpty()) return null;

        return new Cleaned(s, year);
    }

    private static String stripPrefixes(String s) {
        while (true) {
            String prev = s;
            s = s.trim();
            s = PREFIX_PIPE.matcher(s).replaceFirst("");
            s = PREFIX_CLASSIFICATION.matcher(s).replaceFirst("");
            s = PREFIX_COUNTRY.matcher(s).replaceFirst("");
            s = PREFIX_CYRILLIC_FILM.matcher(s).replaceFirst("");
            if (s.startsWith("[") && s.indexOf(']') > 0) {
                String tag = s.substring(1, s.indexOf(']')).trim();
                if (!isYearToken(tag)) {
                    s = s.substring(s.indexOf(']') + 1).trim();
                }
            }
            if (s.equals(prev)) break;
        }
        return s.trim();
    }

    private static String stripBracketedTags(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int depth = 0;
        int bracketStart = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[' || c == '(' || c == '{') {
                if (depth == 0) bracketStart = i;
                depth++;
            } else if (c == ']' || c == ')' || c == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0) {
                        String inside = s.substring(bracketStart + 1, i).trim();
                        if (isYearToken(inside) && s.length() == (i - bracketStart + 1)) {
                            // If the whole string was (1917), preserve it
                            sb.append(inside);
                        } else {
                            sb.append(' ');
                        }
                    }
                }
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String stripTrailingTags(String s) {
        s = s.trim();
        while (true) {
            String prev = s;
            s = s.replaceAll("(?i)[\\s._\\-]+(?:1080p|1080i|720p|720i|2160p|4320p|480p|576p|4k|8k|2k|uhd|fhd|hd|sd|bluray|blu-ray|bdrip|brrip|web-dl|webdl|web-rip|webrip|web|hdtv|pdtv|dvdrip|dvd|remux|cam|ts|tc|scr|x264|x265|h264|h265|hevc|avc|xvid|divx|aac|ac3|eac3|dts|dts-hd|truehd|atmos|dd5\\.1|5\\.1|7\\.1|multi|multisubs|multis|sub|subs|subbed|subtitled|dub|dubbed|dual|vostfr|truefrench|french|ita|esp|german|deutsch|hindi|tamil|telugu|malayalam|korean|japanese|chinese|rus|eng|english|latino|extended|unrated|directors cut|proper|repack|imax)\\s*$", "").trim();
            if (s.equals(prev)) break;
        }
        return s;
    }

    private static String cleanPunctuation(String s) {
        // Collapse multiple spaces
        s = s.replaceAll("\\s+", " ").trim();
        // Remove leading or trailing dashes, colons, slashes
        s = s.replaceAll("^[-:/\\s]+", "").replaceAll("[-:/\\s]+$", "").trim();
        return s;
    }

    private static boolean isYearToken(String s) {
        if (s == null || s.length() != 4) return false;
        // Plausible movie years: 1888 to 2029 (19xx or 200x-202x)
        if (s.startsWith("19") || s.startsWith("200") || s.startsWith("201") || s.startsWith("202")) {
            for (int i = 0; i < 4; i++) {
                char c = s.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            return true;
        }
        return false;
    }
}
