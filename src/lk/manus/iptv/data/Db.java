package lk.manus.iptv.data;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lk.manus.iptv.Config;

/**
 * catalogue.db (NEXT-GEN 7.3). T54 only moves user state into it: favourites and recents (table "list"), play counts ("play") and the per-channel
 * choices ("choice": subs, audio, vols keyed by {@link Keys#of}, vers keyed by Dedup's group key). Everything is read once into memory on a worker thread,
 * so Store's reads stay as fast as before; writes go through one background thread. First open imports the old SharedPreferences strings (see migrate).
 * Tables beyond 7.3: "play" and "choice" (7.3 has nowhere for a play count or a remembered choice). Other 7.3 tables are created empty for later tasks.
 */
public final class Db extends SQLiteOpenHelper {
    public static final int VERSION = 6;
    public static final String ADD_ARCHIVE_DAYS = "ALTER TABLE item ADD COLUMN archive_days INTEGER NOT NULL DEFAULT 0"; // v6: Xtream catch-up window of a live channel; existing rows keep their data
    private static final String TAG = "IPTV", MIGRATED = "db_migrated";
    private static final String[] CHOICE_KINDS = {"subs", "audio", "vols", "vers"}; // same order as OldState.NAMES[3..6]
    /** DEBUG hook (PlayerActivity extra "dbfail"): the import throws after writing, to prove the rollback. Never set in a release build. */
    public static volatile boolean forceFail;

    private static volatile boolean started;
    private static volatile Db inst;
    private static final CountDownLatch ready = new CountDownLatch(1);

