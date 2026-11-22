package lk.manus.iptv.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Series logic: next episode calculation, watched states, episode items,
 * and button text rules (T92b). Pure algorithms paired with SQLite queries.
 */
public final class SeriesLogic {
    private static final String TAG = "IPTV";

    private SeriesLogic() {}

    public static final String NEXT_EPISODE_PILL_PREFIX = "Next episode in ";
    public static final String STILL_WATCHING_TEXT = "Still watching?";
    public static final int STILL_WATCHING_THRESHOLD = 3;
    public static final int COUNTDOWN_START_SEC = 10;
    public static final long NEXT_EPISODE_TRIGGER_MS = 30000L;

    public static String formatNextEpisodeCountdown(int secondsRemaining) {
        return NEXT_EPISODE_PILL_PREFIX + secondsRemaining;
    }

    public static boolean shouldShowNextEpisodePill(long positionMs, long durationMs,
                                                    boolean streamEnded, boolean isEpisode,
                                                    boolean hasNextEpisode, boolean pillDismissed) {
        if (!isEpisode || !hasNextEpisode || pillDismissed) return false;
        if (streamEnded) return true;
        return durationMs > 0 && positionMs > 0 && (durationMs - positionMs <= NEXT_EPISODE_TRIGGER_MS);
    }

    public static final class StillWatchingTracker {
        private int autoPlayCount = 0;

        public StillWatchingTracker() {}

        public StillWatchingTracker(int initialCount) {
            this.autoPlayCount = initialCount;
        }

        public int getCount() {
            return autoPlayCount;
        }

        public void onAutoPlay() {
            autoPlayCount++;
        }

        public void onKeyPress() {
            autoPlayCount = 0;
        }

        public boolean shouldPromptStillWatching() {
            return autoPlayCount >= STILL_WATCHING_THRESHOLD;
        }

        public void reset() {
            autoPlayCount = 0;
        }
    }

    public static class Ep {
        public final long itemId;
        public final int season;
        public final int episode;
        public final long positionMs;
        public final long durationMs;
        public final boolean watched;

        public Ep(long itemId, int season, int episode, long positionMs, long durationMs, boolean watched) {
            this.itemId = itemId;
            this.season = season;
            this.episode = episode;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.watched = watched;
        }

        public Ep(SeriesSource.Episode e) {
            this(e != null ? e.itemId : 0, e != null ? e.season : 0, e != null ? e.episode : 0,
                 e != null ? e.positionMs : 0, e != null ? e.durationMs : 0, e != null && e.watched);
        }

        public Ep withProgress(long pos, long dur, boolean w) {
            return new Ep(this.itemId, this.season, this.episode, pos, dur, w);
        }
    }

    public static final class SeriesProgress {
        public final int totalEpisodes;
        public final int watchedEpisodes;
        public final Ep target;
        public final boolean hasAnyProgress;

        public SeriesProgress(int total, int watched, Ep target, boolean hasAnyProgress) {
            this.totalEpisodes = total;
            this.watchedEpisodes = watched;
            this.target = target;
            this.hasAnyProgress = hasAnyProgress;
        }
    }

    public static boolean isWatched(Ep ep) {
        if (ep == null) return false;
        if (ep.watched) return true;
        return ep.durationMs > 0 && FilmResume.isFinished(ep.positionMs, ep.durationMs);
    }

    public static String formatEpisodeTitle(int season, int episode, String rawTitle) {
        String epTitle = (rawTitle != null && !rawTitle.trim().isEmpty()) ? rawTitle.trim() : ("Episode " + (episode > 0 ? episode : 1));
        if (season > 0 && episode > 0) {
            String prefix = "S" + season + " E" + episode + " · ";
            if (epTitle.startsWith(prefix)) return epTitle;
            return prefix + epTitle;
        } else if (episode > 0) {
            return epTitle;
        } else {
            return epTitle;
        }
    }

