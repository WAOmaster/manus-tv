package lk.manus.iptv.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import lk.manus.iptv.data.Channel;
import lk.manus.iptv.ui.SeriesNow.Entry;
import lk.manus.iptv.data.Index;
import lk.manus.iptv.data.Store;

/** "Series on now" (T110c): the same poster grid as Movies on now in the place of the channel list and card. One line of facts under it for the focused poster. */
public final class SeriesGrid extends LinearLayout {
    public interface Host {
        void open(Entry e);                         // OK: tune this channel and close the guide
        void left();                                // Left from the first column: back to the rail
    }
    private static final int COLS = 5;

    private final Host host;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final TextView title, sub, facts;
    private final RecyclerView rv;
    private final Adapter ad = new Adapter();
    private final List<Entry> list = new ArrayList<>();
    private Store store;
    private Entry focus;
    private int gen, tileW, tileH, gap;
    private boolean fast;
    private long lastNav;

    public SeriesGrid(Context c, Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
        setBackground(Ui.card(0.94f, 18)); // the panel: scrim behind the whole grid and the facts line
        setPadding(Ui.u(14), Ui.u(14), Ui.u(14), Ui.u(10));
        title = Ui.tv(c, Ui.TITLE, Ui.PAPER, true);
        sub = Ui.tv(c, Ui.CAPTION, Ui.MUTED, false);
        title.setPadding(Ui.u(4), Ui.u(2), 0, 0);
        sub.setPadding(Ui.u(4), Ui.u(3), 0, Ui.u(6));
        title.setText("Series on now");
        addView(title);
        addView(sub);
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
        facts = Ui.tv(c, Ui.BODY, Ui.PAPER, false);
        facts.setPadding(Ui.u(4), Ui.u(8), Ui.u(12), Ui.u(4));
        addView(facts);
        setVisibility(GONE);
    }

    /** Width of the whole area (px): the tile size follows. */
    public void relayout(int widthPx) {
        gap = Ui.u(10);
        tileW = (widthPx - Ui.u(28) - Ui.u(12) * 2 - gap * COLS) / COLS - Ui.u(4);
        tileH = tileW * 3 / 2;
        rv.setPadding(Ui.u(12), Ui.u(4), Ui.u(12), Ui.u(4));
        ad.notifyDataSetChanged();
    }

    /** Builds the entries (cached answers first), then shows them. */
    public void load(final Context c, final Index ix, final Store st) {
        store = st;
        final List<Entry> es = SeriesNow.candidates(c, ix, st, ix.all);
        final int g = ++gen;
        if (list.isEmpty()) sub.setText("Finding series on now…"); // T102: quiet, in place; says what it waits for
        new Thread(new Runnable() {
            @Override public void run() {
                SeriesNow.loadCached(c, es); // cache only, no network
                final List<Entry> sh = SeriesNow.group(SeriesNow.shown(es), st); // T110e: one tile per show
                h.post(new Runnable() { @Override public void run() { if (g == gen) show(sh); } });
            }
        }, "series").start();
    }

    private void show(List<Entry> es) {
        SeriesNow.sort(es, store); // the order is fixed here and never changes while the grid is on screen
        list.clear();
        list.addAll(es);
        sub.setText(list.size() + " series");
        final boolean had = hasFocus() && focus != null; // T102: late data must not pull focus off the tile the viewer moved to
        final String keepUrl = had ? focus.ch.url : null;
        focus = null;
        facts.setText("");
        ad.notifyDataSetChanged();
        int p = 0; // the order is fixed now; focus starts on the first tile
        if (keepUrl != null) for (int i = 0; i < list.size(); i++) if (list.get(i).ch.url.equals(keepUrl)) { p = i; break; }
        if (hasFocus() || wantFocus) focusAt(p);
        else rv.scrollToPosition(p);
    }

    private boolean wantFocus;

    public int count() { return list.size(); }

    public Entry focused() { return focus; }

