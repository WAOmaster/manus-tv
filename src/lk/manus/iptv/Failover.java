package lk.manus.iptv;

import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Dedup;
import lk.manus.iptv.data.FailPlan;
import lk.manus.iptv.ui.Ui;
import lk.manus.iptv.data.Redact;

/** Failed starts and dropped streams: watchdog, reconnect backoff, alternate versions, auto-skip and the failure card. */
final class Failover {
    final PlayState ps;

    Failover(PlayState ps) { this.ps = ps; }

    private static final String TAG = "IPTV";
    private static final String NET = "IPTV-net";
    private static final int MAX_FAILS = 5;
    static final String TRYING = "This channel isn't playing. Trying another link…";
    static final String NOT_PLAYING = "This channel isn't playing right now.";

    private int altI;
    private final List<TextView> failBtns = new ArrayList<>();
    private final List<String> failActs = new ArrayList<>();
    private int failSel;
    private Channel pendingAlt;

    final Runnable bgSkipR = new Runnable() {
        @Override public void run() {
            Log.i(TAG, "DEBUG bgskip: failing the playing stream while the guide is open (guide open=" + ps.guide.isOpen() + ")");
            ps.playing = false;
            fail("error", "simbgskip");
        }
    };

    final Runnable reconnectR = new Runnable() {
        @Override public void run() {
            if (ps.rc >= 2) ps.curVar = -1; // the chosen variant may be the problem: go back to the master
            if (ps.curVar == -1) ps.curUrl = null;
            ps.host.play(ps.playCh != null ? ps.playCh : ps.zapper.cur(), ps.curUrl, true);
        }
    };

    final Runnable altR = new Runnable() {
        @Override public void run() {
            Channel a = pendingAlt;
            pendingAlt = null;
            if (a == null) return;
            ps.failover = true;
            ps.host.play(a);
            ps.failover = false;
            ps.ui.setPill(TRYING); // play() hides the pill of a normal start
        }
    };

    final Runnable skipR = new Runnable() {
        @Override public void run() {
            ps.index = (ps.index + ps.dir + ps.list.size()) % ps.list.size();
            ps.ui.updateBanner();
            ps.host.play(ps.list.get(ps.index));
        }
    };

    final Runnable watchdog = new Runnable() {
        @Override public void run() { fail("timeout", ""); }
    };

    void fail(String result, String detail) {
        ps.h.removeCallbacks(watchdog);
        ps.h.removeCallbacks(ps.ui.connR);
        ps.connBox.setVisibility(View.GONE);
        ps.h.removeCallbacks(ps.host.showBufR);
        ps.h.removeCallbacks(ps.host.longBufR);
        ps.gen++;
        ps.host.releasePlayer();
        ps.tsh.tsReset();
        final boolean was = ps.playing;
        ps.playing = false;
        ps.buffering = false;
        Log.i(TAG, "fail " + result + " " + detail);
        if (ps.spike > 0) { ps.host.spikeRecord(result, detail); return; }
        ps.prober.setPaused(false);
        boolean sim = Config.DEBUG && detail.startsWith("sim"); // DEBUG-simulated failures never touch the saved health
        if (!was && !ps.reconnecting && ps.playCh != null && !sim) { // never started (a drop after playing is not the stream's fault)
            ps.health.record(ps.playCh.url, false);
            ps.health.flushAsync();
            if (ps.idx != null) ps.idx.markDirty();
        }
        if (ps.previewCh != null) { // preview never auto-skips or reconnects
            ps.ui.hidePill();
            ps.guide.setPreviewFailed();
            return;
        }
        if (was || ps.reconnecting) { reconnect(); return; } // it was working: stay on this channel
        ps.ui.hidePill();
        Channel rep = ps.list.get(ps.index); // same channel, other streams (duplicates folded into this row): try them before skipping
        List<Channel> alts = Dedup.alternates(rep);
        if (ps.altFor != rep) { ps.altFor = rep; altI = 0; }
        if (FailPlan.tryAlternate(altI, alts.size())) { // other links of this channel come first, in either mode
            Channel a = alts.get(altI++);
            Log.i(TAG, "trying alternate " + altI + "/" + alts.size() + " " + a.name + " " + Redact.host(a.url));
            pendingAlt = a;
            ps.ui.setPill(TRYING);
            ps.h.postDelayed(altR, 500); // the failed player is still being released: wait before reusing the surface
            return;
        }
        ps.altFor = null;
        if (!autoSkip()) { // every link failed: say so and stay until the viewer zaps (OK tries again)
            ps.gaveUp = true;
            ps.ui.setState(""); // no "Loading…" under the message
            ps.ui.showToast(NOT_PLAYING, 0);
            return;
        }
        if (++ps.fails >= MAX_FAILS) {
            ps.gaveUp = true;
            ps.ui.showToast("Several channels failed — check your internet. Press OK to retry.", 0);
            return;
        }
        ps.ui.showToast("This channel isn't playing. Trying the next one…", 1500);
        ps.h.postDelayed(skipR, 1500);
    }