    /**
     * Pure function over episodes ordered by (season, episode):
     * 1. Last-started unfinished episode if FilmResume.canResume
     * 2. Otherwise the first episode after the highest watched one
     * 3. Otherwise the first episode
     * 4. If every episode is watched, returns null
     */
    public static Ep nextToWatch(List<Ep> eps) {
        if (eps == null || eps.isEmpty()) return null;

        Ep lastResumable = null;
        for (Ep ep : eps) {
            if (ep != null && FilmResume.canResume(ep.positionMs, ep.durationMs, ep.watched)) {
                lastResumable = ep;
            }
        }
        if (lastResumable != null) return lastResumable;

        int lastWatchedIdx = -1;
        for (int i = 0; i < eps.size(); i++) {
            Ep ep = eps.get(i);
            if (ep != null && isWatched(ep)) {
                lastWatchedIdx = i;
            }
        }

        if (lastWatchedIdx >= 0) {
            if (lastWatchedIdx + 1 < eps.size()) {
                return eps.get(lastWatchedIdx + 1);
            }
            return null; // All episodes up to the end are watched
        }

        return eps.get(0);
    }

    /**
     * Continue-watching target for one series (T93b): the in-progress episode, else the
     * episode after the highest watched one. Null when nothing was started or the series is finished.
     */
    public static Ep continueTarget(List<Ep> eps) {
        if (eps == null || eps.isEmpty()) return null;
        boolean started = false;
        for (Ep ep : eps) {
            if (ep != null && (isWatched(ep) || FilmResume.canResume(ep.positionMs, ep.durationMs, ep.watched))) { started = true; break; }
        }
        return started ? nextToWatch(eps) : null;
    }

    public static List<Ep> episodes(Db db, long seriesItemId) {
        List<Ep> eps = new ArrayList<>();
        if (db == null || seriesItemId <= 0) return eps;
        try {
            Cursor c = db.getReadableDatabase().rawQuery(
                    "SELECT i.id, i.season, i.episode, IFNULL(p.position_ms, 0), IFNULL(p.duration_ms, 0), IFNULL(p.watched, 0) " +
                    "FROM item i LEFT JOIN progress p ON p.item_id = i.id AND p.profile_id = 1 " +
                    "WHERE i.parent_id = ? AND i.kind = 'episode' ORDER BY i.season ASC, i.episode ASC, i.id ASC",
                    new String[]{String.valueOf(seriesItemId)});
            try {
                while (c.moveToNext()) eps.add(new Ep(c.getLong(0), c.getInt(1), c.getInt(2), c.getLong(3), c.getLong(4), c.getInt(5) != 0));
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "episodes error: " + t.getMessage());
        }
        return eps;
    }

    public static String buttonText(Ep target, boolean hasAnyProgress) {
        if (target != null) {
            if (hasAnyProgress) {
                return "Continue S" + target.season + " E" + target.episode;
            } else {
                return "Start S" + target.season + " E" + target.episode;
            }
        }
        return "Watch again from S1 E1";
    }

    public static Ep nextEpisodeAfter(List<Ep> eps, long episodeItemId) {
        if (eps == null || eps.isEmpty()) return null;
        for (int i = 0; i < eps.size(); i++) {
            if (eps.get(i).itemId == episodeItemId) {
                if (i + 1 < eps.size()) return eps.get(i + 1);
                return null;
            }
        }
        return null;
    }

    public static long nextEpisodeAfter(Db db, long episodeItemId) {
        if (db == null || episodeItemId <= 0) return -1;
        try {
            SQLiteDatabase d = db.getReadableDatabase();
            long parentId = -1;
            int season = 0, episode = 0;
            Cursor c = d.rawQuery("SELECT parent_id, season, episode FROM item WHERE id = ? AND kind = 'episode'",
                    new String[]{String.valueOf(episodeItemId)});
            try {
                if (c.moveToFirst()) {
                    parentId = c.getLong(0);
                    season = c.getInt(1);
                    episode = c.getInt(2);
                }
            } finally {
                c.close();
            }
            if (parentId <= 0) return -1;

            Cursor cNext = d.rawQuery(
                    "SELECT id FROM item " +
                    "WHERE parent_id = ? AND kind = 'episode' " +
                    "  AND (season > ? OR (season = ? AND episode > ?) OR (season = ? AND episode = ? AND id > ?)) " +
                    "ORDER BY season ASC, episode ASC, id ASC LIMIT 1",
                    new String[]{
                            String.valueOf(parentId),
                            String.valueOf(season),
                            String.valueOf(season), String.valueOf(episode),
                            String.valueOf(season), String.valueOf(episode), String.valueOf(episodeItemId)
                    });
            try {
                if (cNext.moveToFirst()) return cNext.getLong(0);
            } finally {
                cNext.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "nextEpisodeAfter error: " + t.getMessage());
        }
        return -1;
    }

