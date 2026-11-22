package lk.manus.iptv.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import lk.manus.iptv.meta.MetaStore;
import lk.manus.iptv.ui.Films.Entry;

/** "Which film?": a title the matcher could not decide (two films carry it). Candidate posters with name and year, then "None of these". Over the dimmed live picture, like Details; Back is the guide's. */
public final class WhichFilm extends FrameLayout {
    public interface Host {
        void choose(Entry e, MetaStore.Choice c); // OK on a poster
        void none(Entry e);                       // OK on "None of these"
    }

    private final Host host;
    private final TextView listed;
    private final LinearLayout row;
    private final List<Tile> tiles = new ArrayList<>();
    private Entry entry;

    public WhichFilm(Context c, Host host) {
        super(c);
        this.host = host;
        setClickable(true);
        setBackgroundColor(Ui.alpha(Ui.INK, 0.8f));
        setVisibility(GONE);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        listed = Ui.tv(c, Ui.BODY, Ui.MUTED, false);
        listed.setGravity(Gravity.CENTER);
        TextView head = Ui.tv(c, Ui.DISPLAY, Ui.PAPER, true);
        head.setText("Which film?");
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, Ui.u(6), 0, Ui.u(20));
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        col.addView(listed, new LinearLayout.LayoutParams(-1, -2));
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));
        col.addView(row, new LinearLayout.LayoutParams(-2, -2));
        LayoutParams cp = new LayoutParams(-1, -2, Gravity.CENTER);
        cp.setMargins(Ui.marginX(), Ui.marginY(), Ui.marginX(), Ui.marginY());
        addView(col, cp);
    }

    public boolean isUp() { return getVisibility() == VISIBLE; }

    /** The guide title, "Which film?", one poster per candidate (name · year under it), then "None of these". Focus starts on the first poster. */
    public void show(final Entry e, List<MetaStore.Choice> choices) {
        entry = e;
        listed.setText(e.clean.title);
        row.removeAllViews();
        tiles.clear();
        int w = Ui.u(130), h = w * 3 / 2, ring = Ui.u(4);
        for (final MetaStore.Choice ch : choices) {
            Tile t = new Tile(getContext(), Tile.POSTER, true);
            t.poster(ch.title, ch.posterPath, w);
            t.caption(ch.year > 0 ? ch.title + " · " + ch.year : ch.title);
            t.caption.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); // a long name keeps its year
            t.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (entry != null) host.choose(entry, ch); } });
            add(t, w, h, ring);
        }
        Tile none = new Tile(getContext(), Tile.TEXT, true);
        none.text("None of these");
        none.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (entry != null) host.none(entry); } });
        add(none, w, h, ring);
        setVisibility(VISIBLE);
        tiles.get(0).requestFocus();
    }

    private void add(final Tile t, int w, int h, int ring) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w + ring, h + ring + Tile.captionH());
        lp.setMargins(Ui.u(7), 0, Ui.u(7), 0);
        t.setOnKeyListener(new OnKeyListener() { // keep focus on the tiles; nothing leaks to the guide
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN) {
                        int i = tiles.indexOf(t) + (k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                        if (i >= 0 && i < tiles.size()) tiles.get(i).requestFocus();
                    }
                    return true;
                }
                return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
            }
        });
        tiles.add(t);
        row.addView(t, lp);
    }

    public void hide() {
        setVisibility(GONE);
        row.removeAllViews();
        tiles.clear();
        entry = null;
    }
}
