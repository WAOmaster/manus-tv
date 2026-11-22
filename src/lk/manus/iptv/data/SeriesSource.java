package lk.manus.iptv.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.manus.iptv.Config;

/**
 * T91a: Series data sync from Xtream Codes, episode resolution, and season/episode access.
 * Credentials sealed via Secrets, never in logs or db.
 */
public final class SeriesSource {
    private static final String TAG = "IPTV";
    private static final int BATCH = 2000;
    private SeriesSource() {}

    public static final class Episode {
        public final long itemId, positionMs, durationMs;
        public final String providerId, title, plot, cover;
        public final int season, episode, durationSec;
        public final boolean watched;
        public Episode(long id, String pid, int s, int ep, String t, int durSec, String pl, String cov, long pos, long dur, boolean w) {
            this.itemId = id; this.providerId = pid != null ? pid : ""; this.season = s; this.episode = ep;
            this.title = t != null ? t : ""; this.durationSec = durSec; this.plot = pl != null ? pl : "";
            this.cover = cov != null ? cov : ""; this.positionMs = pos; this.durationMs = dur; this.watched = w;
        }
    }

    public static final class Season {
        public final int seasonNumber, episodeCount;
        public Season(int s, int c) { this.seasonNumber = s; this.episodeCount = c; }
    }

    public static final class ParsedEpisode {
        public final String id, title, container, plot, cover;
        public final int season, episodeNum, durationSec;
        public ParsedEpisode(String id, int s, int ep, String title, String ext, String plot, String cover, int dur) {
            this.id = id; this.season = s; this.episodeNum = ep; this.title = title;
            this.container = ext; this.plot = plot; this.cover = cover; this.durationSec = dur;
        }
    }

    public static String buildEpisodeTemplate(String episodeId, String ext) {
        String e = (ext != null && !ext.trim().isEmpty()) ? ext.trim().toLowerCase(Locale.ROOT) : "";
        if (e.startsWith(".")) e = e.substring(1);
        return "{BASE}/series/{USER}/{PASS}/" + (episodeId != null ? episodeId.trim() : "") + (e.isEmpty() ? "" : "." + e);
    }

    public static int seriesCount(Db db, long sourceId) {
        if (db == null) return 0;
        Cursor c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM item WHERE source_id = ? AND kind = 'series'", new String[]{String.valueOf(sourceId)});
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    private static String[] getAuth(SQLiteDatabase d, String query, String[] args) {
        Cursor c = d.rawQuery(query, args);
        String addr = null, ref = null, pid = null; long sid = -1;
        try { if (c.moveToFirst()) { addr = c.getString(0); ref = c.getString(1); if (c.getColumnCount() > 2) pid = c.getString(2); if (c.getColumnCount() > 3) sid = c.getLong(3); } } finally { c.close(); }
        if (addr == null || ref == null) return null;
        String creds = Secrets.open(ref); if (creds == null) return null;
        String[] p = creds.split("\n", 2); if (p.length < 2) return null;
        return new String[]{addr, XtreamSource.urlEncode(p[0]), XtreamSource.urlEncode(p[1]), pid, String.valueOf(sid)};
    }

    private static HttpURLConnection openConn(String urlStr, int readTimeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(readTimeout); c.setRequestProperty("User-Agent", "ManusTV");
        return c;
    }

    private static void bindStr(SQLiteStatement st, int idx, String val) {
        if (val != null && !val.isEmpty()) st.bindString(idx, val); else st.bindNull(idx);
    }

    private static final class ExistingSeries {
        final long id, addedAt; final String title, groupName;
        ExistingSeries(long id, long addedAt, String title, String groupName) {
            this.id = id; this.addedAt = addedAt; this.title = title != null ? title : ""; this.groupName = groupName != null ? groupName : "";
        }
    }

    private static Map<String, String> fetchCategories(String addr, String u, String p) {
        Map<String, String> map = new HashMap<>(); HttpURLConnection c = null;
        try {
            c = openConn(addr + "/player_api.php?username=" + u + "&password=" + p + "&action=get_series_categories", 15000);
            if (c.getResponseCode() < 400) {
                JsonReader cr = new JsonReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                cr.beginArray();
                while (cr.hasNext()) {
                    cr.beginObject(); String id = null, name = null;
                    while (cr.hasNext()) {
                        String k = cr.nextName();
                        if ("category_id".equals(k)) id = nextStr(cr, null); else if ("category_name".equals(k)) name = nextStr(cr, null); else cr.skipValue();
                    }
                    cr.endObject(); if (id != null && name != null) map.put(id.trim(), name.trim());
                }
                cr.endArray(); cr.close();
            }
        } catch (Exception ignored) {} finally { if (c != null) c.disconnect(); }
        return map;
    }

    private static void deleteSeries(SQLiteDatabase d, List<Long> ids) {
        for (int i = 0; i < ids.size(); i += 500) {
            int end = Math.min(i + 500, ids.size());
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < end; j++) { if (j > i) sb.append(','); sb.append(ids.get(j)); }
            String inList = sb.toString();
            String p = "WHERE item_id IN (SELECT id FROM item WHERE parent_id IN (" + inList + "))";
            for (String t : new String[]{"stream", "episode_extra", "progress"}) d.execSQL("DELETE FROM " + t + " " + p);
            d.execSQL("DELETE FROM item WHERE parent_id IN (" + inList + ")");
            d.execSQL("DELETE FROM series_state WHERE item_id IN (" + inList + ")");
            d.execSQL("DELETE FROM item WHERE id IN (" + inList + ")");
        }
    }

