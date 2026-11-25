package lk.manus.iptv.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Index;
import lk.manus.iptv.data.Store;
import lk.manus.iptv.meta.MetaStore;
import lk.manus.iptv.ui.Films.Entry;

/** Home: up to four rows (Movies on now, Starting soon, Favourites, Recent) in the panel the film grid uses, one facts line at the bottom for the focused tile. */
public final class Home extends LinearLayout {
    public interface Host {
        void series(SeriesNow.Entry e);                              // OK on a series that is on now: tune it and close the guide
        void film(Entry e);                                          // OK on a film that is on now: tune it and close the guide
        void allFilms();                                             // OK on "All films"
        void details(Entry e);                                       // OK on a film starting soon; INFO / long OK on any film
        void channel(String sectionId, List<Channel> list, int pos); // OK on a channel tile: tune it and close the guide
        void libFilm(lk.manus.iptv.data.Library.Item item);          // OK on Continue watching tile: opens LibDetails
        void removeContinue(lk.manus.iptv.data.Library.ContinueItem ci); // Long-press or context menu on Continue watching tile
        void myListItem(lk.manus.iptv.data.Library.Item item);       // OK on a My list tile: that film's or series' Details
        void left();                                                 // Left from the first tile: back to the rail
    }

    private static final int CONTINUE = 0, MYLIST = 1, MOVIES = 2, SERIES = 3, SOON = 4, FAV = 5, RECENT = 6;
    private static final int T_POSTER = 0, T_POSTER_CAP = 1, T_LOGO = 2, T_MORE = 3;
    private static final int MAX_FILMS = 20, SOON_HOURS = 3, QUIET_MAX = 6;
    private static final Object MORE = new Object(); // the last tile of "Movies on now"

    private final class RowV {
        final int kind;
        final LinearLayout box = new LinearLayout(getContext());
        final TextView head;
        final RecyclerView rv;
        final List<Object> items = new ArrayList<>();
        final RowAdapter ad = new RowAdapter(this);
        List<Channel> chans = new ArrayList<>(); // FAV / RECENT: the list a tile's OK tunes within
        RowV(int kind, String title) {
            this.kind = kind;
            box.setOrientation(VERTICAL);
            head = Ui.tv(getContext(), Ui.BODY, Ui.PAPER, true);
            head.setText(title);
            head.setPadding(Ui.u(8), Ui.u(8), 0, Ui.u(2));
            box.addView(head);
            rv = new RecyclerView(getContext());
            rv.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
            rv.setRecycledViewPool(pool);
            rv.setAdapter(ad);
            rv.setItemAnimator(null);
            rv.setHasFixedSize(true);
            rv.setClipToPadding(false);
            rv.setOverScrollMode(OVER_SCROLL_NEVER);
            rv.setFocusable(false);
            rv.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            box.addView(rv, new LinearLayout.LayoutParams(-1, Ui.u(100)));
            box.setVisibility(GONE);
        }
        boolean shown() { return !items.isEmpty(); }
        boolean films() { return kind == MOVIES || kind == SOON; }
    }

    private final Host host;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
    private final ScrollView sv;
    private final TextView facts;
    private final RowV[] rows = new RowV[7];
    private Object focus;
    private RowV focusRow;
    private Store store;
    private int gen, pw, ph, lw, lh, gap;
    private boolean fast, wantFocus, wantMy;
    private List<Entry> quiet = new ArrayList<>();
    private long lastNav;

    public Home(Context c, Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
        setBackground(Ui.card(0.94f, 18));
        setPadding(Ui.u(14), Ui.u(8), Ui.u(14), Ui.u(10));
        sv = new ScrollView(c);
        sv.setFocusable(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        String[] names = {"Continue watching", "My list", "Movies on now", "Series on now", "Starting soon", "Favourites", "Recent"};
        for (int i = 0; i < rows.length; i++) { rows[i] = new RowV(i, names[i]); col.addView(rows[i].box); }
        sv.addView(col, new ScrollView.LayoutParams(-1, -2));
        addView(sv, new LayoutParams(-1, 0, 1));
        facts = Ui.tv(c, Ui.BODY, Ui.PAPER, false);
        facts.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); // a long line keeps its start and its end (minutes left, "reminder set")
        facts.setPadding(Ui.u(4), Ui.u(8), Ui.u(12), Ui.u(4));
        addView(facts);
        setVisibility(GONE);
    }

