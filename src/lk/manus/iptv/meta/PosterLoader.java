package lk.manus.iptv.meta;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import lk.manus.iptv.Config;
import lk.manus.iptv.ui.Ui;

/**
 * Loads movie posters into ImageViews.
 * Uses w185 and 30-item LRU on Low tier, w342 and 80-item LRU otherwise.
 * Decodes as RGB_565 with inSampleSize, disk cache in cacheDir/posters capped at 100 MB.
 */
public final class PosterLoader {
    private static final String TAG = "IPTV-poster";
    private static final String DEFAULT_HOST = "https://image.tmdb.org";
    private static final int THREADS = 3;
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final long MAX_DISK_BYTES = 100L * 1024 * 1024; // 100 MB cap
    private static final int TRIM_EVERY = 25;

    private static volatile PosterLoader instance;
    private static volatile String debugImgBase;

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r, "poster-worker");
        t.setDaemon(true);
        return t;
    });

    private final LruCache<String, Bitmap> memory;
    private final Set<String> failed = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Map<String, List<ImageView>> inFlight = new HashMap<>(); // main thread only
    private final File diskDir;
    private final AtomicInteger writes = new AtomicInteger();
    private final Object trimLock = new Object();

    private PosterLoader(Context ctx) {
        context = ctx.getApplicationContext();
        prefs = context.getSharedPreferences("iptv", Context.MODE_PRIVATE);
        diskDir = new File(context.getCacheDir(), "posters");

        // 30 bitmaps on Low tier, 80 otherwise
        int countCap = Ui.low() ? 30 : 80;
        memory = new LruCache<>(countCap);
    }

    public static synchronized PosterLoader get(Context ctx) {
        if (instance == null) instance = new PosterLoader(ctx);
        return instance;
    }

    /** Overrides the image base host in DEBUG builds (for mock server). Ignored in release. */
    public static void setImgBaseUrl(String url) {
        if (Config.DEBUG) {
            debugImgBase = url;
        }
    }

    public void clearMemory() {
        memory.evictAll();
    }

    /**
     * Loads a poster into an ImageView.
     * Checks memory cache, recycled view tags, and fetches asynchronously.
     */
    public void load(String posterPath, ImageView iv, int widthPx) { load(posterPath, iv, widthPx, null); }

    public void loadUrl(String url, ImageView iv, int widthPx) { load(url, iv, widthPx, null); }

    /** Details-page backdrop: w780, Mid / High tier only (the caller checks), one at a time. */
    public void loadBackdrop(String path, ImageView iv) { load(path, iv, 780, "w780"); }

    private final Map<String, String> sizes = new ConcurrentHashMap<>(); // path -> forced image size (backdrops)

    private void load(String posterPath, ImageView iv, int widthPx, String forcedSize) {
        if (iv == null) return;
        if (posterPath == null || posterPath.trim().isEmpty()) {
            iv.setImageDrawable(null);
            iv.setTag(null);
            return;
        }

        String path = posterPath.trim();
        boolean isHttp = path.startsWith("http://") || path.startsWith("https://");
        if (!isHttp && !path.startsWith("/")) path = "/" + path;
        iv.setTag(path);
        if (forcedSize != null) sizes.put(path, forcedSize);

        Bitmap cached = memory.get(path);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }

        iv.setImageDrawable(null);
        if (failed.contains(path)) return;

        List<ImageView> waiting = inFlight.get(path);
        if (waiting != null) {
            waiting.add(iv);
            return;
        }

        waiting = new ArrayList<>();
        waiting.add(iv);
        inFlight.put(path, waiting);

        final String finalPath = path;
        final int targetWidth = widthPx > 0 ? widthPx : (Ui.low() ? 185 : 342);
        pool.execute(() -> fetchAndDecode(finalPath, targetWidth));
    }

    private String getBaseHost() {
        if (Config.DEBUG) {
            if (debugImgBase != null && !debugImgBase.isEmpty()) return debugImgBase;
            if (prefs != null && prefs.contains("tmdb_img_base")) {
                String p = prefs.getString("tmdb_img_base", "").trim();
                if (!p.isEmpty()) return p;
            }
        }
        return DEFAULT_HOST;
    }

    private String buildPosterUrl(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        String base = getBaseHost();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String size = sizes.containsKey(path) ? sizes.get(path) : Ui.low() ? "w185" : "w342";
        return base + "/t/p/" + size + path;
    }

    private void fetchAndDecode(String path, int targetWidth) {
        Bitmap bmp = null;
        try {
            String hash = sha1(path);
            File f = new File(diskDir, hash + ".jpg");
            byte[] bytes = Files.exists(f.toPath()) ? Files.readAllBytes(f.toPath()) : null;
            if (bytes != null) {
                bmp = decode(bytes, targetWidth);
                if (bmp != null) {
                    f.setLastModified(System.currentTimeMillis());
                } else {
                    f.delete();
                }
            }

            if (bmp == null) {
                String url = buildPosterUrl(path);
                bytes = download(url);
                if (bytes != null) {
                    bmp = decode(bytes, targetWidth);
                    if (bmp != null) {
                        writeDisk(f, bytes);
                    } else {
                        failed.add(path);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "poster fetch error " + t.getClass().getSimpleName());
        }

        if (bmp != null) {
            memory.put(path, bmp);
        }
        deliver(path, bmp);
    }

    private void deliver(String path, Bitmap bmp) {
        main.post(() -> {
            List<ImageView> views = inFlight.remove(path);
            if (views == null || bmp == null) return;
            for (ImageView iv : views) {
                if (!path.equals(iv.getTag())) continue; // Recycled ImageView check
                iv.setImageBitmap(bmp);
            }
        });
    }

    private static byte[] download(String urlStr) throws IOException {
        URL u = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 ManusIPTV");
            int code = c.getResponseCode();
            if (code != 200 || c.getContentLengthLong() > MAX_BYTES) return null;

            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int total = 0, n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) return null;
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static Bitmap decode(byte[] bytes, int targetWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while ((bounds.outWidth / sample) > (targetWidth * 1.5)) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    private void writeDisk(File f, byte[] bytes) {
        try {
            diskDir.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(bytes);
            }
            if (!tmp.renameTo(f)) {
                tmp.delete();
                return;
            }
            if (writes.incrementAndGet() % TRIM_EVERY == 0) {
                trimDisk();
            }
        } catch (Throwable t) {
            Log.w(TAG, "disk write error " + t.getClass().getSimpleName());
        }
    }

    private void trimDisk() {
        synchronized (trimLock) {
            File[] files = diskDir.listFiles();
            if (files == null) return;
            long total = 0;
            for (File f : files) total += f.length();
            if (total <= MAX_DISK_BYTES) return;

            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (File f : files) {
                if (total <= MAX_DISK_BYTES) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        }
    }

    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