    private static void deleteEpisode(SQLiteDatabase d, long id) {
        for (String t : new String[]{"stream", "episode_extra", "progress", "item"}) d.execSQL("DELETE FROM " + t + " WHERE " + ("item".equals(t) ? "id" : "item_id") + " = ?", new Object[]{id});
    }

    /** Syncs series for sourceId into catalogue.db. Uses prepared statements, skips unchanged rows, batches ~2000 rows. */
    public static void syncSeries(Context c, long sourceId) {
        if (Config.PLAY) return;
        Db db = Db.get(); if (db == null) return;
        SQLiteDatabase d = db.getWritableDatabase();
        String[] auth = getAuth(d, "SELECT address, secret_ref FROM source WHERE id = ? AND kind = 'xtream'", new String[]{String.valueOf(sourceId)});
        if (auth == null) return;
        String address = auth[0], encUser = auth[1], encPass = auth[2];

        Map<String, String> categories = fetchCategories(address, encUser, encPass);
        Map<String, ExistingSeries> existingSeries = new HashMap<>();
        Cursor curItems = d.rawQuery("SELECT provider_id, id, added_at, title, group_name FROM item WHERE source_id = ? AND kind = 'series'", new String[]{String.valueOf(sourceId)});
        try { while (curItems.moveToNext()) existingSeries.put(curItems.getString(0), new ExistingSeries(curItems.getLong(1), curItems.getLong(2), curItems.getString(3), curItems.getString(4))); } finally { curItems.close(); }

        int countInserted = 0, countUpdated = 0, countSkipped = 0, batchCount = 0;
        boolean inTransaction = false, titleIndexDropped = false; long tSync0 = SystemClock.elapsedRealtime();
        SQLiteStatement stmtInsertItem = null, stmtUpdateItem = null, stmtState = null; HttpURLConnection seriesConn = null;
        try {
            if (existingSeries.size() < 200) {
                d.execSQL("DROP INDEX IF EXISTS item_title");
                titleIndexDropped = true;
            }
            stmtInsertItem = d.compileStatement("INSERT INTO item(source_id, kind, provider_id, title, clean_title, year, group_name, container, added_at) VALUES(?, 'series', ?, ?, ?, ?, ?, '', ?)");
            stmtUpdateItem = d.compileStatement("UPDATE item SET title = ?, clean_title = ?, year = ?, group_name = ?, added_at = ? WHERE id = ?");
            stmtState = d.compileStatement("INSERT OR REPLACE INTO series_state(item_id, fetched_at, cover, plot, rating) VALUES(?, 0, ?, ?, ?)");

            seriesConn = openConn(address + "/player_api.php?username=" + encUser + "&password=" + encPass + "&action=get_series", 60000);
            if (seriesConn.getResponseCode() >= 400) return;
            InputStream sin = seriesConn.getInputStream();
            JsonReader sr = new JsonReader(new InputStreamReader(sin, StandardCharsets.UTF_8));
            sr.beginArray();
            while (sr.hasNext()) {
                sr.beginObject();
                String seriesId = null, name = null, cover = null, plot = null, catId = null, ratingStr = null, lastModStr = null, relDateStr = null;
                while (sr.hasNext()) {
                    String k = sr.nextName();
                    if ("series_id".equals(k)) seriesId = nextStr(sr, null);
                    else if ("name".equals(k) || "title".equals(k)) name = nextStr(sr, null);
                    else if ("cover".equals(k) || "stream_icon".equals(k)) cover = nextStr(sr, null);
                    else if ("plot".equals(k)) plot = nextStr(sr, null);
                    else if ("category_id".equals(k)) catId = nextStr(sr, null);
                    else if ("rating".equals(k) || "rating_5based".equals(k)) ratingStr = nextStr(sr, null);
                    else if ("last_modified".equals(k)) lastModStr = nextStr(sr, null);
                    else if ("releaseDate".equals(k)) relDateStr = nextStr(sr, null);
                    else sr.skipValue();
                }
                sr.endObject();
                if (seriesId == null || seriesId.trim().isEmpty() || "0".equals(seriesId.trim())) continue;
                seriesId = seriesId.trim();

                String rawTitle = name != null ? name : "";
                XtreamSource.CheapTitle ct = XtreamSource.cheapClean(rawTitle);
                int year = (relDateStr != null && relDateStr.length() >= 4) ? toInt(relDateStr.substring(0, 4), 0) : 0;
                if (year == 0) year = ct.year;
                String groupName = (catId != null && categories.containsKey(catId.trim())) ? categories.get(catId.trim()) : "";
                if (groupName == null) groupName = "";
                long addedAt = (lastModStr != null && !lastModStr.trim().isEmpty()) ? toLong(lastModStr, 0) : 0;

                ExistingSeries ex = existingSeries.remove(seriesId);
                if (ex != null && ex.addedAt == addedAt && ex.title.equals(rawTitle) && ex.groupName.equals(groupName)) { countSkipped++; continue; }
                if (batchCount == 0 && !inTransaction) { d.beginTransaction(); inTransaction = true; }
                Double rating = (ratingStr != null && !ratingStr.trim().isEmpty()) ? toDouble(ratingStr) : null;

                long itemId;
                if (ex != null) {
                    stmtUpdateItem.clearBindings();
                    stmtUpdateItem.bindString(1, rawTitle); stmtUpdateItem.bindString(2, ct.cleanTitle);
                    stmtUpdateItem.bindLong(3, year); stmtUpdateItem.bindString(4, groupName);
                    stmtUpdateItem.bindLong(5, addedAt); stmtUpdateItem.bindLong(6, ex.id);
                    stmtUpdateItem.executeUpdateDelete(); itemId = ex.id; countUpdated++;
                } else {
                    stmtInsertItem.clearBindings();
                    stmtInsertItem.bindLong(1, sourceId); stmtInsertItem.bindString(2, seriesId);
                    stmtInsertItem.bindString(3, rawTitle); stmtInsertItem.bindString(4, ct.cleanTitle);
                    stmtInsertItem.bindLong(5, year); stmtInsertItem.bindString(6, groupName);
                    stmtInsertItem.bindLong(7, addedAt); itemId = stmtInsertItem.executeInsert(); countInserted++;
                }
                stmtState.clearBindings(); stmtState.bindLong(1, itemId);
                bindStr(stmtState, 2, cover != null ? cover.trim() : null); bindStr(stmtState, 3, plot != null ? plot.trim() : null);
                if (rating != null) stmtState.bindDouble(4, rating); else stmtState.bindNull(4);
                stmtState.executeInsert();

                if (++batchCount >= BATCH) { d.setTransactionSuccessful(); d.endTransaction(); inTransaction = false; batchCount = 0; }
            }
            sr.endArray(); sin.close();

            if (!existingSeries.isEmpty()) {
                if (!inTransaction) { d.beginTransaction(); inTransaction = true; }
                List<Long> oldIds = new ArrayList<>();
                for (ExistingSeries e : existingSeries.values()) oldIds.add(e.id);
                deleteSeries(d, oldIds);
            }
            if (inTransaction) { d.setTransactionSuccessful(); d.endTransaction(); inTransaction = false; }
            if (titleIndexDropped) {
                d.execSQL("CREATE INDEX IF NOT EXISTS item_title ON item(kind, clean_title)");
                titleIndexDropped = false;
            }
            long totalMs = SystemClock.elapsedRealtime() - tSync0;
            Log.i(TAG, "IPTV series sync: ins=" + countInserted + " upd=" + countUpdated + " skip=" + countSkipped + " in " + totalMs + " ms");
            Library.refreshAny(db, "series");
            Library.invalidateCategories("series");
            Db.applyPendingRestore(db); // T96: series in My list from a backup
        } catch (Exception e) {
            if (inTransaction) { try { d.endTransaction(); } catch (Exception ignored) {} }
            Log.w(TAG, "series sync error: " + e.getMessage());
        } finally {
            if (titleIndexDropped) {
                try { d.execSQL("CREATE INDEX IF NOT EXISTS item_title ON item(kind, clean_title)"); } catch (Exception ignored) {}
            }
            if (seriesConn != null) seriesConn.disconnect();
            if (stmtInsertItem != null) stmtInsertItem.close();
            if (stmtUpdateItem != null) stmtUpdateItem.close();
            if (stmtState != null) stmtState.close();
        }
    }

