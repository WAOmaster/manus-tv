package lk.manus.iptv;

import android.util.Log;
import android.view.KeyEvent;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import lk.manus.iptv.data.Catchup;
import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Db;
import lk.manus.iptv.data.LiveSource;
import lk.manus.iptv.data.Dedup;
import lk.manus.iptv.data.Health;
import lk.manus.iptv.ui.Ui;
import lk.manus.iptv.data.Redact;

/** The one options / version / subtitle / audio chooser pill. */
final class Chooser {
    final PlayState ps;

    Chooser(PlayState ps) { this.ps = ps; }

    private static final String TAG = "IPTV";

    private final List<TextView> menuRows = new ArrayList<>();
    private int menuSel;
    private MenuPick menuPick;

    interface MenuPick { void pick(int i); }

    private final Runnable menuHideR = new Runnable() { @Override public void run() { closeMenu(); } };

    void openMenu(String title, List<String> labels, int sel, MenuPick pick) {
        ps.menuBox.removeViews(1, ps.menuBox.getChildCount() - 1);
        menuRows.clear();
        ps.menuTitle.setText(title);
        for (String l : labels) {
            TextView t = Ui.tv(ps.act, Ui.BODY, Ui.PAPER, true);
            t.setText(l);
            t.setPadding(Ui.u(14), Ui.u(10), Ui.u(14), Ui.u(10));
            ps.menuBox.addView(t);
            menuRows.add(t);
        }
        menuPick = pick;
        menuSel = Math.max(0, Math.min(sel, labels.size() - 1));
        paintMenu();
        ps.modal.show(ps.menuBox);
        ps.ui.wmUpdate();
        ps.h.removeCallbacks(menuHideR);
        ps.h.postDelayed(menuHideR, 20000);
    }

    private void paintMenu() {
        for (int i = 0; i < menuRows.size(); i++) menuRows.get(i).setBackground(i == menuSel ? Ui.ring() : null);
    }

    void closeMenu() {
        ps.h.removeCallbacks(menuHideR);
        ps.modal.hide(ps.menuBox);
        menuPick = null;
        ps.ui.wmUpdate();
        ps.subsCtl.showCue();
    }

    void menuKey(int k) {
        android.util.Log.i("IPTV", "Chooser.menuKey: k=" + k + " sel=" + menuSel + " mpk=" + (menuPick != null));
        ps.h.removeCallbacks(menuHideR);
        ps.h.postDelayed(menuHideR, 20000);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: menuSel = (menuSel + menuRows.size() - 1) % menuRows.size(); paintMenu(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: menuSel = (menuSel + 1) % menuRows.size(); paintMenu(); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: {
                MenuPick mpk = menuPick;
                int i = menuSel;
                closeMenu();
                if (mpk != null) mpk.pick(i);
                break;
            }
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_CAPTIONS: closeMenu(); break;
            default: break;
        }
    }

    /** Right / Menu in full-screen. */
    void openOptions() {
        if (ps.list == null || ps.idx == null) return;
        openOptionsFor(ps.list.get(ps.index), true);
    }

    /** The options pill: version choice (when the channel has several), and in full-screen subtitles + previous channel; always favourite. */
    void openOptionsFor(final Channel c, boolean full) {
        if (ps.list == null || ps.idx == null) return;
        ps.h.removeCallbacks(ps.ui.hideBanner);
        final List<Channel> vs = Dedup.versions(c);
        final List<Runnable> acts = new ArrayList<>();
        List<String> l = new ArrayList<>();
        if (vs.size() > 1) { l.add("Choose version / region  ›  " + vs.size()); acts.add(new Runnable() { @Override public void run() { openVersionMenu(c, vs); } }); }
        if (full) {
            l.add("Subtitles  ›"); acts.add(new Runnable() { @Override public void run() { ps.subsCtl.openSubMenu(); } });
            if (ps.audios.size() > 1) {
                String cur = "";
                for (int i = 0; i < ps.audios.size(); i++) if (ps.audios.get(i).sel) cur = ps.subsCtl.trackName(ps.audios.get(i), i);
                l.add("Audio: " + (cur.isEmpty() ? "default" : cur) + "  ›"); acts.add(new Runnable() { @Override public void run() { ps.subsCtl.openAudioMenu(); } });
            }
            l.add(ps.sleep.label() + "  ›"); acts.add(new Runnable() { @Override public void run() { openSleepMenu(); } });
            if (ps.zapper.previous() != null) { l.add("Previous channel"); acts.add(new Runnable() { @Override public void run() { ps.zapper.prevChannel(); } }); }
        } else if (vs.size() <= 1) { l.add("Close"); acts.add(new Runnable() { @Override public void run() { } }); } // the first item is always harmless: Favourite is never the default focus
        if (c.archiveDays > 0 && !Config.PLAY) { l.add("Earlier programmes  ›"); acts.add(new Runnable() { @Override public void run() { openEarlier(c); } }); } // T95: Xtream catch-up
        l.add(ps.store.isFav(c.url) ? "Remove from favourites" : "Add to favourites");
        acts.add(new Runnable() { @Override public void run() { ps.zapper.toggleFavOf(c); } });
        openMenu("Options", l, 0, new MenuPick() { @Override public void pick(int i) { acts.get(i).run(); } });
    }

