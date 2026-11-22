package lk.manus.iptv.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lk.manus.iptv.Config;
import lk.manus.iptv.meta.TitleCleaner;

/**
 * Paged, cached access to the VOD movies catalogue in catalogue.db (T82).
 */
public final class Library {
    private static final String TAG = "IPTV";

    public static final class Item {
        public final long id;
        public final String kind;
        public final String title;
        public final int year;
        public final String poster;
        public final double rating;
        public final String groupName;
        public final long parentId;
        public final int season;
        public final int episode;

        public Item(long id, String title, int year, String poster, double rating, String groupName) {
            this(id, "movie", title, year, poster, rating, groupName, 0, 0, 0);
        }

        public Item(long id, String kind, String title, int year, String poster, double rating, String groupName) {
            this(id, kind, title, year, poster, rating, groupName, 0, 0, 0);
        }

        public Item(long id, String kind, String title, int year, String poster, double rating, String groupName, long parentId, int season, int episode) {
            this.id = id;
            this.kind = kind != null ? kind : "movie";
            this.title = title != null ? title : "";
            this.year = year;
            this.poster = poster != null ? poster : "";
            this.rating = rating;
            this.groupName = groupName != null ? groupName : "";
            this.parentId = parentId;
            this.season = season;
            this.episode = episode;
        }
    }

    public static final class Category {
        public final String name;
        public final int count;

        public Category(String name, int count) {
            this.name = name != null ? name : "Other";
            this.count = count;
        }
    }

    public static final class ContinueItem {
        public final Item item;
        public final long positionMs;
        public final long durationMs;
        public final long updatedAt;
        public final long seriesId; // 0 for films; one tile per series

        public ContinueItem(Item item, long positionMs, long durationMs, long updatedAt) {
            this(item, positionMs, durationMs, updatedAt, 0L);
        }

        public ContinueItem(Item item, long positionMs, long durationMs, long updatedAt, long seriesId) {
            this.seriesId = seriesId;
            this.item = item;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.updatedAt = updatedAt;
        }

        public float progress() {
            if (durationMs <= 0) return 0f;
            return Math.min(1f, Math.max(0f, (float) positionMs / (float) durationMs));
        }
    }

    public static String normKind(String kind) {
        if (kind == null) return "movie";
        String s = kind.trim().toLowerCase(java.util.Locale.ROOT);
        if ("series".equals(s)) return "series";
        if ("episode".equals(s)) return "episode";
        if ("all".equals(s)) return "all";
        return "movie";
    }

    private static volatile boolean cachedAnyMovie = false;
    private static volatile boolean cachedAnySeries = false;
    private static volatile List<Category> cachedCategories = null;
    private static volatile List<Category> cachedSeriesCategories = null;
    private static volatile boolean indexesCreated = false;

    private Library() {}

    /**
     * True if any item of kind='movie' exists whose source has kind='xtream'.
     * Returns a cached boolean refreshed after syncs; does not query SQLite on every call.
     */
    public static boolean any(Db db) {
        return any(db, "movie");
    }

    /**
     * True if any item of given kind exists whose source has kind='xtream'.
     */
    public static boolean any(Db db, String kind) {
        if (Config.PLAY) return false;
        String k = normKind(kind);
        if ("series".equals(k)) {
            if (!cachedAnySeries && db != null) {
                refreshAny(db, "series");
            }
            return cachedAnySeries;
        } else {
            if (!cachedAnyMovie && db != null) {
                refreshAny(db, "movie");
            }
            return cachedAnyMovie;
        }
    }

    /**
     * Refreshes the cached any(Db) booleans from the database. Called on worker thread after sync.
     */
    public static void refreshAny(Db db) {
        refreshAny(db, "movie");
        refreshAny(db, "series");
    }

