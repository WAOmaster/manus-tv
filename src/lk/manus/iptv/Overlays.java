package lk.manus.iptv;

import android.animation.ValueAnimator;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.CloudData;
import lk.manus.iptv.data.Index;
import lk.manus.iptv.data.Store;
import lk.manus.iptv.meta.MetaStore;
import lk.manus.iptv.meta.PosterLoader;
import lk.manus.iptv.ui.Films;
import lk.manus.iptv.ui.Guide;
import lk.manus.iptv.ui.NowBar;
import lk.manus.iptv.ui.Modal;
import lk.manus.iptv.ui.Ring;
import lk.manus.iptv.ui.LoadingLine;
import lk.manus.iptv.ui.LogoLoader;
import lk.manus.iptv.ui.Ui;
import lk.manus.iptv.data.Sources;
import lk.manus.iptv.player.Vol;

/** All the on-screen pieces of the player: banner, pill, watermark, connecting ring, number box and the layers that hold them. */
final class Overlays {
    final PlayState ps;

    Overlays(PlayState ps) { this.ps = ps; }

    private static final int TIMEOUT_MS = 15000;

    private LinearLayout bottomStack;
    private int pillKind;
    private TextView bNum;
    private TextView bName;
    private TextView bInfo;
    private TextView bState;
    private TextView bSec;
    private TextView bClock;
    private TextView toast;
    private ImageView bLogo;
    private View bBar;
    private ValueAnimator barAnim;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.US);
    private TextView bNow;
    private TextView bNext;
    private TextView bVol;
    private NowBar bNowBar;
    private TextView bFilm;      // "2014 · ★ 6.7 · Sci-fi, Action" under the programme title
    private ImageView bPoster;   // film poster in the channel logo's place (same column, never taller than the card content)
    private CloudData.Slot filmSlot;
    private CloudData.Slot bannerSlot; // the programme whose film line the banner shows (null = none)
    private MetaStore.Film bannerFilm;
    private Ring connRing;
    private LinearLayout wm;
    private ImageView wmLogo;
    private TextView wmTitle;
    private String nowTitle;

    final Runnable connR = new Runnable() {
        @Override public void run() {
            if (ps.playing || ps.fov.autoSkip() || ps.previewCh != null || ps.guide.isOpen() || ps.userPaused || ps.failUp) return;
            ps.connBox.setVisibility(View.VISIBLE);
            ringTick.run();
        }
    };

    private final Runnable ringTick = new Runnable() {
        @Override public void run() {
            if (ps.connBox.getVisibility() != View.VISIBLE) return;
            connRing.set((SystemClock.elapsedRealtime() - ps.t0) / (float) TIMEOUT_MS);
            ps.h.postDelayed(this, 100);
        }
    };

    private final Runnable wmHideR = new Runnable() {
        @Override public void run() {
            if (!Ui.anim()) { wm.setVisibility(View.GONE); return; }
            wm.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() { @Override public void run() { wm.setVisibility(View.GONE); } }).start();
        }
    };

    final Runnable hideBanner = new Runnable() {
        @Override public void run() {
            final Runnable end = new Runnable() { @Override public void run() { ps.banner.setVisibility(View.GONE); ps.bHint.setVisibility(View.GONE); bVol.setVisibility(View.GONE); ps.infoUp = false; stopBar(); wmUpdate(); } };
            if (!Ui.anim()) { end.run(); return; }
            ps.banner.animate().alpha(0f).translationY(Ui.u(24)).setDuration(220).setInterpolator(Ui.DEC).withEndAction(end).start();
        }
    };

    private final Runnable hideToast = new Runnable() {
        @Override public void run() { if (pillKind == 2) { toast.setVisibility(View.GONE); pillKind = 0; } }
    };

    void buildUi() {
        FrameLayout root = new FrameLayout(ps.act);
        root.setBackgroundColor(Ui.INK);
        ps.sv = new SurfaceView(ps.act);
        ps.sv.getHolder().addCallback(ps.host);
        root.addView(ps.sv, new FrameLayout.LayoutParams(-1, -1));
        ps.tex = new TextureView(ps.act);
        ps.tex.setVisibility(View.GONE);
        lk.manus.iptv.ui.Ui.blurVideo(ps.tex, 30);
        ps.tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
                ps.texSurface = new Surface(st);
                ps.host.tryPending();
                ps.host.ensurePlaying();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (ps.useTex) ps.host.stopAll();
                if (ps.texSurface != null) ps.texSurface.release();
                ps.texSurface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        root.addView(ps.tex, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { ps.host.fitTex(); }
        });

        ps.message = Ui.tv(ps.act, Ui.TITLE, Ui.PAPER, false);
        ps.message.setSingleLine(false);
        ps.message.setGravity(Gravity.CENTER);
        root.addView(ps.message, new FrameLayout.LayoutParams(-1, -1));

        // T105b: cold-start screen = splash look (logo + name + a LoadingLine naming each start-up step); removed by PlayerActivity.hideBoot() once channels or an error arrive
        FrameLayout boot = new FrameLayout(ps.act);
        boot.setBackgroundColor(Ui.INK);
        ImageView bootLogo = new ImageView(ps.act);
        bootLogo.setImageResource(R.mipmap.ic_launcher_foreground);
        int bs = (int) (288 * ps.act.getResources().getDisplayMetrics().density); // same size + place as the system splash icon
        boot.addView(bootLogo, new FrameLayout.LayoutParams(bs, bs, Gravity.CENTER));
        LinearLayout bt = new LinearLayout(ps.act);
        bt.setOrientation(LinearLayout.VERTICAL);
        bt.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView bn = Ui.tv(ps.act, Ui.TITLE, Ui.PAPER, true);
        bn.setText("MANUS TV");
        bt.addView(bn);
        LoadingLine bw = new LoadingLine(ps.act, Ui.u(220)); // names each step (reading / downloading / sorting), Signal hairline sweeping under it
        bw.set(lk.manus.iptv.data.LoadText.reading(1));
        LinearLayout.LayoutParams bwp = new LinearLayout.LayoutParams(-2, -2);
        bwp.topMargin = Ui.u(10);
        bt.addView(bw, bwp);
        ps.bootLine = bw;
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        tp.topMargin = bs / 2 + Ui.u(40); // just under the logo
        boot.addView(bt, tp);
        ps.boot = boot;
        root.addView(boot, new FrameLayout.LayoutParams(-1, -1));

        ps.guide = new Guide(ps.act, new Guide.Host() {
            @Override public Index index() { return ps.idx; }
            @Override public Store store() { return ps.store; }
            @Override public Sources sources() { return ps.sources; }
            @Override public void sourcesChanged() { ps.act.load(); }
            @Override public String playingUrl() { return ps.list == null ? "" : ps.zapper.cur().url; }
            @Override public String activeSection() { return ps.zapSection; }
            @Override public int mainDropped() { return ps.eng == null ? 0 : ps.eng.droppedFrames(); }
            @Override public void onCommit(String id, String name, List<Channel> l, int pos) { ps.zapper.commit(id, name, l, pos); }
            @Override public void onClose(boolean restore) { ps.zapper.closeGuide(restore); }
            @Override public String netSpeed() { return ps.host.speedText(); }
            @Override public void exitApp() { ps.act.finish(); }
            @Override public void onOptions(Channel c) { ps.menus.openOptionsFor(c, false); }
            @Override public void pill(String text) { showToastNow(text, 2500); }
            @Override public void restoreFavourites(List<String> urls) { ps.store.setFavs(urls); ps.guide.onFavChanged(); ps.undoUrl = null; }
            @Override public void edgesChanged() { applySafe(); }
            @Override public void remindersChanged() { ps.remind.arm(); }
            @Override public void restored() { ps.undoUrl = null; ps.remind.arm(); applySafe(); ps.act.load(); } // playlists are in the restored list: reload them (Sources was already re-read)
            @Override public void filmChanged() { refreshNow(); }
            @Override public void playFilm(lk.manus.iptv.data.Library.Item item, long startPosMs) { ps.filmPlayer.play(item, startPosMs); }
            @Override public void openMenu(String title, java.util.List<String> labels, int sel, final lk.manus.iptv.ui.Guide.Host.Pick pick) {
                ps.menus.openMenu(title, labels, sel, new Chooser.MenuPick() {
                    @Override public void pick(int i) {
                        if (pick != null) pick.pick(i);
                    }
                });
            }
        });
        root.addView(ps.guide, new FrameLayout.LayoutParams(-1, -1));

        // banner: glass bar, number | logo | name + chips | clock, loading line underneath
        LinearLayout bl = new LinearLayout(ps.act);
        bl.setOrientation(LinearLayout.VERTICAL);
        bl.setBackground(Ui.card(0.94f, 18));
        LinearLayout row = new LinearLayout(ps.act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.u(24), Ui.u(14), Ui.u(28), Ui.u(12));
        bNum = Ui.tv(ps.act, Ui.DISPLAY, Ui.PAPER, true);
        bNum.setMinWidth(Ui.u(64));
        bLogo = new ImageView(ps.act);
        bLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bLogo.setBackground(Ui.rect(Ui.PANEL, 10));
        bLogo.setPadding(Ui.u(4), Ui.u(4), Ui.u(4), Ui.u(4));
        LinearLayout mid = new LinearLayout(ps.act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(Ui.u(18), 0, Ui.u(12), 0);
        bName = Ui.tv(ps.act, Ui.TITLE, Ui.PAPER, true);
        LinearLayout meta = new LinearLayout(ps.act);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(0, Ui.u(8), 0, 0);
        bSec = Ui.tv(ps.act, Ui.MICRO, Ui.MUTED, true);
        bSec.setBackground(Ui.chip());
        bSec.setPadding(Ui.u(10), Ui.u(4), Ui.u(10), Ui.u(4));
        bInfo = Ui.tv(ps.act, Ui.CAPTION, Ui.MUTED, false);
        bInfo.setPadding(Ui.u(12), 0, 0, 0);
        bState = Ui.tv(ps.act, Ui.CAPTION, Ui.MUTED, true);
        bState.setPadding(Ui.u(12), 0, 0, 0);
        meta.addView(bSec);
        meta.addView(bInfo);
        meta.addView(bState);
        ps.bSpeed = Ui.tv(ps.act, Ui.CAPTION, Ui.MUTED, false);
        ps.bSpeed.setPadding(Ui.u(16), 0, 0, 0);
        meta.addView(ps.bSpeed);
        ps.bCC = Ui.tv(ps.act, Ui.MICRO, Ui.MUTED, true);
        ps.bCC.setText("CC");
        ps.bCC.setBackground(Ui.chip());
        ps.bCC.setPadding(Ui.u(10), Ui.u(4), Ui.u(10), Ui.u(4));
        ps.bCC.setVisibility(View.GONE);
        LinearLayout.LayoutParams ccp = new LinearLayout.LayoutParams(-2, -2);
        ccp.leftMargin = Ui.u(14);
        meta.addView(ps.bCC, ccp);
        mid.addView(bName);
        mid.addView(meta);
        bNow = Ui.tv(ps.act, Ui.BODY, Ui.PAPER, true);
        bNow.setPadding(0, Ui.u(8), 0, 0);
        bNowBar = new NowBar(ps.act);
        LinearLayout.LayoutParams nbp = new LinearLayout.LayoutParams(-1, Ui.u(3));
        nbp.topMargin = Ui.u(6);
        nbp.rightMargin = Ui.u(40);
        bNext = Ui.tv(ps.act, Ui.CAPTION, Ui.MUTED, false);
        bNext.setPadding(0, Ui.u(6), 0, 0);
        bFilm = Ui.tv(ps.act, Ui.CAPTION, Ui.MUTED, false);
        bFilm.setPadding(0, Ui.u(4), 0, 0);
        bFilm.setVisibility(View.GONE);
        mid.addView(bNow);
        mid.addView(bFilm);
        mid.addView(bNowBar, nbp);
        mid.addView(bNext);
        bNow.setVisibility(View.GONE); bNowBar.setVisibility(View.GONE); bNext.setVisibility(View.GONE);
        ps.bHint = Ui.tv(ps.act, Ui.CAPTION, Ui.MUTED, false);
        ps.bHint.setPadding(0, Ui.u(6), 0, 0);
        ps.bHint.setVisibility(View.GONE);
        mid.addView(ps.bHint);
        bVol = Ui.tv(ps.act, Ui.CAPTION, Ui.PAPER, false);
        bVol.setPadding(0, Ui.u(6), 0, 0);
        bVol.setVisibility(View.GONE);
        mid.addView(bVol);
        bClock = Ui.tv(ps.act, Ui.TITLE, Ui.PAPER, false);
        row.addView(bNum);
        bPoster = new ImageView(ps.act);
        bPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bPoster.setBackground(Ui.rect(Ui.PANEL, 8));
        bPoster.setVisibility(View.GONE);
        row.addView(bLogo, new LinearLayout.LayoutParams(Ui.u(96), Ui.u(54)));
        row.addView(bPoster, new LinearLayout.LayoutParams(Ui.u(96), Ui.u(132)));
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(bClock);
        bl.addView(row);
        FrameLayout line = new FrameLayout(ps.act);
        line.setClipChildren(true);
        bBar = new View(ps.act);
        bBar.setBackgroundColor(Ui.SIGNAL);
        bBar.setVisibility(View.INVISIBLE);
        line.addView(bBar, new FrameLayout.LayoutParams(Ui.u(220), Ui.u(3)));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, Ui.u(3));
        ll.setMargins(Ui.u(18), 0, Ui.u(18), 0);
        bl.addView(line, ll);
        ps.banner = bl;
        ps.banner.setVisibility(View.GONE);

        // subtitle line: shares the bottom column with the banner, so it always sits above it (UX-SPEC 4)
        ps.subTv = Ui.tv(ps.act, Ui.BODY, Ui.PAPER, false);
        ps.subTv.setSingleLine(false);
        ps.subTv.setEllipsize(null); // never "…": a long cue shrinks instead (Subs.showCue + SubFit)
        ps.subTv.setMaxLines(Integer.MAX_VALUE);
        ps.subTv.setLineSpacing(0, 1.08f);
        ps.subTv.setShadowLayer(Ui.u(3), 0, Ui.u(1), 0xCC000000); // readable on bright scenes as well as through the box
        ps.subTv.setGravity(Gravity.CENTER);
        ps.subTv.setBackground(Ui.rect(Ui.alpha(Ui.INK, 0.6f), 10));
        ps.subTv.setPadding(Ui.u(16), Ui.u(6), Ui.u(16), Ui.u(6));
        ps.subTv.setVisibility(View.GONE);
        bottomStack = new LinearLayout(ps.act);
        bottomStack.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        sp.bottomMargin = Ui.u(12);
        bottomStack.addView(ps.subTv, sp);
        bottomStack.addView(ps.banner, new LinearLayout.LayoutParams(-1, -2));
        root.addView(bottomStack, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // the one pill: top-centre (status, messages, undo); a new one replaces the old
        toast = Ui.tv(ps.act, Ui.BODY, Ui.PAPER, true);
        toast.setBackground(Ui.card(0.95f, 14));
        toast.setPadding(Ui.u(28), Ui.u(12), Ui.u(28), Ui.u(12));
        toast.setSingleLine(false);
        toast.setMaxLines(2); // the T40 download message is long
        toast.setGravity(Gravity.CENTER);
        toast.setVisibility(View.GONE);
        root.addView(toast, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        // number box: top-right, alone there
        ps.numTv = Ui.tv(ps.act, Ui.DISPLAY, Ui.PAPER, true);
        ps.numTv.setBackground(Ui.card(0.95f, 14));
        ps.numTv.setPadding(Ui.u(28), Ui.u(10), Ui.u(28), Ui.u(10));
        ps.numTv.setVisibility(View.GONE);
        root.addView(ps.numTv, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));

        // T27: connecting ring: non-modal, centre (no dim; keys still zap)
        ps.connBox = new LinearLayout(ps.act);
        ps.connBox.setOrientation(LinearLayout.VERTICAL);
        ps.connBox.setGravity(Gravity.CENTER_HORIZONTAL);
        ps.connBox.setBackground(Ui.card(0.95f, 14));
        ps.connBox.setPadding(Ui.u(36), Ui.u(24), Ui.u(36), Ui.u(22));
        connRing = new Ring(ps.act);
        ps.connBox.addView(connRing, new LinearLayout.LayoutParams(Ui.u(64), Ui.u(64)));
        TextView ct = Ui.tv(ps.act, Ui.BODY, Ui.PAPER, true);
        ct.setText("Connecting…");
        ct.setEllipsize(null); ps.connBox.setMinimumWidth(Ui.u(230)); // the 64dp ring alone made the box too narrow: "Conn…"
        ct.setPadding(0, Ui.u(14), 0, 0);
        ps.connBox.addView(ct);
        ps.connBox.setVisibility(View.GONE);
        root.addView(ps.connBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // corner watermark: logo + one line, 60% opacity
        wm = new LinearLayout(ps.act);
        wm.setGravity(Gravity.CENTER_VERTICAL);
        wmLogo = new ImageView(ps.act);
        wmLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        wm.addView(wmLogo, new LinearLayout.LayoutParams(Ui.u(64), Ui.u(40)));
        wmTitle = Ui.tv(ps.act, Ui.CAPTION, Ui.PAPER, false);
        wmTitle.setShadowLayer(4, 0, 1, Ui.INK);
        wmTitle.setPadding(Ui.u(10), 0, 0, 0);
        wm.addView(wmTitle);
        wm.setVisibility(View.GONE);
        root.addView(wm, new FrameLayout.LayoutParams(-2, -2));

        // T30 seek chip: top-left, alone there
        ps.tsBox = new LinearLayout(ps.act);
        ps.tsBox.setOrientation(LinearLayout.VERTICAL);
        ps.tsBox.setBackground(Ui.card(0.95f, 14));
        ps.tsBox.setPadding(Ui.u(20), Ui.u(12), Ui.u(20), Ui.u(14));
        ps.tsChip = Ui.tv(ps.act, Ui.BODY, Ui.PAPER, true);
        ps.tsBox.addView(ps.tsChip);
        ps.tsTrack = new FrameLayout(ps.act);
        ps.tsTrack.setBackground(Ui.rect(Ui.LINE, 3));
        ps.tsFill = new View(ps.act);
        ps.tsFill.setBackgroundColor(Ui.SIGNAL);
        ps.tsTrack.addView(ps.tsFill, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams tkp = new LinearLayout.LayoutParams(Ui.u(360), Ui.u(6));
        tkp.topMargin = Ui.u(10);
        ps.tsBox.addView(ps.tsTrack, tkp);
        ps.tsBox.setVisibility(View.GONE);
        root.addView(ps.tsBox, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        // modal layer (top): options / version / subtitle chooser and the failure card, one at a time, dimmed 60%, centred, <= 50% of the safe width
        ps.modal = new Modal(ps.act);
        ps.failBox = new LinearLayout(ps.act);
        ps.failBox.setOrientation(LinearLayout.VERTICAL);
        ps.failBox.setGravity(Gravity.CENTER_HORIZONTAL);
        ps.failBox.setBackground(Ui.card(0.95f, 14));
        ps.failBox.setPadding(Ui.u(36), Ui.u(28), Ui.u(36), Ui.u(28));
        TextView ft = Ui.tv(ps.act, Ui.TITLE, Ui.WARN, true);
        ft.setText("Channel isn't responding");
        ps.failBox.addView(ft);
        ps.failSub = Ui.tv(ps.act, Ui.CAPTION, Ui.MUTED, false);
        ps.failSub.setPadding(0, Ui.u(6), 0, Ui.u(22));
        ps.failBox.addView(ps.failSub);
        ps.failRow = new LinearLayout(ps.act);
        ps.failRow.setOrientation(LinearLayout.VERTICAL);
        ps.failBox.addView(ps.failRow, new LinearLayout.LayoutParams(-1, -2));
        ps.failBox.setVisibility(View.GONE);
        ps.modal.add(ps.failBox, 0);
        ps.menuBox = new LinearLayout(ps.act);
        ps.menuBox.setOrientation(LinearLayout.VERTICAL);
        ps.menuBox.setBackground(Ui.card(0.95f, 14));
        ps.menuBox.setPadding(Ui.u(16), Ui.u(14), Ui.u(16), Ui.u(14));
        ps.menuTitle = Ui.tv(ps.act, Ui.CAPTION, Ui.MUTED, true);
        ps.menuTitle.setPadding(Ui.u(8), 0, 0, Ui.u(8));
        ps.menuBox.addView(ps.menuTitle);
        ps.menuBox.setVisibility(View.GONE);
        ps.modal.add(ps.menuBox, 0);
        root.addView(ps.modal, new FrameLayout.LayoutParams(-1, -1));
        ps.filmPlayer.buildUi(root);
        applySafe();
        ps.act.setContentView(root);
    }

    /** Places everything that hugs an edge inside the title-safe area (also re-run when Settings > Screen edges changes). */
    private void applySafe() {
        int mx = Ui.marginX(), my = Ui.marginY(), top = my + Ui.u(8);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bottomStack.getLayoutParams();
        lp.setMargins(mx, 0, mx, my);
        bottomStack.setLayoutParams(lp);
        ps.subTv.setMaxWidth(Ui.safeW() * 86 / 100); // 60% wrapped two-speaker cues into 4+ lines and the last one was cut
        toast.setMaxWidth(Ui.safeW() * 6 / 10);
        lp = (FrameLayout.LayoutParams) toast.getLayoutParams(); lp.setMargins(0, top, 0, 0); toast.setLayoutParams(lp);
        lp = (FrameLayout.LayoutParams) ps.numTv.getLayoutParams(); lp.setMargins(0, top, mx, 0); ps.numTv.setLayoutParams(lp);
        lp = (FrameLayout.LayoutParams) ps.tsBox.getLayoutParams(); lp.setMargins(mx, top, 0, 0); ps.tsBox.setLayoutParams(lp);
        wmTitle.setMaxWidth(Ui.safeW() * 2 / 10);
        ps.modal.relayout();
        ps.filmPlayer.relayout();
        wm.setVisibility(View.GONE);
    }

    /** Status pill (Buffering, Reconnecting...): the one pill, replaces whatever shows. */
    void setPill(String s) {
        if (isGuideOpen()) return; // T98: the guide / library / settings / search own the screen: no status text over them
        if (ps.remind.offering() || ps.sleep.warning()) return; // a film reminder keeps its 20 s; the sleep warning its minute
        ps.h.removeCallbacks(hideToast);
        toast.animate().cancel();
        toast.setText(s);
        toast.setAlpha(1f);
        toast.setVisibility(View.VISIBLE);
        pillKind = 1;
    }

    /** Ends a status pill only; a message pill keeps its own timer. */
    /** Moves the subtitle line (and the bottom stack it lives in) up by px, e.g. above the film's playback bar; 0 = back to its place. */
    void liftSubs(int px) {
        float y = -Math.max(0, px);
        if (bottomStack.getTranslationY() == y) return;
        bottomStack.animate().cancel();
        if (Ui.anim()) bottomStack.animate().translationY(y).setDuration(160).setInterpolator(Ui.DEC).start(); else bottomStack.setTranslationY(y);
    }

    void hidePill() { if (pillKind == 1) { toast.setVisibility(View.GONE); pillKind = 0; } }

    void clearToast() { if (ps.remind.offering() || ps.sleep.warning()) return; ps.h.removeCallbacks(hideToast); if (pillKind == 2) { toast.setVisibility(View.GONE); pillKind = 0; } }

    void updateBanner() {
        Channel c = ps.list.get(ps.index);
        bNum.setText(String.valueOf(ps.index + 1));
        bName.setText(c.name);
        bSec.setText(ps.zapName);
        StringBuilder s = new StringBuilder();
        if (!c.quality.isEmpty()) s.append(c.quality);
        String f = Ui.flag(c.country);
        if (!f.isEmpty()) s.append(s.length() > 0 ? "   " : "").append(f).append(' ').append(Ui.country(c.country));
        bInfo.setText(s);
        LogoLoader.get(ps.act).load(c.logo, c.name, bLogo);
        refreshNow();
    }

    /** Now/next for the tuned channel: banner, watermark title. Re-runs on the minute and at programme changes (UI thread). */
    void refreshNow() {
        ps.h.removeCallbacks(nowR);
        if (ps.list == null || ps.list.isEmpty()) return;
        Channel c = ps.list.get(ps.index);
        long t = System.currentTimeMillis();
        CloudData.Slot n = CloudData.now(c.id, t), x = CloudData.next(c.id, t);
        setNowTitle(n == null ? null : n.title);
        filmLookup(n);
        bNow.setVisibility(n == null ? View.GONE : View.VISIBLE);
        bNowBar.setVisibility(n == null ? View.GONE : View.VISIBLE);
        bNext.setVisibility(x == null ? View.GONE : View.VISIBLE);
        if (n != null) { bNow.setText("Now: " + n.title + "  ·  " + CloudData.hm(n.start) + "–" + CloudData.hm(n.stop)); bNowBar.set(n.progress(t)); }
        if (x != null) bNext.setText("Next: " + x.title + "  " + CloudData.hm(x.start));
        if (wm.getVisibility() == View.VISIBLE) wmTitle.setText(nowTitle != null ? nowTitle : c.name);
        long d = 60000 - t % 60000 + 200;
        if (n != null) d = Math.min(d, Math.max(1000, n.stop * 1000 - t + 500));
        ps.h.postDelayed(nowR, d);
    }

    private final Runnable nowR = new Runnable() { @Override public void run() { refreshNow(); } };

    /** Film info for a film-length programme with a key: from this run's answers at once, else asked 0.5 s after the banner settles (never per key press). */
    private void filmLookup(CloudData.Slot n) {
        ps.h.removeCallbacks(filmR);
        filmSlot = null;
        bannerSlot = n;
        if (n == null || (n.stop - n.start) / 60 < Films.MIN_FILM_MINUTES || !Films.enabled(ps.act)) { showFilm(null); return; }
        MetaStore.Film k = Films.known(n);
        if (k != null) { showFilm(k); return; }
        filmSlot = n;
        ps.h.postDelayed(filmR, 500);
    }

    private final Runnable filmR = new Runnable() {
        @Override public void run() {
            final CloudData.Slot s = filmSlot;
            if (s == null || ps.banner.getVisibility() != View.VISIBLE) return;
            Films.resolve(ps.act, s, new java.util.function.Consumer<MetaStore.Film>() {
                @Override public void accept(MetaStore.Film f) { if (s == filmSlot) showFilm(f); }
            });
        }
    };

    private void showFilm(MetaStore.Film f) {
        boolean on = f != null && f.isMatched();
        bFilm.setVisibility(on ? View.VISIBLE : View.GONE);
        bannerFilm = on ? f : null;
        boolean pic = on && !f.posterPath.isEmpty();
        bPoster.setVisibility(pic ? View.VISIBLE : View.GONE);
        bLogo.setVisibility(pic ? View.GONE : View.VISIBLE); // the poster takes the logo's column, so nothing else moves
        if (!on) return;
        bFilm.setText(Films.bannerLine(f));
        bPoster.setImageDrawable(null);
        if (!f.posterPath.isEmpty()) PosterLoader.get(ps.act).load(f.posterPath, bPoster, Ui.u(84));
    }

    /** INFO again while the banner shows a film line: Details for that film (the guide's page, over the live picture; Back returns to live). False = no film line, INFO stays as it was. */
    boolean openBannerDetails() {
        if (ps.banner.getVisibility() != View.VISIBLE || bFilm.getVisibility() != View.VISIBLE || bannerFilm == null || bannerSlot == null || ps.list == null) return false;
        Films.Entry e = Films.entry(ps.list.get(ps.index), bannerSlot, bannerFilm);
        if (e == null) return false;
        ps.zapper.prepGuide();
        ps.infoUp = false;
        ps.guide.openDetails(e);
        wmUpdate();
        return true;
    }

    void setState(String s) {
        bState.setText(s);
        bState.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        if (s.isEmpty()) stopBar(); else if (ps.banner.getVisibility() == View.VISIBLE) startBar();
    }

    private void startBar() {
        bBar.setVisibility(View.VISIBLE);
        if (barAnim != null && barAnim.isRunning()) return;
        final int w = Math.max(ps.banner.getWidth(), Ui.u(900)), bw = Ui.u(220);
        barAnim = ValueAnimator.ofFloat(-bw, w);
        barAnim.setDuration(1100);
        barAnim.setRepeatCount(ValueAnimator.INFINITE);
        barAnim.setInterpolator(Ui.DEC);
        barAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) { bBar.setTranslationX((Float) a.getAnimatedValue()); }
        });
        barAnim.start();
    }

    void stopBar() {
        if (barAnim != null) barAnim.cancel();
        bBar.setVisibility(View.INVISIBLE);
    }

    void showBanner() {
        if (ps.list == null || ps.guide.isOpen()) return;
        ps.h.removeCallbacks(hideBanner);
        ps.banner.animate().cancel();
        bClock.setText(clock.format(new Date()));
        ps.bSpeed.setText(ps.host.speedText());
        if (ps.banner.getVisibility() != View.VISIBLE || ps.banner.getAlpha() < 1f) {
            if (!Ui.anim()) { ps.banner.setAlpha(1f); ps.banner.setTranslationY(0); ps.banner.setVisibility(View.VISIBLE); }
            else {
                if (ps.banner.getVisibility() != View.VISIBLE) { ps.banner.setAlpha(0f); ps.banner.setTranslationY(Ui.u(28)); }
                ps.banner.setVisibility(View.VISIBLE);
                ps.banner.animate().alpha(1f).translationY(0).setDuration(200).setInterpolator(Ui.DEC).start();
            }
        }
        if (bState.getVisibility() == View.VISIBLE) startBar();
        if (ps.playing) ps.h.postDelayed(hideBanner, 3500); // while loading, stay visible
        wmUpdate();
    }

    /** Programme title for the watermark; null = show the channel name. */
    public void setNowTitle(String t) { nowTitle = t; }

    /** Shows / hides the watermark for the current state; hidden whenever the banner, guide or a chooser is up. */
    void wmUpdate() {
        String pos = ps.store.wmPos();
        boolean on = !pos.equals("off") && ps.playing && ps.previewCh == null && ps.list != null && !ps.guide.isOpen()
                && ps.banner.getVisibility() != View.VISIBLE && ps.modal.getVisibility() != View.VISIBLE
                && ps.numTv.getVisibility() != View.VISIBLE && ps.tsBox.getVisibility() != View.VISIBLE; // corners are never shared (UX-SPEC 4)
        if (!on) {
            ps.h.removeCallbacks(wmHideR);
            wm.animate().cancel();
            wm.setVisibility(View.GONE);
            return;
        }
        boolean always = ps.store.wmMode().equals("always");
        if (wm.getVisibility() == View.VISIBLE || (!always && !ps.wmArm)) return;
        ps.wmArm = false;
        Channel c = ps.list.get(ps.index);
        CloudData.Slot ns = CloudData.now(c.id, System.currentTimeMillis());
        setNowTitle(ns == null ? null : ns.title);
        LogoLoader.get(ps.act).load(c.logo, c.name, wmLogo);
        wmTitle.setText(nowTitle != null ? nowTitle : c.name);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) wm.getLayoutParams();
        lp.gravity = (pos.charAt(0) == 't' ? Gravity.TOP : Gravity.BOTTOM) | (pos.charAt(1) == 'l' ? Gravity.START : Gravity.END);
        boolean bottom = pos.charAt(0) == 'b';
        lp.setMargins(Ui.marginX(), Ui.marginY() + Ui.u(8), Ui.marginX(), Ui.marginY() + Ui.u(8) + (bottom ? Ui.u(96) : 0)); // bottom corners sit above the subtitle lines
        wm.setLayoutParams(lp);
        wm.animate().cancel();
        wm.setVisibility(View.VISIBLE);
        if (Ui.anim()) { wm.setAlpha(0f); wm.animate().alpha(0.6f).setDuration(200).start(); } else wm.setAlpha(0.6f);
        ps.h.removeCallbacks(wmHideR);
        if (!always) ps.h.postDelayed(wmHideR, 8000);
    }

    void showVol() {
        if (ps.playCh == null) return;
        bVol.setText("Channel volume  ◀ " + Vol.bar(ps.store.volume(ps.playCh.url)) + " ▶");
        bVol.setVisibility(View.VISIBLE);
    }

    boolean volKey(int delta) {
        if (ps.playCh == null || ps.eng == null || ps.banner.getVisibility() != View.VISIBLE) { ps.infoUp = false; return false; }
        float v = Vol.step(ps.store.volume(ps.playCh.url), delta);
        ps.store.setVolume(ps.playCh.url, v);
        ps.eng.setVolume(v);
        showVol();
        ps.h.removeCallbacks(hideBanner);
        ps.h.postDelayed(hideBanner, 6000);
        return true;
    }

    /** True while this message pill is the one on screen. */
    boolean toastShows(String s) { return pillKind == 2 && toast.getVisibility() == View.VISIBLE && s.contentEquals(toast.getText()); }

    /** True while the guide overlay is open. */
    boolean isGuideOpen() { return ps.guide != null && ps.guide.isOpen(); }

    /** Message pill (top-centre, one line): replaces any pill on screen; ms = 0 stays until the next zap. */
    void showToast(String s, int ms) {
        if (isGuideOpen()) { queueToast(s, ms); return; } // T110b: the guide / Home / library / settings own the screen; wait for them to close
        showToastNow(s, ms);
    }

    private static final long QUEUE_STALE_MS = 15000;
    private String qText; private int qMs; private long qAt;
    private final Runnable flushQ = new Runnable() { @Override public void run() {
        if (qText == null) return;
        if (SystemClock.uptimeMillis() - qAt > QUEUE_STALE_MS) { qText = null; return; } // stale: the moment has passed
        if (isGuideOpen()) { ps.h.postDelayed(this, 400); return; }
        String t = qText; qText = null;
        showToastNow(t, qMs);
    } };
    /** Latest queued message wins; shown when the overlay closes, dropped after QUEUE_STALE_MS. */
    private void queueToast(String s, int ms) {
        boolean idle = qText == null;
        qText = s; qMs = ms; qAt = SystemClock.uptimeMillis();
        if (Config.DEBUG) android.util.Log.i("IPTV", "toast queued (overlay open): " + s);
        if (idle) { ps.h.removeCallbacks(flushQ); ps.h.postDelayed(flushQ, 400); }
    }

    /** Immediate show: guide's own pills, the film reminder and the sleep-timer warning (explicit user-set) bypass the overlay rule. */
    void showToastNow(String s, int ms) {
        if ((ps.remind.offering() || ps.sleep.warning()) && !toastShows(s)) return; // a film reminder keeps its 20 s; other messages wait their turn (they are dropped)
        ps.h.removeCallbacks(hideToast);
        toast.animate().cancel();
        toast.setText(s);
        pillKind = 2;
        toast.setVisibility(View.VISIBLE);
        if (Ui.anim()) { toast.setAlpha(0f); toast.animate().alpha(1f).setDuration(160).start(); } else toast.setAlpha(1f);
        if (ms > 0) ps.h.postDelayed(hideToast, ms);
    }
}