    private void openSleepMenu() {
        final int[] mins = {30, 60, 90, 0};
        int sel = 3;
        for (int i = 0; i < 3; i++) if (mins[i] == ps.sleep.minutes()) sel = i;
        openMenu(ps.sleep.label(), java.util.Arrays.asList("30 min", "60 min", "90 min", "Off"), sel, new MenuPick() { @Override public void pick(int i) {
            ps.sleep.set(mins[i]);
            ps.ui.showToast(ps.sleep.label(), 2000);
        } });
    }

    private static final int EARLIER_PAGE = 7;

    /** T95: fetches the channel's earlier programmes off the UI thread, then lists them (OK plays one in the film player). */
    private void openEarlier(final Channel c) {
        ps.ui.showToast("Loading…", 1500);
        new Thread(new Runnable() { @Override public void run() {
            final List<Catchup.Prog> ps0 = LiveSource.earlier(Db.get(), c.xtItem, c.archiveDays, System.currentTimeMillis() / 1000);
            ps.h.post(new Runnable() { @Override public void run() {
                if (ps0.isEmpty()) { ps.ui.showToast("No earlier programmes", 2500); return; }
                earlierPage(c, ps0, 0);
            } });
        } }, "catchup-list").start();
    }

    private void earlierPage(final Channel c, final List<Catchup.Prog> all, final int from) {
        final int to = Math.min(all.size(), from + EARLIER_PAGE);
        final java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault());
        List<String> l = new ArrayList<>();
        for (int i = from; i < to; i++) l.add(f.format(new java.util.Date(all.get(i).start * 1000)) + "  ·  " + all.get(i).title);
        if (to < all.size()) l.add("Older  ›");
        openMenu("Earlier programmes", l, 0, new MenuPick() { @Override public void pick(int i) {
            if (from + i < to) ps.filmPlayer.playCatchup(c, all.get(from + i));
            else earlierPage(c, all, to);
        } });
    }

    static String feedOf(Channel c) {
        int at = c.id.indexOf('@');
        return at >= 0 && at < c.id.length() - 1 ? c.id.substring(at + 1) : Redact.host(c.url);
    }

    private void openVersionMenu(final Channel rep, final List<Channel> vs) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < vs.size(); i++) {
            Channel v = vs.get(i);
            int st = ps.health.status(v.url);
            String f = Ui.flag(v.country);
            l.add((i == 0 ? "✓ " : "") + (f.isEmpty() ? "" : f + " ") + feedOf(v) + "  ·  " + (v.quality.isEmpty() ? "—" : v.quality)
                    + "  ·  " + (st == Health.ALIVE ? "alive" : st == Health.LIKELY ? "likely up" : st == Health.DEAD ? "down" : "untested"));
        }
        openMenu("Choose version / region", l, 0, new MenuPick() { @Override public void pick(int i) { chooseVersion(rep, vs.get(i)); } });
    }

    /** Remembers the choice for the whole group, re-ranks, and re-tunes if this is the channel being watched. */
    private void chooseVersion(Channel rep, Channel pick) {
        if (ps.idx == null) return;
        ps.store.chooseVersion(Dedup.choiceKeys(pick), pick.url); // also when it is already the row: pins it
        if (pick == rep) { ps.ui.showToast("Version saved", 1500); return; }
        Dedup.promote(rep, pick);
        ps.idx.rebuildNow();
        boolean cur = ps.list.get(ps.index) == rep && ps.previewCh == null;
        int at = ps.list.indexOf(rep);
        if (at >= 0) { ps.list = new ArrayList<>(ps.list); ps.list.set(at, pick); }
        Log.i(TAG, "version chosen for " + pick.name + ": " + feedOf(pick) + " " + Redact.host(pick.url));
        ps.guide.onVersionsChanged();
        if (!cur) { ps.ui.showToast("Version saved", 1500); return; }
        ps.altFor = null; ps.fails = 0; ps.gaveUp = false;
        ps.ui.updateBanner();
        ps.gen++;
        ps.h.removeCallbacks(ps.fov.skipR); ps.h.removeCallbacks(ps.fov.altR); ps.h.removeCallbacks(ps.fov.watchdog); ps.h.removeCallbacks(ps.fov.reconnectR);
        ps.reconnecting = false;
        ps.ui.setState("Loading…");
        ps.h.removeCallbacks(ps.host.startR);
        ps.h.post(ps.host.startR);
        ps.ui.showBanner();
    }
}