    /** Backoff 1, 2, 4, 8 s on the same channel, then give up without skipping. */
    private void reconnect() {
        if (ps.rc >= 4) {
            ps.reconnecting = false;
            ps.gaveUp = true;
            ps.ui.hidePill();
            Log.i(NET, "reconnect gave up");
            ps.ui.showToast(NOT_PLAYING, 0);
            return;
        }
        long d = 1000L << ps.rc++;
        ps.reconnecting = true;
        ps.reconnectsTotal++;
        Log.i(NET, "reconnect try " + ps.rc + "/4 in " + d + " ms");
        ps.ui.setPill("Reconnecting…");
        ps.h.removeCallbacks(reconnectR);
        ps.h.postDelayed(reconnectR, d);
    }

    boolean autoSkip() { return ps.dbgAutoSkip != null ? ps.dbgAutoSkip : ps.store.autoSkip(); }

    private void showFail(final Channel rep, List<Channel> alts) {
        ps.h.removeCallbacks(ps.ui.hideBanner); // one thing at a time: no "Loading…" banner or pill under the card
        ps.banner.animate().cancel();
        ps.banner.setVisibility(View.GONE);
        ps.ui.setState("");
        ps.ui.clearToast();
        ps.ui.hidePill();
        failActs.clear();
        failActs.add("Retry");
        if (altI < alts.size()) failActs.add("Try another version");
        failActs.add("Next channel");
        failActs.add("Back to guide");
        ps.failRow.removeAllViews();
        failBtns.clear();
        for (String a : failActs) {
            TextView b = Ui.tv(ps.act, Ui.BODY, Ui.PAPER, true);
            b.setText(a);
            b.setGravity(Gravity.CENTER);
            b.setPadding(Ui.u(18), Ui.u(12), Ui.u(18), Ui.u(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = failBtns.isEmpty() ? 0 : Ui.u(8);
            ps.failRow.addView(b, lp);
            failBtns.add(b);
        }
        ps.failSub.setText(rep.name);
        failSel = 0;
        paintFail();
        ps.failUp = true;
        ps.gaveUp = true; // nothing restarts by itself while the card is up
        ps.modal.show(ps.failBox);
        Log.i(TAG, "failure card: " + failActs);
    }

    private void paintFail() {
        for (int i = 0; i < failBtns.size(); i++) failBtns.get(i).setBackground(i == failSel ? Ui.ring() : Ui.rect(Ui.alpha(Ui.PANEL, 0.6f), 14));
    }

    void hideFail() {
        ps.failUp = false;
        ps.modal.hide(ps.failBox);
    }

    void failKey(int k, int repeat) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_LEFT: failSel = Math.max(0, failSel - 1); paintFail(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_RIGHT: failSel = Math.min(failBtns.size() - 1, failSel + 1); paintFail(); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: if (repeat == 0) failDo(failActs.get(failSel)); break;
            case KeyEvent.KEYCODE_BACK: if (repeat == 0) failDo("Back to guide"); break;
            default: break;
        }
    }

    private void failDo(String act) {
        final Channel rep = ps.list.get(ps.index);
        hideFail();
        ps.gaveUp = false;
        if (act.equals("Retry")) {
            Log.i(TAG, "failure card: retry");
            ps.fails = 0;
            ps.failover = true; // keep the version cursor
            ps.host.play(ps.playCh != null ? ps.playCh : rep);
            ps.failover = false;
        } else if (act.equals("Try another version")) {
            List<Channel> alts = Dedup.alternates(rep);
            if (ps.altFor != rep) { ps.altFor = rep; altI = 0; }
            if (altI >= alts.size()) { ps.zapper.zap(1); return; }
            Channel a = alts.get(altI++);
            Log.i(TAG, "failure card: version " + altI + "/" + alts.size() + " " + Chooser.feedOf(a) + " " + Redact.host(a.url));
            ps.failover = true;
            ps.host.play(a);
            ps.failover = false;
        } else if (act.equals("Next channel")) {
            ps.zapper.zap(1);
        } else {
            ps.zapper.openGuide();
        }
    }
}
