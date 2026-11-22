package lk.manus.iptv.data;

/** Order of "Movies on now": poster first, newest year, higher rating (only with enough votes). Ties compare equal, so a stable sort keeps the old order. */
public final class FilmOrder {
    public static final int MIN_VOTES = 50;

    public static final class Key {
        final boolean poster, dead; final int year; final double rating;
        /** year: TMDB release year, else the listed one (0 = unknown). rating counts only with votes >= MIN_VOTES. */
        public Key(boolean poster, int year, double rating, int votes) { this(poster, year, rating, votes, false); }
        /** dead: the channel is known dead; such films sort after every live or untested one. */
        public Key(boolean poster, int year, double rating, int votes, boolean dead) {
            this.dead = dead; this.poster = poster; this.year = year; this.rating = votes >= MIN_VOTES ? rating : 0;
        }
    }

    public static int compare(Key a, Key b) {
        if (a.dead != b.dead) return a.dead ? 1 : -1;  // live/untested group first, dead group last
        if (a.poster != b.poster) return a.poster ? -1 : 1;
        if (!a.poster) return 0;                       // letter tiles keep their existing order
        if (a.year != b.year) return b.year - a.year;  // newer first
        return Double.compare(b.rating, a.rating);     // unrated (0) sorts after rated
    }

    private FilmOrder() {}
}
