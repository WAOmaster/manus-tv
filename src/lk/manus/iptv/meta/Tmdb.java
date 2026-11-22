package lk.manus.iptv.meta;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import lk.manus.iptv.Config;

/**
 * TMDB API client.
 * Enforces: single worker thread, at most 4 req/s, 8 s timeouts, 429 back-off with 1 retry,
 * 401 key disqualification, and never logging keys or full URLs with keys.
 */
public final class Tmdb {
    private static final String TAG = "IPTV-tmdb";
    private static final String DEFAULT_BASE = "https://api.themoviedb.org";
    private static final int TIMEOUT_MS = 8000;
    private static final long MIN_INTERVAL_MS = 250; // max 4 requests/sec

    public static class Details {
        public final int id;
        public final String title;
        public final int year;
        public final int runtime;
        public final String genres;
        public final String overview;
        public final double voteAverage;
        public final int voteCount;
        public final String posterPath;
        public final String backdropPath;
        public final String cast;
        public final String trailer;
        public final List<String> knownTitles;

        public Details(int id, String title, int year, int runtime, String genres,
                       String overview, double voteAverage, int voteCount,
                       String posterPath, String backdropPath, String cast, String trailer,
                       List<String> knownTitles) {
            this.id = id;
            this.title = title;
            this.year = year;
            this.runtime = runtime;
            this.genres = genres != null ? genres : "";
            this.overview = overview != null ? overview : "";
            this.voteAverage = voteAverage;
            this.voteCount = voteCount;
            this.posterPath = posterPath != null ? posterPath : "";
            this.backdropPath = backdropPath != null ? backdropPath : "";
            this.cast = cast != null ? cast : "";
            this.trailer = trailer != null ? trailer : "";
            this.knownTitles = knownTitles != null ? Collections.unmodifiableList(knownTitles) : Collections.emptyList();
        }

        public Details(int id, String title, int year, int runtime, String genres,
                       String overview, double voteAverage, int voteCount,
                       String posterPath, String backdropPath, String cast, String trailer) {
            this(id, title, year, runtime, genres, overview, voteAverage, voteCount,
                 posterPath, backdropPath, cast, trailer, Collections.emptyList());
        }
    }