    /**
     * Ensures episodes for seriesItemId are fetched and stored in catalogue.db.
     * Blocks caller. If fetched within 24h and episodes exist, returns without network call.
     * Preserves existing episode item_id so progress rows survive.
     * Returns true on success (cached or fetched), false on network/HTTP failure.
     */
    public static boolean ensureEpisodes(Db db, long seriesItemId, boolean force) {
        if (db == null) return false;
        SQLiteDatabase d = db.getWritableDatabase();
        long fetchedAt = 0;
        Cursor cs = d.rawQuery("SELECT fetched_at FROM series_state WHERE item_id = ?", new String[]{String.valueOf(seriesItemId)});
        try { if (cs.moveToFirst()) fetchedAt = cs.getLong(0); } finally { cs.close(); }

        boolean hasEpisodes = false;
        Cursor ce = d.rawQuery("SELECT 1 FROM item WHERE parent_id = ? AND kind = 'episode' LIMIT 1", new String[]{String.valueOf(seriesItemId)});
        try { hasEpisodes = ce.moveToFirst(); } finally { ce.close(); }

        long now = System.currentTimeMillis();
        if (!force && (now - fetchedAt < 24L * 3600 * 1000L) && (hasEpisodes || fetchedAt > 0)) {
            Log.i(TAG, "seriesepisodes: cached (no network call) seriesItemId=" + seriesItemId);
            return true;
        }

        String[] auth = getAuth(d, "SELECT s.address, s.secret_ref, i.provider_id, s.id FROM item i JOIN source s ON s.id = i.source_id WHERE i.id = ?", new String[]{String.valueOf(seriesItemId)});
        if (auth == null) return false;
        String address = auth[0], encUser = auth[1], encPass = auth[2], providerId = auth[3];
        long sourceId = Long.parseLong(auth[4]);

        HttpURLConnection conn = null; List<ParsedEpisode> episodes;
        try {
            conn = openConn(address + "/player_api.php?username=" + encUser + "&password=" + encPass + "&action=get_series_info&series_id=" + providerId, 20000);
            if (conn.getResponseCode() >= 400) return false;
            episodes = parseEpisodesJson(conn.getInputStream());
        } catch (Exception e) {
            Log.w(TAG, "ensureEpisodes fetch error: " + e.getMessage());
            return false;
        } finally { if (conn != null) conn.disconnect(); }

        Map<String, Long> existing = new HashMap<>();
        Cursor cOld = d.rawQuery("SELECT provider_id, id FROM item WHERE parent_id = ? AND kind = 'episode'", new String[]{String.valueOf(seriesItemId)});
        try { while (cOld.moveToNext()) existing.put(cOld.getString(0), cOld.getLong(1)); } finally { cOld.close(); }

        SQLiteStatement stmtInsertItem = null, stmtUpdateItem = null, stmtStream = null, stmtExtra = null;
        boolean ok = false;
        d.beginTransaction();
        try {
            stmtInsertItem = d.compileStatement("INSERT INTO item(source_id, kind, provider_id, parent_id, season, episode, title, clean_title, year, group_name, container, added_at) VALUES(?, 'episode', ?, ?, ?, ?, ?, '', 0, '', ?, 0)");
            stmtUpdateItem = d.compileStatement("UPDATE item SET season = ?, episode = ?, title = ?, container = ? WHERE id = ?");
            stmtStream = d.compileStatement("INSERT OR REPLACE INTO stream(item_id, url_template, quality, \"order\") VALUES(?, ?, '', 0)");
            stmtExtra = d.compileStatement("INSERT OR REPLACE INTO episode_extra(item_id, cover, plot, duration_s) VALUES(?, ?, ?, ?)");

            for (ParsedEpisode ep : episodes) {
                long itemId; Long oldId = existing.remove(ep.id);
                if (oldId != null) {
                    stmtUpdateItem.clearBindings();
                    stmtUpdateItem.bindLong(1, ep.season); stmtUpdateItem.bindLong(2, ep.episodeNum);
                    stmtUpdateItem.bindString(3, ep.title); stmtUpdateItem.bindString(4, ep.container);
                    stmtUpdateItem.bindLong(5, oldId); stmtUpdateItem.executeUpdateDelete(); itemId = oldId;
                } else {
                    stmtInsertItem.clearBindings();
                    stmtInsertItem.bindLong(1, sourceId); stmtInsertItem.bindString(2, ep.id);
                    stmtInsertItem.bindLong(3, seriesItemId); stmtInsertItem.bindLong(4, ep.season);
                    stmtInsertItem.bindLong(5, ep.episodeNum); stmtInsertItem.bindString(6, ep.title);
                    stmtInsertItem.bindString(7, ep.container); itemId = stmtInsertItem.executeInsert();
                }
                stmtStream.clearBindings(); stmtStream.bindLong(1, itemId);
                stmtStream.bindString(2, buildEpisodeTemplate(ep.id, ep.container)); stmtStream.executeInsert();

                stmtExtra.clearBindings(); stmtExtra.bindLong(1, itemId);
                bindStr(stmtExtra, 2, ep.cover); bindStr(stmtExtra, 3, ep.plot);
                stmtExtra.bindLong(4, ep.durationSec); stmtExtra.executeInsert();
            }
            for (Long oldId : existing.values()) deleteEpisode(d, oldId);

            d.execSQL("INSERT INTO series_state(item_id, fetched_at, cover, plot, rating) VALUES(?, ?, '', '', 0) ON CONFLICT(item_id) DO UPDATE SET fetched_at = excluded.fetched_at", new Object[]{seriesItemId, System.currentTimeMillis()});
            d.setTransactionSuccessful();
            ok = true;
            return true;
        } finally {
            d.endTransaction();
            if (stmtInsertItem != null) stmtInsertItem.close(); if (stmtUpdateItem != null) stmtUpdateItem.close();
            if (stmtStream != null) stmtStream.close(); if (stmtExtra != null) stmtExtra.close();
            if (ok) Db.applyPendingRestore(db); // T96: episode progress from a backup, now that the episodes exist
        }
    }