    /** Focus a tile (the remembered one, else the first). Safe before the list has loaded: it waits for it. */
    public void focusTile() {
        if (list.isEmpty()) { wantFocus = true; return; }
        int at = 0;
        if (focus != null) at = Math.max(0, list.indexOf(focus));
        focusAt(at);
    }

    private void focusAt(final int p) {
        wantFocus = false;
        if (p < 0 || p >= list.size()) return;
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(p);
        if (vh != null) { vh.itemView.requestFocus(); return; }
        rv.scrollToPosition(p);
        rv.post(new Runnable() {
            @Override public void run() {
                RecyclerView.ViewHolder v = rv.findViewHolderForAdapterPosition(p);
                if (v != null) v.itemView.requestFocus();
            }
        });
    }

    public void hide() { gen++; wantFocus = false; setVisibility(GONE); }

    /** The minute turned over: minutes left change. */
    public void tick() { if (focus != null) facts.setText(SeriesNow.facts(focus)); }

    @Override public boolean hasFocus() { return rv.hasFocus(); }

    private void navigated() {
        long now = SystemClock.uptimeMillis();
        fast = now - lastNav < 160; // key-repeat scrolling: no lookups until it settles
        lastNav = now;
        h.removeCallbacks(calmR);
        h.postDelayed(calmR, 200);
    }

    private final Runnable calmR = new Runnable() {
        @Override public void run() {
            fast = false;
            for (int i = 0; i < rv.getChildCount(); i++) {
                RecyclerView.ViewHolder vh = rv.getChildViewHolder(rv.getChildAt(i));
                int p = vh.getAdapterPosition();
                if (p >= 0 && p < list.size()) ask(list.get(p));
            }
        }
    };

    private void ask(final Entry e) {
        if (fast) return;
        SeriesNow.ask(getContext(), e, new Runnable() {
            @Override public void run() {
                int i = list.indexOf(e);
                if (i < 0) return;
                ad.notifyItemChanged(i); // swaps in place; nothing moves while the grid is open
                if (e == focus) facts.setText(SeriesNow.facts(e));
            }
        });
    }

    // ---- tiles ----

    private Tile newTile(Context c) {
        final Tile t = new Tile(c, Tile.POSTER, false);
        t.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) {
                Entry e = (Entry) t.item;
                if (!f || e == null) return;
                focus = e;
                facts.setText(SeriesNow.facts(e));
                navigated();
            }
        });
        t.setOnKeyListener(new OnKeyListener() { // RecyclerView has no D-pad moves of its own and the activity swallows unhandled arrows: move here
            @Override public boolean onKey(View v, int k, KeyEvent ev) {
                if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT && k != KeyEvent.KEYCODE_DPAD_UP && k != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                Entry e = (Entry) t.item;
                if (ev.getAction() != KeyEvent.ACTION_DOWN || e == null) return true;
                int p = list.indexOf(e), n = list.size(), col = p % COLS;
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) { if (col == 0) { if (ev.getRepeatCount() == 0) host.left(); } else focusAt(p - 1); }
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { if (col < COLS - 1 && p + 1 < n) focusAt(p + 1); }
                else if (k == KeyEvent.KEYCODE_DPAD_UP) { if (p - COLS >= 0) focusAt(p - COLS); }
                else if (p + COLS < n) focusAt(p + COLS);
                else if (p / COLS < (n - 1) / COLS) focusAt(n - 1); // a shorter last row: its last tile
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
            return new RecyclerView.ViewHolder(tile) { };
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder vh, int p) {
            Tile t = (Tile) vh.itemView;
            Entry en = list.get(p);
            t.item = en;
            t.poster(en.title(), en.posterPath(), tileW);
            t.badge(en.seLabel());
            t.rating(en.matched() ? Films.rating(en.film) : "");
            t.more(en.groupSize());
            if (en == focus) facts.setText(SeriesNow.facts(en));
            ask(en);
        }
        @Override public int getItemCount() { return list.size(); }
    }
}
