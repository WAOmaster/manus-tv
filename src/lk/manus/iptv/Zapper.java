package lk.manus.iptv;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.util.List;
import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.ZapLogic;

/** Channel changes: zap, number entry, previous channel, guide hand-over and favourites. */
final class Zapper {
    final PlayState ps;

    Zapper(PlayState ps) { this.ps = ps; }

    private static final String TAG = "IPTV";
    private static final int DEBOUNCE_MS = 400;
    private static final int UNDO_MS = 6000;

    private String undoName;
    private boolean undoAdded;
    private long undoAt;

    private final Runnable numR = new Runnable() { @Override public void run() { commitNum(); } };

    Channel cur() { return ps.previewCh != null ? ps.previewCh : ps.list.get(ps.index); }

    void zap(int d) {
        if (ps.list == null || ps.list.isEmpty()) return;
        zapTo((ps.index + d + ps.list.size()) % ps.list.size(), d);
    }

    private void zapTo(int ni, int d) {
        if (!ps.playing && ps.h.hasCallbacks(ps.fov.watchdog)) Log.i(TAG, "start abandoned by a zap: not recorded as a failure");
        ps.tsh.clearPause();
        ps.fov.hideFail();
        ps.h.removeCallbacks(ps.ui.connR);
        ps.connBox.setVisibility(View.GONE);
        ps.dir = d;
        ps.index = ni;
        ps.fails = 0;
        ps.gaveUp = false;
        ps.gen++; // ignore callbacks of the channel we are leaving
        ps.h.removeCallbacks(ps.fov.skipR);
        ps.h.removeCallbacks(ps.fov.altR);
        ps.h.removeCallbacks(ps.fov.watchdog);
        ps.h.removeCallbacks(ps.fov.reconnectR);
        ps.reconnecting = false;
        ps.ui.hidePill();
        ps.ui.clearToast();
        ps.ui.updateBanner();
        ps.ui.setState(lk.manus.iptv.data.LoadText.tuning());
        ps.ui.showBanner();
        ps.h.removeCallbacks(ps.host.startR);
        ps.h.postDelayed(ps.host.startR, DEBOUNCE_MS);
    }

    void numKey(int d) {
        if (ps.list == null || ps.num.length() >= ZapLogic.MAX_DIGITS) return;
        ps.num.append(d);
        ps.numTv.setText(ps.num.toString());
        ps.numTv.setVisibility(View.VISIBLE);
        ps.ui.wmUpdate();
        ps.h.removeCallbacks(numR);
        ps.h.postDelayed(numR, 1500);
    }

    void cancelNum() {
        ps.h.removeCallbacks(numR);
        ps.num.setLength(0);
        ps.numTv.setVisibility(View.GONE);
        ps.ui.wmUpdate();
    }

    void commitNum() {
        String s = ps.num.toString();
        cancelNum();
        if (ps.list == null || ps.list.isEmpty()) return;
        int r = ZapLogic.resolve(s, ps.list.size(), ps.index);
        switch (r) {
            case ZapLogic.EMPTY: break;
            case ZapLogic.PREV: prevChannel(); break;
            case ZapLogic.NONE: ps.ui.showToast("No channel " + s, 2000); break;
            case ZapLogic.SAME: ps.ui.showBanner(); break;
            default: zapTo(r, 1);
        }
    }

    /** The channel watched before this one (from the saved recents), or null. */
    Channel previous() {
        if (ps.list == null || ps.idx == null) return null;
        return ZapLogic.previous(ps.store.recents(), ps.list.get(ps.index).url, ps.idx.byUrl);
    }

    /** Toggle to the channel watched before this one (recents[1]); switches list if it is not in the current one. */
    void prevChannel() {
        if (ps.list == null || ps.idx == null) return;
        Channel c = previous();
        if (c == null) { ps.ui.showToast("No previous channel", 1800); return; }
        int pos = ps.list.indexOf(c);
        if (pos >= 0) { zapTo(pos, 1); return; }
        String sec = !c.country.isEmpty() && ps.idx.countries.containsKey(c.country) ? "cc:" + c.country : "all";
        List<Channel> l = ps.idx.sorted(sec, ps.store);
        pos = l.indexOf(c);
        if (pos < 0) { sec = "all"; l = ps.idx.sorted(sec, ps.store); pos = l.indexOf(c); }
        if (pos < 0) { ps.ui.showToast("No previous channel", 1800); return; }
        commit(sec, ps.idx.title(sec), l, pos);
    }

    void openSearch() {
        if (ps.list == null) return;
        if (ps.idx == null) { ps.ui.showToast("Loading channels…", 1500); return; }
        prepGuide();
        ps.guide.openSearch();
        ps.ui.wmUpdate();
    }

    boolean guideWhenReady;

    /** Called once the channel list is in: opens the guide for a key pressed while it loaded. */
    void openPendingGuide() { if (guideWhenReady) { guideWhenReady = false; openGuide(); } }