    public static SeriesProgress seriesProgress(List<Ep> eps) {
        if (eps == null || eps.isEmpty()) return new SeriesProgress(0, 0, null, false);
        int watchedCount = 0;
        boolean hasAny = false;
        for (Ep e : eps) {
            if (e != null) {
                if (isWatched(e)) watchedCount++;
                if (e.watched || e.positionMs > 0) hasAny = true;
            }
        }
        Ep target = nextToWatch(eps);
        return new SeriesProgress(eps.size(), watchedCount, target, hasAny);
    }

    public static SeriesProgress seriesProgress(Db db, long seriesItemId) {
        if (db == null || seriesItemId <= 0) return new SeriesProgress(0, 0, null, false);
        List<Ep> eps = new ArrayList<>();
        int watchedCount = 0;
        boolean hasAny = false;
        try {
            SQLiteDatabase d = db.getReadableDatabase();
            Cursor c = d.rawQuery(
                    "SELECT i.id, i.season, i.episode, " +
                    "       IFNULL(p.position_ms, 0), IFNULL(p.duration_ms, 0), IFNULL(p.watched, 0), " +
                    "       CASE WHEN p.item_id IS NOT NULL THEN 1 ELSE 0 END " +
                    "FROM item i " +
                    "LEFT JOIN progress p ON p.item_id = i.id AND p.profile_id = 1 " +
                    "WHERE i.parent_id = ? AND i.kind = 'episode' " +
                    "ORDER BY i.season ASC, i.episode ASC, i.id ASC",
                    new String[]{String.valueOf(seriesItemId)});
            try {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    int s = c.getInt(1);
                    int ep = c.getInt(2);
                    long pos = c.getLong(3);
                    long dur = c.getLong(4);
                    boolean watched = c.getInt(5) != 0;
                    boolean hasRow = c.getInt(6) != 0;

                    Ep e = new Ep(id, s, ep, pos, dur, watched);
                    eps.add(e);

                    if (isWatched(e)) watchedCount++;
                    if (hasRow && (watched || pos > 0)) hasAny = true;
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "seriesProgress error: " + t.getMessage());
        }
        Ep target = nextToWatch(eps);
        return new SeriesProgress(eps.size(), watchedCount, target, hasAny);
    }

    public static void markWatched(Db db, long episodeItemId, boolean watched) {
        if (db == null || episodeItemId <= 0) return;
        try {
            SQLiteDatabase d = db.getWritableDatabase();
            if (watched) {
                ContentValues v = new ContentValues();
                v.put("profile_id", 1);
                v.put("item_id", episodeItemId);
                v.put("position_ms", 0L);
                v.put("duration_ms", 0L);
                v.put("watched", 1);
                v.put("updated_at", System.currentTimeMillis());
                d.insertWithOnConflict("progress", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            } else {
                d.delete("progress", "profile_id = 1 AND item_id = ?", new String[]{String.valueOf(episodeItemId)});
            }
        } catch (Throwable t) {
            Log.w(TAG, "markWatched error: " + t.getMessage());
        }
    }

    public static void markSeasonWatched(Db db, long seriesItemId, int season, boolean watched) {
        if (db == null || seriesItemId <= 0) return;
        try {
            SQLiteDatabase d = db.getWritableDatabase();
            d.beginTransaction();
            try {
                if (watched) {
                    long now = System.currentTimeMillis();
                    d.execSQL("INSERT OR REPLACE INTO progress(profile_id, item_id, position_ms, duration_ms, watched, updated_at) " +
                              "SELECT 1, id, 0, 0, 1, ? FROM item WHERE parent_id = ? AND season = ? AND kind = 'episode'",
                              new Object[]{now, seriesItemId, season});
                } else {
                    d.execSQL("DELETE FROM progress WHERE profile_id = 1 AND item_id IN " +
                              "(SELECT id FROM item WHERE parent_id = ? AND season = ? AND kind = 'episode')",
                              new Object[]{seriesItemId, season});
                }
                d.setTransactionSuccessful();
            } finally {
                d.endTransaction();
            }
        } catch (Throwable t) {
            Log.w(TAG, "markSeasonWatched error: " + t.getMessage());
        }
    }
}
