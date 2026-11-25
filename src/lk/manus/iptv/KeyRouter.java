package lk.manus.iptv;

import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import lk.manus.iptv.data.Channel;
import lk.manus.iptv.ui.Keys;

/** Routes remote-control keys to the right part. The Activity forwards dispatchKeyEvent / onKeyDown here. */
final class KeyRouter {
    final PlayState ps;

    KeyRouter(PlayState ps) { this.ps = ps; }

    private static final String KEYTAG = "IPTV-key";

    private boolean okDown;

    /** OK needs press/long-press tracking, so keys are intercepted before the views see them. */
    public boolean dispatch(KeyEvent e) {
        if (ps.spike > 0 || ps.list == null) return ps.act.superDispatch(e);
        int k = e.getKeyCode(), a = e.getAction();
        boolean vol = k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE;
        if (vol) return ps.act.superDispatch(e);
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && ps.sleep.warning()) { // OK on the sleep warning keeps the TV on
            if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) ps.sleep.cancel();
            return true;
        }
        if (ps.filmPlayer != null && ps.filmPlayer.isActive()) {
            if (ps.filmPlayer.dispatch(e)) return true;
            return ps.act.superDispatch(e);
        }
        boolean first = a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        if (ps.guide.isOpen()) ps.guide.touch();
        if (ps.guide.modalUp()) return ps.guide.modalKey(e) || ps.act.superDispatch(e); // remote-keys modals own every key; volume stays with the system
        if (first && !ps.guide.rawKeys()) logKey(e);
        if (ps.failUp) { if (!vol && a == KeyEvent.ACTION_DOWN) ps.fov.failKey(k, e.getRepeatCount()); return !vol || ps.act.superDispatch(e); }
        if (ps.menuBox.getVisibility() == View.VISIBLE) {
            Log.i(KEYTAG, "menuBox visible, routing key " + k + " action " + a);
            if (a == KeyEvent.ACTION_DOWN) ps.menus.menuKey(k);
            return true;
        } // the chooser owns every key
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && !ps.guide.ownsOk() && ps.remind.offering()) { // OK on the reminder pill tunes the film's channel
            if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) { okDown = false; ps.remind.accept(); }
            return true;
        }
        String act = Keys.action(k);
        if (act != null && (!ps.guide.rawKeys() || act.equals(Keys.SEARCH))) { if (first) doAction(act); return true; }
        if (ps.guide.rawKeys() && k != KeyEvent.KEYCODE_BACK) return ps.act.superDispatch(e); // text boxes + settings handle their own keys
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            if (ps.num.length() > 0 && !ps.guide.isOpen()) { if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) ps.zapper.commitNum(); return true; }
            if (a == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) okDown = true;
                else if (okDown) { okDown = false; ps.zapper.toggleFav(); }
            } else if (okDown) { okDown = false; shortOk(); }
            return true;
        }
        if (ps.num.length() > 0 && !ps.guide.isOpen() && k == KeyEvent.KEYCODE_BACK) { // Back only cancels the number being typed
            if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) ps.zapper.cancelNum();
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK && ps.guide.isOpen()) {
            if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) ps.guide.onBack();
            return true;
        }
        return ps.act.superDispatch(e);
    }

    /** Full screen: channel number. Guide open (M2): letter jump in A–Z lists and menus, row number elsewhere. */
    private void digit(int d, KeyEvent e) {
        if (e.getRepeatCount() != 0) return;
        if (ps.guide.isOpen()) ps.guide.digit(d); else ps.zapper.numKey(d);
    }

    public boolean keyDown(int k, KeyEvent e) {
        if (ps.spike > 0) return ps.act.superKeyDown(k, e);
        if (ps.filmPlayer != null && ps.filmPlayer.isActive()) return ps.filmPlayer.keyDown(k, e);
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) { digit(k - KeyEvent.KEYCODE_0, e); return true; }
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) { digit(k - KeyEvent.KEYCODE_NUMPAD_0, e); return true; }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: if (!ps.guide.isOpen()) ps.zapper.zap(1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (!ps.guide.isOpen()) ps.zapper.zap(-1); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: if (ps.guide.isOpen()) ps.guide.page(1); else ps.zapper.zap(1); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: if (ps.guide.isOpen()) ps.guide.page(-1); else ps.zapper.zap(-1); return true;
            case KeyEvent.KEYCODE_PAGE_UP: if (ps.guide.isOpen()) ps.guide.page(-1); else ps.zapper.zap(1); return true;
            case KeyEvent.KEYCODE_PAGE_DOWN: if (ps.guide.isOpen()) ps.guide.page(1); else ps.zapper.zap(-1); return true;
            case KeyEvent.KEYCODE_LAST_CHANNEL: if (!ps.guide.isOpen() && e.getRepeatCount() == 0) ps.zapper.prevChannel(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (ps.infoUp && !ps.guide.isOpen() && ps.ui.volKey(-1)) return true;
                if (e.getRepeatCount() == 0 && !(ps.tsh.tsBar() && ps.tsh.seekRel(-10000))) ps.zapper.openGuide(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (ps.infoUp && !ps.guide.isOpen() && ps.ui.volKey(1)) return true;
                if (!ps.guide.isOpen() && e.getRepeatCount() == 0 && !(ps.tsh.tsBar() && ps.eng != null && ps.eng.behindMs() >= 1500 && ps.tsh.seekRel(10000))) ps.menus.openOptions(); return true;
            case KeyEvent.KEYCODE_BACK: // never exits: full-screen Back opens the guide on the playing channel (exit = Settings > Exit)
                if (e.getRepeatCount() == 0 && !ps.guide.isOpen()) ps.zapper.openGuide();
                return true;
            default: return ps.act.superKeyDown(k, e);
        }
    }

    private void shortOk() {
        if (ps.guide.isOpen()) { ps.guide.activate(); return; }
        if (ps.tsPaused) { ps.tsh.resumeVideo(); return; }
        if (ps.tsh.tsBar() && ps.eng != null && ps.eng.behindMs() >= 1500) { ps.tsh.goLive(); return; }
        if (ps.gaveUp) {
            ps.gaveUp = false;
            ps.fails = 0;
            ps.ui.clearToast();
            ps.ui.updateBanner();
            ps.host.play(ps.zapper.cur());
        } else ps.zapper.openGuide();
    }

    private void logKey(KeyEvent e) {
        int k = e.getKeyCode();
        String d = Keys.describe(k);
        Log.i(KEYTAG, KeyEvent.keyCodeToString(k) + " scan=" + e.getScanCode() + (d == null ? " UNMAPPED" : " -> " + d));
    }

    private void doAction(String act) {
        if (ps.idx == null && !act.equals(Keys.PAUSE) && !act.equals(Keys.PLAY) && !act.equals(Keys.TOGGLE)) { if (act.equals(Keys.GUIDE) || act.equals(Keys.CATS)) ps.zapper.guideWhenReady = true; return; }
        switch (act) {
            case Keys.FAV: if (!ps.zapper.undoFav()) ps.zapper.toggleFavOf(ps.guide.isOpen() ? ps.guide.selected() : ps.zapper.cur()); break;
            case Keys.SEARCH: ps.zapper.openSearch(); break;
            case Keys.INFO:
                if (ps.guide.isOpen()) { ps.guide.onInfo(); break; }
                if (ps.ui.openBannerDetails()) break;
                ps.ui.updateBanner();
                ps.ui.showBanner();
                ps.infoUp = ps.playing && ps.previewCh == null;
                if (ps.infoUp) ps.ui.showVol();
                if (ps.playing) { ps.h.removeCallbacks(ps.ui.hideBanner); ps.h.postDelayed(ps.ui.hideBanner, 6000); }
                break;
            case Keys.GUIDE: if (ps.guide.isOpen()) ps.guide.dismiss(true); else ps.zapper.openGuide(); break;
            case Keys.CATS: ps.zapper.prepGuide(); ps.guide.openCategories(); ps.ui.wmUpdate(); break;
            case Keys.SETTINGS: ps.zapper.prepGuide(); ps.guide.openSettings(); ps.ui.wmUpdate(); break;
            case Keys.SUBS: ps.subsCtl.openSubMenu(); break;
            case Keys.OPTIONS:
                if (!ps.guide.isOpen()) ps.menus.openOptions();
                else if (!ps.guide.onLongOk()) { Channel c = ps.guide.selected(); if (c != null) ps.menus.openOptionsFor(c, false); }
                break;
            case Keys.TOGGLE: if (ps.userPaused || ps.tsPaused) ps.tsh.resumeVideo(); else ps.tsh.pauseVideo(); break;
            case Keys.FF: if (ps.guide.isOpen() || !ps.tsh.seekRel(10000)) { if (ps.guide.isOpen()) ps.guide.page(1); else ps.zapper.zap(1); } break;
            case Keys.RW: if (ps.guide.isOpen() || !ps.tsh.seekRel(-10000)) { if (ps.guide.isOpen()) ps.guide.page(-1); else ps.zapper.zap(-1); } break;
            case Keys.PAUSE: ps.tsh.pauseVideo(); break;
            case Keys.PLAY: ps.tsh.resumeVideo(); break;
            case Keys.NEXT: if (ps.guide.isOpen()) ps.guide.page(1); else ps.zapper.zap(1); break;
            case Keys.PREV: if (ps.guide.isOpen()) ps.guide.page(-1); else ps.zapper.zap(-1); break;
            default: break;
        }
    }
}
