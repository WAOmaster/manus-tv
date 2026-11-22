package lk.manus.iptv.meta;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lk.manus.iptv.data.Db;

/**
 * Stores film metadata in catalogue.db and manages in-memory LRU cache.
 * Resolves titles via TitleCleaner -> in-memory LRU -> DB -> TMDB search & details.
 */
public final class MetaStore {
    private static final String TAG = "IPTV-meta";
    private static final long SEVEN_DAYS_MS = 7L * 86400000L;
    /** T110d: a show with no TMDB answer is asked again after a day (films: 7 days), so a matching fix reaches the viewer quickly. */
    public static final long SHOW_NOMATCH_MS = 86400000L;
    private static final long SHOW_RULES_MS = 1795248871000L; // 2026-11-21: show no-matches stored before the T110d matching fix are void

    /** A stored show no-match that must be asked again: older than a day, or made by the matching rules before T110d. Matched rows are never "stale" here. */
    public static boolean showNoMatchStale(Film f, long now) {
        return f != null && !f.isMatched() && f.fetchedAt != -1L && (now - f.fetchedAt >= SHOW_NOMATCH_MS || f.fetchedAt < SHOW_RULES_MS);
    }
    private static final long THIRTY_DAYS_MS = 30L * 86400000L;
    private static final long ONE_EIGHTY_DAYS_MS = 180L * 86400000L;

    public static final class Film {
        public final String cleanTitle;
        public final int year;
        public final int tmdbId;
        public final String plot;
        public final double rating;
        public final int votes;
        public final String genres;
        public final int runtime;
        public final String cast;
        public final String posterPath;
        public final String backdropPath;
        public final String trailer;
        public final double matchScore;
        public final String matchSource; // "tmdb", "none", "manual" (chosen by the viewer: never replaced automatically)
        public final long fetchedAt;
        public final String title;   // TMDB's own title ("" / the guide title when not matched)
        public final int releaseYear; // TMDB's release year, 0 = unknown (a matched row with 0 is stale and refreshed)

        public Film(String cleanTitle, int year, int tmdbId, String plot, double rating,
                    int votes, String genres, int runtime, String cast, String posterPath,
                    String backdropPath, String trailer, double matchScore,
                    String matchSource, long fetchedAt) {
            this(cleanTitle, year, tmdbId, plot, rating, votes, genres, runtime, cast, posterPath, backdropPath, trailer,
                 matchScore, matchSource, fetchedAt, cleanTitle, year);
        }

        public Film(String cleanTitle, int year, int tmdbId, String plot, double rating,
                    int votes, String genres, int runtime, String cast, String posterPath,
                    String backdropPath, String trailer, double matchScore,
                    String matchSource, long fetchedAt, String title, int releaseYear) {
            this.title = title != null && !title.isEmpty() ? title : (cleanTitle != null ? cleanTitle : "");
            this.releaseYear = releaseYear;
            this.cleanTitle = cleanTitle != null ? cleanTitle : "";
            this.year = year;
            this.tmdbId = tmdbId;
            this.plot = plot != null ? plot : "";
            this.rating = rating;
            this.votes = votes;
            this.genres = genres != null ? genres : "";
            this.runtime = runtime;
            this.cast = cast != null ? cast : "";
            this.posterPath = posterPath != null ? posterPath : "";
            this.backdropPath = backdropPath != null ? backdropPath : "";
            this.trailer = trailer != null ? trailer : "";
            this.matchScore = matchScore;
            this.matchSource = matchSource != null ? matchSource : "none";
            this.fetchedAt = fetchedAt;
        }

        public boolean isMatched() {
            return ("tmdb".equals(matchSource) || "manual".equals(matchSource)) && tmdbId > 0;
        }
    }

    /** One candidate of an ambiguous title, kept on the no-match row (title, release year, poster path: all from the search result). */
    public static final class Choice {
        public final int tmdbId, year;
        public final String title, posterPath;
        public Choice(int tmdbId, String title, int year, String posterPath) { this.tmdbId = tmdbId; this.title = title; this.year = year; this.posterPath = posterPath; }
    }

    public interface Callback {
        void onResolved(TitleCleaner.Cleaned clean, Film film);
    }