    private final ExecutorService io = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "db-write"); t.setDaemon(true); return t; }
    });
    private List<String> favs = new ArrayList<>(), recents = new ArrayList<>();
    private Map<String, long[]> plays = new HashMap<>();
    private final Map<String, LinkedHashMap<String, String>> choices = new HashMap<>();
    private List<Reminders.Item> reminders = new ArrayList<>();
    private long seq;

    private Db(Context c) { super(c, "catalogue.db", null, VERSION); }

    /** Call once from PlayerActivity.onCreate (after FavGuard.init): opens, migrates if needed and loads on a worker thread. */
    public static synchronized void start(final Context c, final SharedPreferences p) {
        if (started) return;
        started = true;
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                Db d = null;
                try {
                    d = new Db(app);
                    if (!p.getBoolean(MIGRATED, false)) d.migrate(p, app.getFilesDir());
                    d.load();
                    inst = d;
                    Library.refreshAny(d);
                } catch (Throwable e) { // any failure: the old preferences stay the source of truth
                    Log.w(TAG, "db unavailable, using the old preferences: " + e.getClass().getSimpleName() + (e instanceof IllegalStateException ? " " + e.getMessage() : ""));
                    if (d != null) d.close();
                } finally { ready.countDown(); }
            }
        }, "db-open").start();
    }

    /** Waits for start() to finish. null = not started or unavailable: Store then keeps using the old preferences. */
    public static Db get() {
        if (!started) return null;
        try { ready.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        return inst;
    }

    // ---- schema ----

    @Override public void onCreate(SQLiteDatabase d) {
        String[] sql = {
            "CREATE TABLE source(id INTEGER PRIMARY KEY, kind TEXT NOT NULL, name TEXT, address TEXT, secret_ref TEXT, last_sync INTEGER)",
            "CREATE TABLE item(id INTEGER PRIMARY KEY, source_id INTEGER NOT NULL, kind TEXT NOT NULL, provider_id TEXT NOT NULL, parent_id INTEGER, season INTEGER, episode INTEGER,"
                + " title TEXT, clean_title TEXT, year INTEGER, group_name TEXT, container TEXT, added_at INTEGER, archive_days INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE stream(item_id INTEGER NOT NULL, url_template TEXT NOT NULL, quality TEXT, \"order\" INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(item_id, \"order\"))",
            "CREATE TABLE meta(item_id INTEGER PRIMARY KEY, tmdb_id INTEGER, imdb_id TEXT, plot TEXT, rating REAL, votes INTEGER, genres TEXT, runtime INTEGER, \"cast\" TEXT,"
                + " poster TEXT, backdrop TEXT, trailer TEXT, adult INTEGER, lang TEXT, match_score REAL, match_source TEXT, fetched_at INTEGER)",
            "CREATE TABLE progress(profile_id INTEGER NOT NULL, item_id INTEGER NOT NULL, position_ms INTEGER, duration_ms INTEGER, watched INTEGER, updated_at INTEGER, PRIMARY KEY(profile_id, item_id))",
            "CREATE TABLE \"list\"(profile_id INTEGER NOT NULL, item_id INTEGER NOT NULL, kind TEXT NOT NULL, \"order\" INTEGER NOT NULL, added_at INTEGER, PRIMARY KEY(profile_id, item_id, kind))",
            "CREATE TABLE skip(item_id INTEGER PRIMARY KEY, intro_start INTEGER, intro_end INTEGER, credits_start INTEGER)",
            "CREATE TABLE reminder(profile_id INTEGER NOT NULL, channel_key TEXT NOT NULL, title TEXT, start_utc INTEGER, channel_name TEXT)",
            "CREATE TABLE film_choice(item_id INTEGER NOT NULL, ord INTEGER NOT NULL, tmdb_id INTEGER NOT NULL, title TEXT, year INTEGER, poster TEXT, PRIMARY KEY(item_id, ord))",
            "CREATE TABLE profile(id INTEGER PRIMARY KEY, name TEXT, pin_hash TEXT, adult_allowed INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE play(item_id INTEGER PRIMARY KEY, n INTEGER NOT NULL, last_ms INTEGER NOT NULL)",
            "CREATE TABLE choice(kind TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY(kind, k))",
            "CREATE UNIQUE INDEX item_key ON item(source_id, kind, provider_id)",
            "CREATE INDEX item_title ON item(kind, clean_title)",
            "CREATE INDEX list_order ON \"list\"(profile_id, kind, \"order\")",
            "CREATE INDEX progress_recent ON progress(profile_id, updated_at)",
            "INSERT OR IGNORE INTO source(id, kind, name, address) VALUES(0, 'guide', 'TV Guide films', '')",
            "INSERT INTO source(id, kind, name, address) VALUES(1, 'm3u', 'Live channels', '')",
            "INSERT INTO profile(id, name, adult_allowed) VALUES(1, 'Default', 0)",
            "CREATE TABLE IF NOT EXISTS series_state(item_id INTEGER PRIMARY KEY, fetched_at INTEGER NOT NULL DEFAULT 0, cover TEXT, plot TEXT, rating REAL)",
            "CREATE TABLE IF NOT EXISTS episode_extra(item_id INTEGER PRIMARY KEY, cover TEXT, plot TEXT, duration_s INTEGER)",
            "CREATE TABLE IF NOT EXISTS pending_restore(id INTEGER PRIMARY KEY AUTOINCREMENT, source_id INTEGER NOT NULL, provider_id TEXT NOT NULL, kind TEXT NOT NULL, position_ms INTEGER, duration_ms INTEGER, watched INTEGER, updated_at INTEGER, \"order\" INTEGER, created_at INTEGER NOT NULL)"};
        for (String s : sql) d.execSQL(s);
    }

    @Override public void onUpgrade(SQLiteDatabase d, int from, int to) {
        if (from < 2) {
            d.execSQL("INSERT OR IGNORE INTO source(id, kind, name, address) VALUES(0, 'guide', 'TV Guide films', '')");
        }
        if (from < 3) d.execSQL("ALTER TABLE reminder ADD COLUMN channel_name TEXT"); // Settings > Reminders shows the channel without a lookup
        if (from < 4) d.execSQL("CREATE TABLE IF NOT EXISTS film_choice(item_id INTEGER NOT NULL, ord INTEGER NOT NULL, tmdb_id INTEGER NOT NULL, title TEXT, year INTEGER, poster TEXT, PRIMARY KEY(item_id, ord))"); // "Which film?" candidates of an ambiguous title
        if (from < 5) {
            d.execSQL("CREATE TABLE IF NOT EXISTS series_state(item_id INTEGER PRIMARY KEY, fetched_at INTEGER NOT NULL DEFAULT 0, cover TEXT, plot TEXT, rating REAL)");
            d.execSQL("CREATE TABLE IF NOT EXISTS episode_extra(item_id INTEGER PRIMARY KEY, cover TEXT, plot TEXT, duration_s INTEGER)");
        }
        if (from < 6) d.execSQL(ADD_ARCHIVE_DAYS);
    }

    // ---- one-time import of the old preferences ----

    /**
     * (a) backup file with the raw old strings, (b) import in ONE transaction, (c) verify the rows read back equal what was parsed,
     * (d) only then db_migrated=1. Any exception rolls back and leaves the old preferences untouched (they are never modified here).
     */
    private void migrate(SharedPreferences p, File filesDir) throws Exception {
        String[] raw = new String[OldState.NAMES.length];
        boolean any = false;
        for (int i = 0; i < raw.length; i++) { raw[i] = p.getString(OldState.NAMES[i], ""); any |= !raw[i].isEmpty(); }
        OldState o = OldState.parse(raw);
        String note = "nothing to back up";
        if (any) { // a fresh install has nothing to lose
            File dir = new File(filesDir, "backup");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("backup folder not created");
            File f = new File(dir, "pre-db-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".txt");
            byte[] bytes = OldState.backupText(new Date().toString(), raw).getBytes(StandardCharsets.UTF_8);
            Files.write(f.toPath(), bytes);
            if (f.length() != bytes.length) throw new IllegalStateException("backup file incomplete");
            note = f.getName() + " " + f.length() + " B";
        }
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.execSQL("DELETE FROM \"list\"");
            d.execSQL("DELETE FROM play");
            d.execSQL("DELETE FROM choice");
            long now = System.currentTimeMillis();
            replaceList(d, "fav", o.favs, now);
            replaceList(d, "recent", o.recents, now);
            for (Map.Entry<String, long[]> e : o.plays.entrySet()) writePlay(d, e.getKey(), e.getValue()[0], e.getValue()[1]);
            Map<String, String>[] ch = choiceMaps(o);
            for (int i = 0; i < CHOICE_KINDS.length; i++) for (Map.Entry<String, String> e : ch[i].entrySet()) writeChoice(d, CHOICE_KINDS[i], e.getKey(), e.getValue(), ++seq);
            if (forceFail) throw new IllegalStateException("forced failure (DEBUG dbfail)");
            int[] want = o.counts(), got = {urls(d, "fav").size(), urls(d, "recent").size(), count(d, "play", null), count(d, "choice", "subs"), count(d, "choice", "audio"), count(d, "choice", "vols"), count(d, "choice", "vers")};
            if (!Arrays.equals(want, got) || !urls(d, "fav").equals(o.favs) || !urls(d, "recent").equals(o.recents))
                throw new IllegalStateException("count mismatch, expected " + Arrays.toString(want) + " got " + Arrays.toString(got));
            d.setTransactionSuccessful();
            Log.i(TAG, "db migrated " + Arrays.toString(OldState.NAMES) + "=" + Arrays.toString(want) + ", dropped " + o.dropped + ", backup " + note);
        } finally { d.endTransaction(); }
        if (!p.edit().putBoolean(MIGRATED, true).commit()) throw new IllegalStateException("db_migrated not saved");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String>[] choiceMaps(OldState o) { return new Map[]{o.subs, o.audio, o.vols, o.vers}; }

    private static int count(SQLiteDatabase d, String table, String kind) {
        Cursor c = d.rawQuery("SELECT COUNT(*) FROM " + table + (kind == null ? "" : " WHERE kind=?"), kind == null ? null : new String[]{kind});
        try { c.moveToFirst(); return c.getInt(0); } finally { c.close(); }
    }

    // ---- reads (startup, worker thread) ----

    private static List<String> urls(SQLiteDatabase d, String kind) {
        List<String> l = new ArrayList<>();
        Cursor c = d.rawQuery("SELECT s.url_template FROM \"list\" l JOIN stream s ON s.item_id = l.item_id AND s.\"order\" = 0 WHERE l.profile_id = 1 AND l.kind = ? ORDER BY l.\"order\"", new String[]{kind});
        try { while (c.moveToNext()) l.add(c.getString(0)); } finally { c.close(); }
        return l;
    }

    private synchronized void load() {
        SQLiteDatabase d = getReadableDatabase();
        favs = urls(d, "fav");
        recents = urls(d, "recent");
        Cursor c = d.rawQuery("SELECT s.url_template, p.n, p.last_ms FROM play p JOIN stream s ON s.item_id = p.item_id AND s.\"order\" = 0", null);
        try { while (c.moveToNext()) plays.put(c.getString(0), new long[]{c.getLong(1), c.getLong(2)}); } finally { c.close(); }
        c = d.rawQuery("SELECT kind, k, v, seq FROM choice ORDER BY seq", null);
        try {
            while (c.moveToNext()) { map(c.getString(0)).put(c.getString(1), c.getString(2)); seq = Math.max(seq, c.getLong(3)); }
        } finally { c.close(); }
        c = d.rawQuery("SELECT channel_key, title, channel_name, start_utc FROM reminder WHERE profile_id = 1 ORDER BY start_utc", null);
        try { while (c.moveToNext()) reminders.add(new Reminders.Item(c.getString(0), c.getString(1) == null ? "" : c.getString(1), c.getString(2) == null ? "" : c.getString(2), c.getLong(3))); } finally { c.close(); }
        Log.i(TAG, "db ready: favs " + favs.size() + ", recents " + recents.size() + ", plays " + plays.size() + ", subs " + map("subs").size() + ", audio " + map("audio").size()
                + ", vols " + map("vols").size() + ", vers " + map("vers").size());
        if (Config.DEBUG) Log.i(TAG, "db favs keys " + keys(favs) + " recents keys " + keys(recents)); // hashes only, never urls
    }

    private static List<String> keys(List<String> urls) { List<String> k = new ArrayList<>(); for (String u : urls) k.add(Keys.of(u)); return k; }

    private LinkedHashMap<String, String> map(String kind) {
        LinkedHashMap<String, String> m = choices.get(kind);
        if (m == null) choices.put(kind, m = new LinkedHashMap<>());
        return m;
    }

    // ---- Store's view: memory first, then a queued write ----

    /** kind "fav" or "recent": the urls in order (a copy). */
    public synchronized List<String> list(String kind) { return new ArrayList<>(kind.equals("fav") ? favs : recents); }

    public synchronized void putList(final String kind, List<String> urls) {
        final List<String> copy = new ArrayList<>(urls);
        if (kind.equals("fav")) favs = copy; else recents = copy;
        writeNow(new Runnable() { @Override public void run() {
            SQLiteDatabase d = getWritableDatabase();
            d.beginTransaction();
            try { replaceList(d, kind, copy, System.currentTimeMillis()); d.setTransactionSuccessful(); } finally { d.endTransaction(); }
        } });
    }

    /** The plays loaded at startup (url -> {count, last played ms}); Store keeps and edits its own copy and reports each change with {@link #play}. */
    public synchronized Map<String, long[]> loadedPlays() { return new HashMap<>(plays); }

    public void play(final String url, final long n, final long lastMs, final List<String> evicted) {
        write(new Runnable() { @Override public void run() {
            SQLiteDatabase d = getWritableDatabase();
            d.beginTransaction();
            try {
                writePlay(d, url, n, lastMs);
                for (String u : evicted) d.execSQL("DELETE FROM play WHERE item_id IN (SELECT id FROM item WHERE source_id = 1 AND kind = 'live' AND provider_id = ?)", new Object[]{Keys.of(u)});
                d.setTransactionSuccessful();
            } finally { d.endTransaction(); }
        } });
    }

    /** "" when not set. */
    public synchronized String choice(String kind, String key) { String v = map(kind).get(key); return v == null ? "" : v; }

    public synchronized Map<String, String> choices(String kind) { return new HashMap<>(map(kind)); }

    /** Same, oldest first (a backup keeps that order). */
    public synchronized Map<String, String> choicesOrdered(String kind) { return new LinkedHashMap<>(map(kind)); }

    /** val "" removes the choice. Newest-set last; the oldest go once more than cap are held (same rule the preference lists had). */
    public synchronized void setChoice(final String kind, final String key, final String val, int cap) {
        LinkedHashMap<String, String> m = map(kind);
        m.remove(key);
        if (!val.isEmpty()) m.put(key, val);
        final List<String> evicted = new ArrayList<>();
        for (Iterator<String> it = m.keySet().iterator(); m.size() > cap; ) { evicted.add(it.next()); it.remove(); }
        final long s = ++seq;
        writeNow(new Runnable() { @Override public void run() {
            SQLiteDatabase d = getWritableDatabase();
            d.beginTransaction();
            try {
                if (val.isEmpty()) d.execSQL("DELETE FROM choice WHERE kind = ? AND k = ?", new Object[]{kind, key}); else writeChoice(d, kind, key, val, s);
                for (String k : evicted) d.execSQL("DELETE FROM choice WHERE kind = ? AND k = ?", new Object[]{kind, k});
                d.setTransactionSuccessful();
            } finally { d.endTransaction(); }
        } });
    }

    // ---- reminders (memory first, then a queued write) ----

    /** Soonest first (a copy). */
    public synchronized List<Reminders.Item> reminders() { return new ArrayList<>(reminders); }

    public synchronized void reminderAdd(final Reminders.Item i) {
        for (Reminders.Item o : reminders) if (o.startSec == i.startSec && o.key.equals(i.key)) return;
        reminders.add(i);
        java.util.Collections.sort(reminders, new java.util.Comparator<Reminders.Item>() { @Override public int compare(Reminders.Item a, Reminders.Item b) { return Long.compare(a.startSec, b.startSec); } });
        writeNow(new Runnable() { @Override public void run() {
            getWritableDatabase().execSQL("INSERT INTO reminder(profile_id, channel_key, title, start_utc, channel_name) VALUES(1, ?, ?, ?, ?)", new Object[]{i.key, i.title, i.startSec, i.channel});
        } });
    }

    public synchronized void reminderRemove(final String key, final long startSec) {
        for (Iterator<Reminders.Item> it = reminders.iterator(); it.hasNext(); ) { Reminders.Item o = it.next(); if (o.startSec == startSec && o.key.equals(key)) it.remove(); }
        writeNow(new Runnable() { @Override public void run() {
            getWritableDatabase().execSQL("DELETE FROM reminder WHERE profile_id = 1 AND channel_key = ? AND start_utc = ?", new Object[]{key, startSec});
        } });
    }

    // ---- My list (T104): films and series, kind 'mylist' in "list", newest has the highest "order" ----

    public boolean isInMyList(long itemId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM \"list\" WHERE profile_id = 1 AND kind = 'mylist' AND item_id = ?", new String[]{String.valueOf(itemId)});
        try { return c.moveToFirst(); } finally { c.close(); }
    }

    /** Newest added first. */
    public List<Long> myListIds() {
        List<Long> ids = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT item_id FROM \"list\" WHERE profile_id = 1 AND kind = 'mylist' ORDER BY \"order\" DESC", null);
        try { while (c.moveToNext()) ids.add(c.getLong(0)); } finally { c.close(); }
        return ids;
    }

    /** On disk before this returns (T103 pattern). */
    public void setMyList(final long itemId, final boolean on) {
        writeNow(new Runnable() { @Override public void run() {
            SQLiteDatabase d = getWritableDatabase();
            if (!on) { d.execSQL("DELETE FROM \"list\" WHERE profile_id = 1 AND kind = 'mylist' AND item_id = ?", new Object[]{itemId}); return; }
            long next = 0;
            Cursor c = d.rawQuery("SELECT IFNULL(MAX(\"order\"), -1) + 1 FROM \"list\" WHERE profile_id = 1 AND kind = 'mylist'", null);
            try { if (c.moveToFirst()) next = c.getLong(0); } finally { c.close(); }
            d.execSQL("INSERT OR REPLACE INTO \"list\"(profile_id, item_id, kind, \"order\", added_at) VALUES(1, ?, 'mylist', ?, ?)", new Object[]{itemId, next, System.currentTimeMillis()});
        } });
    }

    // ---- film progress (T83) ----

    public static final class Progress {
        public final long positionMs;
        public final long durationMs;
        public final boolean watched;
        public final long updatedAt;

        public Progress(long positionMs, long durationMs, boolean watched, long updatedAt) {
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.watched = watched;
            this.updatedAt = updatedAt;
        }
    }

    public Progress progress(long itemId) {
        SQLiteDatabase d = getReadableDatabase();
        Cursor c = d.rawQuery("SELECT position_ms, duration_ms, watched, updated_at FROM progress WHERE profile_id = 1 AND item_id = ?",
                new String[]{String.valueOf(itemId)});
        try {
            if (c.moveToFirst()) {
                return new Progress(c.getLong(0), c.getLong(1), c.getInt(2) != 0, c.getLong(3));
            }
            return null;
        } finally {
            c.close();
        }
    }

    public void saveProgress(final long itemId, final long positionMs, final long durationMs, final boolean watched) {
        final long now = System.currentTimeMillis();
        write(new Runnable() {
            @Override public void run() {
                saveProgressDirect(itemId, positionMs, durationMs, watched, now);
            }
        });
    }

    public void saveProgressSync(final long itemId, final long positionMs, final long durationMs, final boolean watched) {
        final long now = System.currentTimeMillis();
        writeNow(new Runnable() { @Override public void run() { saveProgressDirect(itemId, positionMs, durationMs, watched, now); } }); // behind any queued save, so the newest position wins
    }

    private void saveProgressDirect(long itemId, long positionMs, long durationMs, boolean watched, long now) {
        SQLiteDatabase d = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("profile_id", 1);
        v.put("item_id", itemId);
        v.put("position_ms", positionMs);
        v.put("duration_ms", durationMs);
        v.put("watched", watched ? 1 : 0);
        v.put("updated_at", now);
        d.insertWithOnConflict("progress", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Episode passed 95% or ended: progress cleared, watched set (same queue as saveProgress, so order holds). */
    public void markWatchedAsync(final long itemId) {
        write(new Runnable() { @Override public void run() { SeriesLogic.markWatched(Db.this, itemId, true); } });
    }

    public void deleteProgress(final long itemId) {
        write(new Runnable() {
            @Override public void run() {
                deleteProgressDirect(itemId);
            }
        });
    }

    public void deleteProgressSync(long itemId) {
        deleteProgressDirect(itemId);
    }

    private void deleteProgressDirect(long itemId) {
        SQLiteDatabase d = getWritableDatabase();
        int n = d.delete("progress", "profile_id = 1 AND item_id = ?", new String[]{String.valueOf(itemId)});
        android.util.Log.i(TAG, "Db.deleteProgressDirect: itemId=" + itemId + " deletedRows=" + n);
    }

    public boolean hasSourceProgress(long sourceId) {
        return XtreamSource.hasProgress(this, sourceId);
    }

    public void deleteSourceProgress(long sourceId) {
        XtreamSource.deleteProgress(this, sourceId);
    }

    // ---- restore (T72) ----

    /**
     * Replaces favourites, recents, play counts, remembered choices and reminders with {@code d} in ONE transaction, and commits the preference editor
     * INSIDE it, just before the transaction is marked good: if the rows do not read back as written, or the preferences do not save, everything rolls back
     * and nothing has changed. Runs on the single db-write thread (writes queued earlier finish first); blocks the caller until done. Throws on failure.
     */
    public void restore(final Backup.Data d, final SharedPreferences.Editor prefs) throws Exception {
        try {
            io.submit(new Callable<Void>() { @Override public Void call() { doRestore(d, prefs); return null; } }).get();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            throw c instanceof Exception ? (Exception) c : e;
        }
    }

    private void doRestore(Backup.Data d, SharedPreferences.Editor prefs) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis(), s = seq;
        db.beginTransaction();
        try {
            replaceList(db, "fav", d.favs, now);
            replaceList(db, "recent", d.recents, now);
            db.execSQL("DELETE FROM play");
            for (Backup.Play p : d.plays) writePlay(db, p.url, p.n, p.lastMs);
            db.execSQL("DELETE FROM choice");
            int nChoices = 0;
            for (String kind : Backup.CHOICE_KINDS) {
                List<String[]> l = d.choices.get(kind);
                if (l != null) for (String[] kv : l) { writeChoice(db, kind, kv[0], kv[1], ++s); nChoices++; }
            }
            db.execSQL("DELETE FROM reminder");
            for (Backup.Rem r : d.reminders)
                db.execSQL("INSERT INTO reminder(profile_id, channel_key, title, start_utc, channel_name) VALUES(1, ?, ?, ?, ?)", new Object[]{r.key, r.title, r.startSec, r.channel});
            if (!urls(db, "fav").equals(d.favs) || !urls(db, "recent").equals(d.recents) || count(db, "play", null) != d.plays.size()
                    || count(db, "choice", null) != nChoices || count(db, "reminder", null) != d.reminders.size())
                throw new IllegalStateException("restore did not read back as written");
            if (!prefs.commit()) throw new IllegalStateException("preferences not saved");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); } // not marked good: everything above is undone
        synchronized (this) { // the database now holds the backup: memory follows
            favs = new ArrayList<>(d.favs);
            recents = new ArrayList<>(d.recents);
            plays = new HashMap<>();
            for (Backup.Play p : d.plays) plays.put(p.url, new long[]{p.n, p.lastMs});
            choices.clear();
            for (String kind : Backup.CHOICE_KINDS) {
                List<String[]> l = d.choices.get(kind);
                if (l != null) for (String[] kv : l) map(kind).put(kv[0], kv[1]);
            }
            seq = s;
            reminders = new ArrayList<>();
            for (Backup.Rem r : d.reminders) reminders.add(new Reminders.Item(r.key, r.title, r.channel, r.startSec));
            java.util.Collections.sort(reminders, new java.util.Comparator<Reminders.Item>() { @Override public int compare(Reminders.Item a, Reminders.Item b) { return Long.compare(a.startSec, b.startSec); } });
        }
    }

    /** T103: a change the viewer made is on disk (one transaction, committed) before this returns, so a force-stop or crash cannot lose it. Same queue as write(), so order holds. */
    private void writeNow(final Runnable r) {
        if (Thread.currentThread().getName().equals("db-write")) { r.run(); return; } // already on the queue
        final boolean[] done = {false};
        io.execute(new Runnable() { @Override public void run() {
            try { r.run(); } catch (RuntimeException e) { Log.w(TAG, "db write failed: " + e.getClass().getSimpleName()); }
            finally { synchronized (Db.this) { done[0] = true; Db.this.notifyAll(); } }
        } });
        long end = System.currentTimeMillis() + 3000; // never hang the screen: past 3 s the write simply finishes in the background
        synchronized (this) { // wait() lets go of the Db lock, so a caller already holding it cannot block the queue
            try { while (!done[0] && System.currentTimeMillis() < end) wait(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void write(final Runnable r) {
        io.execute(new Runnable() { @Override public void run() {
            try { r.run(); } catch (RuntimeException e) { Log.w(TAG, "db write failed: " + e.getClass().getSimpleName()); } // memory stays right for this run
        } });
    }

    // ---- row helpers (call inside a transaction) ----

    private static long itemId(SQLiteDatabase d, String url, long now) {
        Cursor c = d.rawQuery("SELECT id FROM item WHERE source_id = 1 AND kind = 'live' AND provider_id = ?", new String[]{Keys.of(url)});
        try { if (c.moveToFirst()) return c.getLong(0); } finally { c.close(); }
        ContentValues v = new ContentValues();
        v.put("source_id", 1); v.put("kind", "live"); v.put("provider_id", Keys.of(url)); v.put("added_at", now);
        long id = d.insertOrThrow("item", null, v);
        d.execSQL("INSERT INTO stream(item_id, url_template, quality, \"order\") VALUES(?, ?, '', 0)", new Object[]{id, url}); // "order" is a keyword: ContentValues would not quote it
        return id;
    }

    private static void replaceList(SQLiteDatabase d, String kind, List<String> urls, long now) {
        d.execSQL("DELETE FROM \"list\" WHERE profile_id = 1 AND kind = ?", new Object[]{kind});
        int i = 0;
        for (String u : urls) d.execSQL("INSERT OR IGNORE INTO \"list\"(profile_id, item_id, kind, \"order\", added_at) VALUES(1, ?, ?, ?, ?)", new Object[]{itemId(d, u, now), kind, i++, now});
    }

    private static void writePlay(SQLiteDatabase d, String url, long n, long lastMs) {
        ContentValues v = new ContentValues();
        v.put("item_id", itemId(d, url, lastMs)); v.put("n", n); v.put("last_ms", lastMs);
        d.insertWithOnConflict("play", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static void writeChoice(SQLiteDatabase d, String kind, String key, String val, long seq) {
        ContentValues v = new ContentValues();
        v.put("kind", kind); v.put("k", key); v.put("v", val); v.put("seq", seq);
        d.insertWithOnConflict("choice", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public static void ensurePendingRestoreTable(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE IF NOT EXISTS pending_restore(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "source_id INTEGER NOT NULL, " +
                "provider_id TEXT NOT NULL, " +
                "kind TEXT NOT NULL, " +
                "position_ms INTEGER, " +
                "duration_ms INTEGER, " +
                "watched INTEGER, " +
                "updated_at INTEGER, " +
                "\"order\" INTEGER, " +
                "created_at INTEGER NOT NULL)");
    }

    public static void applyPendingRestore(Db db) {
        if (db == null) return;
        SQLiteDatabase d = db.getWritableDatabase();
        ensurePendingRestoreTable(d);
        long now = System.currentTimeMillis();
        d.execSQL("DELETE FROM pending_restore WHERE created_at < ?", new Object[]{now - 30L * 86400000L});

        Cursor c = d.rawQuery("SELECT id, source_id, provider_id, kind, position_ms, duration_ms, watched, updated_at, \"order\" FROM pending_restore", null);
        List<Long> appliedIds = new ArrayList<>();
        d.beginTransaction();
        try {
            while (c.moveToNext()) {
                long rowId = c.getLong(0);
                long sourceId = c.getLong(1);
                String providerId = c.getString(2);
                String kind = c.getString(3);
                long pos = c.getLong(4);
                long dur = c.getLong(5);
                int watched = c.getInt(6);
                long updated = c.getLong(7);
                int order = c.getInt(8);

                // T96: 'ep' = episode (provider_id = seriesId TAB season TAB episode), 'slist' = series; the rest are films
                String sql = "SELECT id FROM item WHERE source_id = ? AND provider_id = ? AND kind = 'movie'";
                String[] args = {String.valueOf(sourceId), providerId};
                if ("slist".equals(kind)) sql = "SELECT id FROM item WHERE source_id = ? AND provider_id = ? AND kind = 'series'";
                else if ("ep".equals(kind)) {
                    String[] pr = providerId.split("\t");
                    if (pr.length != 3) continue;
                    sql = "SELECT e.id FROM item e JOIN item s ON s.id = e.parent_id WHERE e.source_id = ? AND s.provider_id = ? AND s.kind = 'series' AND e.kind = 'episode' AND e.season = ? AND e.episode = ?";
                    args = new String[]{String.valueOf(sourceId), pr[0], pr[1], pr[2]};
                }
                Cursor ci = d.rawQuery(sql, args);
                long itemId = -1;
                try {
                    if (ci.moveToFirst()) itemId = ci.getLong(0);
                } finally {
                    ci.close();
                }

                if (itemId <= 0) continue; // film not yet synced

                if ("progress".equals(kind) || "ep".equals(kind)) {
                    Cursor cp = d.rawQuery("SELECT updated_at FROM progress WHERE profile_id = 1 AND item_id = ?",
                            new String[]{String.valueOf(itemId)});
                    boolean hasLocal = false;
                    long localUpdated = -1;
                    try {
                        if (cp.moveToFirst()) {
                            hasLocal = true;
                            localUpdated = cp.getLong(0);
                        }
                    } finally {
                        cp.close();
                    }

                    if (!hasLocal || updated > localUpdated) {
                        d.execSQL("INSERT OR REPLACE INTO progress(profile_id, item_id, position_ms, duration_ms, watched, updated_at) VALUES(1, ?, ?, ?, ?, ?)",
                                new Object[]{itemId, pos, dur, watched, updated});
                    }
                    appliedIds.add(rowId);
                } else if ("list".equals(kind) || "slist".equals(kind)) {
                    Cursor cl = d.rawQuery("SELECT added_at FROM \"list\" WHERE profile_id = 1 AND item_id = ?",
                            new String[]{String.valueOf(itemId)});
                    boolean hasLocal = false;
                    long localAdded = -1;
                    try {
                        if (cl.moveToFirst()) {
                            hasLocal = true;
                            localAdded = cl.getLong(0);
                        }
                    } finally {
                        cl.close();
                    }

                    if (!hasLocal || updated > localAdded) {
                        d.execSQL("INSERT OR REPLACE INTO \"list\"(profile_id, item_id, kind, \"order\", added_at) VALUES(1, ?, 'mylist', ?, ?)",
                                new Object[]{itemId, order, updated});
                    }
                    appliedIds.add(rowId);
                }
            }
            for (long id : appliedIds) {
                d.execSQL("DELETE FROM pending_restore WHERE id = ?", new Object[]{id});
            }
            d.setTransactionSuccessful();
        } finally {
            c.close();
            d.endTransaction();
        }
    }
}