    /** Width of the whole area (px): tile sizes follow the type scale, not the width. */
    public void relayout(int widthPx) {
        gap = Ui.u(10);
        pw = Ui.u(100); ph = pw * 3 / 2;
        lw = Ui.u(130); lh = lw * 9 / 16;
        int ring = Ui.u(4);
        for (RowV r : rows) {
            int itemH = (r.kind == CONTINUE || r.kind == MYLIST || r.kind == MOVIES || r.kind == SERIES ? ph : r.kind == SOON ? ph + Tile.captionH() : lh + Tile.captionH()) + ring + gap;
            r.rv.getLayoutParams().height = itemH + Ui.u(4);
            r.rv.setPadding(Ui.u(8), Ui.u(2), Ui.u(8), Ui.u(2));
        }
        pool.clear();
        for (RowV r : rows) r.ad.notifyDataSetChanged();
    }

    /** Builds the rows (cached answers first), then shows them. */
    public void load(final Context c, final Index ix, final Store st) {
        store = st;
        final List<Channel> fav = ix.list("fav", st), rec = ix.list("recent", st), movies = ix.list("cat:Movies", st);
        final boolean on = Films.enabled(c);
        final int g = ++gen;
        if (firstShown() == null) { facts.setTextColor(Ui.MUTED); facts.setText("Finding what's on now…"); } // T102: quiet, in place; says what it waits for
        new Thread(new Runnable() {
            @Override public void run() {
                final List<lk.manus.iptv.data.Library.ContinueItem> cw = lk.manus.iptv.data.Library.continueWatching(lk.manus.iptv.data.Db.get(), 20);
                final List<lk.manus.iptv.data.Library.Item> ml = lk.manus.iptv.data.Library.myList(lk.manus.iptv.data.Db.get());
                List<Entry> now = on ? Films.candidates(c, ix, st, movies) : new ArrayList<Entry>();
                final List<Entry> soon = on ? Films.upcoming(c, movies, SOON_HOURS) : new ArrayList<Entry>();
                MetaStore ms = MetaStore.get(c);
                for (Entry e : now) e.film = ms.get(e.clean.title, e.clean.year); // cache only, no network
                final List<Entry> ok = new ArrayList<>();
                final List<Entry> todo = new ArrayList<>(); // unknown ones resolve quietly after the rows are up, for the next Home open
                for (Entry e : soon) { // the row holds only films already known as matched
                    e.film = ms.get(e.clean.title, e.clean.year);
                    if (e.matched()) { if (ok.size() < MAX_FILMS) ok.add(e); }
                    else if (e.film == null && todo.size() < QUIET_MAX) todo.add(e);
                }
                quiet = todo;
                final List<SeriesNow.Entry> ser = new ArrayList<>(); // Series on now: marked or cached-matched shows, order fixed here
                if (on) {
                    List<SeriesNow.Entry> sa = SeriesNow.candidates(c, ix, st, ix.all);
                    SeriesNow.loadCached(c, sa);
                    List<SeriesNow.Entry> sh = SeriesNow.group(SeriesNow.shown(sa), st);
                    SeriesNow.sort(sh, st);
                    for (SeriesNow.Entry e : sh) if (ser.size() < MAX_FILMS) ser.add(e);
                }
                now = Films.group(now, st); // T110e: one tile per film, before the order is fixed
                Films.sort(now, st);
                final List<Entry> top = new ArrayList<>(); // Home only: known matches first (pinned countries first among them), then unresolved; known no-matches stay in the full grid
                for (Entry e : now) if (e.matched() && top.size() < MAX_FILMS) top.add(e);
                for (Entry e : now) if (!e.matched() && !e.unmatched() && top.size() < MAX_FILMS) top.add(e);
                h.post(new Runnable() { @Override public void run() { if (g == gen) { show(cw, ml, top, ser, ok, fav, rec); h.postDelayed(new Runnable() { @Override public void run() { quietNext(c, g, 0); } }, 1500); } } });
            }
        }, "home").start();
    }