    private static volatile MetaStore instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Film> memory = new LruCache<>(200);
    private final Map<String, List<Choice>> choiceMem = new HashMap<>(); // providerId -> candidates (empty list = none), guarded by itself
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "meta-store");
        t.setDaemon(true);
        return t;
    });

    private MetaStore(Context ctx) {
        context = ctx.getApplicationContext();
        prefs = context.getSharedPreferences("iptv", Context.MODE_PRIVATE);

        // Check if key changed or was removed
        checkKeyChanged();

        // Delete rows older than 180 days at startup
        startupPurge();
    }

    public static synchronized MetaStore get(Context ctx) {
        if (instance == null) instance = new MetaStore(ctx);
        return instance;
    }

    public static String providerId(String cleanTitle, int year) {
        return TitleCleaner.fold(cleanTitle) + "|" + (year > 0 ? year : 0);
    }

    /**
     * Retrieves film from memory LRU, then DB. Returns null if not found.
     */
    public Film get(String cleanTitle, int year) { return get("movie", cleanTitle, year); }

    /** T110a: cached TV show for a clean show title (cache only, no network). Shows live in the same item/meta tables with kind 'show'. */
    public Film getShow(String cleanTitle) { return get(SHOW, cleanTitle, 0); }

    private static final String SHOW = "show";
    private static String mkey(String kind, String cleanTitle, int year) { return (SHOW.equals(kind) ? "tv:" : "") + providerId(cleanTitle, year); }

    private Film get(String kind, String cleanTitle, int year) {
        if (cleanTitle == null || cleanTitle.trim().isEmpty()) return null;
        String key = mkey(kind, cleanTitle, year);
        Film cached = memory.get(key);
        if (cached != null) return cached;

        Db db = Db.get();
        if (db == null) return null;

        try {
            SQLiteDatabase d = db.getReadableDatabase();
            String sql = "SELECT i.provider_id, i.year, m.tmdb_id, m.plot, m.rating, m.votes, m.genres, "
                       + "m.runtime, m.\"cast\", m.poster, m.backdrop, m.trailer, m.match_score, m.match_source, m.fetched_at, i.title "
                       + "FROM item i JOIN meta m ON m.item_id = i.id "
                       + "WHERE i.source_id = 0 AND i.kind = ? AND i.provider_id = ?";
            try (Cursor c = d.rawQuery(sql, new String[]{kind, providerId(cleanTitle, year)})) {
                if (c.moveToFirst()) {
                    String pid = c.getString(0);
                    int bar = pid.lastIndexOf('|');
                    String ct = bar >= 0 ? pid.substring(0, bar) : pid; // folded clean title; the key only needs the fold
                    int cy = 0;
                    try { cy = bar >= 0 ? Integer.parseInt(pid.substring(bar + 1)) : 0; } catch (NumberFormatException ignored) { }
                    boolean matched = "tmdb".equals(c.getString(13)) || "manual".equals(c.getString(13));
                    Film f = new Film(
                        ct,
                        cy,
                        c.getInt(2),
                        c.getString(3),
                        c.getDouble(4),
                        c.getInt(5),
                        c.getString(6),
                        c.getInt(7),
                        c.getString(8),
                        c.getString(9),
                        c.getString(10),
                        c.getString(11),
                        c.getDouble(12),
                        c.getString(13),
                        c.getLong(14),
                        c.getString(15),
                        matched ? c.getInt(1) : 0
                    );
                    memory.put(key, f);
                    return f;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "db read error " + t.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * Resolves raw title into metadata.
     * Clean -> Cache hit -> Search -> Pick -> Details -> Store -> Callback on main thread.
     */
    public void resolve(final String rawTitle, final Callback callback) {
        resolve(rawTitle, 0, callback);
    }

    public void resolve(final String rawTitle, final int slotMinutes, final Callback callback) {
        io.execute(() -> {
            TitleCleaner.Cleaned clean = TitleCleaner.clean(rawTitle);
            if (clean == null) {
                main.post(() -> callback.onResolved(null, null));
                return;
            }

            Film cached = get(clean.title, clean.year);
            long now = System.currentTimeMillis();
            if (cached != null) {
                if ("none".equals(cached.matchSource)) {
                    if (cached.fetchedAt == -1L) {
                        // Manual "not this film", never auto-retried
                        final Film f = cached;
                        main.post(() -> callback.onResolved(clean, f));
                        return;
                    }
                    if ((now - cached.fetchedAt) < SEVEN_DAYS_MS) {
                        // Cached no-match within 7 days
                        final Film f = cached;
                        main.post(() -> callback.onResolved(clean, f));
                        return;
                    }
                    // Older than 7 days: retry
                } else if ("manual".equals(cached.matchSource)) {
                    final Film f = cached; // the viewer's own choice: never refreshed or replaced
                    main.post(() -> callback.onResolved(clean, f));
                    return;
                } else if ("tmdb".equals(cached.matchSource)) {
                    if ((now - cached.fetchedAt) < THIRTY_DAYS_MS && cached.releaseYear > 0) { // year 0: saved before T61b, refreshed now
                        // Fresh cached match within 30 days
                        final Film f = cached;
                        main.post(() -> callback.onResolved(clean, f));
                        return;
                    }
                    // Older than 30 days: refresh when next asked
                }
            }

            Tmdb tmdb = Tmdb.get(context);
            if (!tmdb.hasKey()) {
                final Film f = cached;
                main.post(() -> callback.onResolved(clean, f));
                return;
            }

            List<Matcher.Candidate> candidates = tmdb.search(clean.title, clean.year);
            if (candidates != null && !candidates.isEmpty()) {
                Matcher.Candidate pick = Matcher.pick(clean.title, clean.year, candidates);
                if (pick != null) {
                    Tmdb.Details details = tmdb.details(pick.id);
                    if (details != null) {
                        Matcher.Candidate next = Matcher.nextExactCandidate(clean.title, pick, candidates);
                        Tmdb.Details nextDetails = null;
                        int nextRuntime = 0;
                        if (next != null && slotMinutes > 0) {
                            nextDetails = tmdb.details(next.id);
                            if (nextDetails != null) nextRuntime = nextDetails.runtime;
                        }
                        Matcher.Candidate winner = Matcher.breakSlotTie(pick, next, slotMinutes, details.runtime, nextRuntime);
                        if (winner == pick) {
                            Film stored = saveMatch(clean.title, clean.year, pick.id, pick.score, details, now);
                            main.post(() -> callback.onResolved(clean, stored));
                            return;
                        } else if (winner == next && nextDetails != null) {
                            next.score = Matcher.score(clean.title, clean.year, next);
                            Film stored = saveMatch(clean.title, clean.year, next.id, next.score, nextDetails, now);
                            main.post(() -> callback.onResolved(clean, stored));
                            return;
                        }
                        // Vetoed with no fitting candidate -> no match
                    }
                } else {
                    // Stage 2: fetch details for top 2 candidates
                    int limit = Math.min(2, candidates.size());
                    Map<Integer, List<String>> knownTitles = new HashMap<>();
                    Map<Integer, Tmdb.Details> fetchedDetails = new HashMap<>();
                    for (int i = 0; i < limit; i++) {
                        Matcher.Candidate c = candidates.get(i);
                        Tmdb.Details d = tmdb.details(c.id);
                        if (d != null) {
                            fetchedDetails.put(c.id, d);
                            knownTitles.put(c.id, d.knownTitles);
                        }
                    }
                    Matcher.Candidate pick2 = Matcher.pickWithTitles(clean.title, clean.year, candidates, knownTitles);
                    if (pick2 != null) {
                        Matcher.Candidate other = Matcher.nextStage2Candidate(clean.title, pick2, candidates, knownTitles);
                        Tmdb.Details d1 = fetchedDetails.get(pick2.id);
                        Tmdb.Details d2 = (other != null) ? fetchedDetails.get(other.id) : null;
                        int rt1 = d1 != null ? d1.runtime : 0;
                        int rt2 = d2 != null ? d2.runtime : 0;
                        Matcher.Candidate winner = Matcher.breakSlotTie(pick2, other, slotMinutes, rt1, rt2);
                        if (winner != null) {
                            Tmdb.Details winnerDetails = fetchedDetails.get(winner.id);
                            if (winnerDetails != null) {
                                Film stored = saveMatch(clean.title, clean.year, winner.id, winner.score, winnerDetails, now);
                                main.post(() -> callback.onResolved(clean, stored));
                                return;
                            }
                        }
                    }
                }
            }

            // No confident match: record no-match (retry after 7 days)
            List<Choice> amb = new java.util.ArrayList<>();
            if (candidates != null) for (Matcher.Candidate c : Matcher.ambiguousChoices(clean.title, clean.year, candidates)) amb.add(new Choice(c.id, c.title, c.year, c.posterPath));
            Film noMatch = saveNoMatch(clean.title, clean.year, now, amb);
            main.post(() -> callback.onResolved(clean, noMatch));
        });
    }

    /**
     * T110a: resolves a clean show title against TMDB TV (cache first: matched 30 days, no-match 7 days; /search/tv then /tv/{id}).
     * Same rate limits and key handling as films (Tmdb). cb runs on the UI thread with a matched show, a no-match film, or null (no key / not reachable).
     */
    public void resolveShow(final String cleanTitle, final Callback callback) {
        io.execute(() -> {
            final TitleCleaner.Cleaned clean = new TitleCleaner.Cleaned(cleanTitle, 0);
            long now = System.currentTimeMillis();
            Film cached = get(SHOW, cleanTitle, 0);
            if (cached != null) {
                boolean fresh = "none".equals(cached.matchSource) ? !showNoMatchStale(cached, now) : (cached.fetchedAt == -1L || now - cached.fetchedAt < THIRTY_DAYS_MS);
                if (fresh) { final Film f = cached; main.post(() -> callback.onResolved(clean, f)); return; }
            }
            Tmdb tmdb = Tmdb.get(context);
            if (!tmdb.hasKey()) { final Film f = cached; main.post(() -> callback.onResolved(clean, f)); return; }
            List<Matcher.Candidate> candidates = tmdb.searchTv(cleanTitle);
            if (candidates == null) { final Film f = cached; main.post(() -> callback.onResolved(clean, f)); return; } // network trouble: no verdict stored
            Matcher.Candidate pick = candidates.isEmpty() ? null : Matcher.pickShow(cleanTitle, candidates);
            Tmdb.Details d = pick == null ? null : tmdb.tvDetails(pick.id);
            final Film out = d != null
                ? saveMatch(SHOW, cleanTitle, 0, pick.id, Matcher.score(cleanTitle, 0, pick), d, now, "tmdb")
                : saveNoMatch(SHOW, cleanTitle, 0, now, new java.util.ArrayList<Choice>());
            main.post(() -> callback.onResolved(clean, out));
        });
    }

    /** The other films this title could be (never the one already matched); empty when there is nothing to choose from. Cheap: memory first, then one small query. */
    public List<Choice> choices(Film film) {
        List<Choice> out = new java.util.ArrayList<>();
        if (film == null) return out;
        String key = providerId(film.cleanTitle, film.year);
        List<Choice> all;
        synchronized (choiceMem) { all = choiceMem.get(key); }
        if (all == null) {
            all = new java.util.ArrayList<>();
            Db db = Db.get();
            if (db != null) try {
                String sql = "SELECT c.tmdb_id, c.title, c.year, c.poster FROM film_choice c JOIN item i ON i.id = c.item_id "
                           + "WHERE i.source_id = 0 AND i.kind = 'movie' AND i.provider_id = ? ORDER BY c.ord";
                try (Cursor c = db.getReadableDatabase().rawQuery(sql, new String[]{key})) {
                    while (c.moveToNext()) all.add(new Choice(c.getInt(0), c.getString(1) == null ? "" : c.getString(1), c.getInt(2), c.getString(3) == null ? "" : c.getString(3)));
                }
            } catch (Throwable t) { Log.w(TAG, "choice read error " + t.getClass().getSimpleName()); }
            synchronized (choiceMem) { choiceMem.put(key, all); }
        }
        for (Choice c : all) if (c.tmdbId != film.tmdbId) out.add(c);
        return out;
    }

    /** The viewer picked one: fetches its details once, stores them as match_source = 'manual' (never auto-replaced). cb runs on the UI thread with the film, or null when TMDB could not be reached. */
    public void choose(final Film film, final int tmdbId, final java.util.function.Consumer<Film> cb) {
        io.execute(() -> {
            Tmdb.Details details = Tmdb.get(context).details(tmdbId);
            final Film stored = details == null ? null : saveMatch(film.cleanTitle, film.year, tmdbId, 1.0, details, System.currentTimeMillis(), "manual");
            main.post(() -> cb.accept(stored));
        });
    }

    private static void writeChoices(SQLiteDatabase d, long itemId, List<Choice> l) {
        d.execSQL("DELETE FROM film_choice WHERE item_id = ?", new Object[]{itemId});
        int i = 0;
        for (Choice c : l) d.execSQL("INSERT INTO film_choice(item_id, ord, tmdb_id, title, year, poster) VALUES(?, ?, ?, ?, ?, ?)", new Object[]{itemId, i++, c.tmdbId, c.title, c.year, c.posterPath});
    }

    /**
     * Records a manual "not this film" (match_source = 'none', never auto-retried).
     */
    public void forget(final Film film) {
        if (film == null) return;
        final String key = providerId(film.cleanTitle, film.year);
        final Film forgotten = new Film(film.cleanTitle, film.year, 0, "", 0, 0, "", 0, "", "", "", "", 0.0, "none", -1L);
        memory.put(key, forgotten);
        synchronized (choiceMem) { choiceMem.put(key, new java.util.ArrayList<Choice>()); } // nothing left to choose from

        io.execute(() -> {
            Db db = Db.get();
            if (db == null) return;
            SQLiteDatabase d = db.getWritableDatabase();
            d.beginTransaction();
            try {
                long itemId = getOrCreateItem(d, "movie", film.cleanTitle, film.year, System.currentTimeMillis());
                ContentValues v = new ContentValues();
                v.put("item_id", itemId);
                v.put("tmdb_id", 0);
                v.put("plot", "");
                v.put("rating", 0.0);
                v.put("votes", 0);
                v.put("genres", "");
                v.put("runtime", 0);
                v.put("\"cast\"", "");
                v.put("poster", "");
                v.put("backdrop", "");
                v.put("trailer", "");
                v.put("match_score", 0.0);
                v.put("match_source", "none");
                v.put("fetched_at", -1L); // -1 = never auto-retry
                d.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                writeChoices(d, itemId, new java.util.ArrayList<Choice>());
                d.setTransactionSuccessful();
            } finally {
                d.endTransaction();
            }
        });
    }

    /**
     * Wipes all source_id = 0 items, meta rows, and poster cache files.
     */
    public void wipe() {
        memory.evictAll();
        synchronized (choiceMem) { choiceMem.clear(); }
        io.execute(() -> {
            Db db = Db.get();
            if (db != null) {
                SQLiteDatabase d = db.getWritableDatabase();
                d.beginTransaction();
                try {
                    d.execSQL("DELETE FROM film_choice WHERE item_id IN (SELECT id FROM item WHERE source_id = 0)");
                    d.execSQL("DELETE FROM meta WHERE item_id IN (SELECT id FROM item WHERE source_id = 0)");
                    d.execSQL("DELETE FROM item WHERE source_id = 0");
                    d.setTransactionSuccessful();
                } finally {
                    d.endTransaction();
                }
                try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM item WHERE source_id = 0", null)) {
                    if (c.moveToFirst()) Log.i(TAG, "wipe done: film items left for source 0 = " + c.getInt(0)); // count only, no titles
                }
            }

            File postersDir = new File(context.getCacheDir(), "posters");
            File[] files = postersDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            PosterLoader.get(context).clearMemory();
        });
    }

    private Film saveMatch(String cleanTitle, int year, int tmdbId, double matchScore, Tmdb.Details details, long now) {
        return saveMatch(cleanTitle, year, tmdbId, matchScore, details, now, "tmdb");
    }

    private Film saveMatch(String cleanTitle, int year, int tmdbId, double matchScore, Tmdb.Details details, long now, String source) {
        return saveMatch("movie", cleanTitle, year, tmdbId, matchScore, details, now, source);
    }

    private Film saveMatch(String kind, String cleanTitle, int year, int tmdbId, double matchScore, Tmdb.Details details, long now, String source) {
        String key = mkey(kind, cleanTitle, year);
        Film film = new Film(cleanTitle, year, tmdbId, details.overview, details.voteAverage, details.voteCount,
                             details.genres, details.runtime, details.cast, details.posterPath,
                             details.backdropPath, details.trailer, matchScore, source, now, details.title, details.year);
        memory.put(key, film);
        if ("tmdb".equals(source)) synchronized (choiceMem) { choiceMem.put(key, new java.util.ArrayList<Choice>()); } // matched by itself: the old candidates are stale; a manual choice keeps them

        Db db = Db.get();
        if (db != null) {
            SQLiteDatabase d = db.getWritableDatabase();
            d.beginTransaction();
            try {
                long itemId = getOrCreateItem(d, kind, cleanTitle, year, now);
                ContentValues v = new ContentValues();
                v.put("item_id", itemId);
                v.put("tmdb_id", tmdbId);
                v.put("plot", details.overview);
                v.put("rating", details.voteAverage);
                v.put("votes", details.voteCount);
                v.put("genres", details.genres);
                v.put("runtime", details.runtime);
                v.put("\"cast\"", details.cast);
                v.put("poster", details.posterPath);
                v.put("backdrop", details.backdropPath);
                v.put("trailer", details.trailer);
                v.put("match_score", matchScore);
                v.put("match_source", source);
                v.put("fetched_at", now);
                d.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                if ("tmdb".equals(source)) d.execSQL("DELETE FROM film_choice WHERE item_id = ?", new Object[]{itemId});
                d.execSQL("UPDATE item SET title = ?, year = ? WHERE id = ?", new Object[]{film.title, details.year, itemId}); // TMDB title + release year
                d.setTransactionSuccessful();
            } finally {
                d.endTransaction();
            }
        }
        return film;
    }

    private Film saveNoMatch(String cleanTitle, int year, long now, List<Choice> choices) { return saveNoMatch("movie", cleanTitle, year, now, choices); }

    private Film saveNoMatch(String kind, String cleanTitle, int year, long now, List<Choice> choices) {
        String key = mkey(kind, cleanTitle, year);
        Film film = new Film(cleanTitle, year, 0, "", 0.0, 0, "", 0, "", "", "", "", 0.0, "none", now);
        memory.put(key, film);
        synchronized (choiceMem) { choiceMem.put(key, new java.util.ArrayList<>(choices)); }

        Db db = Db.get();
        if (db != null) {
            SQLiteDatabase d = db.getWritableDatabase();
            d.beginTransaction();
            try {
                long itemId = getOrCreateItem(d, kind, cleanTitle, year, now);
                ContentValues v = new ContentValues();
                v.put("item_id", itemId);
                v.put("tmdb_id", 0);
                v.put("plot", "");
                v.put("rating", 0.0);
                v.put("votes", 0);
                v.put("genres", "");
                v.put("runtime", 0);
                v.put("\"cast\"", "");
                v.put("poster", "");
                v.put("backdrop", "");
                v.put("trailer", "");
                v.put("match_score", 0.0);
                v.put("match_source", "none");
                v.put("fetched_at", now);
                d.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                writeChoices(d, itemId, choices);
                d.setTransactionSuccessful();
            } finally {
                d.endTransaction();
            }
        }
        return film;
    }

    private long getOrCreateItem(SQLiteDatabase d, String kind, String cleanTitle, int year, long now) {
        String pid = providerId(cleanTitle, year);
        try (Cursor c = d.rawQuery("SELECT id FROM item WHERE source_id = 0 AND kind = ? AND provider_id = ?", new String[]{kind, pid})) {
            if (c.moveToFirst()) return c.getLong(0);
        }
        ContentValues v = new ContentValues();
        v.put("source_id", 0);
        v.put("kind", kind);
        v.put("provider_id", pid);
        v.put("title", cleanTitle);
        v.put("clean_title", TitleCleaner.fold(cleanTitle));
        v.put("year", year > 0 ? year : 0);
        v.put("added_at", now);
        return d.insertOrThrow("item", null, v);
    }

    private void startupPurge() {
        io.execute(() -> {
            Db db = Db.get();
            if (db == null) return;
            try {
                long cutoff = System.currentTimeMillis() - ONE_EIGHTY_DAYS_MS;
                SQLiteDatabase d = db.getWritableDatabase();
                d.beginTransaction();
                try {
                    d.execSQL("DELETE FROM film_choice WHERE item_id IN (SELECT id FROM item WHERE source_id = 0 AND added_at < ?)", new Object[]{cutoff});
                    d.execSQL("DELETE FROM meta WHERE item_id IN (SELECT id FROM item WHERE source_id = 0 AND added_at < ?)", new Object[]{cutoff});
                    d.execSQL("DELETE FROM item WHERE source_id = 0 AND added_at < ?", new Object[]{cutoff});
                    d.setTransactionSuccessful();
                } finally {
                    d.endTransaction();
                }
            } catch (Throwable t) {
                Log.w(TAG, "purge error " + t.getClass().getSimpleName());
            }
        });
    }

    private void checkKeyChanged() {
        String currentKey = Tmdb.get(context).getKey();
        String savedKey = prefs.getString("last_tmdb_key", null);
        if (savedKey != null && !savedKey.equals(currentKey)) {
            wipe();
        }
        prefs.edit().putString("last_tmdb_key", currentKey).apply();
    }
}
