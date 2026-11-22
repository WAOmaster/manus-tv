package lk.manus.iptv.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * T72: backup and restore on this TV (the Android side; the file format and its checks are in {@link Backup}).
 * Copies live in filesDir/backup: manus-tv-yyyyMMdd-HHmm.mtvb (the newest 3 kept) and undo-*.mtvb (made automatically before every restore, the newest 3 kept).
 * Nothing here logs a passphrase, the JSON or an address; failures are logged by class name only.
 */
public final class BackupOps {
    private BackupOps() {}

    private static final String TAG = "IPTV", UNDO_KEY = "undo_key", EXT = ".mtvb";
    public static final int KEEP = 3;
    private static final String COPY = "manus-tv-\\d{8}-\\d{4}\\.mtvb", UNDO = "undo-\\d{8}-\\d{6}\\.mtvb";
    private static final int UNDO_KEY_BYTES = 32;

    private static File dir(Context ctx) { return new File(ctx.getFilesDir(), "backup"); }

    // ---- what is on this TV now ----

    /** The state as a backup holds it. Needs the database (it holds the favourites and the rest). */
    public static Backup.Data capture(Context ctx, SharedPreferences p, Store st, Sources src) {
        Db db = Db.get();
        if (db == null) throw new IllegalStateException("database unavailable");
        Backup.Data d = new Backup.Data();
        d.made = System.currentTimeMillis() / 1000;
        try { d.app = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception e) { d.app = ""; }
        if (d.app == null) d.app = "";
        d.sources.addAll(src.backupList());
        d.favs.addAll(st.favs());
        d.recents.addAll(st.recents());
        for (Map.Entry<String, long[]> e : st.playsSnapshot().entrySet()) d.plays.add(new Backup.Play(e.getKey(), e.getValue()[0], e.getValue()[1]));
        Collections.sort(d.plays, new java.util.Comparator<Backup.Play>() { @Override public int compare(Backup.Play a, Backup.Play b) { return Long.compare(b.lastMs, a.lastMs); } });
        for (String kind : Backup.CHOICE_KINDS) {
            List<String[]> l = new ArrayList<>();
            for (Map.Entry<String, String> e : db.choicesOrdered(kind).entrySet()) l.add(new String[]{e.getKey(), e.getValue()});
            if (!l.isEmpty()) d.choices.put(kind, l);
        }
        d.settings.putAll(Backup.settingsFromPrefs(p.getAll()));
        for (Reminders.Item i : Reminders.list()) d.reminders.add(new Backup.Rem(i.key, i.title, i.startSec, i.channel));
        if (!lk.manus.iptv.Config.PLAY) {
            android.database.sqlite.SQLiteDatabase sqldb = db.getReadableDatabase();
            Map<Long, Integer> sourceToXtIndex = new java.util.HashMap<>();
            android.database.Cursor cs = sqldb.rawQuery("SELECT id, name, address, secret_ref FROM source WHERE kind = 'xtream' ORDER BY id ASC", null);
            try {
                while (cs.moveToNext()) {
                    long sid = cs.getLong(0);
                    String name = cs.getString(1);
                    String addr = cs.getString(2);
                    String sref = cs.getString(3);
                    if (sref == null) continue;
                    String creds = Secrets.open(sref);
                    if (creds == null) continue;
                    String[] parts = creds.split("\n", 2);
                    if (parts.length < 2) continue;
                    String user = parts[0], pass = parts[1];
                    int idx = d.xt.size();
                    d.xt.add(new Backup.Xtream(name != null ? name : "", addr != null ? addr : "", user, pass));
                    sourceToXtIndex.put(sid, idx);
                }
            } finally {
                cs.close();
            }

            if (!sourceToXtIndex.isEmpty()) {
                android.database.Cursor cp = sqldb.rawQuery(
                        "SELECT i.source_id, i.provider_id, p.position_ms, p.duration_ms, p.watched, p.updated_at " +
                        "FROM progress p JOIN item i ON i.id = p.item_id " +
                        "WHERE p.profile_id = 1 AND i.kind = 'movie' ORDER BY p.updated_at DESC", null);
                try {
                    while (cp.moveToNext()) {
                        long sid = cp.getLong(0);
                        Integer xtIdx = sourceToXtIndex.get(sid);
                        if (xtIdx == null) continue;
                        String provId = cp.getString(1);
                        long pos = cp.getLong(2);
                        long dur = cp.getLong(3);
                        boolean watched = cp.getInt(4) != 0;
                        long updated = cp.getLong(5);
                        d.fp.add(new Backup.FilmProgress(xtIdx, provId, pos, dur, watched, updated));
                    }
                } finally {
                    cp.close();
                }

                android.database.Cursor cl = sqldb.rawQuery(
                        "SELECT i.source_id, i.provider_id, l.\"order\", l.added_at " +
                        "FROM \"list\" l JOIN item i ON i.id = l.item_id " +
                        "WHERE l.profile_id = 1 AND i.kind = 'movie' ORDER BY l.\"order\" ASC", null);
                try {
                    while (cl.moveToNext()) {
                        long sid = cl.getLong(0);
                        Integer xtIdx = sourceToXtIndex.get(sid);
                        if (xtIdx == null) continue;
                        String provId = cl.getString(1);
                        int order = cl.getInt(2);
                        long added = cl.getLong(3);
                        d.ml.add(new Backup.MyListFilm(xtIdx, provId, order, added));
                    }
                } finally {
                    cl.close();
                }

                // T96: episode progress and series in My list, keyed by source + series provider id (+ season + episode)
                android.database.Cursor ce = sqldb.rawQuery(
                        "SELECT e.source_id, s.provider_id, e.season, e.episode, p.position_ms, p.duration_ms, p.watched, p.updated_at " +
                        "FROM progress p JOIN item e ON e.id = p.item_id JOIN item s ON s.id = e.parent_id " +
                        "WHERE p.profile_id = 1 AND e.kind = 'episode' AND s.kind = 'series' ORDER BY p.updated_at DESC", null);
                try {
                    while (ce.moveToNext()) {
                        Integer xtIdx = sourceToXtIndex.get(ce.getLong(0));
                        if (xtIdx == null) continue;
                        d.ep.add(new Backup.EpProgress(xtIdx, ce.getString(1), ce.getInt(2), ce.getInt(3), ce.getLong(4), ce.getLong(5), ce.getInt(6) != 0, ce.getLong(7)));
                    }
                } finally {
                    ce.close();
                }
                android.database.Cursor cq = sqldb.rawQuery(
                        "SELECT i.source_id, i.provider_id, l.\"order\", l.added_at " +
                        "FROM \"list\" l JOIN item i ON i.id = l.item_id " +
                        "WHERE l.profile_id = 1 AND l.kind = 'mylist' AND i.kind = 'series' ORDER BY l.\"order\" ASC", null);
                try {
                    while (cq.moveToNext()) {
                        Integer xtIdx = sourceToXtIndex.get(cq.getLong(0));
                        if (xtIdx == null) continue;
                        d.sl.add(new Backup.SeriesListEntry(xtIdx, cq.getString(1), cq.getInt(2), cq.getLong(3)));
                    }
                } finally {
                    cq.close();
                }
            }
        }
        return d;
    }