    /** Resolves the unknown upcoming films one by one, never while keys are moving; the row itself is not touched (they show the next time Home opens). */
    private void quietNext(final Context c, final int g, final int i) {
        if (g != gen || i >= quiet.size()) return;
        if (SystemClock.uptimeMillis() - lastNav < 800) { // a key was just pressed: try again later
            h.postDelayed(new Runnable() { @Override public void run() { quietNext(c, g, i); } }, 1000);
            return;
        }
        Films.resolve(c, quiet.get(i).slot, new java.util.function.Consumer<MetaStore.Film>() {
            @Override public void accept(MetaStore.Film f) { quietNext(c, g, i + 1); }
        });
    }

    private void show(List<lk.manus.iptv.data.Library.ContinueItem> cw, List<lk.manus.iptv.data.Library.Item> ml, List<Entry> now, List<SeriesNow.Entry> ser, List<Entry> soon, List<Channel> fav, List<Channel> rec) {
        if (lk.manus.iptv.Config.DEBUG) android.util.Log.i("IPTV", "home pinned " + store.fyCountries() + ", first 8 countries: " + Films.countries(now, 8) + "; soon " + soon.size() + "; continue " + (cw != null ? cw.size() : 0));
        final RowV keepRow = hasFocus() ? focusRow : null; // T102: late rows must not pull focus off the tile the viewer moved to
        final int keepAt = keepRow == null ? -1 : keepRow.items.indexOf(focus);
        rows[CONTINUE].items.clear();
        if (cw != null) rows[CONTINUE].items.addAll(cw);
        rows[MYLIST].items.clear(); rows[MYLIST].items.addAll(ml);
        rows[MOVIES].items.clear(); rows[MOVIES].items.addAll(now);
        if (!now.isEmpty()) rows[MOVIES].items.add(MORE);
        rows[SERIES].items.clear(); rows[SERIES].items.addAll(ser);
        rows[SOON].items.clear(); rows[SOON].items.addAll(soon);
        rows[FAV].items.clear(); rows[FAV].items.addAll(fav); rows[FAV].chans = fav;
        rows[RECENT].items.clear(); rows[RECENT].items.addAll(rec); rows[RECENT].chans = rec;
        focus = null; focusRow = null;
        facts.setTextColor(Ui.PAPER); facts.setText("");
        for (RowV r : rows) {
            r.box.setVisibility(r.shown() ? VISIBLE : GONE);
            r.rv.scrollToPosition(0);
            r.ad.notifyDataSetChanged();
        }
        if (keepRow != null && keepAt >= 0 && keepRow.shown()) { focusAt(keepRow, Math.min(keepAt, keepRow.items.size() - 1)); return; }
        sv.scrollTo(0, 0);
        if (hasFocus() || wantFocus) focusTile();
    }

    public boolean hasFocus() { return sv.hasFocus(); }

    /** Focus the first tile of the first row. Safe before the rows have loaded: it waits for them. */
    public void focusTile() {
        RowV r = firstShown();
        if (r == null) { wantFocus = true; return; }
        wantFocus = false;
        if (wantMy) { wantMy = false; if (rows[MYLIST].shown()) { focusAt(rows[MYLIST], 0); return; } }
        int at = focusRow != null && focusRow.shown() ? focusRow.items.indexOf(focus) : -1; // back from Details: the tile that was focused
        if (at >= 0) focusAt(focusRow, at); else focusAt(r, 0);
    }

    /** T111: the menu's My list entry: Home, cursor on the My list row (waits for the rows like focusTile). */
    public void focusMyList() { wantMy = true; focusTile(); }

