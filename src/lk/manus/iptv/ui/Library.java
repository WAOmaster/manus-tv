package lk.manus.iptv.ui;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import lk.manus.iptv.Config;
import lk.manus.iptv.data.Db;
import lk.manus.iptv.data.SeriesSource;
import lk.manus.iptv.data.XtreamSource;

/**
 * "Movies" rail entry screen (T82):
 * Top row of category chips + sort chip; 5-column poster grid; facts line under it.
 * Progressive background sync on open if stale (> 12 h) or empty.
 */
public final class Library extends LinearLayout {
    public interface Host {
        void details(lk.manus.iptv.data.Library.Item item);
        void left(); // Left from first column or first chip: back to rail
        void back(); // Back when in search mode: back to search
    }

    private static final int COLS = 5;
    private static final int PAGE_SIZE = 100;
    private static final String[] SORT_NAMES = {"Recently added", "A–Z", "Rating"};

    private final String kind;
    private final boolean isSeries;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView title, sub, facts;
    private final HorizontalScrollView chipsScroll;
    private final LinearLayout chipsRow;
    private final RecyclerView rv;
    private final Adapter ad = new Adapter();

    private final List<lk.manus.iptv.data.Library.Item> list = new ArrayList<>();
    private final List<lk.manus.iptv.data.Library.Category> categories = new ArrayList<>();

    private int sort = 0; // 0: Recently added, 1: A-Z, 2: Rating
    private String selectedCat = null; // null = All
    private boolean isSearchMode = false;
    private String searchQuery = "";

    private lk.manus.iptv.data.Library.Item focus;
    private int focusedIndex = 0;
    private int tileW, tileH, gap;
    private boolean loadingPage = false;
    private boolean hasMore = true;
    private boolean wantFocus = false;
    private volatile boolean syncing = false;
    private long lastSyncProgressMs = 0;
    private long lastSyncRefreshMs = 0;

    private ChipView sortChip;
    private final List<ChipView> catChips = new ArrayList<>();

    public Library(Context c, Host host) {
        this(c, host, "movie");
    }