    // ---- making a backup ----

    /** Builds the encrypted file from the state now, keeps a copy here (newest 3) and returns the download. */
    public static AddServer.Download backup(Context ctx, SharedPreferences p, Store st, Sources src, char[] pass) throws AddServer.Refused {
        byte[] file;
        int dropped;
        try {
            Backup.Data d = capture(ctx, p, st, src);
            dropped = Backup.sanitise(d);
            file = Backup.make(d, pass);
        } catch (Backup.BackupException e) {
            Log.w(TAG, "backup refused: state not valid"); // the state on this TV does not pass the rules a restore applies
            throw new AddServer.Refused("The backup could not be made.", false);
        } catch (RuntimeException e) {
            Log.w(TAG, "backup failed: " + e.getClass().getSimpleName());
            throw new AddServer.Refused("The backup could not be made.", false);
        }
        String name = "manus-tv-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + EXT;
        boolean kept = keep(ctx, name, file, COPY);
        Log.i(TAG, "backup made: " + file.length + " B, copy kept on this TV: " + kept + ", items left out: " + dropped);
        return new AddServer.Download(name, file, kept, dropped);
    }

    /** Writes dir/name (via a temp file, length checked) and prunes to the newest {@link #KEEP} matching {@code pattern}. False = no copy kept. */
    private static boolean keep(Context ctx, String name, byte[] file, String pattern) {
        File d = dir(ctx), tmp = new File(d, name + ".tmp");
        try {
            if (!d.isDirectory() && !d.mkdirs()) return false;
            Files.write(tmp.toPath(), file);
            if (tmp.length() != file.length) { tmp.delete(); return false; }
            Files.move(tmp.toPath(), new File(d, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
            List<File> all = files(ctx, pattern);
            for (int i = KEEP; i < all.size(); i++) all.get(i).delete();
            return true;
        } catch (IOException e) {
            Log.w(TAG, "backup copy not kept: " + e.getClass().getSimpleName());
            tmp.delete();
            return false;
        }
    }

    /** Files in the backup folder whose name matches, newest first (the date is in the name). */
    private static List<File> files(Context ctx, String pattern) {
        List<File> out = new ArrayList<>();
        File[] fs = dir(ctx).listFiles();
        if (fs != null) for (File f : fs) if (f.isFile() && f.getName().matches(pattern)) out.add(f);
        Collections.sort(out, new java.util.Comparator<File>() { @Override public int compare(File a, File b) { return b.getName().compareTo(a.getName()); } });
        return out;
    }

    /** The up-to-3 copies kept on this TV, newest first. */
    public static List<File> localCopies(Context ctx) { List<File> l = files(ctx, COPY); return l.size() > KEEP ? l.subList(0, KEEP) : l; }

    /** 0 = none. */
    public static long lastBackupMs(Context ctx) { List<File> l = files(ctx, COPY); return l.isEmpty() ? 0 : l.get(0).lastModified(); }

    /** "16 Nov 21:40". */
    public static String when(long ms) { return new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date(ms)); }

    // ---- reading a backup ----

    /** One of the copies kept on this TV, by file name (checked against the pattern: no path ever comes from outside). */
    public static byte[] readLocal(Context ctx, String name) throws AddServer.Refused {
        if (name == null || !name.matches(COPY)) throw new AddServer.Refused(Backup.TEXT[Backup.NOT_BACKUP], false);
        try {
            return Files.readAllBytes(new File(dir(ctx), name).toPath());
        } catch (IOException e) {
            Log.w(TAG, "backup copy not read: " + e.getClass().getSimpleName());
            throw new AddServer.Refused(Backup.TEXT[Backup.NOT_BACKUP], false);
        }
    }

    /** Decrypts and checks a file. The refusal text is the exact one for the phone page; a wrong passphrase counts as a wrong try. */
    public static Backup.Data open(byte[] file, char[] pass) throws AddServer.Refused {
        try {
            return Backup.open(file, pass);
        } catch (Backup.BackupException e) {
            throw new AddServer.Refused(e.getMessage(), e.kind == Backup.BAD_PASSPHRASE);
        }
    }

    // ---- restoring ----

    /**
     * Puts {@code d} on this TV. Order: (1) a safety copy of what is here now is written and read back; if that fails nothing else happens,
     * (2) one database transaction + one preference commit (see {@link Db#restore}), (3) playlists and caches re-read. Throws on any failure; on a
     * failure in (1) or (2) the TV is exactly as it was.
     */
    public static void restore(Context ctx, SharedPreferences p, Store st, Sources src, Backup.Data d) throws Exception {
        Db db = Db.get();
        if (db == null) throw new IllegalStateException("database unavailable");
        safetyCopy(ctx, p, capture(ctx, p, st, src));

        SharedPreferences.Editor ed = p.edit();
        ed.putString("sources", Sources.restoreJson(d.sources));
        boolean feedChanges = !java.util.Objects.equals(p.getString("feed", null), d.settings.get("feed"));
        for (String n : Backup.SETTING_NAMES) {
            Object v = d.settings.get(n);
            if (v == null) { ed.remove(n); continue; } // a setting the backup does not have goes back to its default
            Object pv = Backup.settingToPref(n, v);
            if (pv instanceof Boolean) ed.putBoolean(n, (Boolean) pv);
            else if (pv instanceof Integer) ed.putInt(n, (Integer) pv);
            else ed.putString(n, (String) pv);
        }
        if (feedChanges) for (String k : p.getAll().keySet()) if (k.startsWith("etag_")) ed.remove(k); // another feed: its files are not the old feed's

        FavGuard.snapshot(st.favs(), FavGuard.names()); // the favourites as they were stay available in Settings > Restore favourites
        synchronized (src) { // no playlist refresh may save its old list over the restored one in between
            db.restore(d, ed);
            src.afterRestore();
        }
        restoreXtream(ctx, db, d);
        st.reloadAfterRestore();
        FavGuard.log(true, "(backup restored: " + d.favs.size() + " favourites)");
        Log.i(TAG, "restore applied: favs " + d.favs.size() + ", sources " + d.sources.size() + ", recents " + d.recents.size() + ", plays " + d.plays.size() + ", reminders " + d.reminders.size());
    }

    public static void restoreXtream(Context ctx, Db db, Backup.Data d) {
        if (lk.manus.iptv.Config.PLAY || db == null || d == null || d.xt.isEmpty()) return;
        android.database.sqlite.SQLiteDatabase sqldb = db.getWritableDatabase();
        Db.ensurePendingRestoreTable(sqldb);
        long[] xtToSourceId = new long[d.xt.size()];
        List<Long> sourcesToSync = new ArrayList<>();

        for (int i = 0; i < d.xt.size(); i++) {
            Backup.Xtream x = d.xt.get(i);
            String normBase = XtreamSource.normaliseAddress(x.base);
            if (normBase == null) normBase = x.base;
            long sid = findExistingXtreamSource(db, normBase, x.user);
            if (sid > 0) {
                xtToSourceId[i] = sid;
            } else {
                sid = XtreamSource.add(db, x.name, normBase, x.user, x.pass);
                if (sid > 0) {
                    xtToSourceId[i] = sid;
                    sourcesToSync.add(sid);
                } else {
                    xtToSourceId[i] = -1;
                }
            }
        }

        long now = System.currentTimeMillis();
        sqldb.beginTransaction();
        try {
            for (Backup.FilmProgress fp : d.fp) {
                if (fp.xtIndex >= 0 && fp.xtIndex < xtToSourceId.length) {
                    long sid = xtToSourceId[fp.xtIndex];
                    if (sid > 0) {
                        sqldb.execSQL("INSERT INTO pending_restore(source_id, provider_id, kind, position_ms, duration_ms, watched, updated_at, \"order\", created_at) " +
                                "VALUES(?, ?, 'progress', ?, ?, ?, ?, NULL, ?)",
                                new Object[]{sid, fp.providerId, fp.positionMs, fp.durationMs, fp.watched ? 1 : 0, fp.updatedAt, now});
                    }
                }
            }
            for (Backup.MyListFilm ml : d.ml) {
                if (ml.xtIndex >= 0 && ml.xtIndex < xtToSourceId.length) {
                    long sid = xtToSourceId[ml.xtIndex];
                    if (sid > 0) {
                        sqldb.execSQL("INSERT INTO pending_restore(source_id, provider_id, kind, position_ms, duration_ms, watched, updated_at, \"order\", created_at) " +
                                "VALUES(?, ?, 'list', NULL, NULL, NULL, ?, ?, ?)",
                                new Object[]{sid, ml.providerId, ml.addedAt, ml.order, now});
                    }
                }
            }
            // T96: series data waits in pending_restore as kind 'ep' (provider_id = seriesId TAB season TAB episode) and 'slist' (provider_id = seriesId).
            // Simplest that works: a row whose series/episode is not in the catalogue yet stays pending (30 days) and is applied after the series sync / when the episodes are first fetched.
            for (Backup.EpProgress e : d.ep) {
                if (e.xtIndex >= 0 && e.xtIndex < xtToSourceId.length && xtToSourceId[e.xtIndex] > 0) {
                    sqldb.execSQL("INSERT INTO pending_restore(source_id, provider_id, kind, position_ms, duration_ms, watched, updated_at, \"order\", created_at) " +
                            "VALUES(?, ?, 'ep', ?, ?, ?, ?, NULL, ?)",
                            new Object[]{xtToSourceId[e.xtIndex], e.seriesId + "\t" + e.season + "\t" + e.episode, e.positionMs, e.durationMs, e.watched ? 1 : 0, e.updatedAt, now});
                }
            }
            for (Backup.SeriesListEntry m : d.sl) {
                if (m.xtIndex >= 0 && m.xtIndex < xtToSourceId.length && xtToSourceId[m.xtIndex] > 0) {
                    sqldb.execSQL("INSERT INTO pending_restore(source_id, provider_id, kind, position_ms, duration_ms, watched, updated_at, \"order\", created_at) " +
                            "VALUES(?, ?, 'slist', NULL, NULL, NULL, ?, ?, ?)",
                            new Object[]{xtToSourceId[m.xtIndex], m.seriesId, m.addedAt, m.order, now});
                }
            }
            sqldb.setTransactionSuccessful();
        } finally {
            sqldb.endTransaction();
        }

        Db.applyPendingRestore(db);

        for (long sid : sourcesToSync) {
            XtreamSource.sync(ctx, sid, null);
        }
    }

    private static long findExistingXtreamSource(Db db, String base, String user) {
        if (db == null || base == null || user == null) return -1;
        String normBase = XtreamSource.normaliseAddress(base);
        if (normBase == null) normBase = base;
        android.database.sqlite.SQLiteDatabase d = db.getReadableDatabase();
        android.database.Cursor c = d.rawQuery("SELECT id, address, secret_ref FROM source WHERE kind = 'xtream'", null);
        try {
            while (c.moveToNext()) {
                long sid = c.getLong(0);
                String addr = c.getString(1);
                String sref = c.getString(2);
                if (sref == null) continue;
                String normAddr = XtreamSource.normaliseAddress(addr);
                if (normAddr == null) normAddr = addr;
                if (normBase.equalsIgnoreCase(normAddr)) {
                    String creds = Secrets.open(sref);
                    if (creds != null) {
                        String[] parts = creds.split("\n", 2);
                        if (parts.length > 0 && parts[0].equals(user)) {
                            return sid;
                        }
                    }
                }
            }
        } finally {
            c.close();
        }
        return -1;
    }

    /** The automatic undo copy: same format, key = a random value kept only in this app's private preferences. Written, read back and compared; throws if any step fails. */
    private static void safetyCopy(Context ctx, SharedPreferences p, Backup.Data current) throws Exception {
        String key = p.getString(UNDO_KEY, null);
        if (key == null || key.length() < 2 * UNDO_KEY_BYTES) {
            StringBuilder b = new StringBuilder();
            for (byte x : Backup.random(UNDO_KEY_BYTES)) b.append(String.format("%02x", x & 0xff));
            key = b.toString();
            if (!p.edit().putString(UNDO_KEY, key).commit()) throw new IllegalStateException("undo key not saved");
        }
        char[] pass = key.toCharArray();
        try {
            Backup.sanitise(current);
            String json = Backup.checkedJson(current);
            byte[] file = Backup.encrypt(json, pass);
            String name = "undo-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + EXT;
            if (!keep(ctx, name, file, UNDO)) throw new IOException("undo copy not written");
            if (!Backup.toJson(Backup.open(Files.readAllBytes(new File(dir(ctx), name).toPath()), pass)).equals(Backup.toJson(Backup.fromJson(json))))
                throw new IllegalStateException("undo copy does not read back");
            Log.i(TAG, "undo copy written and read back: " + file.length + " B, favs " + current.favs.size() + ", sources " + current.sources.size());
        } finally { Arrays.fill(pass, '\0'); }
    }
}