    void openGuide() {
        if (ps.list == null) { // nothing to play yet (no sources): OK opens Settings so a playlist can be added
            if (ps.idx != null && ps.idx.all.isEmpty()) { prepGuide(); ps.guide.openSettings(); ps.ui.wmUpdate(); }
            else if (ps.idx == null) guideWhenReady = true; // T102: channels still loading: the key is kept, the guide opens when they arrive
            return;
        }
        if (ps.idx == null) { guideWhenReady = true; return; }
        prepGuide();
        ps.ui.hidePill(); // a status pill from the picture underneath must not ride over the guide
        ps.guide.open();
        ps.ui.wmUpdate();
        if (Config.DEBUG && ps.bgSkipSec > 0) { ps.h.removeCallbacks(ps.fov.bgSkipR); ps.h.postDelayed(ps.fov.bgSkipR, ps.bgSkipSec * 1000L); ps.bgSkipSec = 0; }
    }

    void prepGuide() { // every way into the guide (Back / Left, Categories, Settings, Home shortcuts) passes here
        if (ps.gaveUp) ps.ui.clearToast(); // the "isn't playing" message has no timeout: it stayed over every guide page (seen on the emulator)
        ps.h.removeCallbacks(ps.ui.hideBanner);
        ps.banner.setVisibility(View.GONE);
        ps.ui.stopBar();
        ps.previewCh = null;
    }

    void commit(String id, String name, List<Channel> l, int pos) {
        Channel chosen = l.get(pos);
        boolean same = ps.playing && cur().url.equals(chosen.url);
        ps.zapSection = id;
        ps.zapName = name;
        if (!id.equals("search")) ps.store.setSection(id); // a result list can't be rebuilt after a restart
        ps.list = l;
        ps.index = pos;
        ps.previewCh = null;
        ps.tsh.clearPause();
        ps.fails = 0;
        ps.gaveUp = false;
        Log.i(TAG, "commit " + id + " #" + (pos + 1) + " " + chosen.name + " (" + l.size() + " in section)");
        ps.ui.updateBanner();
        if (same) {
            ps.prefs.edit().putString("last", chosen.url).apply();
            ps.store.pushRecent(chosen.url);
        } else {
            ps.gen++;
            ps.h.removeCallbacks(ps.fov.skipR);
            ps.h.removeCallbacks(ps.fov.altR);
            ps.h.removeCallbacks(ps.fov.watchdog);
            ps.h.removeCallbacks(ps.fov.reconnectR);
            ps.reconnecting = false;
            ps.ui.setState(lk.manus.iptv.data.LoadText.tuning());
            ps.h.removeCallbacks(ps.host.startR);
            ps.h.post(ps.host.startR);
        }
        ps.ui.showBanner();
    }

    void closeGuide(boolean restore) {
        if (restore && ps.previewCh != null) {
            Log.i(TAG, "restore -> " + ps.list.get(ps.index).name);
            ps.previewCh = null;
            ps.h.removeCallbacks(ps.host.startR);
            ps.gen++;
            ps.host.play(ps.list.get(ps.index));
        }
        ps.previewCh = null;
        if (ps.list == null) return;
        if (ps.gaveUp) ps.ui.showToast(Failover.NOT_PLAYING, 0); // back on the channel that failed: say so again (a new choice resets gaveUp)
        ps.ui.wmUpdate();
        ps.subsCtl.showCue();
        if (ps.eng != null && ps.eng.smart()) ps.tsh.tsUpdate();
    }

    void toggleFav() {
        if (ps.idx == null) return;
        if (undoFav()) return;                         // within 6 s of a change the same key undoes it, whichever row is selected
        if (ps.guide.isOpen() && ps.guide.onLongOk()) return; // long OK on a country/category row = hide it
        if (ps.guide.isOpen() && ps.guide.inFavourites()) { favMenu(ps.guide.selected()); return; } // long OK in Favourites = reorder menu
        toggleFavOf(ps.guide.isOpen() ? ps.guide.selected() : cur());
    }

    private void favMenu(final Channel c) {
        ps.menus.openMenu(c.name, java.util.Arrays.asList("Move up", "Move down", "Move to top", "Remove"), 0, new Chooser.MenuPick() {
            @Override public void pick(int i) { if (i == 3) toggleFavOf(c); else ps.guide.moveFav(c, i); }
        });
    }

    private static String shortName(String n) { return n.length() > 24 ? n.substring(0, 23) + "…" : n; }

    /** T33: the pill after an add / remove says "hold OK to undo"; the same key again within 6 s puts the list back (a second stray press can never remove a second favourite). */
    boolean undoFav() {
        if (ps.undoUrl == null || SystemClock.elapsedRealtime() - undoAt > UNDO_MS) { ps.undoUrl = null; return false; }
        boolean now = ps.store.toggleFav(ps.undoUrl, undoName);
        Log.i(TAG, "favourite undo " + (now ? "re-added " : "removed ") + undoName);
        ps.ui.showToast(undoAdded ? "Undone: " + shortName(undoName) + " is not a favourite" : "Undone: " + shortName(undoName) + " is a favourite again", 2500);
        ps.undoUrl = null;
        if (ps.guide.isOpen()) ps.guide.onFavChanged();
        return true;
    }

    void toggleFavOf(Channel c) {
        if (ps.idx == null || c == null) return;
        boolean now = ps.store.toggleFav(c.url, c.name);
        Log.i(TAG, "favourite " + (now ? "added " : "removed ") + c.name);
        ps.undoUrl = c.url; undoName = c.name; undoAdded = now; undoAt = SystemClock.elapsedRealtime();
        ps.ui.showToast((now ? "★  Added " : "Removed ") + shortName(c.name) + "  ·  hold OK to undo", UNDO_MS);
        if (ps.guide.isOpen()) ps.guide.onFavChanged();
    }
}