    public Library(Context c, Host host, String kind) {
        super(c);
        this.host = host;
        this.kind = lk.manus.iptv.data.Library.normKind(kind);
        this.isSeries = "series".equals(this.kind);
        setOrientation(VERTICAL);
        setBackground(Ui.card(0.94f, 18));
        setPadding(Ui.u(14), Ui.u(14), Ui.u(14), Ui.u(10));

        title = Ui.tv(c, Ui.TITLE, Ui.PAPER, true);
        sub = Ui.tv(c, Ui.CAPTION, Ui.MUTED, false);
        title.setPadding(Ui.u(4), Ui.u(2), 0, 0);
        sub.setPadding(Ui.u(4), Ui.u(3), 0, Ui.u(4));
        title.setText(isSeries ? "Series" : "Movies");
        addView(title);
        addView(sub);

        // Chips row: horizontally scrollable
        chipsScroll = new HorizontalScrollView(c);
        chipsScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setFocusable(false);
        chipsRow = new LinearLayout(c);
        chipsRow.setOrientation(HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        chipsRow.setPadding(Ui.u(4), Ui.u(4), Ui.u(4), Ui.u(8));
        chipsScroll.addView(chipsRow, new LayoutParams(-2, -2));
        addView(chipsScroll, new LayoutParams(-1, -2));

        // Grid
        rv = new RecyclerView(c);
        rv.setLayoutManager(new GridLayoutManager(c, COLS));
        rv.setAdapter(ad);
        rv.setItemAnimator(null);
        rv.setHasFixedSize(true);
        rv.setClipToPadding(false);
        rv.setOverScrollMode(OVER_SCROLL_NEVER);
        rv.setFocusable(false);
        rv.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        addView(rv, new LayoutParams(-1, 0, 1));

        // Facts line
        facts = Ui.tv(c, Ui.BODY, Ui.PAPER, false);
        facts.setPadding(Ui.u(4), Ui.u(8), Ui.u(12), Ui.u(4));
        addView(facts);

        setVisibility(GONE);
    }

    public void relayout(int widthPx) {
        gap = Ui.u(10);
        tileW = (widthPx - Ui.u(28) - Ui.u(12) * 2 - gap * COLS) / COLS - Ui.u(4);
        tileH = tileW * 3 / 2;
        rv.setPadding(Ui.u(12), Ui.u(4), Ui.u(12), Ui.u(4));
        ad.notifyDataSetChanged();
    }

    public boolean isSearchMode() {
        return isSearchMode;
    }

    public void load() {
        isSearchMode = false;
        searchQuery = "";
        title.setText(isSeries ? "Series" : "Movies");
        loadCategories();
        loadPage(true);
        checkSync();
    }

    public void loadSearch(String query) {
        isSearchMode = true;
        searchQuery = query != null ? query.trim() : "";
        boolean anySeries = !Config.PLAY && lk.manus.iptv.data.Library.any(Db.get(), "series");
        boolean anyMovies = !Config.PLAY && lk.manus.iptv.data.Library.any(Db.get(), "movie");
        if (isSeries) {
            title.setText("Series: " + searchQuery);
        } else if (anySeries && anyMovies) {
            title.setText("Search: " + searchQuery);
        } else if (anySeries) {
            title.setText("Series: " + searchQuery);
        } else {
            title.setText("Movies: " + searchQuery);
        }
        chipsScroll.setVisibility(GONE);
        focus = null;
        focusedIndex = 0;
        list.clear();
        ad.notifyDataSetChanged();
        facts.setText("");

        new Thread(new Runnable() {
            @Override public void run() {
                final String searchKind = isSeries ? "series" : "all";
                final List<lk.manus.iptv.data.Library.Item> results = lk.manus.iptv.data.Library.search(Db.get(), searchKind, searchQuery, 200);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!isSearchMode) return;
                        list.clear();
                        list.addAll(results);
                        ad.notifyDataSetChanged();
                        if (list.isEmpty()) {
                            boolean hasSeries = !Config.PLAY && lk.manus.iptv.data.Library.any(Db.get(), "series");
                            sub.setText(isSeries ? "No series yet" : hasSeries ? "No titles match." : "No films in this library.");
                            sub.setVisibility(VISIBLE);
                        } else {
                            sub.setVisibility(GONE);
                            if (hasFocus() || wantFocus) {
                                focusAt(0);
                            } else {
                                focusedIndex = 0;
                                focus = list.get(0);
                                facts.setText(factsText(focus));
                                rv.scrollToPosition(0);
                            }
                        }
                    }
                });
            }
        }, "lib-search").start();
    }

    private void loadCategories() {
        if (isSearchMode) return;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<lk.manus.iptv.data.Library.Category> cats = lk.manus.iptv.data.Library.categories(Db.get(), kind);
                main.post(new Runnable() {
                    @Override public void run() {
                        categories.clear();
                        categories.addAll(cats);
                        buildChips();
                    }
                });
            }
        }, "lib-cats").start();
    }

    private void buildChips() {
        if (isSearchMode) {
            chipsScroll.setVisibility(GONE);
            return;
        }
        chipsScroll.setVisibility(VISIBLE);
        chipsRow.removeAllViews();
        catChips.clear();

        // 1. "All" chip
        ChipView allChip = new ChipView(getContext(), "All", false, null);
        allChip.setSelectedChip(selectedCat == null);
        setupChip(allChip);
        catChips.add(allChip);
        chipsRow.addView(allChip);

        // 2. Category chips
        for (lk.manus.iptv.data.Library.Category cat : categories) {
            ChipView cv = new ChipView(getContext(), cat.name + " (" + cat.count + ")", false, cat.name);
            cv.setSelectedChip(cat.name.equals(selectedCat));
            setupChip(cv);
            catChips.add(cv);
            chipsRow.addView(cv);
        }

        // 3. Sort chip
        sortChip = new ChipView(getContext(), SORT_NAMES[sort], true, null);
        sortChip.setSelectedChip(true);
        setupChip(sortChip);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = Ui.u(20);
        chipsRow.addView(sortChip, sp);
    }

    private void setupChip(final ChipView cv) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cv.getLayoutParams();
        if (lp == null) lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.u(10);
        cv.setLayoutParams(lp);

        cv.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (cv.isSort) {
                    sort = (sort + 1) % 3;
                    cv.setText(SORT_NAMES[sort]);
                    loadPage(true);
                } else {
                    selectedCat = cv.category;
                    for (ChipView c : catChips) {
                        c.setSelectedChip(c.category == null ? selectedCat == null : c.category.equals(selectedCat));
                    }
                    loadPage(true);
                }
            }
        });

        cv.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent ev) {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                    int idx = chipsRow.indexOfChild(cv);
                    if (idx == 0) {
                        if (ev.getRepeatCount() == 0) host.left();
                        return true;
                    }
                } else if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (!list.isEmpty()) {
                        focusAt(Math.min(focusedIndex, list.size() - 1));
                    } else wantFocus = true; // T102: the key is kept: focus lands on the first tile when the page arrives
                    return true;
                }
                return false;
            }
        });
    }

    private void loadPage(final boolean resetFocus) {
        if (isSearchMode) return;
        loadingPage = true;
        if (list.isEmpty() && !syncing) { sub.setText("Loading…"); sub.setVisibility(VISIBLE); } // T102: quiet, in place
        final int querySort = sort;
        final String queryCat = selectedCat;
        final boolean onChip = chipsRow.hasFocus(); // a chip press must leave focus on the chip

        new Thread(new Runnable() {
            @Override public void run() {
                final List<lk.manus.iptv.data.Library.Item> pageRows = lk.manus.iptv.data.Library.page(Db.get(), kind, queryCat, querySort, 0, PAGE_SIZE);
                main.post(new Runnable() {
                    @Override public void run() {
                        loadingPage = false;
                        if (querySort != sort || (queryCat == null ? selectedCat != null : !queryCat.equals(selectedCat))) return;
                        list.clear();
                        list.addAll(pageRows);
                        hasMore = pageRows.size() >= PAGE_SIZE;
                        ad.notifyDataSetChanged();

                        if (list.isEmpty()) {
                            if (isSeries) {
                                sub.setText("No series yet");
                                sub.setVisibility(VISIBLE);
                            } else {
                                if (syncing) {
                                    sub.setText("No films yet. Your film list is still loading.");
                                    sub.setVisibility(VISIBLE);
                                } else {
                                    sub.setText("No films in this library.");
                                    sub.setVisibility(VISIBLE);
                                }
                            }
                            facts.setText("");
                        } else {
                            if (!syncing) sub.setVisibility(GONE);
                            int target = 0;
                            if (!resetFocus && focus != null) {
                                for (int i = 0; i < list.size(); i++) {
                                    if (list.get(i).id == focus.id) { target = i; break; }
                                }
                            }
                            if ((!onChip || wantFocus) && (hasFocus() || wantFocus)) {
                                focusAt(target);
                            } else {
                                focusedIndex = target;
                                focus = list.get(target);
                                facts.setText(factsText(focus));
                                rv.scrollToPosition(target);
                            }
                        }
                    }
                });
            }
        }, "lib-page").start();
    }

    private void loadNextPage() {
        if (isSearchMode || loadingPage || !hasMore) return;
        loadingPage = true;
        final int offset = list.size();
        final int querySort = sort;
        final String queryCat = selectedCat;

        new Thread(new Runnable() {
            @Override public void run() {
                final List<lk.manus.iptv.data.Library.Item> nextRows = lk.manus.iptv.data.Library.page(Db.get(), kind, queryCat, querySort, offset, PAGE_SIZE);
                main.post(new Runnable() {
                    @Override public void run() {
                        loadingPage = false;
                        if (querySort != sort || (queryCat == null ? selectedCat != null : !queryCat.equals(selectedCat))) return;
                        if (nextRows.isEmpty()) {
                            hasMore = false;
                            return;
                        }
                        int start = list.size();
                        list.addAll(nextRows);
                        hasMore = nextRows.size() >= PAGE_SIZE;
                        ad.notifyItemRangeInserted(start, nextRows.size());
                    }
                });
            }
        }, "lib-next-page").start();
    }

    private void checkSync() {
        if (Config.PLAY || isSearchMode) return;
        Db db = Db.get();
        if (db == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                SQLiteDatabase d = db.getReadableDatabase();
                long sourceId = -1;
                long lastSync = 0;
                Cursor cur = d.rawQuery("SELECT id, last_sync FROM source WHERE kind = 'xtream' LIMIT 1", null);
                try {
                    if (cur.moveToFirst()) {
                        sourceId = cur.getLong(0);
                        lastSync = cur.getLong(1);
                    }
                } finally {
                    cur.close();
                }
                if (sourceId < 0) return;

                long now = System.currentTimeMillis();
                if (lastSync == 0 || (now - lastSync) > 12 * 3600 * 1000L) {
                    final long sId = sourceId;
                    main.post(new Runnable() {
                        @Override public void run() {
                            startSync(sId);
                        }
                    });
                }
            }
        }, "lib-check-sync").start();
    }

    private void startSync(final long sourceId) {
        if (syncing) return;
        syncing = true;
        if (isSeries) {
            sub.setText("Syncing series…");
            sub.setVisibility(VISIBLE);
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        SeriesSource.syncSeries(getContext(), sourceId);
                    } catch (Throwable t) {
                        Log.w("IPTV", "syncSeries: " + t.getMessage());
                    }
                    main.post(new Runnable() {
                        @Override public void run() {
                            syncing = false;
                            lk.manus.iptv.data.Library.invalidateCategories("series");
                            loadCategories();
                            loadPage(false);
                            if (list.isEmpty()) {
                                sub.setText("No series yet");
                                sub.setVisibility(VISIBLE);
                            } else {
                                sub.setVisibility(GONE);
                            }
                        }
                    });
                }
            }, "series-sync").start();
            return;
        }

        sub.setText("Syncing films…  0 so far");
        sub.setVisibility(VISIBLE);

        XtreamSource.sync(getContext(), sourceId, new XtreamSource.Listener() {
            @Override public void onProgress(final int films) {
                main.post(new Runnable() {
                    @Override public void run() {
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastSyncProgressMs >= 500) {
                            lastSyncProgressMs = now;
                            sub.setText("Syncing films…  " + films + " so far");
                            sub.setVisibility(VISIBLE);
                        }
                        if (now - lastSyncRefreshMs >= 1000) {
                            lastSyncRefreshMs = now;
                            lk.manus.iptv.data.Library.invalidateCategories("movie");
                            loadCategories();
                            loadPage(false);
                        }
                    }
                });
            }

            @Override public void onDone(final int films, final String errorOrNull) {
                main.post(new Runnable() {
                    @Override public void run() {
                        syncing = false;
                        lk.manus.iptv.data.Library.invalidateCategories("movie");
                        loadCategories();
                        loadPage(false);
                        if (errorOrNull != null) {
                            if (!list.isEmpty()) {
                                sub.setText("Couldn't update films. Showing what we have.");
                                sub.setVisibility(VISIBLE);
                            } else {
                                sub.setText("No films in this library.");
                                sub.setVisibility(VISIBLE);
                            }
                        } else {
                            if (list.isEmpty()) {
                                sub.setText("No films in this library.");
                                sub.setVisibility(VISIBLE);
                            } else {
                                sub.setVisibility(GONE);
                            }
                        }
                    }
                });
            }
        });
    }

    public static String factsText(lk.manus.iptv.data.Library.Item item) {
        if (item == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(item.title);
        if (item.year > 0) {
            sb.append(" (").append(item.year).append(")");
        }
        if (item.rating > 0) {
            sb.append(" · ").append(String.format(Locale.US, "%.1f", item.rating)).append("/10");
        }
        if (item.groupName != null && !item.groupName.isEmpty()) {
            sb.append(" · ").append(item.groupName);
        }
        return sb.toString();
    }

    public void focusTile() {
        wantFocus = true;
        if (list.isEmpty()) {
            if (chipsRow.getChildCount() > 0) chipsRow.getChildAt(0).requestFocus();
            return;
        }
        focusAt(Math.max(0, Math.min(focusedIndex, list.size() - 1)));
    }

    public void focusAt(final int p) {
        wantFocus = false;
        if (p < 0 || p >= list.size()) return;
        focusedIndex = p;
        focus = list.get(p);
        facts.setText(factsText(focus));

        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(p);
        if (vh != null) {
            vh.itemView.requestFocus();
            return;
        }
        rv.scrollToPosition(p);
        rv.post(new Runnable() {
            @Override public void run() {
                RecyclerView.ViewHolder v = rv.findViewHolderForAdapterPosition(p);
                if (v != null) v.itemView.requestFocus();
            }
        });
    }

    public void activate() {
        View f = chipsRow.hasFocus() ? chipsRow.findFocus() : null;
        if (f != null) { f.performClick(); return; } // OK on a chip = the chip's own action (the Guide key router calls this, not the view)
        if (focus != null && host != null) {
            host.details(focus);
        }
    }

    public void hide() {
        setVisibility(GONE);
    }

    private Tile newTile(Context c) {
        final Tile t = new Tile(c, Tile.POSTER, false);
        t.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) {
                lk.manus.iptv.data.Library.Item item = (lk.manus.iptv.data.Library.Item) t.item;
                if (!f || item == null) return;
                focus = item;
                focusedIndex = list.indexOf(item);
                facts.setText(factsText(item));

                if (focusedIndex >= list.size() - 25) {
                    loadNextPage();
                }
            }
        });

        t.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                lk.manus.iptv.data.Library.Item item = (lk.manus.iptv.data.Library.Item) t.item;
                if (item != null && host != null) {
                    host.details(item);
                }
            }
        });

        t.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent ev) {
                if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT &&
                    k != KeyEvent.KEYCODE_DPAD_UP && k != KeyEvent.KEYCODE_DPAD_DOWN) {
                    return false;
                }
                lk.manus.iptv.data.Library.Item item = (lk.manus.iptv.data.Library.Item) t.item;
                if (ev.getAction() != KeyEvent.ACTION_DOWN || item == null) return true;

                int p = list.indexOf(item);
                int n = list.size();
                int col = p % COLS;

                if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (col == 0) {
                        if (ev.getRepeatCount() == 0) {
                            if (isSearchMode) host.back(); else host.left();
                        }
                    } else {
                        focusAt(p - 1);
                    }
                } else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (col < COLS - 1 && p + 1 < n) {
                        focusAt(p + 1);
                    }
                } else if (k == KeyEvent.KEYCODE_DPAD_UP) {
                    if (p - COLS >= 0) {
                        focusAt(p - COLS);
                    } else {
                        // Move up to chips row
                        if (chipsRow.getChildCount() > 0) {
                            chipsRow.getChildAt(0).requestFocus();
                        }
                    }
                } else if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (p + COLS < n) {
                        focusAt(p + COLS);
                    } else if (p / COLS < (n - 1) / COLS) {
                        focusAt(n - 1);
                    }
                }
                return true;
            }
        });
        return t;
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
            Tile tile = newTile(p.getContext());
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(tileW + Ui.u(4), tileH + Ui.u(4));
            lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
            tile.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(tile) {};
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder vh, int p) {
            Tile t = (Tile) vh.itemView;
            lk.manus.iptv.data.Library.Item item = list.get(p);
            t.item = item;
            t.poster(item.title, item.poster, tileW);
            t.badge(isSearchMode && "series".equals(item.kind) ? "Series" : null);
            if (item == focus) {
                facts.setText(factsText(item));
            }
        }

        @Override public int getItemCount() {
            return list.size();
        }
    }

    private static final class ChipView extends TextView {
        final boolean isSort;
        final String category;
        boolean selected;

        ChipView(Context c, String text, boolean isSort, String category) {
            super(c);
            this.isSort = isSort;
            this.category = category;
            setText(text);
            setFocusable(true);
            setGravity(Gravity.CENTER);
            setPadding(Ui.u(14), Ui.u(6), Ui.u(14), Ui.u(6));
            Ui.size(this, Ui.CAPTION);
            updateStyle();
            setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    updateStyle();
                }
            });
        }

        void setSelectedChip(boolean sel) {
            this.selected = sel;
            updateStyle();
        }

        void updateStyle() {
            if (hasFocus()) {
                setBackground(Ui.ring());
                setTextColor(Ui.PAPER);
            } else if (selected) {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(Ui.u(12));
                g.setStroke(Math.max(1, Ui.u(1)), Ui.PAPER);
                setBackground(g);
                setTextColor(Ui.PAPER);
            } else {
                setBackground(Ui.chip());
                setTextColor(Ui.MUTED);
            }
        }
    }
}
