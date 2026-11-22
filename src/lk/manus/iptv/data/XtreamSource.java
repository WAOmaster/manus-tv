package lk.manus.iptv.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteStatement;
import android.os.Process;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lk.manus.iptv.Config;
import lk.manus.iptv.meta.TitleCleaner;

/**
 * Xtream Codes source management, account verification, and background film sync.
 * Keystore encrypted credentials; stream templates contain no login.
 */
public final class XtreamSource {

    private XtreamSource() {}

    public static final class Account {
        public static final int OK = 0;
        public static final int WRONG_LOGIN = 1;
        public static final int EXPIRED = 2;
        public static final int UNREACHABLE = 3;
        public static final int BAD_ADDRESS = 4;

        public final int state;
        public final String message;

        public Account(int state, String message) {
            this.state = state;
            this.message = message;
        }

        public String stateName() {
            switch (state) {
                case OK: return "OK";
                case WRONG_LOGIN: return "WRONG_LOGIN";
                case EXPIRED: return "EXPIRED";
                case UNREACHABLE: return "UNREACHABLE";
                case BAD_ADDRESS: return "BAD_ADDRESS";
                default: return "UNKNOWN";
            }
        }
    }

    public static final class VodInfo {
        public final String plot;
        public final String genres;
        public final int runtime;
        public final String cast;
        public final String backdrop;
        public final long tmdbId;
        public final double rating;

        public VodInfo(String plot, String genres, int runtime, String cast, String backdrop, long tmdbId, double rating) {
            this.plot = plot != null ? plot : "";
            this.genres = genres != null ? genres : "";
            this.runtime = runtime;
            this.cast = cast != null ? cast : "";
            this.backdrop = backdrop != null ? backdrop : "";
            this.tmdbId = tmdbId;
            this.rating = rating;
        }
    }

    public interface VodInfoCallback {
        void onInfo(VodInfo info);
    }

    public interface Listener {
        void onProgress(int films);
        void onDone(int films, String errorOrNull);
    }

    static final class ExistingItem {
        final long id;
        final long addedAt;
        final String title;
        final String groupName;

        ExistingItem(long id, long addedAt, String title, String groupName) {
            this.id = id;
            this.addedAt = addedAt;
            this.title = title != null ? title : "";
            this.groupName = groupName != null ? groupName : "";
        }
    }

    // ---- Pure Java helpers (for testing on host JVM) ----