    /**
     * Refreshes the cached any(Db, kind) boolean from the database.
     */
    public static void refreshAny(Db db, String kind) {
        String k = normKind(kind);
        if (Config.PLAY || db == null) {
            if ("series".equals(k)) cachedAnySeries = false;
            else cachedAnyMovie = false;
            return;
        }
        try {
            SQLiteDatabase d = db.getReadableDatabase();
            Cursor c = d.rawQuery(
                    "SELECT 1 FROM item i JOIN source s ON s.id = i.source_id WHERE i.kind = ? AND s.kind = 'xtream' LIMIT 1",
                    new String[]{k});
            try {
                boolean found = c.moveToFirst();
                if ("series".equals(k)) cachedAnySeries = found;
                else cachedAnyMovie = found;
                Log.i(TAG, "Library.refreshAny(" + k + "): " + found);
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Library.refreshAny(" + k + "): " + t.getMessage());
        }
    }

    public static synchronized void invalidateCategories() {
        cachedCategories = null;
        cachedSeriesCategories = null;
    }

    public static synchronized void invalidateCategories(String kind) {
        if ("series".equals(normKind(kind))) {
            cachedSeriesCategories = null;
        } else {
            cachedCategories = null;
        }
    }

    /**
     * Categories list (name, count) from item.group_name of movies (empty name -> 'Other'),
     * biggest first, cached.
     */
    public static List<Category> categories(Db db) {
        return categories(db, "movie");
    }

    /**
     * Categories list (name, count) from item.group_name of given kind (empty name -> 'Other'),
     * biggest first, cached.
     */
    public static synchronized List<Category> categories(Db db, String kind) {
        if (Config.PLAY || db == null) return Collections.emptyList();
        String k = normKind(kind);
        boolean isSeries = "series".equals(k);
        if (isSeries && cachedSeriesCategories != null) return cachedSeriesCategories;
        if (!isSeries && cachedCategories != null) return cachedCategories;
        List<Category> list = new ArrayList<>();
        try {
            SQLiteDatabase d = db.getReadableDatabase();
            Cursor c = d.rawQuery(
                    "SELECT CASE WHEN IFNULL(i.group_name, '') = '' THEN 'Other' ELSE TRIM(i.group_name) END AS cat, COUNT(*) AS cnt " +
                    "FROM item i JOIN source s ON s.id = i.source_id " +
                    "WHERE i.kind = ? AND s.kind = 'xtream' " +
                    "GROUP BY cat ORDER BY cnt DESC", new String[]{k});
            try {
                while (c.moveToNext()) {
                    String name = c.getString(0);
                    int count = c.getInt(1);
                    list.add(new Category(categoryName(name), count));
                }
            } finally {
                c.close();
            }
            List<Category> unmod = Collections.unmodifiableList(list);
            if (isSeries) cachedSeriesCategories = unmod;
            else cachedCategories = unmod;
        } catch (Throwable t) {
            Log.w(TAG, "Library.categories(" + k + "): " + t.getMessage());
        }
        List<Category> res = isSeries ? cachedSeriesCategories : cachedCategories;
        return res != null ? res : Collections.<Category>emptyList();
    }

    /**
     * Returns SQL ORDER BY clause for sort parameter:
     * 0: Recently added (added_at DESC, id DESC)
     * 1: A–Z (clean_title ASC, id ASC)
     * 2: Rating (meta.rating DESC, nulls last; for series: COALESCE(ss.rating, m.rating))
     */
    public static String sortClause(int sort) {
        return sortClause(sort, "movie");
    }

    public static String sortClause(int sort, String kind) {
        boolean isSeries = "series".equals(normKind(kind));
        switch (sort) {
            case 1:
                return "i.clean_title ASC, i.id ASC";
            case 2:
                if (isSeries) {
                    return "CASE WHEN COALESCE(ss.rating, m.rating) IS NULL THEN 1 ELSE 0 END, COALESCE(ss.rating, m.rating) DESC, i.id DESC";
                }
                return "CASE WHEN m.rating IS NULL THEN 1 ELSE 0 END, m.rating DESC, i.id DESC";
            default:
                return "i.added_at DESC, i.id DESC";
        }
    }

    public static String categoryName(String raw) {
        if (raw == null) return "Other";
        String s = raw.trim();
        return s.isEmpty() ? "Other" : s;
    }

    public static String displayTitle(String rawTitle, String cleanTitle) {
        if (rawTitle != null && !rawTitle.trim().isEmpty()) {
            TitleCleaner.Cleaned cl = TitleCleaner.clean(rawTitle);
            if (cl != null && cl.title != null && !cl.title.isEmpty()) {
                return cl.title;
            }
            return rawTitle.trim();
        }
        return cleanTitle != null ? cleanTitle.trim() : "";
    }

    public static String fold(String query) {
        return Search.fold(query);
    }

    private static void ensureIndexes(SQLiteDatabase d) {
        if (indexesCreated) return;
        synchronized (Library.class) {
            if (indexesCreated) return;
            try {
                d.execSQL("CREATE INDEX IF NOT EXISTS item_added ON item(kind, added_at, id)");
                d.execSQL("CREATE INDEX IF NOT EXISTS item_movie_group ON item(kind, group_name)");
                indexesCreated = true;
            } catch (Throwable t) {
                Log.w(TAG, "Library.ensureIndexes: " + t.getMessage());
            }
        }
    }

    /**
     * Pure Java in-memory any check on an Item list fixture.
     */
    public static boolean any(List<Item> items, String kind) {
        if (items == null || items.isEmpty()) return false;
        String k = normKind(kind);
        for (Item it : items) {
            if (it != null && k.equals(it.kind)) return true;
        }
        return false;
    }

    /**
     * Pure Java in-memory page filter on an Item list fixture.
     */
    public static List<Item> page(List<Item> items, String kind, String category, int sort, int offset, int limit) {
        if (items == null || items.isEmpty()) return Collections.emptyList();
        String k = normKind(kind);
        List<Item> filtered = new ArrayList<>();
        for (Item it : items) {
            if (it == null || !k.equals(it.kind)) continue;
            if (category != null && !category.trim().isEmpty() && !"All".equalsIgnoreCase(category.trim())) {
                if ("Other".equalsIgnoreCase(category.trim())) {
                    if (it.groupName != null && !it.groupName.isEmpty() && !"Other".equalsIgnoreCase(it.groupName)) continue;
                } else {
                    if (!category.trim().equalsIgnoreCase(it.groupName)) continue;
                }
            }
            filtered.add(it);
        }
        if (sort == 1) {
            Collections.sort(filtered, new java.util.Comparator<Item>() {
                @Override public int compare(Item a, Item b) {
                    return a.title.compareToIgnoreCase(b.title);
                }
            });
        } else if (sort == 2) {
            Collections.sort(filtered, new java.util.Comparator<Item>() {
                @Override public int compare(Item a, Item b) {
                    return Double.compare(b.rating, a.rating);
                }
            });
        }
        if (offset >= filtered.size()) return Collections.emptyList();
        int to = Math.min(filtered.size(), offset + limit);
        return new ArrayList<>(filtered.subList(offset, to));
    }

    public static String anySql(String kind) {
        return "SELECT 1 FROM item i JOIN source s ON s.id = i.source_id WHERE i.kind = '" + normKind(kind) + "' AND s.kind = 'xtream' LIMIT 1";
    }

    public static String pageSql(String kind, String category, int sort) {
        String k = normKind(kind);
        boolean isSeries = "series".equals(k);
        String order = sortClause(sort, k);
        String posterCol = isSeries ? "CASE WHEN IFNULL(m.poster, '') != '' THEN m.poster ELSE IFNULL(ss.cover, '') END" : "m.poster";
        String ratingCol = isSeries ? "COALESCE(ss.rating, m.rating, 0.0)" : "m.rating";
        String joins = "FROM item i JOIN source s ON s.id = i.source_id LEFT JOIN meta m ON m.item_id = i.id"
                + (isSeries ? " LEFT JOIN series_state ss ON ss.item_id = i.id" : "");
        String where = "WHERE i.kind = '" + k + "' AND s.kind = 'xtream'";
        if (category != null && !category.trim().isEmpty() && !"All".equalsIgnoreCase(category.trim())) {
            if ("Other".equalsIgnoreCase(category.trim())) {
                where += " AND (i.group_name IS NULL OR TRIM(i.group_name) = '' OR TRIM(i.group_name) = 'Other')";
            } else {
                where += " AND TRIM(i.group_name) = '" + category.trim() + "'";
            }
        }
        return "SELECT i.id, i.title, i.clean_title, i.year, " + posterCol + ", " + ratingCol + ", i.group_name "
                + joins + " " + where + " ORDER BY " + order;
    }

    public static String searchSql(String kind) {
        String k = kind != null ? normKind(kind) : "all";
        boolean isAll = "all".equals(k);
        boolean isSeries = "series".equals(k);
        String posterCol = (isAll || isSeries)
                ? "CASE WHEN i.kind = 'series' THEN (CASE WHEN IFNULL(m.poster, '') != '' THEN m.poster ELSE IFNULL(ss.cover, '') END) ELSE m.poster END"
                : "m.poster";
        String ratingCol = (isAll || isSeries) ? "COALESCE(ss.rating, m.rating, 0.0)" : "m.rating";
        String joins = "FROM item i JOIN source s ON s.id = i.source_id LEFT JOIN meta m ON m.item_id = i.id"
                + ((isAll || isSeries) ? " LEFT JOIN series_state ss ON ss.item_id = i.id" : "");
        String kindClause = isAll ? "(i.kind = 'movie' OR i.kind = 'series')" : "i.kind = '" + k + "'";
        return "SELECT i.id, i.title, i.clean_title, i.year, " + posterCol + ", " + ratingCol + ", i.group_name, i.kind "
                + joins + " WHERE " + kindClause + " AND s.kind = 'xtream' AND i.clean_title LIKE ? "
                + "ORDER BY CASE WHEN i.clean_title LIKE ? THEN 0 ELSE 1 END, i.clean_title ASC LIMIT ?";
    }

    /**
     * Pure Java in-memory search on an Item list fixture.
     */
    public static List<Item> search(List<Item> items, String query, int limit) {
        return search(items, "all", query, limit);
    }

    public static List<Item> search(List<Item> items, String kind, String query, int limit) {
        if (items == null || items.isEmpty() || query == null) return Collections.emptyList();
        final String folded = fold(query).trim();
        if (folded.isEmpty()) return Collections.emptyList();
        String k = kind != null ? normKind(kind) : "all";
        boolean isAll = "all".equals(k);
        List<Item> matches = new ArrayList<>();
        for (Item it : items) {
            if (it == null) continue;
            if (!isAll && !k.equals(it.kind)) continue;
            String tf = fold(it.title);
            if (tf.contains(folded)) {
                matches.add(it);
            }
        }
        Collections.sort(matches, new java.util.Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                String fa = fold(a.title);
                String fb = fold(b.title);
                boolean aStarts = fa.startsWith(folded);
                boolean bStarts = fb.startsWith(folded);
                if (aStarts != bStarts) return aStarts ? -1 : 1;
                return fa.compareTo(fb);
            }
        });
        if (limit > 0 && matches.size() > limit) {
            return new ArrayList<>(matches.subList(0, limit));
        }
        return matches;
    }

    /**
     * Paged light rows: id, title, year, poster, rating, groupName.
     * Logs ms via "IPTV lib page ms=".
     */
    public static List<Item> page(Db db, String category, int sort, int offset, int limit) {
        return page(db, "movie", category, sort, offset, limit);
    }

    public static List<Item> page(Db db, String kind, String category, int sort, int offset, int limit) {
        if (Config.PLAY || db == null) return Collections.emptyList();
        long t0 = SystemClock.elapsedRealtime();
        List<Item> list = new ArrayList<>();
        String k = normKind(kind);
        boolean isSeries = "series".equals(k);
        try {
            SQLiteDatabase d = db.getReadableDatabase();
            ensureIndexes(d);
            String order = sortClause(sort, k);
            String posterCol = isSeries ? "CASE WHEN IFNULL(m.poster, '') != '' THEN m.poster ELSE IFNULL(ss.cover, '') END" : "m.poster";
            String ratingCol = isSeries ? "COALESCE(ss.rating, m.rating, 0.0)" : "m.rating";
            String joins = "FROM item i JOIN source s ON s.id = i.source_id LEFT JOIN meta m ON m.item_id = i.id"
                    + (isSeries ? " LEFT JOIN series_state ss ON ss.item_id = i.id" : "");
            String baseSelect = "SELECT i.id, i.title, i.clean_title, i.year, " + posterCol + ", " + ratingCol + ", i.group_name " + joins;

            Cursor c;
            if (category == null || category.trim().isEmpty() || "All".equalsIgnoreCase(category.trim())) {
                c = d.rawQuery(
                        baseSelect + " WHERE i.kind = ? AND s.kind = 'xtream' ORDER BY " + order + " LIMIT ? OFFSET ?",
                        new String[]{k, String.valueOf(limit), String.valueOf(offset)});
            } else if ("Other".equalsIgnoreCase(category.trim())) {
                c = d.rawQuery(
                        baseSelect + " WHERE i.kind = ? AND s.kind = 'xtream' " +
                        "  AND (i.group_name IS NULL OR TRIM(i.group_name) = '' OR TRIM(i.group_name) = 'Other') " +
                        "ORDER BY " + order + " LIMIT ? OFFSET ?",
                        new String[]{k, String.valueOf(limit), String.valueOf(offset)});
            } else {
                c = d.rawQuery(
                        baseSelect + " WHERE i.kind = ? AND s.kind = 'xtream' " +
                        "  AND TRIM(i.group_name) = ? " +
                        "ORDER BY " + order + " LIMIT ? OFFSET ?",
                        new String[]{k, category.trim(), String.valueOf(limit), String.valueOf(offset)});
            }
            try {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String rawTitle = c.getString(1);
                    String cleanTitle = c.getString(2);
                    int year = c.getInt(3);
                    String poster = c.getString(4) == null ? "" : c.getString(4);
                    double rating = c.isNull(5) ? 0.0 : c.getDouble(5);
                    String grp = c.getString(6);
                    String dispTitle = displayTitle(rawTitle, cleanTitle);
                    if (year == 0 && rawTitle != null) {
                        TitleCleaner.Cleaned cl = TitleCleaner.clean(rawTitle);
                        if (cl != null && cl.year > 0) year = cl.year;
                    }
                    list.add(new Item(id, k, dispTitle, year, poster, rating, categoryName(grp)));
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Library.page(" + k + "): " + t.getMessage());
        }
        long elapsed = SystemClock.elapsedRealtime() - t0;
        Log.i(TAG, "IPTV lib page(" + k + ") ms=" + elapsed);
        return list;
    }

    /**
     * Search against clean_title using Search.fold.
     */
    public static List<Item> search(Db db, String query, int limit) {
        boolean hasSeries = !Config.PLAY && any(db, "series");
        return search(db, hasSeries ? "all" : "movie", query, limit);
    }

    public static List<Item> search(Db db, String kind, String query, int limit) {
        if (Config.PLAY || db == null || query == null) return Collections.emptyList();
        String folded = Search.fold(query).trim();
        if (folded.isEmpty()) return Collections.emptyList();
        String k = normKind(kind);
        boolean isAll = "all".equals(k);
        boolean isSeries = "series".equals(k);
        List<Item> list = new ArrayList<>();
        try {
            SQLiteDatabase d = db.getReadableDatabase();
            ensureIndexes(d);
            String posterCol = (isAll || isSeries)
                    ? "CASE WHEN i.kind = 'series' THEN (CASE WHEN IFNULL(m.poster, '') != '' THEN m.poster ELSE IFNULL(ss.cover, '') END) ELSE m.poster END"
                    : "m.poster";
            String ratingCol = (isAll || isSeries) ? "COALESCE(ss.rating, m.rating, 0.0)" : "m.rating";
            String joins = "FROM item i JOIN source s ON s.id = i.source_id LEFT JOIN meta m ON m.item_id = i.id"
                    + ((isAll || isSeries) ? " LEFT JOIN series_state ss ON ss.item_id = i.id" : "");
            String kindClause = isAll ? "(i.kind = 'movie' OR i.kind = 'series')" : "i.kind = ?";
            String sql = "SELECT i.id, i.title, i.clean_title, i.year, " + posterCol + ", " + ratingCol + ", i.group_name, i.kind " +
                    joins + " " +
                    "WHERE " + kindClause + " AND s.kind = 'xtream' " +
                    "  AND i.clean_title LIKE ? " +
                    "ORDER BY CASE WHEN i.clean_title LIKE ? THEN 0 ELSE 1 END, i.clean_title ASC " +
                    "LIMIT ?";
            String[] args = isAll
                    ? new String[]{"%" + folded + "%", folded + "%", String.valueOf(limit)}
                    : new String[]{k, "%" + folded + "%", folded + "%", String.valueOf(limit)};
            Cursor c = d.rawQuery(sql, args);
            try {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String rawTitle = c.getString(1);
                    String cleanTitle = c.getString(2);
                    int year = c.getInt(3);
                    String poster = c.getString(4) == null ? "" : c.getString(4);
                    double rating = c.isNull(5) ? 0.0 : c.getDouble(5);
                    String grp = c.getString(6);
                    String itemKind = c.getString(7);
                    if (itemKind == null || itemKind.isEmpty()) itemKind = isSeries ? "series" : "movie";
                    String dispTitle = displayTitle(rawTitle, cleanTitle);
                    if (year == 0 && rawTitle != null) {
                        TitleCleaner.Cleaned cl = TitleCleaner.clean(rawTitle);
                        if (cl != null && cl.year > 0) year = cl.year;
                    }
                    list.add(new Item(id, itemKind, dispTitle, year, poster, rating, categoryName(grp)));
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Library.search(" + k + "): " + t.getMessage());
        }
        return list;
    }

    public static Item get(Db db, long itemId) {
        return get(db, null, itemId);
    }

    public static Item get(Db db, String kind, long itemId) {
        if (Config.PLAY || db == null) return null;
        try {
            SQLiteDatabase d = db.getReadableDatabase();
            String posterCol = "CASE " +
                    "WHEN i.kind = 'episode' THEN (CASE WHEN IFNULL(ee.cover, '') != '' THEN ee.cover ELSE IFNULL(pss.cover, '') END) " +
                    "WHEN IFNULL(m.poster, '') != '' THEN m.poster " +
                    "ELSE IFNULL(ss.cover, '') END";
            String grpCol = "CASE WHEN i.kind = 'episode' THEN IFNULL(pi.title, '') ELSE i.group_name END";
            String sql = "SELECT i.id, i.title, i.clean_title, i.year, " +
                    posterCol + ", " +
                    "COALESCE(ss.rating, m.rating, 0.0), " +
                    grpCol + ", i.kind, " +
                    "IFNULL(i.season, 0), IFNULL(i.episode, 0), IFNULL(i.parent_id, 0) " +
                    "FROM item i " +
                    "LEFT JOIN meta m ON m.item_id = i.id " +
                    "LEFT JOIN series_state ss ON ss.item_id = i.id " +
                    "LEFT JOIN episode_extra ee ON ee.item_id = i.id " +
                    "LEFT JOIN item pi ON pi.id = i.parent_id " +
                    "LEFT JOIN series_state pss ON pss.item_id = i.parent_id " +
                    "WHERE i.id = ?" + (kind != null && !kind.trim().isEmpty() ? " AND i.kind = ?" : "");
            String[] args = (kind != null && !kind.trim().isEmpty())
                    ? new String[]{String.valueOf(itemId), normKind(kind)}
                    : new String[]{String.valueOf(itemId)};
            Cursor c = d.rawQuery(sql, args);
            try {
                if (c.moveToFirst()) {
                    long id = c.getLong(0);
                    String rawTitle = c.getString(1);
                    String clean = c.getString(2);
                    int year = c.getInt(3);
                    String poster = c.getString(4) == null ? "" : c.getString(4);
                    double rating = c.isNull(5) ? 0.0 : c.getDouble(5);
                    String grp = c.getString(6);
                    String itemKind = c.getString(7);
                    int season = c.getInt(8);
                    int episode = c.getInt(9);
                    long parentId = c.getLong(10);

                    if ("episode".equals(itemKind)) {
                        String dispTitle = SeriesLogic.formatEpisodeTitle(season, episode, rawTitle);
                        String grpName = grp != null ? grp : "";
                        return new Item(id, itemKind, dispTitle, year, poster, rating, grpName, parentId, season, episode);
                    }

                    String dispTitle = displayTitle(rawTitle, clean);
                    if (year == 0 && rawTitle != null) {
                        TitleCleaner.Cleaned cl = TitleCleaner.clean(rawTitle);
                        if (cl != null && cl.year > 0) year = cl.year;
                    }
                    return new Item(id, itemKind, dispTitle, year, poster, rating, categoryName(grp), parentId, season, episode);
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Library.get: " + t.getMessage());
        }
        return null;
    }

    public static boolean isContinueWatching(long positionMs, long durationMs, boolean watched) {
        return FilmResume.canResume(positionMs, durationMs, watched);
    }

    public static List<ContinueItem> filterContinueWatching(List<ContinueItem> items, int limit) {
        if (items == null || items.isEmpty()) return Collections.emptyList();
        if (limit <= 0) limit = 20;
        List<ContinueItem> res = new ArrayList<>();
        for (ContinueItem ci : items) {
            if (ci != null && isContinueWatching(ci.positionMs, ci.durationMs, false)) {
                res.add(ci);
            }
        }
        Collections.sort(res, new java.util.Comparator<ContinueItem>() {
            @Override public int compare(ContinueItem a, ContinueItem b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        if (res.size() > limit) {
            return new ArrayList<>(res.subList(0, limit));
        }
        return res;
    }

    public static String continueWatchingSql() {
        return "SELECT i.id, i.title, i.clean_title, i.year, m.poster, m.rating, i.group_name, p.position_ms, p.duration_ms, p.updated_at " +
                "FROM progress p " +
                "JOIN item i ON i.id = p.item_id " +
                "JOIN source s ON s.id = i.source_id " +
                "LEFT JOIN meta m ON m.item_id = i.id " +
                "WHERE p.profile_id = 1 " +
                "  AND p.watched = 0 " +
                "  AND p.position_ms > 5000 " +
                "  AND (p.duration_ms <= 0 OR p.position_ms < (p.duration_ms * 95 / 100)) " +
                "  AND i.kind = 'movie' AND s.kind = 'xtream' " +
                "ORDER BY p.updated_at DESC " +
                "LIMIT ?";
    }

    public static String seriesTileTitle(String series, int season, int episode) {
        return (series != null ? series : "") + " · S" + season + " E" + episode;
    }

    /** Films plus series tiles: one tile per series (newest wins), newest first, capped at limit. */
    public static List<ContinueItem> mergeContinue(List<ContinueItem> films, List<ContinueItem> series, int limit) {
        List<ContinueItem> all = new ArrayList<>();
        if (films != null) all.addAll(films);
        java.util.Map<Long, ContinueItem> best = new java.util.LinkedHashMap<>();
        if (series != null) for (ContinueItem ci : series) {
            if (ci == null) continue;
            ContinueItem o = best.get(ci.seriesId);
            if (o == null || ci.updatedAt > o.updatedAt) best.put(ci.seriesId, ci);
        }
        all.addAll(best.values());
        Collections.sort(all, new java.util.Comparator<ContinueItem>() {
            @Override public int compare(ContinueItem a, ContinueItem b) { return Long.compare(b.updatedAt, a.updatedAt); }
        });
        if (limit > 0 && all.size() > limit) return new ArrayList<>(all.subList(0, limit));
        return all;
    }

    /** The single Continue tile of one series, or null (not started, or finished). */
    public static ContinueItem seriesTile(Db db, long seriesId, long updatedAt) {
        SeriesLogic.Ep t = SeriesLogic.continueTarget(SeriesLogic.episodes(db, seriesId));
        if (t == null) return null;
        Item s = get(db, "series", seriesId);
        if (s == null) return null;
        Item it = new Item(t.itemId, "episode", seriesTileTitle(s.title, t.season, t.episode), s.year, s.poster, s.rating, s.groupName, seriesId, t.season, t.episode);
        boolean resume = FilmResume.canResume(t.positionMs, t.durationMs, t.watched);
        return new ContinueItem(it, resume ? t.positionMs : 0L, resume ? t.durationMs : 0L, updatedAt, seriesId);
    }

    public static List<ContinueItem> continueSeries(Db db, int limit) {
        List<ContinueItem> list = new ArrayList<>();
        if (Config.PLAY || db == null) return list;
        try {
            SQLiteDatabase d = db.getReadableDatabase();
            Cursor c = d.rawQuery("SELECT i.parent_id, MAX(p.updated_at) FROM progress p JOIN item i ON i.id = p.item_id " +
                    "WHERE p.profile_id = 1 AND i.kind = 'episode' AND i.parent_id > 0 GROUP BY i.parent_id ORDER BY 2 DESC LIMIT ?",
                    new String[]{String.valueOf(limit)});
            List<long[]> rows = new ArrayList<>();
            try { while (c.moveToNext()) rows.add(new long[]{c.getLong(0), c.getLong(1)}); } finally { c.close(); }
            for (long[] r : rows) {
                ContinueItem ci = seriesTile(db, r[0], r[1]);
                if (ci != null) list.add(ci);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Library.continueSeries: " + t.getMessage());
        }
        return list;
    }

    /** T104: the films and series the viewer added to My list, newest first. */
    public static List<Item> myList(Db db) {
        List<Item> out = new ArrayList<>();
        if (Config.PLAY || db == null) return out;
        for (long id : db.myListIds()) {
            Item it = get(db, id);
            if (it != null && ("movie".equals(it.kind) || "series".equals(it.kind))) out.add(it);
        }
        return out;
    }

    public static List<ContinueItem> continueWatching(Db db, int limit) {
        if (limit <= 0) limit = 20;
        return mergeContinue(continueMovies(db, limit), continueSeries(db, limit), limit);
    }

    public static List<ContinueItem> continueMovies(Db db, int limit) {
        if (Config.PLAY || db == null) return Collections.emptyList();
        if (limit <= 0) limit = 20;
        List<ContinueItem> list = new ArrayList<>();
        try {
            SQLiteDatabase d = db.getReadableDatabase();
            ensureIndexes(d);
            Cursor c = d.rawQuery(continueWatchingSql(), new String[]{String.valueOf(limit)});
            try {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String rawTitle = c.getString(1);
                    String clean = c.getString(2);
                    int year = c.getInt(3);
                    String poster = c.getString(4) == null ? "" : c.getString(4);
                    double rating = c.isNull(5) ? 0.0 : c.getDouble(5);
                    String grp = c.getString(6);
                    long pos = c.getLong(7);
                    long dur = c.getLong(8);
                    long updated = c.getLong(9);

                    if (!FilmResume.canResume(pos, dur, false)) continue;

                    String dispTitle = displayTitle(rawTitle, clean);
                    if (year == 0 && rawTitle != null) {
                        TitleCleaner.Cleaned cl = TitleCleaner.clean(rawTitle);
                        if (cl != null && cl.year > 0) year = cl.year;
                    }
                    Item it = new Item(id, dispTitle, year, poster, rating, categoryName(grp));
                    list.add(new ContinueItem(it, pos, dur, updated));
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Library.continueWatching: " + t.getMessage());
        }
        return list;
    }
}