    public static List<Episode> episodes(Db db, long seriesItemId) {
        List<Episode> list = new ArrayList<>(); if (db == null) return list;
        Cursor c = db.getReadableDatabase().rawQuery("SELECT i.id, i.provider_id, i.season, i.episode, i.title, IFNULL(e.duration_s, 0), IFNULL(e.plot, ''), IFNULL(e.cover, ''), IFNULL(p.position_ms, 0), IFNULL(p.duration_ms, 0), IFNULL(p.watched, 0) FROM item i LEFT JOIN episode_extra e ON e.item_id = i.id LEFT JOIN progress p ON p.item_id = i.id AND p.profile_id = 1 WHERE i.parent_id = ? AND i.kind = 'episode' ORDER BY i.season ASC, i.episode ASC, i.id ASC", new String[]{String.valueOf(seriesItemId)});
        try {
            while (c.moveToNext()) {
                list.add(new Episode(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4),
                        c.getInt(5), c.getString(6), c.getString(7), c.getLong(8), c.getLong(9), c.getInt(10) != 0));
            }
        } finally { c.close(); }
        return list;
    }

    public static List<Season> seasons(Db db, long seriesItemId) {
        List<Season> list = new ArrayList<>(); if (db == null) return list;
        Cursor c = db.getReadableDatabase().rawQuery("SELECT season, COUNT(*) FROM item WHERE parent_id = ? AND kind = 'episode' GROUP BY season ORDER BY season ASC", new String[]{String.valueOf(seriesItemId)});
        try { while (c.moveToNext()) list.add(new Season(c.getInt(0), c.getInt(1))); } finally { c.close(); }
        return list;
    }

    public static List<ParsedEpisode> parseEpisodesJson(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int r;
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        return parseEpisodesJson(bos.toString("UTF-8"));
    }

    public static List<ParsedEpisode> parseEpisodesJson(String json) {
        List<ParsedEpisode> list = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return list;
        Object root = new JsonParser(json).parse();
        if (!(root instanceof Map)) return list;
        Map<?, ?> rootMap = (Map<?, ?>) root;
        Object epsObj = rootMap.get("episodes");
        if (epsObj instanceof Map) {
            Map<?, ?> epsMap = (Map<?, ?>) epsObj;
            for (Map.Entry<?, ?> entry : epsMap.entrySet()) {
                int kSeason = toInt(String.valueOf(entry.getKey()), 0);
                if (entry.getValue() instanceof List) {
                    for (Object o : (List<?>) entry.getValue()) {
                        if (o instanceof Map) { ParsedEpisode ep = parseEpisodeMap((Map<?, ?>) o, kSeason); if (ep != null) list.add(ep); }
                    }
                }
            }
        } else if (epsObj instanceof List) {
            List<?> epsList = (List<?>) epsObj;
            int arrSeason = 1;
            for (Object item : epsList) {
                if (item instanceof List) {
                    for (Object o : (List<?>) item) {
                        if (o instanceof Map) { ParsedEpisode ep = parseEpisodeMap((Map<?, ?>) o, arrSeason); if (ep != null) list.add(ep); }
                    }
                    arrSeason++;
                } else if (item instanceof Map) {
                    ParsedEpisode ep = parseEpisodeMap((Map<?, ?>) item, 0); if (ep != null) list.add(ep);
                }
            }
        }
        return list;
    }

    private static ParsedEpisode parseEpisodeMap(Map<?, ?> m, int defaultSeason) {
        Object idObj = m.get("id"); if (idObj == null) return null;
        String id = String.valueOf(idObj).trim();
        if (id.isEmpty() || "null".equals(id) || "0".equals(id)) return null;

        Object titleObj = m.get("title");
        String title = titleObj != null ? String.valueOf(titleObj) : null;
        int epNum = toInt(str(m.get("episode_num")), 0);
        int season = toInt(str(m.get("season")), 0);
        int finalSeason = season > 0 ? season : (defaultSeason > 0 ? defaultSeason : 1);
        String finalTitle = title != null ? title : ("Episode " + (epNum > 0 ? epNum : 1));

        String ext = str(m.get("container_extension"));
        ext = ext.trim().toLowerCase(Locale.ROOT);
        if (ext.startsWith(".")) ext = ext.substring(1);

        String plot = "", cover = ""; int durationSec = 0;
        Object infoObj = m.get("info");
        if (infoObj instanceof Map) {
            Map<?, ?> info = (Map<?, ?>) infoObj;
            if (info.containsKey("plot")) plot = str(info.get("plot"));
            if (info.containsKey("movie_image")) cover = str(info.get("movie_image"));
            else if (info.containsKey("cover")) cover = str(info.get("cover"));
            durationSec = toInt(str(info.get("duration_secs")), 0);
            if (durationSec == 0 && info.containsKey("duration")) durationSec = parseDuration(str(info.get("duration")));
        }
        if (plot.isEmpty() && m.containsKey("plot")) plot = str(m.get("plot"));
        if (cover.isEmpty()) {
            if (m.containsKey("movie_image")) cover = str(m.get("movie_image"));
            else if (m.containsKey("cover")) cover = str(m.get("cover"));
        }
        return new ParsedEpisode(id, finalSeason, epNum > 0 ? epNum : 1, finalTitle, ext, plot, cover, durationSec);
    }

    private static int parseDuration(String s) {
        if (s == null) return 0;
        String[] p = s.trim().split(":");
        try {
            if (p.length == 3) return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
            if (p.length == 2) return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
            if (p.length == 1) return Integer.parseInt(p[0]);
        } catch (Exception ignored) {}
        return 0;
    }

    private static int toInt(String s, int d) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; } }
    private static long toLong(String s, long d) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return d; } }
    private static Double toDouble(String s) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return null; } }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String nextStr(JsonReader r, String def) throws IOException {
        android.util.JsonToken t = r.peek();
        if (t == android.util.JsonToken.NULL) { r.nextNull(); return def; }
        if (t == android.util.JsonToken.NUMBER || t == android.util.JsonToken.STRING) return r.nextString();
        if (t == android.util.JsonToken.BOOLEAN) return String.valueOf(r.nextBoolean());
        r.skipValue(); return def;
    }

    private static final class JsonParser {
        private final String s; private int i;
        JsonParser(String s) { this.s = s; }
        private void ws() { while (i < s.length() && s.charAt(i) <= ' ') i++; }
        Object parse() { ws(); return val(); }
        private Object val() {
            ws(); if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '{') return obj(); if (c == '[') return arr(); if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return num();
        }
        private Map<String, Object> obj() {
            Map<String, Object> m = new HashMap<>(); i++; ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (i < s.length()) {
                ws(); if (i >= s.length() || s.charAt(i) != '"') break;
                String k = str(); ws(); if (i < s.length() && s.charAt(i) == ':') i++;
                m.put(k, val()); ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
            }
            return m;
        }
        private List<Object> arr() {
            List<Object> l = new ArrayList<>(); i++; ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (i < s.length()) {
                l.add(val()); ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
            }
            return l;
        }
        private String str() {
            i++; StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    if (e == '"' || e == '\\' || e == '/') b.append(e);
                    else if (e == 'n') b.append('\n'); else if (e == 'r') b.append('\r'); else if (e == 't') b.append('\t');
                    else if (e == 'u' && i + 4 <= s.length()) {
                        try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); } catch (Exception ignored) {}
                        i += 4;
                    } else b.append(e);
                } else b.append(c);
            }
            return b.toString();
        }
        private Object num() {
            int st = i; if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E')) i++;
            return s.substring(st, i);
        }
    }
}