    /**
     * Normalises base address: trim, strip trailing slashes, strip trailing /player_api.php.
     * Must be http(s)://host[:port] with no path/query/fragment. Returns null if invalid.
     */
    public static String normaliseAddress(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1).trim();
        if (s.toLowerCase(Locale.ROOT).endsWith("/player_api.php")) {
            s = s.substring(0, s.length() - "/player_api.php".length()).trim();
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1).trim();
        }
        try {
            URI u = new URI(s);
            String scheme = u.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            if (u.getHost() == null || u.getHost().isEmpty()) {
                return null;
            }
            String p = u.getPath();
            if (p != null && !p.isEmpty() && !p.equals("/")) {
                return null;
            }
            if (u.getQuery() != null || u.getFragment() != null) {
                return null;
            }
            int port = u.getPort();
            return scheme.toLowerCase(Locale.ROOT) + "://" + u.getHost().toLowerCase(Locale.ROOT) + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return null;
        }
    }

    public static String urlEncode(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    public static String buildTemplate(String streamId, String ext) {
        String e = (ext != null && !ext.trim().isEmpty()) ? ext.trim().toLowerCase(Locale.ROOT) : "";
        if (e.startsWith(".")) e = e.substring(1);
        return "{BASE}/movie/{USER}/{PASS}/" + streamId + (e.isEmpty() ? "" : "." + e);
    }

    public static String joinUrl(String template, String base, String user, String pass) {
        if (template == null || base == null || user == null || pass == null) return null;
        String normBase = normaliseAddress(base);
        if (normBase == null) normBase = base;
        return template.replace("{BASE}", normBase)
                .replace("{USER}", urlEncode(user))
                .replace("{PASS}", urlEncode(pass));
    }

    public static Account mapAccount(int auth, String status) {
        if (auth == 0) return new Account(Account.WRONG_LOGIN, "That login was not accepted.");
        if (status == null || !status.equalsIgnoreCase("Active")) return new Account(Account.EXPIRED, "This account has expired.");
        return new Account(Account.OK, "OK");
    }

    public static Account parseAccountResponse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new Account(Account.UNREACHABLE, "Can't reach that server.");
        }
        int auth = 0;
        Matcher ma = Pattern.compile("\"auth\"\\s*:\\s*[\"]?(\\d+)[\"]?").matcher(json);
        if (ma.find()) {
            try { auth = Integer.parseInt(ma.group(1)); } catch (Exception ignored) {}
        } else {
            return new Account(Account.WRONG_LOGIN, "That login was not accepted.");
        }
        if (auth == 0) {
            return new Account(Account.WRONG_LOGIN, "That login was not accepted.");
        }
        String status = null;
        Matcher ms = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (ms.find()) {
            status = ms.group(1);
        }
        return mapAccount(auth, status);
    }

    // ---- Android & Network operations ----

    /**
     * Checks account with one API call: GET {base}/player_api.php?username=&password=
     * Timeouts 10s connect / 15s read; response capped at 256 KB.
     * Never logs full URLs, passwords, or raw exceptions containing them.
     */
    public static Account verify(String base, String user, String pass) {
        String normBase = normaliseAddress(base);
        if (normBase == null) {
            return new Account(Account.BAD_ADDRESS, "That address doesn't look right.");
        }
        HttpURLConnection conn = null;
        try {
            String u = normBase + "/player_api.php?username=" + urlEncode(user != null ? user : "")
                    + "&password=" + urlEncode(pass != null ? pass : "");
            URL url = new URL(u);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "ManusTV");

            int code = conn.getResponseCode();
            if (code >= 400) {
                return new Account(Account.UNREACHABLE, "Can't reach that server.");
            }

            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int cap = 256 * 1024;
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                int toWrite = Math.min(n, cap - total);
                if (toWrite > 0) {
                    bos.write(buf, 0, toWrite);
                    total += toWrite;
                }
                if (total >= cap) break;
            }
            in.close();
            return parseAccountResponse(bos.toString("UTF-8"));
        } catch (Exception e) {
            return new Account(Account.UNREACHABLE, "Can't reach that server.");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static boolean validCredentials(String user, String pass) {
        if (user == null || pass == null) return false;
        if (user.indexOf('\n') >= 0 || user.indexOf('\r') >= 0) return false;
        if (pass.indexOf('\n') >= 0 || pass.indexOf('\r') >= 0) return false;
        return true;
    }

    public static final class CheapTitle {
        public final String cleanTitle;
        public final int year;
        public CheapTitle(String cleanTitle, int year) {
            this.cleanTitle = cleanTitle;
            this.year = year;
        }
    }

    public static CheapTitle cheapClean(String raw) {
        if (raw == null) return new CheapTitle("", 0);
        String s = raw.trim();
        if (s.isEmpty()) return new CheapTitle("", 0);

        StringBuilder sb = new StringBuilder(s.length());
        boolean lastSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastSpace) {
                    sb.append(' ');
                    lastSpace = true;
                }
            } else {
                sb.append(c);
                lastSpace = false;
            }
        }
        s = sb.toString().trim().toLowerCase(Locale.ROOT);

        int year = 0;
        if (s.endsWith(")") && s.length() >= 7) {
            int open = s.lastIndexOf('(');
            if (open > 0 && open == s.length() - 6) {
                String inside = s.substring(open + 1, s.length() - 1);
                if (inside.length() == 4) {
                    char c0 = inside.charAt(0);
                    char c1 = inside.charAt(1);
                    char c2 = inside.charAt(2);
                    char c3 = inside.charAt(3);
                    if (c0 >= '0' && c0 <= '9' && c1 >= '0' && c1 <= '9' && c2 >= '0' && c2 <= '9' && c3 >= '0' && c3 <= '9') {
                        try {
                            int y = Integer.parseInt(inside);
                            if (y >= 1888 && y <= 2035) {
                                year = y;
                                s = s.substring(0, open).trim();
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        return new CheapTitle(s, year);
    }

    public static String unescapeJson(String s) {
        if (s == null || s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                if (next == 'n') b.append('\n');
                else if (next == 'r') b.append('\r');
                else if (next == 't') b.append('\t');
                else if (next == 'b') b.append('\b');
                else if (next == 'f') b.append('\f');
                else if (next == 'u' && i + 4 < s.length()) {
                    try {
                        b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    } catch (Exception e) {
                        b.append(next);
                    }
                } else {
                    b.append(next);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    public static VodInfo parseVodInfo(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        String plot = "";
        String genres = "";
        int runtime = 0;
        String cast = "";
        String backdrop = "";
        long tmdbId = 0;
        double rating = 0.0;

        Matcher mp = Pattern.compile("\"plot\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (mp.find()) plot = unescapeJson(mp.group(1));

        Matcher mg = Pattern.compile("\"(?:genre|genres)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (mg.find()) genres = unescapeJson(mg.group(1));

        Matcher mc = Pattern.compile("\"cast\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (mc.find()) cast = unescapeJson(mc.group(1));

        Matcher mb = Pattern.compile("\"backdrop_path\"\\s*:\\s*\\[\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (mb.find()) {
            backdrop = unescapeJson(mb.group(1));
        } else {
            Matcher mbs = Pattern.compile("\"backdrop_path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
            if (mbs.find()) backdrop = unescapeJson(mbs.group(1));
        }

        Matcher mt = Pattern.compile("\"tmdb(?:_id)?\"\\s*:\\s*[\"]?(\\d+)[\"]?").matcher(json);
        if (mt.find()) {
            try { tmdbId = Long.parseLong(mt.group(1)); } catch (Exception ignored) {}
        }

        Matcher md = Pattern.compile("\"duration_secs\"\\s*:\\s*[\"]?(\\d+)[\"]?").matcher(json);
        if (md.find()) {
            try {
                int secs = Integer.parseInt(md.group(1));
                if (secs > 0) runtime = (secs + 30) / 60;
            } catch (Exception ignored) {}
        }
        if (runtime == 0) {
            Matcher mdu = Pattern.compile("\"duration\"\\s*:\\s*\"(\\d+):(\\d+):(\\d+)\"").matcher(json);
            if (mdu.find()) {
                try {
                    int h = Integer.parseInt(mdu.group(1));
                    int m = Integer.parseInt(mdu.group(2));
                    runtime = h * 60 + m;
                } catch (Exception ignored) {}
            }
        }

        Matcher mr = Pattern.compile("\"rating(?:_5based)?\"\\s*:\\s*[\"]?([0-9.]+)[\"]?").matcher(json);
        if (mr.find()) {
            try { rating = Double.parseDouble(mr.group(1)); } catch (Exception ignored) {}
        }

        return new VodInfo(plot, genres, runtime, cast, backdrop, tmdbId, rating);
    }

    /**
     * Fetches VOD info from provider once and caches into meta table:
     * plot, genres, runtime, cast, backdrop, tmdb_id, rating.
     * Uses sealed Keystore credentials; never logs logins or full URLs.
     */
    public static void vodInfo(final Db db, final long itemId, final VodInfoCallback cb) {
        if (Config.PLAY || db == null) {
            if (cb != null) cb.onInfo(null);
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                SQLiteDatabase d = db.getReadableDatabase();
                String plot = null, genres = null, cast = null, backdrop = null;
                int runtime = 0;
                long tmdbId = 0;
                double rating = 0.0;
                long fetchedAt = 0;

                Cursor cm = d.rawQuery(
                        "SELECT plot, genres, runtime, \"cast\", backdrop, tmdb_id, rating, fetched_at FROM meta WHERE item_id = ?",
                        new String[]{String.valueOf(itemId)});
                try {
                    if (cm.moveToFirst()) {
                        plot = cm.getString(0);
                        genres = cm.getString(1);
                        runtime = cm.getInt(2);
                        cast = cm.getString(3);
                        backdrop = cm.getString(4);
                        if (!cm.isNull(5)) tmdbId = cm.getLong(5);
                        if (!cm.isNull(6)) rating = cm.getDouble(6);
                        fetchedAt = cm.getLong(7);
                    }
                } finally {
                    cm.close();
                }

                if (fetchedAt > 0) {
                    final VodInfo info = new VodInfo(plot, genres, runtime, cast, backdrop, tmdbId, rating);
                    if (cb != null) cb.onInfo(info);
                    return;
                }

                String providerId = null, address = null, secretRef = null;
                Cursor ci = d.rawQuery(
                        "SELECT i.provider_id, s.address, s.secret_ref FROM item i JOIN source s ON s.id = i.source_id WHERE i.id = ?",
                        new String[]{String.valueOf(itemId)});
                try {
                    if (ci.moveToFirst()) {
                        providerId = ci.getString(0);
                        address = ci.getString(1);
                        secretRef = ci.getString(2);
                    }
                } finally {
                    ci.close();
                }

                if (providerId == null || address == null || secretRef == null) {
                    if (cb != null) cb.onInfo(null);
                    return;
                }

                String creds = Secrets.open(secretRef);
                if (creds == null) {
                    if (cb != null) cb.onInfo(null);
                    return;
                }
                String[] parts = creds.split("\n", 2);
                if (parts.length < 2) {
                    if (cb != null) cb.onInfo(null);
                    return;
                }
                String user = parts[0], pass = parts[1];

                HttpURLConnection conn = null;
                try {
                    String u = address + "/player_api.php?username=" + urlEncode(user)
                            + "&password=" + urlEncode(pass) + "&action=get_vod_info&vod_id=" + urlEncode(providerId);
                    URL url = new URL(u);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", "ManusTV");
                    if (conn.getResponseCode() < 400) {
                        InputStream in = conn.getInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            bos.write(buf, 0, n);
                        }
                        in.close();
                        String json = bos.toString("UTF-8");
                        VodInfo parsed = parseVodInfo(json);
                        if (parsed != null) {
                            SQLiteDatabase w = db.getWritableDatabase();
                            w.execSQL(
                                    "UPDATE meta SET " +
                                    "  plot = CASE WHEN ? != '' THEN ? ELSE plot END, " +
                                    "  genres = CASE WHEN ? != '' THEN ? ELSE genres END, " +
                                    "  runtime = CASE WHEN ? > 0 THEN ? ELSE runtime END, " +
                                    "  \"cast\" = CASE WHEN ? != '' THEN ? ELSE \"cast\" END, " +
                                    "  backdrop = CASE WHEN ? != '' THEN ? ELSE backdrop END, " +
                                    "  tmdb_id = CASE WHEN ? > 0 THEN ? ELSE tmdb_id END, " +
                                    "  rating = CASE WHEN ? > 0 THEN ? ELSE rating END, " +
                                    "  fetched_at = ? " +
                                    "WHERE item_id = ?",
                                    new Object[]{
                                        parsed.plot, parsed.plot,
                                        parsed.genres, parsed.genres,
                                        parsed.runtime, parsed.runtime,
                                        parsed.cast, parsed.cast,
                                        parsed.backdrop, parsed.backdrop,
                                        parsed.tmdbId, parsed.tmdbId,
                                        parsed.rating, parsed.rating,
                                        System.currentTimeMillis(),
                                        itemId
                                    });

                            Cursor cr = w.rawQuery(
                                    "SELECT plot, genres, runtime, \"cast\", backdrop, tmdb_id, rating FROM meta WHERE item_id = ?",
                                    new String[]{String.valueOf(itemId)});
                            try {
                                if (cr.moveToFirst()) {
                                    plot = cr.getString(0);
                                    genres = cr.getString(1);
                                    runtime = cr.getInt(2);
                                    cast = cr.getString(3);
                                    backdrop = cr.getString(4);
                                    if (!cr.isNull(5)) tmdbId = cr.getLong(5);
                                    if (!cr.isNull(6)) rating = cr.getDouble(6);
                                }
                            } finally {
                                cr.close();
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w("IPTV", "vodInfo error: " + t.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }

                final VodInfo res = new VodInfo(plot, genres, runtime, cast, backdrop, tmdbId, rating);
                if (cb != null) cb.onInfo(res);
            }
        }, "vod-info-" + itemId).start();
    }

    /**
     * Inserts a source row (kind='xtream', address=base, secret_ref=sealed(user + "\n" + pass)).
     * Returns its id, or -1 if Config.PLAY or invalid inputs.
     */
    public static long add(Db db, String name, String base, String user, String pass) {
        if (Config.PLAY) return -1;
        if (db == null) return -1;
        String normBase = normaliseAddress(base);
        if (normBase == null) return -1;
        if (!validCredentials(user, pass)) return -1;
        String sealed = Secrets.seal(user + "\n" + pass);
        if (sealed == null) return -1;
        ContentValues v = new ContentValues();
        v.put("kind", "xtream");
        v.put("name", name != null ? name : "");
        v.put("address", normBase);
        v.put("secret_ref", sealed);
        v.put("last_sync", 0L);
        return db.getWritableDatabase().insert("source", null, v);
    }

    /**
     * Loads the stream template + the source's sealed secret, returns the real URL (login URL-encoded)
     * or null if anything is missing. This is the only place the login is joined. Never logged, never stored.
     */
    public static String playUrl(Db db, long itemId) {
        if (db == null) return null;
        SQLiteDatabase d = db.getReadableDatabase();
        Cursor c = d.rawQuery(
                "SELECT s.url_template, src.address, src.secret_ref " +
                "FROM stream s " +
                "JOIN item i ON i.id = s.item_id " +
                "JOIN source src ON src.id = (CASE WHEN i.source_id > 0 THEN i.source_id ELSE (SELECT p.source_id FROM item p WHERE p.id = i.parent_id) END) " +
                "WHERE s.item_id = ? AND s.\"order\" = 0",
                new String[]{String.valueOf(itemId)});
        String template = null, address = null, secretRef = null;
        try {
            if (c.moveToFirst()) {
                template = c.getString(0);
                address = c.getString(1);
                secretRef = c.getString(2);
            }
        } finally {
            c.close();
        }
        if (template == null || address == null || secretRef == null) return null;
        String creds = Secrets.open(secretRef);
        if (creds == null) return null;
        String[] parts = creds.split("\n", 2);
        if (parts.length < 2) return null;
        return joinUrl(template, address, parts[0], parts[1]);
    }

    public static int seriesCount(Db db, long sourceId) {
        return SeriesSource.seriesCount(db, sourceId);
    }

    public static final class SourceInfo {
        public final long id;
        public final String name;
        public final String address;
        public final long lastSync;
        public final int filmCount;

        public SourceInfo(long id, String name, String address, long lastSync, int filmCount) {
            this.id = id;
            this.name = (name != null && !name.trim().isEmpty()) ? name.trim() : (address != null ? Redact.host(address) : "Xtream");
            this.address = address != null ? address : "";
            this.lastSync = lastSync;
            this.filmCount = filmCount;
        }
    }

    public static List<SourceInfo> list(Db db) {
        List<SourceInfo> res = new ArrayList<>();
        if (Config.PLAY || db == null) return res;
        try {
            SQLiteDatabase d = db.getReadableDatabase();
            Cursor c = d.rawQuery(
                    "SELECT s.id, s.name, s.address, s.last_sync, " +
                    "  (SELECT count(*) FROM item i WHERE i.source_id = s.id AND i.kind = 'movie') " +
                    "FROM source s WHERE s.kind = 'xtream' ORDER BY s.id ASC",
                    null);
            try {
                while (c.moveToNext()) {
                    res.add(new SourceInfo(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4)));
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Log.w("IPTV", "XtreamSource.list error: " + t.getMessage());
        }
        return res;
    }

    public static SourceInfo get(Db db, long sourceId) {
        if (Config.PLAY || db == null) return null;
        try {
            SQLiteDatabase d = db.getReadableDatabase();
            Cursor c = d.rawQuery(
                    "SELECT s.id, s.name, s.address, s.last_sync, " +
                    "  (SELECT count(*) FROM item i WHERE i.source_id = s.id AND i.kind = 'movie') " +
                    "FROM source s WHERE s.id = ? AND s.kind = 'xtream'",
                    new String[]{String.valueOf(sourceId)});
            try {
                if (c.moveToFirst()) {
                    return new SourceInfo(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4));
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Log.w("IPTV", "XtreamSource.get error: " + t.getMessage());
        }
        return null;
    }

    public static boolean hasProgress(Db db, long sourceId) {
        if (db == null) return false;
        try {
            SQLiteDatabase d = db.getReadableDatabase();
            Cursor c = d.rawQuery(
                    "SELECT 1 FROM progress p JOIN item i ON i.id = p.item_id WHERE i.source_id = ? LIMIT 1",
                    new String[]{String.valueOf(sourceId)});
            try {
                return c.moveToFirst();
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    public static void deleteProgress(Db db, long sourceId) {
        if (db == null) return;
        try {
            SQLiteDatabase d = db.getWritableDatabase();
            d.execSQL("DELETE FROM progress WHERE item_id IN (SELECT id FROM item WHERE source_id = ?)", new Object[]{sourceId});
        } catch (Throwable t) {
            Log.w("IPTV", "XtreamSource.deleteProgress: " + t.getMessage());
        }
    }

    /**
     * Deletes the source and all its item/stream/meta rows (+ film_choice, play) in one transaction.
     * If deleteProgress is true, progress for its films is also deleted in the same transaction.
     */
    public static void remove(Db db, long sourceId) {
        remove(db, sourceId, false);
    }

    public static void remove(Db db, long sourceId, boolean deleteProgress) {
        if (db == null) return;
        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            if (deleteProgress) {
                d.execSQL("DELETE FROM progress WHERE item_id IN (SELECT id FROM item WHERE source_id = ?)", new Object[]{sourceId});
            }
            d.execSQL("DELETE FROM series_state WHERE item_id IN (SELECT id FROM item WHERE source_id = ?)", new Object[]{sourceId});
            d.execSQL("DELETE FROM episode_extra WHERE item_id IN (SELECT id FROM item WHERE source_id = ?)", new Object[]{sourceId});
            d.execSQL("DELETE FROM stream WHERE item_id IN (SELECT id FROM item WHERE source_id = ?)", new Object[]{sourceId});
            d.execSQL("DELETE FROM meta WHERE item_id IN (SELECT id FROM item WHERE source_id = ?)", new Object[]{sourceId});
            d.execSQL("DELETE FROM film_choice WHERE item_id IN (SELECT id FROM item WHERE source_id = ?)", new Object[]{sourceId});
            d.execSQL("DELETE FROM play WHERE item_id IN (SELECT id FROM item WHERE source_id = ?)", new Object[]{sourceId});
            d.execSQL("DELETE FROM \"list\" WHERE item_id IN (SELECT id FROM item WHERE source_id = ?)", new Object[]{sourceId});
            d.execSQL("DELETE FROM item WHERE source_id = ?", new Object[]{sourceId});
            d.execSQL("DELETE FROM source WHERE id = ?", new Object[]{sourceId});
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        Library.refreshAny(db);
        Library.invalidateCategories();
    }

    private static final Map<Long, Integer> activeSyncProgress = new ConcurrentHashMap<>();

    public static boolean isSyncing(long sourceId) {
        return activeSyncProgress.containsKey(sourceId);
    }

    public static Integer syncingProgress(long sourceId) {
        return activeSyncProgress.get(sourceId);
    }

    public static String syncStatus(long sourceId, long lastSync) {
        if (isSyncing(sourceId)) {
            Integer count = syncingProgress(sourceId);
            return "Syncing films\u2026 " + (count != null ? count : 0) + " so far";
        }
        if (lastSync <= 0) return "not synced yet";
        long m = (System.currentTimeMillis() - lastSync) / 60000;
        if (m < 1) return "Synced just now";
        if (m < 60) return "Synced " + m + " min ago";
        if (m < 1440) return "Synced " + (m / 60) + " h ago";
        return "Synced " + (m / 1440) + " days ago";
    }

    /**
     * Low-priority background sync of films and film categories into catalogue.db.
     * Batches of 500 items per transaction.
     */
    public static void sync(final Context c, final long sourceId, final Listener l) {
        if (Config.PLAY) {
            if (l != null) l.onDone(0, "Not available");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + 2);
                } catch (Throwable ignored) {}
                doSync(c, sourceId, l);
            }
        }, "xtream-sync-" + sourceId);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static final int BATCH = 2000;
    private static final String TITLE_INDEX = "CREATE INDEX IF NOT EXISTS item_title ON item(kind, clean_title)";

    private static void doSync(Context c, long sourceId, Listener l) {
        Db db = Db.get();
        if (db == null) {
            if (l != null) l.onDone(0, "Database unavailable");
            return;
        }
        SQLiteDatabase d = db.getWritableDatabase();
        String address = null, secretRef = null;
        Cursor cur = d.rawQuery("SELECT address, secret_ref FROM source WHERE id = ? AND kind = 'xtream'",
                new String[]{String.valueOf(sourceId)});
        try {
            if (cur.moveToFirst()) {
                address = cur.getString(0);
                secretRef = cur.getString(1);
            }
        } finally {
            cur.close();
        }
        if (address == null || secretRef == null) {
            if (l != null) l.onDone(0, "Source not found");
            return;
        }
        String creds = Secrets.open(secretRef);
        if (creds == null) {
            if (l != null) l.onDone(0, "Credentials unavailable");
            return;
        }
        String[] parts = creds.split("\n", 2);
        if (parts.length < 2) {
            if (l != null) l.onDone(0, "Invalid credentials");
            return;
        }
        String user = parts[0];
        String pass = parts[1];
        String encUser = urlEncode(user);
        String encPass = urlEncode(pass);

        // 1. Fetch categories
        Map<String, String> categories = new HashMap<>();
        HttpURLConnection catConn = null;
        try {
            URL catUrl = new URL(address + "/player_api.php?username=" + encUser + "&password=" + encPass + "&action=get_vod_categories");
            catConn = (HttpURLConnection) catUrl.openConnection();
            catConn.setConnectTimeout(10000);
            catConn.setReadTimeout(15000);
            catConn.setRequestProperty("User-Agent", "ManusTV");
            if (catConn.getResponseCode() < 400) {
                InputStream cin = catConn.getInputStream();
                JsonReader cr = new JsonReader(new InputStreamReader(cin, StandardCharsets.UTF_8));
                cr.beginArray();
                while (cr.hasNext()) {
                    cr.beginObject();
                    String catId = null, catName = null;
                    while (cr.hasNext()) {
                        String k = cr.nextName();
                        if ("category_id".equals(k)) catId = nextStringOrNull(cr);
                        else if ("category_name".equals(k)) catName = nextStringOrNull(cr);
                        else cr.skipValue();
                    }
                    cr.endObject();
                    if (catId != null && catName != null) {
                        categories.put(catId.trim(), catName.trim());
                    }
                }
                cr.endArray();
                cin.close();
            }
        } catch (Exception ignored) {
            // categories failure is non-fatal: empty categories map
        } finally {
            if (catConn != null) catConn.disconnect();
        }

        // 2. Fetch and stream VOD streams
        HttpURLConnection streamConn = null;
        SQLiteStatement stmtInsertItem = null;
        SQLiteStatement stmtUpdateItem = null;
        SQLiteStatement stmtInsertStream = null;
        SQLiteStatement stmtInsertMeta = null;
        SQLiteStatement stmtMetaMulti = null;
        MetaBuf mb = new MetaBuf();
        int totalFilms = 0;
        int batchCount = 0;
        long lastProgressTime = 0;
        boolean inTransaction = false;
        long tSync0 = SystemClock.elapsedRealtime();
        long nsDb = 0, nsCommit = 0;
        long msPre = 0, msIdx = 0;
        boolean titleIndexDropped = false;
        boolean bulk = false; // first sync of this source: every row is new, so stream rows are written set-based
        long streamedUpTo = 0, lastItemId = 0;

        activeSyncProgress.put(sourceId, 0);
        try {
            // Preload existing items for this source in memory (O(1) lookups, zero exceptions, zero temp table)
            Map<String, ExistingItem> existingItems = new HashMap<>();
            Cursor curItems = d.rawQuery("SELECT provider_id, id, added_at, title, group_name FROM item WHERE source_id = ? AND kind = 'movie'",
                    new String[]{String.valueOf(sourceId)});
            try {
                while (curItems.moveToNext()) {
                    existingItems.put(curItems.getString(0),
                            new ExistingItem(curItems.getLong(1), curItems.getLong(2), curItems.getString(3), curItems.getString(4)));
                }
            } finally {
                curItems.close();
            }

            int countInserted = 0, countUpdated = 0, countSkipped = 0;

            msPre = SystemClock.elapsedRealtime() - tSync0;
            // A crash mid-sync must never leave the title index missing: re-create it first (no-op when present).
            d.execSQL(TITLE_INDEX);
            // Bulk first sync: build the title index once at the end instead of 50k random B-tree inserts.
            if (existingItems.isEmpty()) {
                d.execSQL("DROP INDEX IF EXISTS item_title");
                titleIndexDropped = true;
            }
            bulk = existingItems.isEmpty();
            Cursor cm = d.rawQuery("SELECT IFNULL(MAX(id), 0) FROM item", null);
            try { if (cm.moveToFirst()) streamedUpTo = cm.getLong(0); } finally { cm.close(); }
            stmtInsertItem = d.compileStatement("INSERT INTO item(source_id, kind, provider_id, title, clean_title, year, group_name, container, added_at) VALUES(?, 'movie', ?, ?, ?, ?, ?, ?, ?)");
            stmtUpdateItem = d.compileStatement("UPDATE item SET title = ?, clean_title = ?, year = ?, group_name = ?, container = ?, added_at = ? WHERE id = ?");
            stmtInsertStream = d.compileStatement("INSERT OR REPLACE INTO stream(item_id, url_template, quality, \"order\") VALUES(?, ?, '', 0)");
            stmtInsertMeta = d.compileStatement("INSERT OR REPLACE INTO meta(item_id, tmdb_id, rating, poster, match_source) VALUES(?, ?, ?, ?, 'provider')");

            if (bulk) stmtMetaMulti = d.compileStatement(MetaBuf.multiSql());
            URL streamUrl = new URL(address + "/player_api.php?username=" + encUser + "&password=" + encPass + "&action=get_vod_streams");
            streamConn = (HttpURLConnection) streamUrl.openConnection();
            streamConn.setConnectTimeout(10000);
            streamConn.setReadTimeout(60000);
            streamConn.setRequestProperty("User-Agent", "ManusTV");

            int code = streamConn.getResponseCode();
            if (code >= 400) {
                if (l != null) l.onDone(0, "Can't reach that server.");
                return;
            }

            InputStream sin = streamConn.getInputStream();
            JsonReader sr = new JsonReader(new InputStreamReader(sin, StandardCharsets.UTF_8));
            sr.beginArray();

            while (sr.hasNext()) {
                sr.beginObject();
                String streamId = null;
                String name = null;
                String ext = null;
                String catId = null;
                String addedStr = null;
                String streamIcon = null;
                String ratingStr = null;
                String tmdbStr = null;

                while (sr.hasNext()) {
                    String k = sr.nextName();
                    if ("stream_id".equals(k)) streamId = nextStringOrNull(sr);
                    else if ("name".equals(k)) name = nextStringOrNull(sr);
                    else if ("container_extension".equals(k)) ext = nextStringOrNull(sr);
                    else if ("category_id".equals(k)) catId = nextStringOrNull(sr);
                    else if ("added".equals(k)) addedStr = nextStringOrNull(sr);
                    else if ("stream_icon".equals(k)) streamIcon = nextStringOrNull(sr);
                    else if ("rating".equals(k)) ratingStr = nextStringOrNull(sr);
                    else if ("tmdb".equals(k)) tmdbStr = nextStringOrNull(sr);
                    else sr.skipValue();
                }
                sr.endObject();

                if (streamId == null || streamId.trim().isEmpty() || "0".equals(streamId.trim())) {
                    continue; // skip entries with no stream_id
                }
                streamId = streamId.trim();

                String rawTitle = name != null ? name : "";
                CheapTitle ct = cheapClean(rawTitle);
                String cleanTitle = ct.cleanTitle;
                int year = ct.year;
                String groupName = (catId != null && categories.containsKey(catId.trim())) ? categories.get(catId.trim()) : "";
                if (groupName == null) groupName = "";
                String container = ext != null ? ext.trim().toLowerCase(Locale.ROOT) : "";
                if (container.startsWith(".")) container = container.substring(1);
                long addedAt = 0;
                if (addedStr != null && !addedStr.isEmpty()) {
                    try { addedAt = Long.parseLong(addedStr.trim()); } catch (Exception ignored) {}
                }

                // Cheap re-sync check: if unchanged, skip all writes for it
                ExistingItem existing = existingItems.remove(streamId);
                if (existing != null && existing.addedAt == addedAt && existing.title.equals(rawTitle) && existing.groupName.equals(groupName)) {
                    countSkipped++;
                    totalFilms++;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastProgressTime >= 500) {
                        lastProgressTime = now;
                        activeSyncProgress.put(sourceId, totalFilms);
                        if (l != null) l.onProgress(totalFilms);
                    }
                    continue;
                }

                if (batchCount == 0 && !inTransaction) {
                    d.beginTransaction();
                    inTransaction = true;
                }

                String poster = (streamIcon != null && !streamIcon.trim().isEmpty()) ? streamIcon.trim() : null;
                Double rating = null;
                if (ratingStr != null && !ratingStr.trim().isEmpty()) {
                    try { rating = Double.parseDouble(ratingStr.trim()); } catch (Exception ignored) {}
                }
                Long tmdbId = null;
                if (tmdbStr != null && !tmdbStr.trim().isEmpty()) {
                    try {
                        long tid = Long.parseLong(tmdbStr.trim());
                        if (tid > 0) tmdbId = tid;
                    } catch (Exception ignored) {}
                }

                long ns0 = System.nanoTime();
                long itemId;
                if (existing != null) {
                    countUpdated++;
                    itemId = existing.id;
                    stmtUpdateItem.clearBindings();
                    stmtUpdateItem.bindString(1, rawTitle);
                    stmtUpdateItem.bindString(2, cleanTitle);
                    stmtUpdateItem.bindLong(3, year);
                    stmtUpdateItem.bindString(4, groupName);
                    stmtUpdateItem.bindString(5, container);
                    stmtUpdateItem.bindLong(6, addedAt);
                    stmtUpdateItem.bindLong(7, itemId);
                    stmtUpdateItem.executeUpdateDelete();
                } else {
                    countInserted++;
                    stmtInsertItem.clearBindings();
                    stmtInsertItem.bindLong(1, sourceId);
                    stmtInsertItem.bindString(2, streamId);
                    stmtInsertItem.bindString(3, rawTitle);
                    stmtInsertItem.bindString(4, cleanTitle);
                    stmtInsertItem.bindLong(5, year);
                    stmtInsertItem.bindString(6, groupName);
                    stmtInsertItem.bindString(7, container);
                    stmtInsertItem.bindLong(8, addedAt);
                    itemId = stmtInsertItem.executeInsert();
                }

                if (bulk) {
                    lastItemId = itemId; // stream row comes from flushStreams, same template as buildTemplate
                } else {
                    // Stream row: url_template = {BASE}/movie/{USER}/{PASS}/{id}.{ext}
                    String urlTemplate = buildTemplate(streamId, container);
                    stmtInsertStream.clearBindings();
                    stmtInsertStream.bindLong(1, itemId);
                    stmtInsertStream.bindString(2, urlTemplate);
                    stmtInsertStream.executeInsert();
                }

                // Meta row: provider data (bulk: buffered, written 100 rows per statement)
                if (bulk) {
                    mb.add(itemId, tmdbId, rating, poster);
                    if (mb.n == MetaBuf.ROWS) mb.flush(stmtMetaMulti, stmtInsertMeta);
                } else {
                    stmtInsertMeta.clearBindings();
                    stmtInsertMeta.bindLong(1, itemId);
                    if (tmdbId != null) stmtInsertMeta.bindLong(2, tmdbId); else stmtInsertMeta.bindNull(2);
                    if (rating != null) stmtInsertMeta.bindDouble(3, rating); else stmtInsertMeta.bindNull(3);
                    if (poster != null) stmtInsertMeta.bindString(4, poster); else stmtInsertMeta.bindNull(4);
                    stmtInsertMeta.executeInsert();
                }

                batchCount++;
                totalFilms++;
                nsDb += System.nanoTime() - ns0;

                if (batchCount >= BATCH) {
                    long nc0 = System.nanoTime();
                    if (bulk) mb.flush(stmtMetaMulti, stmtInsertMeta);
                    if (bulk && lastItemId > streamedUpTo) { flushStreams(d, streamedUpTo, sourceId); streamedUpTo = lastItemId; }
                    d.setTransactionSuccessful();
                    d.endTransaction();
                    nsCommit += System.nanoTime() - nc0;
                    inTransaction = false;
                    batchCount = 0;

                    long now = SystemClock.elapsedRealtime();
                    if (now - lastProgressTime >= 500) {
                        lastProgressTime = now;
                        activeSyncProgress.put(sourceId, totalFilms);
                        if (l != null) l.onProgress(totalFilms);
                    }
                }
            }
            sr.endArray();
            sin.close();

            if (inTransaction) {
                if (bulk) mb.flush(stmtMetaMulti, stmtInsertMeta);
                if (bulk && lastItemId > streamedUpTo) { flushStreams(d, streamedUpTo, sourceId); streamedUpTo = lastItemId; }
                d.setTransactionSuccessful();
                d.endTransaction();
                inTransaction = false;
                batchCount = 0;
            }

            if (titleIndexDropped) {
                long ti0 = SystemClock.elapsedRealtime();
                d.execSQL(TITLE_INDEX);
                titleIndexDropped = false;
                msIdx = SystemClock.elapsedRealtime() - ti0;
            }

            // Final transaction: delete rows of that source not seen in this run, update source.last_sync
            d.beginTransaction();
            inTransaction = true;
            if (!existingItems.isEmpty()) {
                java.util.List<Long> oldIds = new java.util.ArrayList<Long>();
                for (ExistingItem ei : existingItems.values()) oldIds.add(ei.id);
                for (int i = 0; i < oldIds.size(); i += 500) {
                    int end = Math.min(i + 500, oldIds.size());
                    StringBuilder sb = new StringBuilder();
                    for (int j = i; j < end; j++) {
                        if (j > i) sb.append(',');
                        sb.append(oldIds.get(j));
                    }
                    String inList = sb.toString();
                    d.execSQL("DELETE FROM series_state WHERE item_id IN (" + inList + ")");
                    d.execSQL("DELETE FROM episode_extra WHERE item_id IN (" + inList + ")");
                    d.execSQL("DELETE FROM stream WHERE item_id IN (" + inList + ")");
                    d.execSQL("DELETE FROM meta WHERE item_id IN (" + inList + ")");
                    d.execSQL("DELETE FROM film_choice WHERE item_id IN (" + inList + ")");
                    d.execSQL("DELETE FROM play WHERE item_id IN (" + inList + ")");
                    d.execSQL("DELETE FROM \"list\" WHERE item_id IN (" + inList + ")");
                    d.execSQL("DELETE FROM progress WHERE item_id IN (" + inList + ")");
                    d.execSQL("DELETE FROM item WHERE id IN (" + inList + ")");
                }
            }
            d.execSQL("UPDATE source SET last_sync = ? WHERE id = ?", new Object[]{System.currentTimeMillis(), sourceId});
            d.setTransactionSuccessful();
            d.endTransaction();
            inTransaction = false;

            long totalMs = SystemClock.elapsedRealtime() - tSync0;
            Log.i("IPTV", "IPTV xtream sync: ins=" + countInserted + " upd=" + countUpdated + " skip=" + countSkipped + " in " + totalMs + " ms");
            Log.i("IPTV", "xtream phase: sync total=" + totalMs + " ms, preload=" + msPre + " ms, rows_db=" + nsDb / 1000000 + " ms, commits=" + nsCommit / 1000000 + " ms, index=" + msIdx + " ms, films=" + totalFilms);
            Library.refreshAny(db);
            Library.invalidateCategories();
            Db.applyPendingRestore(db);
            if (l != null) {
                l.onProgress(totalFilms);
                l.onDone(totalFilms, null);
            }
            try {
                SeriesSource.syncSeries(c, sourceId);
            } catch (Throwable t) {
                Log.w("IPTV", "series sync error: " + t.getMessage());
            }
            try {
                LiveSource.syncLive(sourceId); // T95: live channels + their catch-up window
            } catch (Throwable t) {
                Log.w("IPTV", "live sync error: " + t.getClass().getSimpleName());
            }
        } catch (Exception e) {
            if (inTransaction) {
                try { d.endTransaction(); } catch (Exception ignored) {}
            }
            if (l != null) {
                l.onDone(totalFilms, "Can't reach that server.");
            }
        } finally {
            activeSyncProgress.remove(sourceId);
            if (titleIndexDropped) {
                try { d.execSQL(TITLE_INDEX); } catch (Exception ignored) {}
            }
            if (streamConn != null) streamConn.disconnect();
            if (stmtInsertItem != null) stmtInsertItem.close();
            if (stmtUpdateItem != null) stmtUpdateItem.close();
            if (stmtInsertStream != null) stmtInsertStream.close();
            if (stmtInsertMeta != null) stmtInsertMeta.close();
            if (stmtMetaMulti != null) stmtMetaMulti.close();
        }
    }

    /** Buffers meta rows of a first sync and writes ROWS of them per statement (a full buffer) or one by one (the remainder). */
    private static final class MetaBuf {
        static final int ROWS = 100;
        final long[] id = new long[ROWS];
        final Long[] tmdb = new Long[ROWS];
        final Double[] rating = new Double[ROWS];
        final String[] poster = new String[ROWS];
        int n;

        static String multiSql() {
            StringBuilder sb = new StringBuilder("INSERT OR REPLACE INTO meta(item_id, tmdb_id, rating, poster, match_source) VALUES ");
            for (int i = 0; i < ROWS; i++) sb.append(i > 0 ? ",(?,?,?,?,'provider')" : "(?,?,?,?,'provider')");
            return sb.toString();
        }

        void add(long itemId, Long t, Double r, String p) {
            id[n] = itemId; tmdb[n] = t; rating[n] = r; poster[n] = p; n++;
        }

        void flush(SQLiteStatement multi, SQLiteStatement one) {
            if (n == 0) return;
            SQLiteStatement st = n == ROWS ? multi : one;
            if (st == multi) st.clearBindings();
            for (int i = 0; i < n; i++) {
                int b = st == multi ? i * 4 : 0;
                if (st == one) st.clearBindings();
                st.bindLong(b + 1, id[i]);
                if (tmdb[i] != null) st.bindLong(b + 2, tmdb[i]); else st.bindNull(b + 2);
                if (rating[i] != null) st.bindDouble(b + 3, rating[i]); else st.bindNull(b + 3);
                if (poster[i] != null) st.bindString(b + 4, poster[i]); else st.bindNull(b + 4);
                if (st == one) st.executeInsert();
            }
            if (st == multi) st.executeInsert();
            n = 0;
        }
    }

    /** One statement writes the stream rows of all items inserted since {@code afterId}; same template as {@link #buildTemplate}. */
    private static void flushStreams(SQLiteDatabase d, long afterId, long sourceId) {
        d.execSQL("INSERT OR REPLACE INTO stream(item_id, url_template, quality, \"order\") "
                + "SELECT id, '{BASE}/movie/{USER}/{PASS}/' || provider_id || CASE WHEN IFNULL(container, '') = '' THEN '' ELSE '.' || container END, '', 0 "
                + "FROM item WHERE id > ? AND source_id = ? AND kind = 'movie'", new Object[]{afterId, sourceId});
    }

    private static String nextStringOrNull(JsonReader r) throws IOException {
        JsonToken token = r.peek();
        if (token == JsonToken.NULL) {
            r.nextNull();
            return null;
        }
        if (token == JsonToken.NUMBER || token == JsonToken.STRING) {
            return r.nextString();
        }
        if (token == JsonToken.BOOLEAN) {
            return String.valueOf(r.nextBoolean());
        }
        r.skipValue();
        return null;
    }

    /**
     * Measures the three sync phases separately on a 50k dataset:
     * 1. Network + JSON parse only (no DB)
     * 2. TitleCleaner.clean only (50k calls)
     * 3. DB inserts only (50k rows in batches of 500)
     * Logs ms only via "IPTV xtream phase:".
     */
    public static void measurePhases(Context c, String base, String user, String pass) {
        String normBase = normaliseAddress(base);
        if (normBase == null) {
            Log.e("IPTV", "measurePhases: bad address");
            return;
        }
        String encUser = urlEncode(user);
        String encPass = urlEncode(pass);

        // Phase 1: Network + JSON parse only (no DB)
        HttpURLConnection conn = null;
        String[] titles = new String[55000];
        int count = 0;
        long netJsonMs = 0;
        try {
            URL streamUrl = new URL(normBase + "/player_api.php?username=" + encUser + "&password=" + encPass + "&action=get_vod_streams");
            long tNet0 = SystemClock.elapsedRealtime();
            conn = (HttpURLConnection) streamUrl.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("User-Agent", "ManusTV");
            if (conn.getResponseCode() >= 400) {
                Log.e("IPTV", "measurePhases: server error " + conn.getResponseCode());
                return;
            }
            InputStream sin = conn.getInputStream();
            JsonReader sr = new JsonReader(new InputStreamReader(sin, StandardCharsets.UTF_8));
            sr.beginArray();
            while (sr.hasNext()) {
                sr.beginObject();
                String streamId = null;
                String name = null;
                String ext = null;
                String catId = null;
                String addedStr = null;
                String streamIcon = null;
                String ratingStr = null;
                String tmdbStr = null;
                while (sr.hasNext()) {
                    String k = sr.nextName();
                    if ("stream_id".equals(k)) streamId = nextStringOrNull(sr);
                    else if ("name".equals(k)) name = nextStringOrNull(sr);
                    else if ("container_extension".equals(k)) ext = nextStringOrNull(sr);
                    else if ("category_id".equals(k)) catId = nextStringOrNull(sr);
                    else if ("added".equals(k)) addedStr = nextStringOrNull(sr);
                    else if ("stream_icon".equals(k)) streamIcon = nextStringOrNull(sr);
                    else if ("rating".equals(k)) ratingStr = nextStringOrNull(sr);
                    else if ("tmdb".equals(k)) tmdbStr = nextStringOrNull(sr);
                    else sr.skipValue();
                }
                sr.endObject();
                if (count < titles.length) {
                    titles[count] = name != null ? name : "";
                }
                count++;
            }
            sr.endArray();
            sin.close();
            netJsonMs = SystemClock.elapsedRealtime() - tNet0;
        } catch (Exception e) {
            Log.e("IPTV", "measurePhases phase 1 failed: " + e.getMessage());
            return;
        } finally {
            if (conn != null) conn.disconnect();
        }

        // Phase 2: TitleCleaner.clean only (50k calls)
        long tClean0 = SystemClock.elapsedRealtime();
        for (int i = 0; i < count; i++) {
            TitleCleaner.clean(titles[i]);
        }
        long cleanMs = SystemClock.elapsedRealtime() - tClean0;

        // Phase 3: DB inserts only (50k rows in batches of 500)
        Db db = Db.get();
        if (db == null) {
            Log.e("IPTV", "measurePhases: db unavailable");
            return;
        }
        SQLiteDatabase d = db.getWritableDatabase();
        long benchSourceId = 88888L;
        d.execSQL("DELETE FROM item WHERE source_id = ?", new Object[]{benchSourceId});
        SQLiteStatement stmtInsertItem = d.compileStatement("INSERT INTO item(source_id, kind, provider_id, title, clean_title, year, group_name, container, added_at) VALUES(?, 'movie', ?, ?, ?, ?, ?, ?, ?)");
        SQLiteStatement stmtInsertStream = d.compileStatement("INSERT OR REPLACE INTO stream(item_id, url_template, quality, \"order\") VALUES(?, ?, '', 0)");
        SQLiteStatement stmtInsertMeta = d.compileStatement("INSERT OR REPLACE INTO meta(item_id, tmdb_id, rating, poster, match_source) VALUES(?, ?, ?, ?, 'provider')");

        long tDb0 = SystemClock.elapsedRealtime();
        int batchCount = 0;
        boolean inTx = false;
        for (int i = 0; i < count; i++) {
            if (batchCount == 0) {
                d.beginTransaction();
                inTx = true;
            }
            stmtInsertItem.clearBindings();
            stmtInsertItem.bindLong(1, benchSourceId);
            stmtInsertItem.bindString(2, String.valueOf(1001 + i));
            stmtInsertItem.bindString(3, titles[i]);
            stmtInsertItem.bindString(4, titles[i]);
            stmtInsertItem.bindLong(5, 2020);
            stmtInsertItem.bindString(6, "Action");
            stmtInsertItem.bindString(7, "mp4");
            stmtInsertItem.bindLong(8, 1600000000L);
            long itemId = stmtInsertItem.executeInsert();

            stmtInsertStream.clearBindings();
            stmtInsertStream.bindLong(1, itemId);
            stmtInsertStream.bindString(2, "{BASE}/movie/{USER}/{PASS}/" + (1001 + i) + ".mp4");
            stmtInsertStream.executeInsert();

            stmtInsertMeta.clearBindings();
            stmtInsertMeta.bindLong(1, itemId);
            stmtInsertMeta.bindNull(2);
            stmtInsertMeta.bindNull(3);
            stmtInsertMeta.bindNull(4);
            stmtInsertMeta.executeInsert();

            batchCount++;
            if (batchCount >= 500) {
                d.setTransactionSuccessful();
                d.endTransaction();
                inTx = false;
                batchCount = 0;
            }
        }
        if (inTx) {
            d.setTransactionSuccessful();
            d.endTransaction();
        }
        long dbMs = SystemClock.elapsedRealtime() - tDb0;
        stmtInsertItem.close();
        stmtInsertStream.close();
        stmtInsertMeta.close();

        // Clean up bench rows
        d.execSQL("DELETE FROM stream WHERE item_id IN (SELECT id FROM item WHERE source_id = ?)", new Object[]{benchSourceId});
        d.execSQL("DELETE FROM meta WHERE item_id IN (SELECT id FROM item WHERE source_id = ?)", new Object[]{benchSourceId});
        d.execSQL("DELETE FROM item WHERE source_id = ?", new Object[]{benchSourceId});

        // Log required line: log ms only, via "IPTV xtream phase:"
        Log.i("IPTV", "IPTV xtream phase: network_json=" + netJsonMs + " ms, clean=" + cleanMs + " ms, db=" + dbMs + " ms");
        Log.i("IPTV", "IPTV xtream phase: network_json=" + netJsonMs + " ms");
        Log.i("IPTV", "IPTV xtream phase: clean=" + cleanMs + " ms");
        Log.i("IPTV", "IPTV xtream phase: db=" + dbMs + " ms");
    }
}