    private RowV firstShown() { for (RowV r : rows) if (r.shown()) return r; return null; }

    private RowV step(RowV from, int d) {
        for (int i = from.kind + d; i >= 0 && i < rows.length; i += d) if (rows[i].shown()) return rows[i];
        return null;
    }

    public void hide() {
        gen++;
        wantFocus = false;
        wantMy = false;
        h.removeCallbacks(calmR);
        LogoLoader.quiet = false;
        setVisibility(GONE);
    }

    public Entry focusedFilm() { return focus instanceof Entry ? (Entry) focus : null; }
    public SeriesNow.Entry focusedSeries() { return focus instanceof SeriesNow.Entry ? (SeriesNow.Entry) focus : null; }

    /** OK on the focused tile. */
    public void activate() {
        if (focus == null || focusRow == null) return;
        if (focusRow.kind == CONTINUE && focus instanceof lk.manus.iptv.data.Library.ContinueItem) {
            host.libFilm(((lk.manus.iptv.data.Library.ContinueItem) focus).item);
        }
        else if (focusRow.kind == MYLIST && focus instanceof lk.manus.iptv.data.Library.Item) host.myListItem((lk.manus.iptv.data.Library.Item) focus);
        else if (focus == MORE) host.allFilms();
        else if (focus instanceof SeriesNow.Entry) host.series((SeriesNow.Entry) focus);
        else if (focus instanceof Channel) host.channel(focusRow.kind == FAV ? "fav" : "recent", focusRow.chans, focusRow.chans.indexOf(focus));
        else if (focusRow.kind == SOON) host.details((Entry) focus);
        else host.film((Entry) focus);
    }

    public boolean onLongOk() {
        if (focusRow != null && focusRow.kind == CONTINUE && focus instanceof lk.manus.iptv.data.Library.ContinueItem) {
            host.removeContinue((lk.manus.iptv.data.Library.ContinueItem) focus);
            return true;
        }
        return false;
    }

    public void removeContinue(lk.manus.iptv.data.Library.ContinueItem ci) {
        int i = rows[CONTINUE].items.indexOf(ci);
        if (i >= 0) drop(rows[CONTINUE], i);
    }

