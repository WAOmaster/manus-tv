package lk.manus.iptv.data;

import java.text.NumberFormat;
import java.util.Locale;

/** Wording for every wait in the app: always says what is loading (never a bare "Loading…"). Pure Java, testable. */
public final class LoadText {
    static final int MAX_NAME = 40;

    private LoadText() {}

    /** A title or channel name trimmed for a one-line status. */
    public static String name(String s) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        return t.length() <= MAX_NAME ? t : t.substring(0, MAX_NAME - 1).trim() + "…";
    }

    private static String num(int n) { return NumberFormat.getIntegerInstance(Locale.US).format(n); }

    /** Cold start, step 1: reading the saved copies of the user's lists. */
    public static String reading(int sources) {
        return sources <= 1 ? "Reading your channel list…" : "Reading your " + sources + " channel lists…";
    }

    /** Cold start, first run of a source (no saved copy yet). */
    public static String downloading(String source) {
        String n = name(source);
        return n.isEmpty() ? "Downloading your channel list…" : "Downloading “" + n + "”…";
    }

    /** Cold start, last step: building the guide (categories, countries, For you). */
    public static String sorting(int channels) {
        return channels <= 0 ? "Getting your guide ready…" : "Sorting " + num(channels) + (channels == 1 ? " channel…" : " channels…");
    }

    /** A live channel is starting (the banner already shows its name). */
    public static String tuning() { return "Tuning in…"; }

    /** A film / episode / catch-up programme is starting. */
    public static String film(String title, long startMs, boolean catchup) {
        String t = name(title);
        if (t.isEmpty()) t = catchup ? "the programme" : "your film";
        if (catchup) return "Opening " + t + " from the archive…";
        return startMs >= 1000 ? "Resuming " + t + " at " + FilmResume.formatTime(startMs) + "…" : "Opening " + t + "…";
    }

    /** Catch-up list for a channel. */
    public static String earlier(String channel) {
        String n = name(channel);
        return n.isEmpty() ? "Getting earlier programmes…" : "Getting earlier programmes on " + n + "…";
    }
}
