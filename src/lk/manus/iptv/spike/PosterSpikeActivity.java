package lk.manus.iptv.spike;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lk.manus.iptv.Config;

/**
 * T55 poster spike: poster row + grid, RecyclerView vs framework widgets.
 * DEBUG-only: finishes immediately in release builds.
 */
public final class PosterSpikeActivity extends Activity {
    private static final int TOTAL_ITEMS = 60;
    private static final int POSTER_W = 185;
    private static final int POSTER_H = 278; // 185 * 1.5
    private static final int CARD_GAP = 15;
    private static final int COL_GAP = 24;
    private static final int ROW_GAP = 20;
    private static final int OUTLINE_RED = 0xFFE3262E;
    private static final int BG_COLOR = 0xFF0B0F19;

    private static final String[] TITLES = {
        "COSMIC DAWN", "NEON SHADOWS", "SILENT ORBIT", "CYBER RUNNER", "THE LAST VOYAGE",
        "DEEP ECHOES", "MIDNIGHT DRIVE", "QUANTUM REALM", "STELLAR DUST", "AURORA SKY",
        "GHOST SIGNAL", "VELOCITY ZERO", "EMERALD PEAK", "IRON HORIZON", "DARK MATTER",
        "SOLAR FLARE", "NEBULA DRIFT", "LOST PROTOCOL", "VAPOR TRAIL", "CHRONO SHIFT",
        "FROZEN STATIC", "ECLIPSE RISING", "TITAN ENGINE", "OBSIDIAN BAY", "PULSAR FLASH",
        "RADIO SILENCE", "ALPHA STATION", "CIRCUIT BREAKER", "VOID RUNNER", "DESERT MIRAGE",
        "HYPER LIGHT", "NIGHT CALLER", "STEEL OCEAN", "RED SECTOR", "FRACTURE LINE",
        "ORBITAL DECAY", "SHADOW FALL", "TWILIGHT ZONE", "INFINITY CORE", "ROGUE PLANET",
        "ZERO HOUR", "PRISM BREAK", "TERMINAL NODE", "VECTOR FIELDS", "STATIC DREAMS",
        "BEYOND HORIZONS", "PHANTOM GRID", "COBALT SKY", "SUB ZERO", "WARP MATRIX",
        "METEOR RAIN", "GRAVITY WELL", "CARBON DRIFT", "ASTRAL PLANE", "NEON HIGHWAY",
        "THUNDER RUN", "DIGITAL RAIN", "OUTER LIMITS", "OMEGA POINT", "FINAL FRONTIER"
    };