    /** T104: back from a Details page: the My list row follows what was added or removed there; the focused tile stays, or its neighbour takes over. */
    public void refreshMyList() {
        final int g = gen;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<lk.manus.iptv.data.Library.Item> ml = lk.manus.iptv.data.Library.myList(lk.manus.iptv.data.Db.get());
                h.post(new Runnable() { @Override public void run() { if (g == gen) applyMyList(ml); } });
            }
        }, "home-mylist").start();
    }

    private void applyMyList(List<lk.manus.iptv.data.Library.Item> ml) {
        RowV r = rows[MYLIST];
        long fid = focusRow == r && focus instanceof lk.manus.iptv.data.Library.Item ? ((lk.manus.iptv.data.Library.Item) focus).id : -1;
        int fat = focusRow == r ? r.items.indexOf(focus) : -1;
        r.items.clear(); r.items.addAll(ml);
        r.box.setVisibility(r.shown() ? VISIBLE : GONE);
        r.ad.notifyDataSetChanged();
        if (focusRow == r) {
            int at = -1;
            for (int i = 0; i < ml.size(); i++) if (ml.get(i).id == fid) at = i;
            if (at < 0 && !ml.isEmpty()) at = Math.min(Math.max(fat, 0), ml.size() - 1);
            focus = at >= 0 ? ml.get(at) : null;
            if (at < 0) focusRow = null;
            if (at >= 0) { focusAt(r, at); return; }
        }
        if (focusRow == null) sv.scrollTo(0, 0);
        focusTile();
    }

    /** The minute turned over: minutes left and "Now:" titles change. */
    public void tick() {
        if (focus != null && focusRow != null) facts.setText(factsOf(focusRow, focus));
        for (RowV r : rows) if (!r.films() && r.kind != CONTINUE && r.kind != MYLIST && r.kind != SERIES && r.shown()) r.ad.notifyItemRangeChanged(0, r.items.size());
    }

    /** A film was chosen for a tile while a page is over Home: tiles and facts follow, focus stays where it is. */
    public void refresh() {
        for (RowV r : rows) if (r.films()) r.ad.notifyItemRangeChanged(0, r.items.size());
        if (focus != null && focusRow != null) facts.setText(factsOf(focusRow, focus));
    }

    /** A film was removed from the focused tile ("Not this film"): the tile is plain now. */
    public void redraw() {
        for (RowV r : rows) if (r.films()) r.ad.notifyItemRangeChanged(0, r.items.size());
        if (focus != null && focusRow != null) facts.setText(factsOf(focusRow, focus));
        focusTile();
    }

    private String factsOf(RowV r, Object o) {
        if (r.kind == CONTINUE && o instanceof lk.manus.iptv.data.Library.ContinueItem) {
            lk.manus.iptv.data.Library.ContinueItem ci = (lk.manus.iptv.data.Library.ContinueItem) o;
            return ci.item.title + (ci.item.year > 0 ? " (" + ci.item.year + ")" : "") + (ci.positionMs > 0 ? " · " + lk.manus.iptv.data.FilmResume.resumeButtonText(ci.positionMs) : "");
        }
        if (r.kind == MYLIST && o instanceof lk.manus.iptv.data.Library.Item) {
            lk.manus.iptv.data.Library.Item it = (lk.manus.iptv.data.Library.Item) o;
            return it.title + (it.year > 0 ? " (" + it.year + ")" : "") + ("series".equals(it.kind) ? " · Series" : "");
        }
        if (o == MORE) return "All films";
        if (o instanceof Channel) return Films.factsChannel((Channel) o);
        if (o instanceof SeriesNow.Entry) return SeriesNow.facts((SeriesNow.Entry) o);
        return r.kind == SOON ? Films.factsSoon((Entry) o) : Films.facts((Entry) o);
    }

    // ---- focus and keys ----

    private void focusAt(final RowV r, final int p) {
        if (p < 0 || p >= r.items.size()) return;
        RecyclerView.ViewHolder vh = r.rv.findViewHolderForAdapterPosition(p);
        if (vh != null) { vh.itemView.requestFocus(); return; }
        r.rv.scrollToPosition(p);
        r.rv.post(new Runnable() {
            @Override public void run() {
                RecyclerView.ViewHolder v = r.rv.findViewHolderForAdapterPosition(p);
                if (v != null) v.itemView.requestFocus();
            }
        });
    }

    private RowV rowOf(View tile) {
        View p = (View) tile.getParent();
        for (RowV r : rows) if (r.rv == p) return r;
        return null;
    }

    /** Up / Down: the tile of the next row whose middle is nearest to this one's, so the cursor keeps its place across the screen. */
    private void vertical(View from, RowV to) {
        int[] loc = new int[2];
        from.getLocationOnScreen(loc);
        int cx = loc[0] + from.getWidth() / 2, best = 0, bd = Integer.MAX_VALUE;
        for (int i = 0; i < to.rv.getChildCount(); i++) {
            View c = to.rv.getChildAt(i);
            int p = to.rv.getChildAdapterPosition(c);
            if (p < 0) continue;
            c.getLocationOnScreen(loc);
            int d = Math.abs(loc[0] + c.getWidth() / 2 - cx);
            if (d < bd) { bd = d; best = p; }
        }
        focusAt(to, best);
    }

    private void keyOn(Tile t, int k, KeyEvent ev) {
        RowV r = rowOf(t);
        if (r == null) return;
        int p = r.items.indexOf(t.item);
        if (k == KeyEvent.KEYCODE_DPAD_LEFT) { if (p <= 0) { if (ev.getRepeatCount() == 0) host.left(); } else focusAt(r, p - 1); }
        else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) focusAt(r, p + 1);
        else {
            RowV to = step(r, k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (to != null) vertical(t, to);
        }
    }

    private void focused(Tile t) {
        RowV r = rowOf(t);
        if (r == null || t.item == null) return;
        if (lk.manus.iptv.Config.DEBUG) android.util.Log.i("IPTV", "home focus row " + r.kind + " tile " + r.items.indexOf(t.item) + "/" + r.items.size());
        boolean newRow = r != focusRow;
        focus = t.item;
        focusRow = r;
        facts.setText(factsOf(r, focus));
        if (newRow) { // bring the row's title to the top of the panel
            final int y = r.box.getTop();
            if (Ui.anim()) sv.smoothScrollTo(0, y); else sv.scrollTo(0, y);
        }
        long now = SystemClock.uptimeMillis();
        fast = now - lastNav < 160; // key-repeat scrolling: no lookups and no logo loads until it settles
        LogoLoader.quiet = fast;
        lastNav = now;
        h.removeCallbacks(calmR);
        h.postDelayed(calmR, 200);
    }

    private final Runnable calmR = new Runnable() {
        @Override public void run() {
            fast = false;
            LogoLoader.quiet = false;
            if (LogoLoader.deferred) { LogoLoader.deferred = false; for (RowV r : rows) if (!r.films()) r.ad.notifyItemRangeChanged(0, r.items.size()); }
            for (RowV r : rows) {
                if (!r.films() && r.kind != SERIES) continue;
                for (int i = 0; i < r.rv.getChildCount(); i++) {
                    int p = r.rv.getChildAdapterPosition(r.rv.getChildAt(i));
                    if (p >= 0 && p < r.items.size() && r.items.get(p) instanceof Entry) ask(r, (Entry) r.items.get(p));
                    else if (p >= 0 && p < r.items.size() && r.items.get(p) instanceof SeriesNow.Entry) askSeries(r, (SeriesNow.Entry) r.items.get(p));
                }
            }
        }
    };

    private void ask(final RowV r, final Entry e) {
        if (fast) return;
        Films.ask(getContext(), e, new Runnable() {
            @Override public void run() {
                int i = r.items.indexOf(e);
                if (i < 0) return;
                if (r.kind == SOON && e.unmatched()) { drop(r, i); return; } // not a film after all: leaves the row
                r.ad.notifyItemChanged(i); // swaps in place; nothing else moves
                if (e == focus) facts.setText(factsOf(r, e));
            }
        });
    }

    /** A late TMDB answer swaps the tile in place (poster, rating); the row is never re-sorted and focus stays put (T102). */
    private void askSeries(final RowV r, final SeriesNow.Entry e) {
        if (fast) return;
        SeriesNow.ask(getContext(), e, new Runnable() {
            @Override public void run() {
                int i = r.items.indexOf(e);
                if (i < 0) return;
                r.ad.notifyItemChanged(i);
                if (e == focus) facts.setText(factsOf(r, e));
            }
        });
    }

    /** A "Starting soon" tile turned out not to be a film. */
    private void drop(final RowV r, final int i) {
        boolean wasFocus = r.items.get(i) == focus;
        r.items.remove(i);
        r.ad.notifyItemRemoved(i);
        if (r.items.isEmpty()) r.box.setVisibility(GONE);
        if (!wasFocus) return;
        focus = null; focusRow = null;
        final RowV to = r.items.isEmpty() ? (step(r, -1) != null ? step(r, -1) : step(r, 1)) : r;
        final int at = to == r ? Math.min(i, r.items.size() - 1) : 0;
        if (to != null) r.rv.post(new Runnable() { @Override public void run() { focusAt(to, at); } });
    }

    // ---- tiles ----

    private final class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final RowV row;
        RowAdapter(RowV r) { row = r; }

        @Override public int getItemViewType(int p) {
            if (row.kind == CONTINUE || row.kind == MYLIST || row.kind == SERIES) return T_POSTER;
            if (row.kind == MOVIES) return row.items.get(p) == MORE ? T_MORE : T_POSTER;
            return row.kind == SOON ? T_POSTER_CAP : T_LOGO;
        }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            final Tile t = new Tile(parent.getContext(), type == T_LOGO ? Tile.LOGO : type == T_MORE ? Tile.TEXT : Tile.POSTER, type == T_POSTER_CAP || type == T_LOGO);
            int ring = Ui.u(4), w = (type == T_LOGO ? lw : pw) + ring;
            int hh = (type == T_LOGO ? lh : ph) + ring + (type == T_POSTER_CAP || type == T_LOGO ? Tile.captionH() : 0);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, hh);
            lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
            t.setLayoutParams(lp);
            t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean f) { if (f) focused(t); }
            });
            t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (row.kind == CONTINUE && t.item instanceof lk.manus.iptv.data.Library.ContinueItem) {
                        host.removeContinue((lk.manus.iptv.data.Library.ContinueItem) t.item);
                        return true;
                    }
                    return false;
                }
            });
            t.setOnKeyListener(new View.OnKeyListener() { // RecyclerView has no D-pad moves of its own and the activity swallows unhandled arrows: move here
                @Override public boolean onKey(View v, int k, KeyEvent ev) {
                    if (k == KeyEvent.KEYCODE_MENU || k == 256 || k == 257) {
                        if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                            if (row.kind == CONTINUE && t.item instanceof lk.manus.iptv.data.Library.ContinueItem) {
                                host.removeContinue((lk.manus.iptv.data.Library.ContinueItem) t.item);
                                return true;
                            }
                        }
                    }
                    if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT && k != KeyEvent.KEYCODE_DPAD_UP && k != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                    if (ev.getAction() == KeyEvent.ACTION_DOWN) keyOn(t, k, ev);
                    return true;
                }
            });
            return new RecyclerView.ViewHolder(t) { };
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder vh, int p) {
            Tile t = (Tile) vh.itemView;
            Object o = row.items.get(p);
            t.item = o;
            t.badge(null); t.rating(null); t.more(0); // tiles are shared between rows
            if (o instanceof SeriesNow.Entry) {
                SeriesNow.Entry se = (SeriesNow.Entry) o;
                t.progress(0f);
                t.caption("");
                t.poster(se.title(), se.posterPath(), pw);
                t.badge(se.seLabel());
                t.rating(se.matched() ? Films.rating(se.film) : "");
                t.more(se.groupSize());
                askSeries(row, se);
            } else if (row.kind == CONTINUE && o instanceof lk.manus.iptv.data.Library.ContinueItem) {
                lk.manus.iptv.data.Library.ContinueItem ci = (lk.manus.iptv.data.Library.ContinueItem) o;
                t.poster(ci.item.title, ci.item.poster, pw);
                t.progress(ci.progress());
                t.caption("");
            } else if (row.kind == MYLIST && o instanceof lk.manus.iptv.data.Library.Item) {
                lk.manus.iptv.data.Library.Item it = (lk.manus.iptv.data.Library.Item) o;
                t.poster(it.title, it.poster, pw);
                t.progress(0f);
                t.caption("");
            } else {
                t.progress(0f);
                if (o == MORE) { t.text("All films"); return; }
                if (o instanceof Channel) {
                    Channel ch = (Channel) o;
                    t.logo(ch);
                    lk.manus.iptv.data.CloudData.Slot n = ch.id.isEmpty() ? null : lk.manus.iptv.data.CloudData.now(ch.id, System.currentTimeMillis());
                    t.caption(n == null ? "" : n.title);
                } else {
                    Entry e = (Entry) o;
                    t.poster(e.clean.title, e.matched() ? e.film.posterPath : "", pw);
                    t.more(row.kind == SOON ? 0 : e.groupSize());
                    t.caption(row.kind == SOON ? lk.manus.iptv.data.CloudData.hm(e.slot.start) + " · " + e.ch.name : "");
                    ask(row, e);
                }
            }
            if (o == focus) facts.setText(factsOf(row, o));
        }

        @Override public int getItemCount() { return row.items.size(); }
    }
}
