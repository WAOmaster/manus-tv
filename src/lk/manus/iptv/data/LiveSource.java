package lk.manus.iptv.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lk.manus.iptv.Config;

/**
 * T95: live channels of an Xtream source (item kind 'live', stream template {BASE}/live/{USER}/{PASS}/id.ts) with the
 * catch-up window of each, and the earlier programmes of a channel. The login is joined only at play / request time, never logged.
 */
public final class LiveSource {
    private static final String TAG = "IPTV";
    private LiveSource() {}

    /** T98: runs (on the sync thread) after a successful live sync so the app can reload its channel list; set by the player screen. */
    public static volatile Runnable onSynced;

    private static String str(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.NULL) { r.nextNull(); return null; }
        if (t == JsonToken.NUMBER || t == JsonToken.STRING) return r.nextString();
        r.skipValue();
        return null;
    }

    private static int num(String s) {
        try { return s == null ? 0 : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static HttpURLConnection open(String url, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(readMs); c.setRequestProperty("User-Agent", "ManusTV");
        return c;
    }

    /** {address, user, pass} (login url-encoded) of a source, null when missing. */
    private static String[] login(SQLiteDatabase d, String sql, String arg) {
        Cursor c = d.rawQuery(sql, new String[]{arg});
        String addr = null, ref = null, extra = null;
        try { if (c.moveToFirst()) { addr = c.getString(0); ref = c.getString(1); if (c.getColumnCount() > 2) extra = c.getString(2); } } finally { c.close(); }
        if (addr == null || ref == null) return null;
        String creds = Secrets.open(ref);
        if (creds == null) return null;
        String[] p = creds.split("\n", 2);
        if (p.length < 2) return null;
        return new String[]{addr, XtreamSource.urlEncode(p[0]), XtreamSource.urlEncode(p[1]), extra};
    }

    /** Blocking. Writes the source's live channels (kind 'live') with archive_days; rows no longer offered are removed. */
    public static void syncLive(long sourceId) {
        if (Config.PLAY) return;
        Db db = Db.get();
        if (db == null) return;
        SQLiteDatabase d = db.getWritableDatabase();
        String[] a = login(d, "SELECT address, secret_ref FROM source WHERE id = ? AND kind = 'xtream'", String.valueOf(sourceId));
        if (a == null) return;
        String api = a[0] + "/player_api.php?username=" + a[1] + "&password=" + a[2] + "&action=";
        Map<String, String> cats = new HashMap<>();
        HttpURLConnection cc = null;
        try {
            cc = open(api + "get_live_categories", 15000);
            if (cc.getResponseCode() < 400) {
                JsonReader r = new JsonReader(new InputStreamReader(cc.getInputStream(), StandardCharsets.UTF_8));
                r.beginArray();
                while (r.hasNext()) {
                    r.beginObject(); String id = null, name = null;
                    while (r.hasNext()) { String k = r.nextName(); if ("category_id".equals(k)) id = str(r); else if ("category_name".equals(k)) name = str(r); else r.skipValue(); }
                    r.endObject();
                    if (id != null && name != null) cats.put(id.trim(), name.trim());
                }
                r.endArray(); r.close();
            }
        } catch (Exception ignored) { // categories are optional
        } finally { if (cc != null) cc.disconnect(); }

        Map<String, Long> old = new HashMap<>();
        Cursor oc = d.rawQuery("SELECT provider_id, id FROM item WHERE source_id = ? AND kind = 'live'", new String[]{String.valueOf(sourceId)});
        try { while (oc.moveToNext()) old.put(oc.getString(0), oc.getLong(1)); } finally { oc.close(); }

        HttpURLConnection sc = null;
        boolean tx = false;
        int n = 0, arch = 0;
        boolean changed = false;
        try {
            sc = open(api + "get_live_streams", 60000);
            if (sc.getResponseCode() >= 400) return;
            JsonReader r = new JsonReader(new InputStreamReader(sc.getInputStream(), StandardCharsets.UTF_8));
            d.beginTransaction(); tx = true;
            r.beginArray();
            while (r.hasNext()) {
                r.beginObject();
                String sid = null, name = null, cat = null, icon = null, tva = null, tvd = null;
                while (r.hasNext()) {
                    String k = r.nextName();
                    if ("stream_id".equals(k)) sid = str(r); else if ("name".equals(k)) name = str(r); else if ("category_id".equals(k)) cat = str(r);
                    else if ("stream_icon".equals(k)) icon = str(r); else if ("tv_archive".equals(k)) tva = str(r); else if ("tv_archive_duration".equals(k)) tvd = str(r);
                    else r.skipValue();
                }
                r.endObject();
                if (sid == null || sid.trim().isEmpty() || "0".equals(sid.trim()) || name == null) continue;
                sid = sid.trim();
                String group = cat != null && cats.containsKey(cat.trim()) ? cats.get(cat.trim()) : "";
                int days = Catchup.archiveDays(num(tva), num(tvd));
                ContentValues v = new ContentValues();
                v.put("title", name); v.put("clean_title", name); v.put("year", 0); v.put("group_name", group); v.put("container", "ts"); v.put("archive_days", days);
                Long id = old.remove(sid);
                if (id != null) d.update("item", v, "id = ?", new String[]{String.valueOf(id)});
                else {
                    v.put("source_id", sourceId); v.put("kind", "live"); v.put("provider_id", sid); v.put("added_at", 0);
                    id = d.insertOrThrow("item", null, v);
                }
                d.execSQL("INSERT OR REPLACE INTO stream(item_id, url_template, quality, \"order\") VALUES(?, ?, '', 0)", new Object[]{id, "{BASE}/live/{USER}/{PASS}/" + sid + ".ts"});
                d.execSQL("INSERT OR REPLACE INTO meta(item_id, poster, match_source) VALUES(?, ?, 'provider')", new Object[]{id, icon != null ? icon.trim() : ""});
                n++;
                if (days > 0) arch++;
            }
            r.endArray(); r.close();
            for (long id : old.values()) {
                d.execSQL("DELETE FROM stream WHERE item_id = ?", new Object[]{id});
                d.execSQL("DELETE FROM meta WHERE item_id = ?", new Object[]{id});
                d.execSQL("DELETE FROM item WHERE id = ?", new Object[]{id});
            }
            d.setTransactionSuccessful();
            Log.i(TAG, "live sync: " + n + " channels, " + arch + " with catch-up");
            changed = true;
        } catch (Exception e) {
            Log.w(TAG, "live sync failed: " + e.getClass().getSimpleName());
        } finally {
            if (tx) d.endTransaction();
            if (sc != null) sc.disconnect();
            Runnable cb = onSynced;
            if (changed && cb != null) cb.run(); // after the commit: the reload reads the new rows
        }
    }

    /** The Xtream live channels as guide rows. The login is part of the address, as for any playlist link, and is never logged. */
    public static List<Channel> channels(Db db) {
        List<Channel> out = new ArrayList<>();
        if (Config.PLAY || db == null) return out;
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT i.id, i.provider_id, i.title, IFNULL(i.group_name, ''), i.archive_days, IFNULL(m.poster, ''), st.url_template, s.address, s.secret_ref, s.id " +
                "FROM item i JOIN source s ON s.id = i.source_id JOIN stream st ON st.item_id = i.id AND st.\"order\" = 0 " +
                "LEFT JOIN meta m ON m.item_id = i.id WHERE i.kind = 'live' AND s.kind = 'xtream'", null);
        Map<String, String[]> creds = new HashMap<>();
        try {
            while (c.moveToNext()) {
                String ref = c.getString(8);
                String[] p = creds.get(ref);
                if (p == null) {
                    String s = Secrets.open(ref);
                    p = s == null ? new String[0] : s.split("\n", 2);
                    creds.put(ref, p);
                }
                if (p.length < 2) continue;
                String url = XtreamSource.joinUrl(c.getString(6), c.getString(7), p[0], p[1]);
                if (url == null) continue;
                String g = c.getString(3);
                Channel raw = new Channel("", c.getString(2), "", c.getString(5), g.isEmpty() ? new String[0] : new String[]{g}, "", url, "", "");
                Channel ch = Normalizer.apply(raw, "xt" + c.getLong(9));
                ch.archiveDays = c.getInt(4);
                ch.xtItem = c.getLong(0);
                out.add(ch);
            }
        } finally { c.close(); }
        return out;
    }

    /** Blocking (network). Earlier programmes of a catch-up channel from get_simple_data_table, inside the archive window, newest first. */
    public static List<Catchup.Prog> earlier(Db db, long itemId, int days, long nowSec) {
        List<Catchup.Prog> all = new ArrayList<>();
        if (db == null) return all;
        String[] a = login(db.getReadableDatabase(), "SELECT s.address, s.secret_ref, i.provider_id FROM item i JOIN source s ON s.id = i.source_id WHERE i.id = ?", String.valueOf(itemId));
        if (a == null || a[3] == null) return all;
        HttpURLConnection hc = null;
        try {
            hc = open(a[0] + "/player_api.php?username=" + a[1] + "&password=" + a[2] + "&action=get_simple_data_table&stream_id=" + XtreamSource.urlEncode(a[3]), 20000);
            if (hc.getResponseCode() >= 400) return all;
            JsonReader r = new JsonReader(new InputStreamReader(hc.getInputStream(), StandardCharsets.UTF_8));
            r.beginObject();
            while (r.hasNext()) {
                if (!"epg_listings".equals(r.nextName())) { r.skipValue(); continue; }
                r.beginArray();
                while (r.hasNext()) {
                    r.beginObject();
                    String title = null, start = null, ts = null, te = null, arch = null;
                    while (r.hasNext()) {
                        String k = r.nextName();
                        if ("title".equals(k)) title = str(r); else if ("start".equals(k)) start = str(r); else if ("start_timestamp".equals(k)) ts = str(r);
                        else if ("stop_timestamp".equals(k)) te = str(r); else if ("has_archive".equals(k)) arch = str(r); else r.skipValue();
                    }
                    r.endObject();
                    try {
                        String t = title == null ? "" : new String(Base64.decode(title, Base64.DEFAULT), StandardCharsets.UTF_8).trim();
                        if (!t.isEmpty() && ts != null && te != null) all.add(new Catchup.Prog(t, start, Long.parseLong(ts.trim()), Long.parseLong(te.trim()), num(arch) == 1));
                    } catch (RuntimeException ignored) { /* one bad row is skipped */ }
                }
                r.endArray();
            }
            r.close();
        } catch (Exception e) {
            Log.w(TAG, "earlier programmes failed: " + e.getClass().getSimpleName());
        } finally { if (hc != null) hc.disconnect(); }
        return Catchup.window(all, nowSec, days);
    }

    /** The real /timeshift/ address of one programme, login joined here and nowhere else. Null when it cannot be built. */
    public static String catchupUrl(Db db, long itemId, Catchup.Prog p) {
        if (db == null) return null;
        SQLiteDatabase d = db.getReadableDatabase();
        Cursor c = d.rawQuery("SELECT i.provider_id, s.address, s.secret_ref FROM item i JOIN source s ON s.id = i.source_id WHERE i.id = ?", new String[]{String.valueOf(itemId)});
        String pid = null, addr = null, ref = null;
        try { if (c.moveToFirst()) { pid = c.getString(0); addr = c.getString(1); ref = c.getString(2); } } finally { c.close(); }
        if (pid == null || addr == null || ref == null) return null;
        String creds = Secrets.open(ref);
        if (creds == null) return null;
        String[] up = creds.split("\n", 2);
        String tpl = Catchup.template(p, pid);
        return up.length < 2 || tpl == null ? null : XtreamSource.joinUrl(tpl, addr, up[0], up[1]);
    }
}