    private static volatile Tmdb instance;
    private static volatile String debugBaseUrl;

    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tmdb-worker");
        t.setDaemon(true);
        return t;
    });

    private long lastRequestMs = 0;
    private volatile boolean keyBad = false;
    private volatile String badKey = null;

    private Tmdb(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences("iptv", Context.MODE_PRIVATE);
    }

    public static synchronized Tmdb get(Context ctx) {
        if (instance == null) instance = new Tmdb(ctx);
        return instance;
    }

    /** Overrides the base URL in DEBUG builds (for the mock server). Ignored in release. */
    public static void setBaseUrl(String url) {
        if (Config.DEBUG) {
            debugBaseUrl = url;
        }
    }

    /** Returns active TMDB key: pref tmdb_key overrides Config.TMDB_KEY. */
    public String getKey() {
        if (prefs != null && prefs.contains("tmdb_key")) {
            String k = prefs.getString("tmdb_key", "").trim();
            if (!k.isEmpty()) return k;
        }
        return Config.TMDB_KEY.trim();
    }

    public boolean hasKey() {
        return !getKey().isEmpty();
    }

    /** A key the viewer added (pref tmdb_key). */
    public boolean hasOwnKey() {
        return prefs != null && !prefs.getString("tmdb_key", "").trim().isEmpty();
    }

    /** Saves the viewer's key. Call only after {@link #verify} said 1. */
    public void setKey(String key) { prefs.edit().putString("tmdb_key", key).apply(); }

    public void clearKey() { prefs.edit().remove("tmdb_key").apply(); }

    /**
     * One cheap request (/3/configuration) that tells if the key works. Blocking: call off the UI thread.
     * 1 = accepted, 0 = rejected (HTTP 401), -1 = could not tell (network, rate limit, server error). Never logs the key.
     */
    public int verify(String key) {
        HttpURLConnection c = null;
        try {
            String url = buildUrl("/3/configuration", "", key);
            if (url.endsWith("?")) url = url.substring(0, url.length() - 1);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            if (key.contains(".")) c.setRequestProperty("Authorization", "Bearer " + key);
            int code = c.getResponseCode();
            return code == 200 ? 1 : code == 401 ? 0 : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Searches TMDB for movies matching title and optional year.
     * Blocks on the single worker thread. Returns null on error/bad key/no key.
     */
    public List<Matcher.Candidate> search(final String title, final int year) {
        Future<List<Matcher.Candidate>> fut = worker.submit(new Callable<List<Matcher.Candidate>>() {
            @Override public List<Matcher.Candidate> call() throws Exception {
                return executeSearch(title, year);
            }
        });
        try {
            return fut.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    /**
     * Fetches movie details including credits and videos.
     * Blocks on the single worker thread. Returns null on error/bad key/no key.
     */
    public Details details(final int id) {
        Future<Details> fut = worker.submit(new Callable<Details>() {
            @Override public Details call() throws Exception {
                return executeDetails(id);
            }
        });
        try {
            return fut.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    /** T110a: searches TMDB TV shows by name (same worker, limits and key handling as films). null on error/bad key/no key. */
    public List<Matcher.Candidate> searchTv(final String title) {
        return onWorker(new Callable<List<Matcher.Candidate>>() {
            @Override public List<Matcher.Candidate> call() throws Exception { return executeSearchTv(title); }
        });
    }

    /** T110a: /tv/{id}: poster, first-air year, rating, votes, genres; the first network goes in cast. null on error/bad key/no key. */
    public Details tvDetails(final int id) {
        return onWorker(new Callable<Details>() {
            @Override public Details call() throws Exception { return executeTvDetails(id); }
        });
    }

    private <T> T onWorker(Callable<T> job) {
        try {
            return worker.submit(job).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    private List<Matcher.Candidate> executeSearchTv(String title) {
        String key = getKey();
        if (key.isEmpty() || isKeyMarkedBad(key)) return null;
        try {
            String url = buildUrl("/3/search/tv", "query=" + URLEncoder.encode(title, "UTF-8") + "&include_adult=false", key);
            return fetchJson(url, key, false, new JsonParser<List<Matcher.Candidate>>() {
                @Override public List<Matcher.Candidate> parse(JsonReader r) throws IOException { return parseCandidates(r); }
            });
        } catch (Exception e) {
            return null;
        }
    }

    private Details executeTvDetails(int id) {
        String key = getKey();
        if (key.isEmpty() || isKeyMarkedBad(key)) return null;
        try {
            String url = buildUrl("/3/tv/" + id, "language=en-US", key);
            return fetchJson(url, key, false, new JsonParser<Details>() {
                @Override public Details parse(JsonReader r) throws IOException { return parseTvDetails(r); }
            });
        } catch (Exception e) {
            return null;
        }
    }

    private List<Matcher.Candidate> executeSearch(String title, int year) {
        String key = getKey();
        if (key.isEmpty() || isKeyMarkedBad(key)) return null;

        try {
            StringBuilder q = new StringBuilder();
            q.append("query=").append(URLEncoder.encode(title, "UTF-8"));
            if (year > 0) q.append("&year=").append(year);
            q.append("&include_adult=false");

            String url = buildUrl("/3/search/movie", q.toString(), key);
            return fetchJson(url, key, false, new JsonParser<List<Matcher.Candidate>>() {
                @Override public List<Matcher.Candidate> parse(JsonReader r) throws IOException {
                    return parseCandidates(r);
                }
            });
        } catch (Exception e) {
            return null;
        }
    }

    private Details executeDetails(int id) {
        String key = getKey();
        if (key.isEmpty() || isKeyMarkedBad(key)) return null;

        try {
            String q = "append_to_response=credits,videos,translations,alternative_titles";
            String url = buildUrl("/3/movie/" + id, q, key);
            return fetchJson(url, key, false, new JsonParser<Details>() {
                @Override public Details parse(JsonReader r) throws IOException {
                    return parseMovieDetails(r);
                }
            });
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isKeyMarkedBad(String key) {
        if (badKey == null && prefs != null) {
            String savedBad = prefs.getString("tmdb_bad_key", null);
            if (savedBad != null) {
                badKey = savedBad;
                keyBad = true;
            }
        }
        if (!keyBad) return false;
        if (!lk.manus.iptv.data.Keys.of(key).equals(badKey)) { // only a hash of the rejected key is kept
            keyBad = false;
            badKey = null;
            if (prefs != null) prefs.edit().remove("tmdb_bad_key").apply();
            return false;
        }
        return true;
    }

    private String getBaseUrl() {
        if (Config.DEBUG) {
            if (debugBaseUrl != null && !debugBaseUrl.isEmpty()) return debugBaseUrl;
            if (prefs != null && prefs.contains("tmdb_base")) {
                String p = prefs.getString("tmdb_base", "").trim();
                if (!p.isEmpty()) return p;
            }
        }
        return DEFAULT_BASE;
    }

    private String buildUrl(String path, String queryParams, String key) throws Exception {
        String base = getBaseUrl();
        int qIdx = base.indexOf('?');
        String root = qIdx >= 0 ? base.substring(0, qIdx) : base;
        String baseQuery = qIdx >= 0 ? base.substring(qIdx + 1) : "";

        if (root.endsWith("/")) root = root.substring(0, root.length() - 1);

        StringBuilder sb = new StringBuilder();
        sb.append(root).append(path).append("?");
        if (!baseQuery.isEmpty()) sb.append(baseQuery).append("&");
        sb.append(queryParams);

        // v4 bearer token (contains dot) is sent in Authorization header; v3 api_key sent in query
        if (!key.contains(".")) {
            sb.append("&api_key=").append(URLEncoder.encode(key, "UTF-8"));
        }
        return sb.toString();
    }

    private interface JsonParser<T> {
        T parse(JsonReader reader) throws IOException;
    }

    private <T> T fetchJson(String urlStr, String key, boolean isRetry, JsonParser<T> parser) {
        // Enforce max 4 requests/sec rate limit
        long now = System.currentTimeMillis();
        long diff = now - lastRequestMs;
        if (diff < MIN_INTERVAL_MS) {
            try { Thread.sleep(MIN_INTERVAL_MS - diff); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        lastRequestMs = System.currentTimeMillis();

        HttpURLConnection c = null;
        try {
            URL u = new URL(urlStr);
            // Log host + path only; never log the key or full URL with query
            Log.d(TAG, "request " + u.getHost() + u.getPath());

            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");

            if (key.contains(".")) {
                c.setRequestProperty("Authorization", "Bearer " + key);
            }

            int code = c.getResponseCode();
            if (code == 401) {
                keyBad = true;
                badKey = lk.manus.iptv.data.Keys.of(key);
                if (prefs != null) prefs.edit().putString("tmdb_bad_key", badKey).apply();
                Log.w(TAG, "tmdb key rejected (HTTP 401) on " + u.getHost() + u.getPath());
                return null;
            }

            if (code == 429) {
                Log.w(TAG, "tmdb rate limited (HTTP 429) on " + u.getHost() + u.getPath());
                if (!isRetry) {
                    int retrySec = 2;
                    String retryHeader = c.getHeaderField("Retry-After");
                    if (retryHeader != null) {
                        try { retrySec = Integer.parseInt(retryHeader.trim()); } catch (Exception ignored) {}
                    }
                    if (retrySec <= 0) retrySec = 2;
                    try { Thread.sleep(retrySec * 1000L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    return fetchJson(urlStr, key, true, parser);
                }
                return null;
            }

            if (code != 200) {
                Log.w(TAG, "tmdb error " + code + " on " + u.getHost() + u.getPath());
                return null;
            }

            try (InputStream in = new BufferedInputStream(c.getInputStream(), 16384);
                 JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return parser.parse(r);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static List<Matcher.Candidate> parseCandidates(JsonReader r) throws IOException {
        List<Matcher.Candidate> list = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            String name = r.nextName();
            if ("results".equals(name) && r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) {
                    Matcher.Candidate c = parseSingleCandidate(r);
                    if (c != null) list.add(c);
                }
                r.endArray();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        return list;
    }

    private static Matcher.Candidate parseSingleCandidate(JsonReader r) throws IOException {
        int id = 0, voteCount = 0, year = 0;
        double popularity = 0.0, voteAverage = 0.0;
        String title = "", originalTitle = "", releaseDate = "", posterPath = "", overview = "";

        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if ("id".equals(k) && r.peek() == JsonToken.NUMBER) id = r.nextInt();
            else if (("title".equals(k) || "name".equals(k)) && r.peek() == JsonToken.STRING) title = r.nextString(); // "name" = TV
            else if (("original_title".equals(k) || "original_name".equals(k)) && r.peek() == JsonToken.STRING) originalTitle = r.nextString();
            else if (("release_date".equals(k) || "first_air_date".equals(k)) && r.peek() == JsonToken.STRING) releaseDate = r.nextString();
            else if ("popularity".equals(k) && r.peek() == JsonToken.NUMBER) popularity = r.nextDouble();
            else if ("vote_count".equals(k) && r.peek() == JsonToken.NUMBER) voteCount = r.nextInt();
            else if ("vote_average".equals(k) && r.peek() == JsonToken.NUMBER) voteAverage = r.nextDouble();
            else if ("poster_path".equals(k) && r.peek() == JsonToken.STRING) posterPath = r.nextString();
            else if ("overview".equals(k) && r.peek() == JsonToken.STRING) overview = r.nextString();
            else r.skipValue();
        }
        r.endObject();

        if (id <= 0 || title.isEmpty()) return null;
        if (releaseDate.length() >= 4) {
            try { year = Integer.parseInt(releaseDate.substring(0, 4)); } catch (Exception ignored) {}
        }
        return new Matcher.Candidate(id, title, originalTitle, year, popularity, voteCount, voteAverage, posterPath, overview);
    }

    private static Details parseMovieDetails(JsonReader r) throws IOException {
        int id = 0, year = 0, runtime = 0, voteCount = 0;
        double voteAverage = 0.0;
        String title = "", releaseDate = "", overview = "", posterPath = "", backdropPath = "";
        String genres = "", cast = "", trailer = "";
        List<String> knownTitles = new ArrayList<>();

        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if ("id".equals(k) && r.peek() == JsonToken.NUMBER) id = r.nextInt();
            else if ("title".equals(k) && r.peek() == JsonToken.STRING) title = r.nextString();
            else if ("release_date".equals(k) && r.peek() == JsonToken.STRING) releaseDate = r.nextString();
            else if ("runtime".equals(k) && r.peek() == JsonToken.NUMBER) runtime = r.nextInt();
            else if ("overview".equals(k) && r.peek() == JsonToken.STRING) overview = r.nextString();
            else if ("vote_average".equals(k) && r.peek() == JsonToken.NUMBER) voteAverage = r.nextDouble();
            else if ("vote_count".equals(k) && r.peek() == JsonToken.NUMBER) voteCount = r.nextInt();
            else if ("poster_path".equals(k) && r.peek() == JsonToken.STRING) posterPath = r.nextString();
            else if ("backdrop_path".equals(k) && r.peek() == JsonToken.STRING) backdropPath = r.nextString();
            else if ("genres".equals(k) && r.peek() == JsonToken.BEGIN_ARRAY) genres = parseGenres(r);
            else if ("credits".equals(k) && r.peek() == JsonToken.BEGIN_OBJECT) cast = parseCast(r);
            else if ("videos".equals(k) && r.peek() == JsonToken.BEGIN_OBJECT) trailer = parseTrailer(r);
            else if ("translations".equals(k) && r.peek() == JsonToken.BEGIN_OBJECT) knownTitles.addAll(parseTranslations(r));
            else if ("alternative_titles".equals(k) && r.peek() == JsonToken.BEGIN_OBJECT) knownTitles.addAll(parseAlternativeTitles(r));
            else r.skipValue();
        }
        r.endObject();

        if (releaseDate.length() >= 4) {
            try { year = Integer.parseInt(releaseDate.substring(0, 4)); } catch (Exception ignored) {}
        }
        return new Details(id, title, year, runtime, genres, overview, voteAverage, voteCount, posterPath, backdropPath, cast, trailer, knownTitles);
    }

    private static Details parseTvDetails(JsonReader r) throws IOException {
        int id = 0, year = 0, voteCount = 0;
        double voteAverage = 0.0;
        String name = "", first = "", overview = "", poster = "", backdrop = "", genres = "", network = "";
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if ("id".equals(k) && r.peek() == JsonToken.NUMBER) id = r.nextInt();
            else if ("name".equals(k) && r.peek() == JsonToken.STRING) name = r.nextString();
            else if ("first_air_date".equals(k) && r.peek() == JsonToken.STRING) first = r.nextString();
            else if ("overview".equals(k) && r.peek() == JsonToken.STRING) overview = r.nextString();
            else if ("vote_average".equals(k) && r.peek() == JsonToken.NUMBER) voteAverage = r.nextDouble();
            else if ("vote_count".equals(k) && r.peek() == JsonToken.NUMBER) voteCount = r.nextInt();
            else if ("poster_path".equals(k) && r.peek() == JsonToken.STRING) poster = r.nextString();
            else if ("backdrop_path".equals(k) && r.peek() == JsonToken.STRING) backdrop = r.nextString();
            else if ("genres".equals(k) && r.peek() == JsonToken.BEGIN_ARRAY) genres = parseGenres(r);
            else if ("networks".equals(k) && r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) {
                    r.beginObject();
                    while (r.hasNext()) {
                        if ("name".equals(r.nextName()) && r.peek() == JsonToken.STRING) { String n = r.nextString(); if (network.isEmpty()) network = n; }
                        else r.skipValue();
                    }
                    r.endObject();
                }
                r.endArray();
            }
            else r.skipValue();
        }
        r.endObject();
        if (first.length() >= 4) {
            try { year = Integer.parseInt(first.substring(0, 4)); } catch (Exception ignored) {}
        }
        return new Details(id, name, year, 0, genres, overview, voteAverage, voteCount, poster, backdrop, network, "");
    }

    private static List<String> parseTranslations(JsonReader r) throws IOException {
        List<String> list = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if ("translations".equals(k) && r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) {
                    r.beginObject();
                    while (r.hasNext()) {
                        String f = r.nextName();
                        if ("data".equals(f) && r.peek() == JsonToken.BEGIN_OBJECT) {
                            r.beginObject();
                            while (r.hasNext()) {
                                String df = r.nextName();
                                if ("title".equals(df) && r.peek() == JsonToken.STRING) {
                                    String t = r.nextString().trim();
                                    if (!t.isEmpty()) list.add(t);
                                } else {
                                    r.skipValue();
                                }
                            }
                            r.endObject();
                        } else {
                            r.skipValue();
                        }
                    }
                    r.endObject();
                }
                r.endArray();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        return list;
    }

    private static List<String> parseAlternativeTitles(JsonReader r) throws IOException {
        List<String> list = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if ("titles".equals(k) && r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) {
                    r.beginObject();
                    while (r.hasNext()) {
                        String f = r.nextName();
                        if ("title".equals(f) && r.peek() == JsonToken.STRING) {
                            String t = r.nextString().trim();
                            if (!t.isEmpty()) list.add(t);
                        } else {
                            r.skipValue();
                        }
                    }
                    r.endObject();
                }
                r.endArray();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        return list;
    }

    private static String parseGenres(JsonReader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        r.beginArray();
        while (r.hasNext()) {
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                if ("name".equals(k) && r.peek() == JsonToken.STRING) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(r.nextString());
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        }
        r.endArray();
        return sb.toString();
    }

    private static String parseCast(JsonReader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if ("cast".equals(k) && r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) {
                    r.beginObject();
                    String actorName = null;
                    while (r.hasNext()) {
                        String f = r.nextName();
                        if ("name".equals(f) && r.peek() == JsonToken.STRING) actorName = r.nextString();
                        else r.skipValue();
                    }
                    r.endObject();
                    if (actorName != null && count < 5) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(actorName);
                        count++;
                    }
                }
                r.endArray();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        return sb.toString();
    }

    private static String parseTrailer(JsonReader r) throws IOException {
        String key = "";
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if ("results".equals(k) && r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) {
                    r.beginObject();
                    String site = "", vKey = "", type = "";
                    while (r.hasNext()) {
                        String f = r.nextName();
                        if ("site".equals(f) && r.peek() == JsonToken.STRING) site = r.nextString();
                        else if ("key".equals(f) && r.peek() == JsonToken.STRING) vKey = r.nextString();
                        else if ("type".equals(f) && r.peek() == JsonToken.STRING) type = r.nextString();
                        else r.skipValue();
                    }
                    r.endObject();
                    if (key.isEmpty() && "YouTube".equalsIgnoreCase(site)) {
                        if ("Trailer".equalsIgnoreCase(type) || key.isEmpty()) {
                            key = vKey;
                        }
                    }
                }
                r.endArray();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        return key;
    }
}
