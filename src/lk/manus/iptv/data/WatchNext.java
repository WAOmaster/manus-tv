package lk.manus.iptv.data;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import lk.manus.iptv.Config;

/**
 * Android TV / Google TV Watch Next integration (T84) using plain TvContract.
 * Free of androidx.tvprovider. Full flavor only; skipped on Play flavor.
 */
public final class WatchNext {
    private static final String TAG = "IPTV";
    private static final String ACTIVITY_CLASS = "lk.manus.iptv.PlayerActivity";

    private WatchNext() {}

    /** One Watch Next row per series, same tile as Home's Continue watching (T93b); removed when the series is finished. */
    public static void updateSeries(final Context context, final long seriesId) {
        if (Config.PLAY || context == null || seriesId <= 0) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Library.ContinueItem ci = Library.seriesTile(Db.get(), seriesId, System.currentTimeMillis());
                    String key = "series:" + seriesId;
                    if (ci == null) { removeKey(context, key); return; }
                    upsert(context, key, ci.item, ci.positionMs, ci.durationMs, TvContract.WatchNextPrograms.TYPE_TV_EPISODE);
                } catch (Throwable t) {
                    Log.w(TAG, "watchnext: series unavailable: " + t.getMessage());
                }
            }
        }, "watchnext-series").start();
    }

    public static void update(final Context context, final Library.Item item, final long positionMs, final long durationMs) {
        if (Config.PLAY || context == null || item == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                updateDirect(context, item, positionMs, durationMs);
            }
        }, "watchnext-update").start();
    }

    public static void remove(final Context context, final long itemId) {
        if (Config.PLAY || context == null || itemId <= 0) return;
        new Thread(new Runnable() {
            @Override public void run() {
                removeDirect(context, itemId);
            }
        }, "watchnext-remove").start();
    }

    public static void updateDirect(Context context, Library.Item item, long positionMs, long durationMs) {
        if (Config.PLAY || context == null || item == null) return;
        upsert(context, "film:" + item.id, item, positionMs, durationMs, TvContract.WatchNextPrograms.TYPE_MOVIE);
    }

    private static void upsert(Context context, String targetProvId, Library.Item item, long positionMs, long durationMs, int type) {
        try {
            ContentResolver cr = context.getContentResolver();
            Uri contentUri = TvContract.WatchNextPrograms.CONTENT_URI;

            long existingRowId = -1;

            Cursor c = cr.query(contentUri,
                    new String[]{TvContract.BaseTvColumns._ID, TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID},
                    null, null, null);
            if (c != null) {
                try {
                    int idIdx = c.getColumnIndex(TvContract.BaseTvColumns._ID);
                    int provIdx = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID);
                    while (c.moveToNext()) {
                        if (provIdx >= 0 && targetProvId.equals(c.getString(provIdx))) {
                            if (idIdx >= 0) existingRowId = c.getLong(idIdx);
                            break;
                        }
                    }
                } finally {
                    c.close();
                }
            }

            Intent in = new Intent(Intent.ACTION_VIEW);
            in.setClassName(context, ACTIVITY_CLASS);
            if ("episode".equals(item.kind)) {
                in.setData(Uri.parse("manus-tv://film/play?id=" + item.id));
                in.putExtra("filmplay", item.id);
                in.putExtra("filmpos", positionMs);
            } else {
                in.setData(Uri.parse("manus-tv://film/details?id=" + item.id));
                in.putExtra("filmopen", item.id);
            }
            String intentUri = in.toUri(Intent.URI_INTENT_SCHEME);

            ContentValues v = new ContentValues();
            v.put(TvContract.WatchNextPrograms.COLUMN_TYPE, type);
            v.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
            v.put(TvContract.WatchNextPrograms.COLUMN_TITLE, item.title);
            if (item.poster != null && !item.poster.isEmpty()) {
                v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, item.poster);
            }
            v.put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, (int) positionMs);
            if (durationMs > 0) {
                v.put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, (int) durationMs);
            }
            v.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, System.currentTimeMillis());
            v.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, intentUri);
            v.put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, targetProvId);

            if (existingRowId > 0) {
                Uri rowUri = ContentUris.withAppendedId(contentUri, existingRowId);
                cr.update(rowUri, v, null, null);
                Log.i(TAG, "watchnext: updated row " + existingRowId + " for film " + item.id + " (" + item.title + ")");
            } else {
                Uri inserted = cr.insert(contentUri, v);
                Log.i(TAG, "watchnext: inserted " + inserted + " for film " + item.id + " (" + item.title + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "watchnext: unavailable: " + t.getMessage());
        }
    }

    public static void removeDirect(Context context, long itemId) {
        if (Config.PLAY || context == null || itemId <= 0) return;
        try {
            ContentResolver cr = context.getContentResolver();
            Uri contentUri = TvContract.WatchNextPrograms.CONTENT_URI;
            String targetProvId = "film:" + itemId;

            Cursor c = cr.query(contentUri,
                    new String[]{TvContract.BaseTvColumns._ID, TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID},
                    null, null, null);
            if (c != null) {
                try {
                    int idIdx = c.getColumnIndex(TvContract.BaseTvColumns._ID);
                    int provIdx = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID);
                    while (c.moveToNext()) {
                        if (provIdx >= 0 && targetProvId.equals(c.getString(provIdx))) {
                            if (idIdx >= 0) {
                                long rowId = c.getLong(idIdx);
                                cr.delete(ContentUris.withAppendedId(contentUri, rowId), null, null);
                                Log.i(TAG, "watchnext: removed row " + rowId + " for film " + itemId);
                            }
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "watchnext: unavailable: " + t.getMessage());
        }
    }

    private static void removeKey(Context context, String targetProvId) {
        try {
            ContentResolver cr = context.getContentResolver();
            Uri contentUri = TvContract.WatchNextPrograms.CONTENT_URI;
            Cursor c = cr.query(contentUri,
                    new String[]{TvContract.BaseTvColumns._ID, TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID},
                    null, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        if (targetProvId.equals(c.getString(1))) cr.delete(ContentUris.withAppendedId(contentUri, c.getLong(0)), null, null);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "watchnext: unavailable: " + t.getMessage());
        }
    }
}