    private PosterLoader loader;
    private String mode = "rv";
    private String shape = "row";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Config.DEBUG) {
            finish();
            return;
        }

        String m = getIntent().getStringExtra("mode");
        if (m != null) mode = m.toLowerCase();
        String s = getIntent().getStringExtra("shape");
        if (s != null) shape = s.toLowerCase();

        loader = new PosterLoader();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG_COLOR);

        if ("rv".equals(mode)) {
            setupRecyclerView(root);
        } else {
            setupFrameworkView(root);
        }

        setContentView(root, new ViewGroup.LayoutParams(1280, 720));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (loader != null) {
            loader.shutdown();
        }
    }

    private String getPosterUrl(int index) {
        return "http://10.0.2.2:8088/posters/poster_" + index + ".jpg";
    }

    // ------------------------------------------------------------------------
    // RecyclerView Setup
    // ------------------------------------------------------------------------
    private void setupRecyclerView(FrameLayout root) {
        RecyclerView rv = new RecyclerView(this);
        rv.setHasFixedSize(true);
        rv.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        if ("row".equals(shape)) {
            LinearLayoutManager lm = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
            rv.setLayoutManager(lm);
            rv.setPadding(40, 10, 40, 10);
            rv.setClipToPadding(false);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, POSTER_H + 50);
            lp.gravity = Gravity.CENTER_VERTICAL;
            root.addView(rv, lp);
        } else {
            GridLayoutManager gm = new GridLayoutManager(this, 6);
            rv.setLayoutManager(gm);
            rv.setPadding(25, 25, 25, 25);
            rv.setClipToPadding(false);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            root.addView(rv, lp);
        }

        RvAdapter adapter = new RvAdapter();
        rv.setAdapter(adapter);

        rv.post(() -> {
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(0);
            if (vh != null) vh.itemView.requestFocus();
        });
    }

    private final class RvAdapter extends RecyclerView.Adapter<RvViewHolder> {
        @NonNull
        @Override
        public RvViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            CardView card = new CardView(PosterSpikeActivity.this);
            if ("row".equals(shape)) {
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    POSTER_W, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = CARD_GAP;
                card.setLayoutParams(lp);
            } else {
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    POSTER_W, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = ROW_GAP;
                lp.rightMargin = COL_GAP / 2;
                lp.leftMargin = COL_GAP / 2;
                card.setLayoutParams(lp);
            }
            return new RvViewHolder(card);
        }

        @Override
        public void onBindViewHolder(@NonNull RvViewHolder holder, int position) {
            holder.card.bind(position, TITLES[position % TITLES.length], getPosterUrl(position), loader);
        }

        @Override
        public int getItemCount() {
            return TOTAL_ITEMS;
        }
    }

    private static final class RvViewHolder extends RecyclerView.ViewHolder {
        final CardView card;
        RvViewHolder(CardView view) {
            super(view);
            this.card = view;
        }
    }

    // ------------------------------------------------------------------------
    // Framework Setup
    // ------------------------------------------------------------------------
    private void setupFrameworkView(FrameLayout root) {
        if ("grid".equals(shape)) {
            GridView gv = new GridView(this);
            gv.setNumColumns(6);
            gv.setColumnWidth(POSTER_W);
            gv.setHorizontalSpacing(COL_GAP);
            gv.setVerticalSpacing(ROW_GAP);
            gv.setPadding(25, 25, 25, 25);
            gv.setClipToPadding(false);
            gv.setStretchMode(GridView.NO_STRETCH);

            // Selector: transparent so CardView handles its own 2px outline
            GradientDrawable sel = new GradientDrawable();
            sel.setColor(Color.TRANSPARENT);
            gv.setSelector(sel);

            FwGridAdapter adapter = new FwGridAdapter();
            gv.setAdapter(adapter);

            gv.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                private View prev;
                @Override
                public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    if (prev instanceof CardView) ((CardView) prev).setFocusedVisual(false);
                    if (v instanceof CardView) ((CardView) v).setFocusedVisual(true);
                    prev = v;
                }
                @Override
                public void onNothingSelected(AdapterView<?> p) {
                    if (prev instanceof CardView) ((CardView) prev).setFocusedVisual(false);
                    prev = null;
                }
            });

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            root.addView(gv, lp);

            gv.post(() -> gv.setSelection(0));
        } else {
            // Horizontal row with manual recycling inside HorizontalScrollView
            RecyclingRowView rowView = new RecyclingRowView(this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, POSTER_H + 50);
            lp.gravity = Gravity.CENTER_VERTICAL;
            root.addView(rowView, lp);
        }
    }

    private final class FwGridAdapter extends BaseAdapter {
        @Override
        public int getCount() { return TOTAL_ITEMS; }
        @Override
        public Object getItem(int position) { return TITLES[position % TITLES.length]; }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            CardView card = (convertView instanceof CardView) ? (CardView) convertView : new CardView(PosterSpikeActivity.this);
            card.setLayoutParams(new GridView.LayoutParams(POSTER_W, POSTER_H + 34));
            card.bind(position, TITLES[position % TITLES.length], getPosterUrl(position), loader);
            return card;
        }
    }

    /**
     * Framework horizontal row with manual recycling:
     * Only visible items (+ small buffer) are attached as children.
     */
    private final class RecyclingRowView extends HorizontalScrollView {
        private final FrameLayout container;
        private final Map<Integer, CardView> active = new HashMap<>();
        private final List<CardView> scrap = new ArrayList<>();
        private int focusedPos = 0;
        private final int step = POSTER_W + CARD_GAP;

        public RecyclingRowView(Context context) {
            super(context);
            setHorizontalScrollBarEnabled(false);
            setFocusable(false);

            container = new FrameLayout(context);
            int totalW = 40 + TOTAL_ITEMS * step + 40;
            addView(container, new LayoutParams(totalW, POSTER_H + 50));

            layoutWindow(0);
            post(() -> {
                CardView v = active.get(0);
                if (v != null) v.requestFocus();
            });
        }

        private void layoutWindow(int center) {
            int start = Math.max(0, center - 3);
            int end = Math.min(TOTAL_ITEMS - 1, center + 6);

            // Reclaim out of window
            List<Integer> toRemove = new ArrayList<>();
            for (Map.Entry<Integer, CardView> entry : active.entrySet()) {
                int pos = entry.getKey();
                if (pos < start || pos > end) {
                    toRemove.add(pos);
                }
            }
            for (int pos : toRemove) {
                CardView v = active.remove(pos);
                container.removeView(v);
                scrap.add(v);
            }

            // Add/bind in window
            for (int i = start; i <= end; i++) {
                if (!active.containsKey(i)) {
                    CardView v;
                    if (!scrap.isEmpty()) {
                        v = scrap.remove(scrap.size() - 1);
                    } else {
                        v = new CardView(getContext());
                    }
                    v.bind(i, TITLES[i % TITLES.length], getPosterUrl(i), loader);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(POSTER_W, POSTER_H + 34);
                    lp.leftMargin = 40 + i * step;
                    lp.topMargin = 0;
                    container.addView(v, lp);
                    active.put(i, v);
                }
            }
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (focusedPos < TOTAL_ITEMS - 1) {
                        focusedPos++;
                        layoutWindow(focusedPos);
                        CardView v = active.get(focusedPos);
                        if (v != null) v.requestFocus();
                        smoothScrollTo(Math.max(0, 40 + focusedPos * step - 400), 0);
                        return true;
                    }
                } else if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (focusedPos > 0) {
                        focusedPos--;
                        layoutWindow(focusedPos);
                        CardView v = active.get(focusedPos);
                        if (v != null) v.requestFocus();
                        smoothScrollTo(Math.max(0, 40 + focusedPos * step - 400), 0);
                        return true;
                    }
                }
            }
            return super.dispatchKeyEvent(event);
        }
    }

    // ------------------------------------------------------------------------
    // Unified CardView: 185 px wide poster, 2px red outline on focus, title line
    // ------------------------------------------------------------------------
    public static final class CardView extends LinearLayout {
        private final FrameLayout posterFrame;
        private final ImageView posterView;
        private final TextView titleView;
        private final GradientDrawable borderDrawable;

        public CardView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(false);

            posterFrame = new FrameLayout(context);
            posterView = new ImageView(context);
            posterView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            posterFrame.addView(posterView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            borderDrawable = new GradientDrawable();
            borderDrawable.setColor(Color.TRANSPARENT);
            borderDrawable.setStroke(0, Color.TRANSPARENT);
            posterFrame.setBackground(borderDrawable);

            addView(posterFrame, new LayoutParams(POSTER_W, POSTER_H));

            titleView = new TextView(context);
            titleView.setSingleLine(true);
            titleView.setTextSize(13);
            titleView.setTextColor(0xFFF5F7FF);
            titleView.setGravity(Gravity.CENTER_HORIZONTAL);
            titleView.setVisibility(View.INVISIBLE); // visible only under focused item

            LayoutParams tlp = new LayoutParams(POSTER_W, 30);
            tlp.topMargin = 4;
            addView(titleView, tlp);

            setOnFocusChangeListener((v, hasFocus) -> setFocusedVisual(hasFocus));
        }

        public void setFocusedVisual(boolean focused) {
            if (focused) {
                borderDrawable.setStroke(2, OUTLINE_RED);
                titleView.setVisibility(View.VISIBLE);
            } else {
                borderDrawable.setStroke(0, Color.TRANSPARENT);
                titleView.setVisibility(View.INVISIBLE);
            }
        }

        public void bind(int pos, String title, String url, PosterLoader loader) {
            titleView.setText(title);
            loader.load(url, posterView);
            setFocusedVisual(isFocused() || isSelected());
        }
    }

    // ------------------------------------------------------------------------
    // Minimal Image Loader (spec: <= 185 px wide, RGB_565, 30 bitmaps LruCache, 3 threads)
    // ------------------------------------------------------------------------
    public static final class PosterLoader {
        private static final int THREADS = 3;
        private static final int MAX_WIDTH = 185;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        private final LruCache<String, Bitmap> cache = new LruCache<>(30);

        public void load(String url, ImageView iv) {
            if (iv == null) return;
            iv.setTag(url);
            Bitmap cached = url != null ? cache.get(url) : null;
            if (cached != null) {
                iv.setImageBitmap(cached);
                return;
            }
            iv.setImageDrawable(null);
            if (url == null) return;

            pool.execute(() -> {
                Bitmap bmp = fetchAndDecode(url);
                if (bmp != null) {
                    cache.put(url, bmp);
                    main.post(() -> {
                        if (url.equals(iv.getTag())) {
                            iv.setImageBitmap(bmp);
                        }
                    });
                }
            });
        }

        private Bitmap fetchAndDecode(String urlStr) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setInstanceFollowRedirects(true);
                if (conn.getResponseCode() != 200) return null;

                byte[] bytes = readAll(conn.getInputStream());
                if (bytes == null || bytes.length == 0) return null;

                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

                int sample = 1;
                while ((bounds.outWidth / (sample * 2)) >= MAX_WIDTH) {
                    sample *= 2;
                }
                if (bounds.outWidth / sample > MAX_WIDTH) {
                    sample *= 2;
                }

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = Math.max(1, sample);
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            } catch (Throwable t) {
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private static byte[] readAll(InputStream in) throws IOException {
            try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        }

        public void shutdown() {
            pool.shutdownNow();
        }
    }
}
