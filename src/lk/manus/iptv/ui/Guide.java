package lk.manus.iptv.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.net.Uri;
import android.widget.EditText;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lk.manus.iptv.Config;
import lk.manus.iptv.R;
import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Db;
import lk.manus.iptv.data.Reminders;
import lk.manus.iptv.data.Curated;
import lk.manus.iptv.data.Dedup;
import lk.manus.iptv.data.CloudData;
import lk.manus.iptv.data.Health;
import lk.manus.iptv.data.Place;
import lk.manus.iptv.data.Prober;
import lk.manus.iptv.data.Index;
import lk.manus.iptv.data.Sources;
import lk.manus.iptv.data.Store;
import lk.manus.iptv.data.YouTubeLive;
import lk.manus.iptv.player.MiniPreview;

/** Immersive overlay guide: rail | channel list | preview card, over the playing video. */
public final class Guide extends FrameLayout {
    public interface Host {
        Index index();
        Store store();
        Sources sources();
        void sourcesChanged();
        String playingUrl();
        String activeSection();
        int mainDropped();       // video frames the full-screen player has dropped so far (the mini preview must not hurt it)
        void onCommit(String sectionId, String name, List<Channel> list, int pos);
        void onClose(boolean restore);
        String netSpeed();       // e.g. "4.2 Mbps ●●●", "" when unknown
        void exitApp();
        void onOptions(Channel c);   // Right / Menu on a channel row: the options pill (versions, favourite)
        void edgesChanged();         // Settings > Screen edges moved: re-apply the safe area
        void pill(String text);      // the one pill (top-centre), owned by the activity
        void restoreFavourites(List<String> urls); // Settings > Restore favourites
        void remindersChanged();     // a reminder was added or removed: the timer re-arms
        void restored();             // a backup was restored: the activity reloads playlists, re-arms reminders, re-applies the safe area
        void filmChanged();          // the film of the programme on screen changed (removed or chosen): the banner follows
        void playFilm(lk.manus.iptv.data.Library.Item item, long startPosMs);
        interface Pick { void pick(int i); }
        void openMenu(String title, List<String> labels, int sel, Pick pick);
    }

    private static final int CARD_MS = 700, LIVE_MS = 1200, RAIL_MS = 250, IDLE_MS = 45000;
    private static final String TAG = "IPTV";

    private static final class Rail {
        final String id, label; int count, icon; boolean nav, divider, header, pin; // nav = opens a level-2 list; header = quiet group label (not focusable); icon = ic_menu_* drawable (0 = none)
        Rail(String i, String l, int c) { id = i; label = l; count = c; }
        Rail ic(int res) { icon = res; return this; }
        Rail pin() { pin = true; return this; } // Search / Settings: drawn in the bar under the list; the list only holds an invisible 1dp row for the cursor
        static Rail nav(String i, String l, int c) { Rail r = new Rail(i, l, c); r.nav = true; return r; }
        static Rail divider() { Rail r = new Rail("", "", -1); r.divider = true; return r; }
        static Rail header(String l) { Rail r = new Rail("", l, -1); r.header = true; return r; }
        boolean selectable() { return !divider && !header; }
    }
    /** A folder-like row: a country or one of a source's folders. id = the list id it opens ("cc:lk", "sf:id TAB folder"). */
    private static final class CC {
        final String id, name, flag, countText;
        CC(String i, String n, String f, String c) { id = i; name = n; flag = f; countText = c; }
    }

    private final Host host;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
    private final View scrim;
    private final FrameLayout col2;
    private final LinearLayout content;
    private final ListView rail, list;
    private final LinearLayout railCol, barBox;
    private final LinearLayout barSearch, barSettings;
    private final TextView railHead;
    private final Modal dlg;
    private final KeyModal kmodal;
    private final TextView dlgTitle, dlgSub, dlgYes, dlgMid, dlgNo;
    private final View dlgBox, alsoBox;      // T110e: the two boxes of the dialog layer: question, and the "Also on" channel list
    private final TextView alsoTitle;
    private final LinearLayout alsoRows;
    private final android.widget.ScrollView alsoScroll;
    private final LinearLayout dlgBtns;
    private final SafeFrame frame;
    private Runnable dlgAct, dlgActMid;
    private View dlgPrev;
    private final TextView title, sub, empty;
    private boolean cardOk = true;   // the preview card needs >= 260 scaled dp
    private final EditText searchEt;
    private final SettingsView settings;
    private final FilmGrid grid;
    private final SeriesGrid sgrid;     // Series on now (T110c)
    private final Library library;
    private final Library seriesLibrary;
    private final Home home;
    private boolean homeDone;          // Home is the opening section only the first time the guide opens after the app starts
    private boolean hasHome;
    private final FilmDetails details;
    private final LibDetails libDetails;
    private final SeriesDetails seriesDetails;
    private final WhichFilm which;
    private final PhoneSearch phone;
    private final TextView phoneBtn;     // Search section: "Type on your phone"
    private boolean focusResults;        // text arrived from the phone: when its results show, the cursor goes to them
    private String oSec, oUrl; // where playback started: the page ('home' / 'films') and the channel it tuned (null = a film)
    private String oZap; // the list that playback was committed into: zapping inside it still counts as the same visit (Back = the tile you clicked)
    // M2: find inside any list with the remote: per-list sort, a name filter, keypad letter jump / row-number jump
    private LinearLayout tools; // header row over a channel list: [Sort: …] [Filter] / filter box
    private TextView sortChip, filterChip, jumpTv;
    private EditText filterEt;
    private String filterQ = "", filterFor; // the filter text and the list id it applies to (cleared when another list shows)
    private int lastDigit = -1, digitPress;
    private long digitAt;
    private String numBuf = "";
    private static final long KEY_REPEAT_MS = 1500, NUM_WAIT_MS = 1200;
    // M3: search filters for the remote, a chip row under "Type on your phone"
    private android.widget.HorizontalScrollView sfScroll;
    private TextView sfType, sfCountry, sfCat, sfQuality, sfFav, sfClear;
    private final lk.manus.iptv.data.SearchFilter sfilter = new lk.manus.iptv.data.SearchFilter();
    private View sfLast; // the chip the cursor was last on (Up from the results returns there)
    private String detFrom; // the page a Details page was opened over when its film started ('home' / 'lib' / 'libsearch' / 'series'): rebuilt under Details on return
    private boolean detailsOnly;   // opened from the banner: just the Details page over live TV, no lists
    private final BaseAdapter railAd, listAd;
    private final LinearLayout card, chips;
    private final ImageView cLogo;
    private final FrameLayout cMini;      // 16:9 window: logo underneath, the muted preview video fades in on top
    private final TextureView cTex;
    private final MiniPreview mini;
    private int miniDrop0;
    private final lk.manus.iptv.player.MiniPolicy miniPolicy = new lk.manus.iptv.player.MiniPolicy(); // one odd channel skips that channel, not the TV
    private String miniUrl = "";
    private int avc = -1;                  // H.264 hardware decoder instances (checked once)
    private final TextView cName, cInfo, cSpeed, cStatus, cNow, cTimes, cNext;
    private final NowBar cBar;

    private final List<Rail> l1 = new ArrayList<>(), l2 = new ArrayList<>();
    private List<Rail> rails = l1;            // the level on screen
    private int level = 1, sel1;              // level 2 shows a country / category / source list in the rail column
    private String l2Kind = "";
    private final List<Object> items = new ArrayList<>();
    private final Map<String, Integer> memo = new HashMap<>();
    // where each channel list was left, by channel: the focused channel, its offset from the top of the list view, the list it was in
    private final Map<String, String> memoUrl = new HashMap<>();
    private final Map<String, Integer> memoTop = new HashMap<>();
    private final Map<String, List<Channel>> memoList = new HashMap<>();
    private Integer nextTop;               // offset for the next applyItems (null = the default)
    private final Set<String> favSet = new HashSet<>();
    private List<Channel> chanList;
    private int railSel, listSel;
    private String shownId, drill, liveUrl = "";
    private boolean open, fast;
    private long lastNav;
    private int searchSeq;
    private String lastQuery = "";
    private static final List<String> NUMS = new ArrayList<>();

    private final Runnable railDwell = new Runnable() { @Override public void run() { if (railSel < rails.size()) showSection(rails.get(railSel)); } };
    private final Runnable speedR = new Runnable() {
        @Override public void run() {
            if (card.getVisibility() != VISIBLE) return;
            cSpeed.setText(host.netSpeed());
            h.postDelayed(this, 1000);
        }
    };
    /** Only the rows on screen get probed, 400 ms after the list settles. */
    private final Runnable probeR = new Runnable() {
        @Override public void run() {
            if (!open) return;
            Prober pr = Prober.get(getContext());
            int b = Math.min(list.getLastVisiblePosition(), items.size() - 1);
            for (int i = b; i >= Math.max(0, list.getFirstVisiblePosition()); i--) if (items.get(i) instanceof Channel) pr.request((Channel) items.get(i));
        }
    };
    private boolean cc; // the playing channel carries subtitles
    private final Runnable cardR = new Runnable() { @Override public void run() { showCard(); } };
    private final Runnable liveR = new Runnable() {
        @Override public void run() {
            Channel c = selected();
            if (c == null || c.url.equals(liveUrl) || !miniOn() || miniPolicy.skip(c.url)) return; // the playing channel is already on screen; a channel that failed here shows its logo
            miniDrop0 = host.mainDropped();
            miniUrl = c.url;
            mini.start(c, c.url, cTex);
            h.removeCallbacks(miniWatchR);
            h.postDelayed(miniWatchR, 5000);
        }
    };
    /** While a preview runs: if the full-screen video starts dropping frames, give the mini window up on this TV for good. */
    private final Runnable miniWatchR = new Runnable() {
        @Override public void run() {
            if (!mini.active()) return;
            int d = host.mainDropped() - miniDrop0;
            Log.i(TAG, "mini: main dropped " + d + " frames since the preview started");
            if (d > 60) { stopMini(); blockMini("main video dropped " + d + " frames"); return; }
            miniDrop0 = host.mainDropped();
            h.postDelayed(this, 5000);
        }
    };
    private final Runnable calmR = new Runnable() { @Override public void run() { fast = false;
            View sel = list.getSelectedView();
            if (sel instanceof Row && Ui.anim()) ((Row) sel).animate().scaleX(1.03f).scaleY(1.03f).setDuration(160).setInterpolator(Ui.DEC).start(); // selected row grows once scrolling stops (not on a Low-tier TV)
            LogoLoader.quiet = false;
            if (LogoLoader.deferred) { LogoLoader.deferred = false; listAd.notifyDataSetChanged(); } // load the logos skipped while scrolling
        }
    };
    private final Runnable searchR = new Runnable() { @Override public void run() { runSearch(); } };
    private final Runnable idleR = new Runnable() {
        @Override public void run() {
            // never close under a text box (search / add-source form) or an open dialog
            if (searchEt.hasFocus() || filterEt.hasFocus() || settings.hasTextFocus() || dlg.getVisibility() == VISIBLE || phone.isUp()) { h.postDelayed(idleR, IDLE_MS); return; }
            Log.i(TAG, "guide idle timeout");
            dismiss(true);
        }
    };

    public Guide(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        setVisibility(GONE);
        int sw = Ui.screenW();
        scrim = new View(ctx);
        scrim.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Ui.alpha(Ui.INK, 0.92f), Ui.alpha(Ui.INK, 0.92f), Ui.alpha(Ui.INK, 0.69f), Ui.alpha(Ui.INK, 0f)})); // functional fade INK -> clear
        addView(scrim, new LayoutParams(sw * 3 / 4, -1, Gravity.START));

        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setPadding(Ui.marginX(), Ui.marginY(), 0, Ui.marginY());
        addView(content, new LayoutParams(-2, -1, Gravity.START));
        frame = new SafeFrame(ctx);

        rail = newList(true);
        railAd = new BaseAdapter() {
            @Override public int getCount() { return rails.size(); }
            @Override public Object getItem(int i) { return rails.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public int getViewTypeCount() { return 4; }
            @Override public int getItemViewType(int i) { return rails.get(i).divider ? 1 : rails.get(i).header ? 2 : rails.get(i).pin ? 3 : 0; }
            @Override public void notifyDataSetChanged() { super.notifyDataSetChanged(); refreshBar(); }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int i) { return rails.get(i).selectable(); }
            @Override public View getView(int i, View v, ViewGroup p) {
                if (rails.get(i).pin) {
                    if (v != null) return v;
                    View z = new View(getContext());
                    z.setLayoutParams(new AbsListView.LayoutParams(-1, Ui.u(1)));
                    return z;
                }
                if (rails.get(i).header) {
                    TextView t = v instanceof TextView ? (TextView) v : groupLabel(getContext());
                    t.setText(rails.get(i).label);
                    return t;
                }
                if (rails.get(i).divider) {
                    if (v != null) return v;
                    FrameLayout d = new FrameLayout(getContext());
                    d.setLayoutParams(new AbsListView.LayoutParams(-1, Ui.u(18)));
                    View line = new View(getContext());
                    line.setBackgroundColor(Ui.LINE);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, Ui.u(1), Gravity.CENTER_VERTICAL);
                    lp.setMargins(Ui.u(14), 0, Ui.u(14), 0);
                    d.addView(line, lp);
                    return d;
                }
                RailRow r = v instanceof RailRow ? (RailRow) v : new RailRow(getContext());
                r.bind(rails.get(i), i);
                return r;
            }
        };
        rail.setAdapter(railAd);
        railCol = new LinearLayout(ctx);
        railCol.setOrientation(LinearLayout.VERTICAL);
        // T111b: the logo sits top-left of the menu (and so of every menu screen); the guide is only ever on screen with the menu
        LinearLayout logo = new LinearLayout(ctx);
        logo.setOrientation(LinearLayout.HORIZONTAL);
        logo.setGravity(Gravity.CENTER_VERTICAL);
        logo.setPadding(Ui.u(2), 0, 0, 0);
        ImageView logoMark = new ImageView(ctx);
        logoMark.setImageResource(R.mipmap.ic_launcher_foreground);
        logo.addView(logoMark, new LinearLayout.LayoutParams(Ui.u(LOGO_H), Ui.u(LOGO_H)));
        TextView logoName = Ui.tv(ctx, Ui.BODY, Ui.PAPER, true);
        logoName.setText("MANUS TV");
        logoName.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams lnp = new LinearLayout.LayoutParams(-2, -2);
        lnp.leftMargin = Ui.u(2);
        logo.addView(logoName, lnp);
        railCol.addView(logo, new LinearLayout.LayoutParams(-1, Ui.u(LOGO_H)));
        railHead = Ui.tv(ctx, Ui.BODY, Ui.MUTED, true);
        railHead.setPadding(Ui.u(26), Ui.u(2), 0, Ui.u(8));
        railHead.setVisibility(GONE);
        railCol.addView(railHead);
        railCol.addView(rail, new LinearLayout.LayoutParams(-1, 0, 1));
        // T111c: Search and Settings side by side under a thin line, always in view; the cursor still lives in the list (see Rail.pin)
        barBox = new LinearLayout(ctx);
        barBox.setOrientation(LinearLayout.VERTICAL);
        View barLine = new View(ctx);
        barLine.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, Math.max(1, Ui.u(1)));
        blp.setMargins(Ui.u(12), Ui.u(4), Ui.u(12), Ui.u(6));
        barBox.addView(barLine, blp);
        LinearLayout barRow = new LinearLayout(ctx);
        barRow.setPadding(Ui.u(12), 0, Ui.u(12), Ui.u(4));
        barSearch = barBtn(ctx, "Search", R.drawable.ic_menu_search);
        barSettings = barBtn(ctx, "Settings", R.drawable.ic_menu_settings);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, Ui.u(ROW_H), 1);
        b1.rightMargin = Ui.u(4);
        barRow.addView(barSearch, b1);
        barRow.addView(barSettings, new LinearLayout.LayoutParams(0, Ui.u(ROW_H), 1));
        barBox.addView(barRow);
        railCol.addView(barBox);
        content.addView(railCol, new LinearLayout.LayoutParams(0, -1));

        col2 = new FrameLayout(ctx);
        LinearLayout c2 = new LinearLayout(ctx);
        c2.setOrientation(LinearLayout.VERTICAL);
        title = Ui.tv(ctx, Ui.TITLE, Ui.PAPER, true);
        sub = Ui.tv(ctx, Ui.CAPTION, Ui.MUTED, false);
        title.setPadding(Ui.u(18), Ui.u(2), 0, 0);
        sub.setPadding(Ui.u(18), Ui.u(3), 0, Ui.u(6));
        c2.addView(title);
        c2.addView(sub);
        searchEt = new EditText(ctx);
        searchEt.setSingleLine(true);
        Ui.size(searchEt, Ui.BODY);
        searchEt.setTextColor(Ui.PAPER);
        searchEt.setHintTextColor(Ui.MUTED);
        searchEt.setHint("🔍  Type or speak to search");
        searchEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchEt.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        searchEt.setPadding(Ui.u(18), Ui.u(10), Ui.u(18), Ui.u(10));
        android.graphics.drawable.StateListDrawable sbg = new android.graphics.drawable.StateListDrawable();
        sbg.addState(new int[]{android.R.attr.state_focused}, Ui.ring());
        sbg.addState(new int[]{}, Ui.rect(Ui.PANEL, 14));
        searchEt.setBackground(sbg);
        searchEt.setVisibility(GONE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(Ui.u(12), Ui.u(4), Ui.u(12), Ui.u(6));
        c2.addView(searchEt, sp);
        phoneBtn = Ui.tv(ctx, Ui.BODY, Ui.PAPER, true);
        phoneBtn.setText("Type on your phone");
        phoneBtn.setFocusable(true);
        phoneBtn.setPadding(Ui.u(18), Ui.u(10), Ui.u(18), Ui.u(10));
        android.graphics.drawable.StateListDrawable pbg = new android.graphics.drawable.StateListDrawable();
        pbg.addState(new int[]{android.R.attr.state_focused}, Ui.ring());
        pbg.addState(new int[]{}, Ui.rect(Ui.PANEL, 14));
        phoneBtn.setBackground(pbg);
        phoneBtn.setVisibility(GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.setMargins(Ui.u(12), 0, Ui.u(12), Ui.u(6));
        c2.addView(phoneBtn, pp);
        sfScroll = new android.widget.HorizontalScrollView(ctx);
        sfScroll.setHorizontalScrollBarEnabled(false);
        sfScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        sfScroll.setClipToPadding(false);
        LinearLayout sfRow = new LinearLayout(ctx);
        sfRow.setOrientation(LinearLayout.HORIZONTAL);
        sfRow.setPadding(Ui.u(4), Ui.u(4), Ui.u(4), Ui.u(4)); // room for the focus ring
        sfType = toolChip(ctx); sfCountry = toolChip(ctx); sfCat = toolChip(ctx); sfQuality = toolChip(ctx); sfFav = toolChip(ctx); sfClear = toolChip(ctx);
        sfClear.setText("✕  Clear filters");
        for (TextView t : new TextView[]{sfType, sfCountry, sfCat, sfQuality, sfFav, sfClear}) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.rightMargin = Ui.u(8);
            sfRow.addView(t, cp);
        }
        sfScroll.addView(sfRow);
        sfScroll.setVisibility(GONE);
        LinearLayout.LayoutParams sfp = new LinearLayout.LayoutParams(-1, -2);
        sfp.setMargins(Ui.u(8), 0, Ui.u(8), Ui.u(4));
        c2.addView(sfScroll, sfp);
        tools = new LinearLayout(ctx);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        sortChip = toolChip(ctx);
        filterChip = toolChip(ctx);
        filterEt = new EditText(ctx);
        filterEt.setSingleLine(true);
        Ui.size(filterEt, Ui.CAPTION);
        filterEt.setTextColor(Ui.PAPER);
        filterEt.setHintTextColor(Ui.MUTED);
        filterEt.setHint("Filter this list");
        filterEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        filterEt.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        filterEt.setPadding(Ui.u(14), Ui.u(6), Ui.u(14), Ui.u(6));
        android.graphics.drawable.StateListDrawable fbg = new android.graphics.drawable.StateListDrawable();
        fbg.addState(new int[]{android.R.attr.state_focused}, Ui.ring());
        fbg.addState(new int[]{}, Ui.rect(Ui.PANEL, 12));
        filterEt.setBackground(fbg);
        filterEt.setVisibility(GONE);
        LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(-2, -2);
        tcp.rightMargin = Ui.u(8);
        tools.addView(sortChip, tcp);
        tools.addView(filterChip, new LinearLayout.LayoutParams(-2, -2));
        tools.addView(filterEt, new LinearLayout.LayoutParams(0, -2, 1));
        tools.setVisibility(GONE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.setMargins(Ui.u(18), 0, Ui.u(12), Ui.u(6));
        c2.addView(tools, tlp);
        list = newList();
        listAd = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public int getViewTypeCount() { return 3; }
            @Override public int getItemViewType(int i) {
                Object o = items.get(i);
                return o instanceof CC ? 1 : o instanceof LibSearchRow ? 2 : 0;
            }
            @Override public View getView(int i, View v, ViewGroup p) {
                Object o = items.get(i);
                if (o instanceof CC) {
                    CountryRow r = v instanceof CountryRow ? (CountryRow) v : new CountryRow(getContext());
                    r.bind((CC) o, i);
                    return r;
                }
                if (o instanceof LibSearchRow) {
                    LibSearchRowView r = v instanceof LibSearchRowView ? (LibSearchRowView) v : new LibSearchRowView(getContext());
                    r.bind((LibSearchRow) o);
                    return r;
                }
                ChanRow r = v instanceof ChanRow ? (ChanRow) v : new ChanRow(getContext());
                r.bind((Channel) o, i);
                return r;
            }
        };
        list.setAdapter(listAd);
        c2.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        col2.addView(c2, new LayoutParams(-1, -1));
        empty = Ui.tv(ctx, Ui.BODY, Ui.MUTED, false);
        empty.setSingleLine(false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.u(40), 0, Ui.u(40), 0);
        col2.addView(empty, new LayoutParams(-1, -1));
        jumpTv = Ui.tv(ctx, Ui.DISPLAY, Ui.PAPER, true); // M2: the letter or number being jumped to, big, top-right of the list
        jumpTv.setGravity(Gravity.CENTER);
        jumpTv.setMinWidth(Ui.u(76));
        jumpTv.setPadding(Ui.u(18), Ui.u(10), Ui.u(18), Ui.u(10));
        jumpTv.setBackground(Ui.card(0.95f, 16));
        jumpTv.setVisibility(GONE);
        LayoutParams jlp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        jlp.setMargins(0, Ui.u(8), Ui.u(18), 0);
        col2.addView(jumpTv, jlp);
        content.addView(col2, new LinearLayout.LayoutParams(0, -1));
        settings = new SettingsView(ctx, new SettingsView.Host() {
            @Override public Index index() { return Guide.this.host.index(); }
            @Override public Store store() { return Guide.this.host.store(); }
            @Override public Sources sources() { return Guide.this.host.sources(); }
            @Override public void sourcesChanged() { Guide.this.host.sourcesChanged(); }
            @Override public void forYouChanged() { Guide.this.forYouChanged(); }
            @Override public void countsChanged() { Guide.this.refreshCounts(); }
            @Override public void focusRail() { rail.requestFocus(); }
            @Override public void openSources() { enterLevel2("srcs", null, true); }
            @Override public void recheckHidden() {
                confirm("Re-check hidden channels?", "Hidden channels get another chance.", "Re-check", "Cancel", new Runnable() {
                    @Override public void run() {
                        Health.get(getContext()).resetDead();
                        Guide.this.host.index().rebuildNow();
                        refreshCounts();
                        listAd.notifyDataSetChanged();
                        pill("Hidden channels will be re-checked");
                    }
                });
            }
            @Override public void pill(String text) { Guide.this.pill(text); }
            @Override public void confirm(String t, String s, String yes, String no, Runnable act) { Guide.this.confirm(t, s, yes, no, act); }
            @Override public void confirm3(String t, String s, String b1, String b2, String b3, Runnable onB1, Runnable onB2) { Guide.this.confirm3(t, s, b1, b2, b3, onB1, onB2); }
            @Override public void restoreFavourites(List<String> urls) { Guide.this.host.restoreFavourites(urls); }
            @Override public void filmsChanged() { Guide.this.onIndexChanged(); }
            @Override public void remindersChanged() { Guide.this.host.remindersChanged(); }
            @Override public void restored() {
                Store st = Guide.this.host.store();
                Ui.setEdge(st.edge());
                Ui.setPerf(st.perf());
                applyLayout();
                Guide.this.host.restored();
                onFavChanged();
                homeDone = false; // the guide opens on Home after a restore: the old list positions mean nothing now
                openSection("home");
            }
            @Override public void frame(boolean on) { if (on) frame.show(); else frame.hideNow(); }
            @Override public void edgesChanged() { applyLayout(); Guide.this.host.edgesChanged(); frame.flash(); }
            @Override public void showRemoteKeys() { Guide.this.kmodal.showHelp(); }
            @Override public void showRemoteTest() { Guide.this.kmodal.showTest(); }
            @Override public void exit() { confirm("Exit Manus TV?", "", "Yes", "No", new Runnable() { @Override public void run() { Guide.this.host.exitApp(); } }); }
        });
        content.addView(settings, new LinearLayout.LayoutParams(0, -1));
        grid = new FilmGrid(ctx, new FilmGrid.Host() {
            @Override public void open(Films.Entry e, List<Channel> src) { openFilm(e); }
            @Override public void details(Films.Entry e) { showDetails(e); }
            @Override public void left() { rail.requestFocus(); }
        });
        content.addView(grid, new LinearLayout.LayoutParams(0, -1));
        sgrid = new SeriesGrid(ctx, new SeriesGrid.Host() {
            @Override public void open(SeriesNow.Entry e) { openSeries(e); }
            @Override public void left() { rail.requestFocus(); }
        });
        content.addView(sgrid, new LinearLayout.LayoutParams(0, -1));
        home = new Home(ctx, new Home.Host() {
            @Override public void film(Films.Entry e) { openFilm(e); }
            @Override public void series(SeriesNow.Entry e) { openSeries(e); }
            @Override public void allFilms() { openSection("films"); }
            @Override public void details(Films.Entry e) { showDetails(e); }
            @Override public void channel(String id, List<Channel> l, int pos) {
                if (pos < 0 || pos >= l.size()) return;
                markOrigin(l.get(pos).url, id);
                host.onCommit(id, host.index().title(id), l, pos);
                hide();
            }
            @Override public void libFilm(lk.manus.iptv.data.Library.Item item) {
                if ("episode".equals(item.kind)) { // series tile: OK plays that episode, resuming if it was started
                    lk.manus.iptv.data.Db db = lk.manus.iptv.data.Db.get();
                    lk.manus.iptv.data.Db.Progress pr = db != null ? db.progress(item.id) : null;
                    markOrigin(null, null);
                    host.playFilm(item, pr != null && lk.manus.iptv.data.FilmResume.canResume(pr.positionMs, pr.durationMs, pr.watched) ? pr.positionMs : 0L);
                    return;
                }
                content.setVisibility(INVISIBLE);
                libDetails.show(item);
            }
            @Override public void myListItem(lk.manus.iptv.data.Library.Item item) {
                if (item == null) return;
                content.setVisibility(INVISIBLE);
                if ("series".equals(item.kind)) seriesDetails.show(item); else libDetails.show(item);
            }
            @Override public void removeContinue(final lk.manus.iptv.data.Library.ContinueItem ci) {
                if (ci == null) return;
                android.util.Log.i(TAG, "Guide.removeContinue requested for item " + ci.item.id + " (" + ci.item.title + ")");
                host.openMenu(ci.item.title, java.util.Collections.singletonList("Remove from Continue watching"), 0, new Host.Pick() {
                    @Override public void pick(int i) {
                        android.util.Log.i(TAG, "Guide.removeContinue pick: i=" + i + " item=" + ci.item.id);
                        if (i == 0) {
                            lk.manus.iptv.data.Db db = lk.manus.iptv.data.Db.get();
                            if (db != null) {
                                db.deleteProgress(ci.item.id);
                            }
                            lk.manus.iptv.data.WatchNext.remove(getContext(), ci.item.id);
                            home.removeContinue(ci);
                        }
                    }
                });
            }
            @Override public void left() { rail.requestFocus(); }
        });
        content.addView(home, new LinearLayout.LayoutParams(0, -1));
        library = new Library(ctx, new Library.Host() {
            @Override public void details(lk.manus.iptv.data.Library.Item item) {
                if (item == null) return;
                content.setVisibility(INVISIBLE);
                if ("series".equals(item.kind)) {
                    if (Config.DEBUG) Log.i("IPTV", "series open " + item.title);
                    seriesDetails.show(item);
                } else {
                    libDetails.show(item);
                }
            }
            @Override public void left() {
                rail.requestFocus();
            }
            @Override public void back() {
                library.hide();
                shownId = null; openSection("search"); // rebuild the results page (it was replaced by the library grid)
            }
        });
        content.addView(library, new LinearLayout.LayoutParams(0, -1));
        seriesLibrary = new Library(ctx, new Library.Host() {
            @Override public void details(lk.manus.iptv.data.Library.Item item) {
                if (Config.DEBUG) Log.i("IPTV", "series open " + (item != null ? item.title : ""));
                if (item != null) {
                    content.setVisibility(INVISIBLE);
                    seriesDetails.show(item);
                }
            }
            @Override public void left() {
                rail.requestFocus();
            }
            @Override public void back() {
                seriesLibrary.hide();
                shownId = null; openSection("search"); // rebuild the results page (it was replaced by the library grid)
            }
        }, "series");
        content.addView(seriesLibrary, new LinearLayout.LayoutParams(0, -1));

        // preview card
        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rect(Ui.alpha(Ui.PANEL, 0.94f), 18));
        card.setPadding(Ui.u(20), Ui.u(20), Ui.u(20), Ui.u(18));
        card.setAlpha(0f);
        card.setVisibility(INVISIBLE);
        cLogo = new ImageView(ctx);
        cLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cLogo.setPadding(Ui.u(12), Ui.u(10), Ui.u(12), Ui.u(10));
        cLogo.setBackground(Ui.rect(Ui.INK, 12));
        cMini = new FrameLayout(ctx);
        cMini.setBackground(Ui.rect(Ui.INK, 12));
        cMini.setClipToOutline(true);
        cMini.addView(cLogo, new FrameLayout.LayoutParams(-1, -1));
        cTex = new TextureView(ctx);
        cTex.setAlpha(0f);
        Ui.blurVideo(cTex, 12);
        cMini.addView(cTex, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        card.addView(cMini, new LinearLayout.LayoutParams(-1, Ui.u(110)));
        mini = new MiniPreview(ctx, new MiniPreview.Listener() {
            @Override public void onFirstFrame() { cTex.animate().cancel(); if (Ui.anim()) cTex.animate().alpha(1f).setDuration(220).start(); else cTex.setAlpha(1f); }
            @Override public void onVideoSize(int w, int hh) { fitMini(w, hh); }
            @Override public void onFail(String why, boolean decoderProblem) {
                stopMini();
                if (miniPolicy.onFail(why, miniUrl) == lk.manus.iptv.player.MiniPolicy.BLOCK_TV) blockMini(why);
                else Log.i(TAG, "mini: " + why + " - this channel shows its logo, the preview stays on");
            }
        });
        cName = Ui.tv(ctx, Ui.TITLE, Ui.PAPER, true);
        cName.setPadding(0, Ui.u(14), 0, Ui.u(8));
        card.addView(cName);
        chips = new LinearLayout(ctx);
        card.addView(chips);
        cInfo = Ui.tv(ctx, Ui.CAPTION, Ui.MUTED, false);
        cInfo.setPadding(0, Ui.u(10), 0, 0);
        card.addView(cInfo);
        cNow = Ui.tv(ctx, Ui.BODY, Ui.PAPER, true);
        cNow.setPadding(0, Ui.u(10), 0, 0);
        cNow.setSingleLine(false);
        cNow.setMaxLines(2);
        cNow.setVisibility(GONE);
        card.addView(cNow);
        cTimes = Ui.tv(ctx, Ui.CAPTION, Ui.MUTED, false);
        cTimes.setPadding(0, Ui.u(4), 0, 0);
        cTimes.setVisibility(GONE);
        card.addView(cTimes);
        cBar = new NowBar(ctx);
        cBar.setVisibility(GONE);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(-1, Ui.u(3));
        cbp.topMargin = Ui.u(6);
        card.addView(cBar, cbp);
        cNext = Ui.tv(ctx, Ui.CAPTION, Ui.MUTED, false);
        cNext.setPadding(0, Ui.u(6), 0, 0);
        cNext.setVisibility(GONE);
        card.addView(cNext);
        cSpeed = Ui.tv(ctx, Ui.CAPTION, Ui.MUTED, true);
        cSpeed.setPadding(0, Ui.u(6), 0, 0);
        card.addView(cSpeed);
        cStatus = Ui.tv(ctx, Ui.CAPTION, Ui.WARN, true);
        cStatus.setText("Not available");
        cStatus.setVisibility(GONE);
        cStatus.setPadding(0, Ui.u(8), 0, 0);
        card.addView(cStatus);
        TextView hint = Ui.tv(ctx, Ui.CAPTION, Ui.MUTED, false);
        hint.setText("OK watch  ·  hold OK ★");
        hint.setPadding(0, Ui.u(14), 0, 0);
        card.addView(hint);
        addView(card, new LayoutParams(0, -2, Gravity.END | Gravity.BOTTOM));
        addView(frame, new LayoutParams(-1, -1));
        details = new FilmDetails(ctx, new FilmDetails.Host() {
            @Override public void watch(Films.Entry e) {
                if (detailsOnly) { Guide.this.dismiss(true); return; } // the film is on this channel already
                Guide.this.closeDetails(); openFilm(e);
            }
            @Override public void trailer(String key) {
                if (!key.matches("[A-Za-z0-9_-]{6,20}") || !YouTubeLive.open(getContext(), "https://www.youtube.com/watch?v=" + key)) { pill("YouTube app not found"); return; }
                hideNow();
            }
            @Override public boolean reminded(Films.Entry e) { return Reminders.has(lk.manus.iptv.data.Keys.of(e.ch.url), e.slot.start); }
            @Override public void remind(final Films.Entry e) {
                final String k = lk.manus.iptv.data.Keys.of(e.ch.url);
                if (!Reminders.has(k, e.slot.start)) { Reminders.add(new Reminders.Item(k, e.film.title, e.ch.name, e.slot.start)); remindersDone(); return; }
                confirm("Cancel this reminder?", "", "Cancel reminder", "Keep", new Runnable() { @Override public void run() { Reminders.remove(k, e.slot.start); remindersDone(); } });
            }
            @Override public void notThisFilm(Films.Entry e) {
                List<lk.manus.iptv.meta.MetaStore.Choice> others = Films.choices(getContext(), e);
                if (!others.isEmpty()) { details.hide(); which.show(e, others); return; } // other films carry this title: ask which one first
                Films.forget(getContext(), e.slot, e.film);
                e.film = Films.known(e.slot);
                Guide.this.leaveDetails();
                pill("Removed. This film won't be matched again.");
            }
        });
        addView(details, new LayoutParams(-1, -1));
        libDetails = new LibDetails(ctx, new LibDetails.Host() {
            @Override public void close() {
                libDetails.hide();
                if (detailsOnly) {
                    dismiss(true);
                } else if (home.getVisibility() == VISIBLE) { // opened from Home: back to Home, My list row up to date
                    content.setVisibility(VISIBLE);
                    home.refreshMyList();
                    home.focusTile();
                } else {
                    content.setVisibility(VISIBLE);
                    library.focusTile();
                }
            }
            @Override public void toast(String text) { pill(text); }
            @Override public void trailer(String key) {
                if (!key.matches("[A-Za-z0-9_-]{6,20}") || !YouTubeLive.open(getContext(), "https://www.youtube.com/watch?v=" + key)) { pill("YouTube app not found"); return; }
                hideNow();
            }
            @Override public void playFilm(lk.manus.iptv.data.Library.Item item, long startPosMs) {
                oSec = null; // Back from a film started on a Details page goes to that page
                detFrom = pageBehind();
                host.playFilm(item, startPosMs);
            }
        });
        addView(libDetails, new LayoutParams(-1, -1));
        seriesDetails = new SeriesDetails(ctx, new SeriesDetails.Host() {
            @Override public void close() {
                seriesDetails.hide();
                detailsOnly = false;
                content.setVisibility(VISIBLE);
                if (home.getVisibility() == VISIBLE) {
                    home.refreshMyList();
                    home.focusTile();
                } else if (library != null && library.getVisibility() == VISIBLE && library.isSearchMode()) {
                    library.focusTile();
                } else {
                    seriesLibrary.setVisibility(VISIBLE);
                    seriesLibrary.focusTile();
                }
            }
            @Override public void playFilm(lk.manus.iptv.data.Library.Item item, long startPosMs) {
                oSec = null; // Back from a film started on a Details page goes to that page
                detFrom = pageBehind();
                host.playFilm(item, startPosMs);
            }
            @Override public void toast(String text) {
                pill(text);
            }
        });
        addView(seriesDetails, new LayoutParams(-1, -1));
        which = new WhichFilm(ctx, new WhichFilm.Host() {
            @Override public void choose(final Films.Entry e, lk.manus.iptv.meta.MetaStore.Choice c) {
                Films.choose(getContext(), e, c.tmdbId, new java.util.function.Consumer<Boolean>() {
                    @Override public void accept(Boolean ok) {
                        if (!ok) { pill("Could not load that film"); return; }
                        grid.refresh();
                        home.refresh();
                        host.filmChanged();
                        which.hide();
                        details.show(e); // the page becomes the normal Details for that film
                    }
                });
            }
            @Override public void none(Films.Entry e) {
                Films.forget(getContext(), e.slot, e.film);
                e.film = Films.known(e.slot);
                Guide.this.leaveDetails();
                pill("Removed. This film won't be matched again.");
            }
        });
        addView(which, new LayoutParams(-1, -1));
        phone = new PhoneSearch(ctx, new PhoneSearch.Host() {
            @Override public void got(String text) { // the phone sent a word: close the QR, run the search, show the results
                closePhone();
                focusResults = true;
                searchEt.setText(text);
                searchEt.setSelection(searchEt.getText().length());
                h.removeCallbacks(searchR);
                runSearch();
            }
        });
        addView(phone, new LayoutParams(-1, -1));

        dlg = new Modal(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackground(Ui.card(1f, 18));
        box.setPadding(Ui.u(32), Ui.u(26), Ui.u(32), Ui.u(26));
        dlgTitle = Ui.tv(ctx, Ui.TITLE, Ui.PAPER, true);
        dlgTitle.setSingleLine(false); dlgTitle.setMaxLines(3); dlgTitle.setGravity(Gravity.CENTER_HORIZONTAL); // long questions wrap, never cut
        dlgSub = Ui.tv(ctx, Ui.BODY, Ui.MUTED, false);
        dlgSub.setPadding(0, Ui.u(8), 0, 0);
        dlgBtns = new LinearLayout(ctx);
        dlgBtns.setGravity(Gravity.CENTER);
        dlgYes = dlgButton(); dlgMid = dlgButton(); dlgNo = dlgButton();
        dlgMid.setVisibility(GONE);
        dlgBtns.addView(dlgYes, new LinearLayout.LayoutParams(0, Ui.u(52), 1));
        LinearLayout.LayoutParams mb = new LinearLayout.LayoutParams(0, Ui.u(52), 1);
        mb.leftMargin = Ui.u(16);
        dlgBtns.addView(dlgMid, mb);
        LinearLayout.LayoutParams nb = new LinearLayout.LayoutParams(0, Ui.u(52), 1);
        nb.leftMargin = Ui.u(16);
        dlgBtns.addView(dlgNo, nb);
        box.addView(dlgTitle);
        box.addView(dlgSub);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = Ui.u(22);
        box.addView(dlgBtns, bp);
        dlgBox = box;
        dlg.add(box, 0);
        alsoBox = new LinearLayout(ctx);
        ((LinearLayout) alsoBox).setOrientation(LinearLayout.VERTICAL);
        alsoBox.setBackground(Ui.card(1f, 18));
        alsoBox.setPadding(Ui.u(32), Ui.u(26), Ui.u(32), Ui.u(26));
        alsoTitle = Ui.tv(ctx, Ui.TITLE, Ui.PAPER, true);
        alsoTitle.setSingleLine(false); alsoTitle.setMaxLines(2);
        TextView alsoSub = Ui.tv(ctx, Ui.CAPTION, Ui.MUTED, false);
        alsoSub.setText("Also on");
        alsoRows = new LinearLayout(ctx);
        alsoRows.setOrientation(LinearLayout.VERTICAL);
        alsoScroll = new android.widget.ScrollView(ctx);
        alsoScroll.setVerticalScrollBarEnabled(false);
        alsoScroll.addView(alsoRows, new FrameLayout.LayoutParams(-1, -2));
        ((LinearLayout) alsoBox).addView(alsoTitle);
        ((LinearLayout) alsoBox).addView(alsoSub);
        LinearLayout.LayoutParams asp = new LinearLayout.LayoutParams(-1, -2);
        asp.topMargin = Ui.u(14);
        ((LinearLayout) alsoBox).addView(alsoScroll, asp);
        dlg.add(alsoBox, 0);
        alsoBox.setVisibility(GONE);
        addView(dlg, new LayoutParams(-1, -1));
        kmodal = new KeyModal(ctx);
        addView(kmodal, new LayoutParams(-1, -1));
        dlgYes.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { Runnable a = dlgAct; closeDlg(); if (a != null) a.run(); } });
        dlgMid.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { Runnable a = dlgActMid; closeDlg(); if (a != null) a.run(); } });
        dlgNo.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { closeDlg(); } });

        applyLayout();
        wire();
    }

    /** The grid of UX-SPEC 4, as fractions of the safe width: rail / level-2 22%, list 42%, card 30%, gutters 3%. Re-run when the screen edges change. */
    public void applyLayout() {
        int sw = Ui.safeW(), g = sw * 3 / 100, rw = sw * 25 / 100, lw = sw * 40 / 100, cw = sw * 29 / 100; // T111: the menu column is wider (icons + two-button bar)
        content.setPadding(Ui.marginX(), Ui.marginY(), 0, Ui.marginY());
        setW(railCol, rw, 0);
        setW(col2, lw, g);
        setW(settings, lw + g + cw, g);
        setW(grid, lw + g + cw, g);
        grid.relayout(lw + g + cw);
        setW(sgrid, lw + g + cw, g);
        sgrid.relayout(lw + g + cw);
        setW(library, lw + g + cw, g);
        library.relayout(lw + g + cw);
        setW(seriesLibrary, lw + g + cw, g);
        seriesLibrary.relayout(lw + g + cw);
        setW(home, lw + g + cw, g);
        home.relayout(lw + g + cw);
        cardOk = cw >= Ui.u(225); // 29% of the safe width is ~251 dp at baseline (T111: was 30% / 240 before the menu got wider): 260 hid the card on the KTC
        LayoutParams cp = (LayoutParams) card.getLayoutParams();
        cp.width = cw;
        cp.setMargins(0, 0, Ui.marginX(), Ui.marginY());
        card.setLayoutParams(cp);
        cMini.getLayoutParams().height = (cw - Ui.u(40)) * 9 / 16; // the logo area is a 16:9 window
    }

    private static void setW(View v, int w, int left) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.width = w;
        lp.leftMargin = left;
        v.setLayoutParams(lp);
    }

    private TextView dlgButton() {
        final TextView b = Ui.tv(getContext(), Ui.BODY, Ui.PAPER, true);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, Ui.ring());
        sl.addState(new int[]{}, Ui.rect(Ui.INK, 14));
        b.setBackground(sl);
        b.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) {
                    return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
                }
                if (dlgMid != null && dlgMid.getVisibility() == VISIBLE) {
                    if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (v == dlgYes) dlgMid.requestFocus();
                        else if (v == dlgMid) dlgNo.requestFocus();
                        return true;
                    }
                    if (k == KeyEvent.KEYCODE_DPAD_UP) {
                        if (v == dlgNo) dlgMid.requestFocus();
                        else if (v == dlgMid) dlgYes.requestFocus();
                        return true;
                    }
                    return k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
                }
                if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    (v == dlgYes ? dlgNo : dlgYes).requestFocus();
                    return true;
                }
                return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
            }
        });
        return b;
    }

    /** Modal question; "No" has focus. Back just closes it. */
    void confirm(String title, String sub, String yes, String no, Runnable onYes) {
        confirm3(title, sub, yes, null, no, onYes, null);
    }

    void confirm3(String title, String sub, String b1, String b2, String b3, Runnable onB1, Runnable onB2) {
        dlgPrev = findFocus();
        dlgAct = onB1;
        dlgActMid = onB2;
        dlgTitle.setText(title);
        dlgSub.setText(sub != null ? sub : "");
        dlgSub.setVisibility(sub != null && !sub.isEmpty() ? VISIBLE : GONE);
        dlgYes.setText(b1 != null ? b1 : "");
        dlgNo.setText(b3 != null ? b3 : "");
        if (b2 != null && !b2.isEmpty()) {
            dlgMid.setText(b2);
            dlgMid.setVisibility(VISIBLE);
            dlgBtns.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(-1, Ui.u(48));
            LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(-1, Ui.u(48));
            p2.topMargin = Ui.u(10);
            LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(-1, Ui.u(48));
            p3.topMargin = Ui.u(10);
            dlgYes.setLayoutParams(p1);
            dlgMid.setLayoutParams(p2);
            dlgNo.setLayoutParams(p3);
        } else {
            dlgMid.setVisibility(GONE);
            dlgBtns.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, Ui.u(52), 1);
            LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(0, Ui.u(52), 1);
            p3.leftMargin = Ui.u(16);
            dlgYes.setLayoutParams(p1);
            dlgNo.setLayoutParams(p3);
        }
        dlg.show(dlgBox);
        dlgNo.requestFocus();
    }

    private void closeDlg() {
        dlg.setVisibility(GONE);
        dlgAct = null;
        dlgActMid = null;
        if (dlgPrev != null && dlgPrev.isAttachedToWindow() && dlgPrev.getVisibility() == VISIBLE) dlgPrev.requestFocus(); else rail.requestFocus();
        dlgPrev = null;
    }

    private ListView newList() { return newList(false); }

    // fade = T111d: the menu rail ends above the Search/Settings bar; a short bottom fade (INK to clear, the scrim exception) hides any half-cut row there
    private ListView newList(boolean fade) {
        ListView l = fade ? new ListView(getContext()) {
            @Override protected float getTopFadingEdgeStrength() { return 0f; }
        } : new ListView(getContext());
        if (fade) { l.setVerticalFadingEdgeEnabled(true); l.setFadingEdgeLength(Ui.u(14)); }
        l.setSelector(new ColorDrawable(0));
        l.setDivider(null);
        l.setCacheColorHint(0);
        l.setOverScrollMode(OVER_SCROLL_NEVER);
        l.setVerticalScrollBarEnabled(false);
        l.setClipChildren(false);
        l.setPadding(Ui.u(12), Ui.u(4), Ui.u(12), Ui.u(4));
        l.setItemsCanFocus(false);
        l.setScrollingCacheEnabled(false);
        return l;
    }

    private void wire() {
        rail.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == railSel && !rail.hasFocus()) return;
                railSel = pos;
                if (level == 2) memo.put("L2:" + l2Kind, pos);
                refreshRail();
                if (rails == l1 && pos > 0 && l1.get(pos - 1).header) { // a group's first row: keep its label in view
                    final int at = pos;
                    rail.post(new Runnable() {
                        @Override public void run() {
                            View hv = rail.getChildAt(at - 1 - rail.getFirstVisiblePosition());
                            if (rails == l1 && at == railSel && (hv == null || hv.getTop() < 0)) rail.setSelectionFromTop(at, Ui.u(GROUP_H));
                        }
                    });
                }
                if (rail.hasFocus()) { h.removeCallbacks(railDwell); h.postDelayed(railDwell, RAIL_MS); }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!list.hasFocus()) return;
                listSel = pos;
                memo.put(memoKey(), pos);
                if (pos < items.size() && items.get(pos) instanceof Channel) { memoUrl.put(memoKey(), ((Channel) items.get(pos)).url); memoList.put(memoKey(), chanList); }
                long now = SystemClock.uptimeMillis();
                fast = now - lastNav < 160; // key-repeat scrolling: skip the row scale animation and logo fades
                LogoLoader.quiet = fast;
                lastNav = now;
                h.removeCallbacks(calmR);
                h.postDelayed(calmR, 200);
                dwell();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        OnFocusChangeListener fl = new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) {
                refreshRail();
                if (v == list) { if (f) dwell(); else { h.removeCallbacks(cardR); h.removeCallbacks(liveR); stopMini(); hideCard(); } }
            }
        };
        searchEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { h.removeCallbacks(searchR); h.postDelayed(searchR, 150); }
        });
        searchEt.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                hideIme();
                h.removeCallbacks(searchR);
                runSearch();
                list.requestFocus();
                return true;
            }
        });
        searchEt.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (k == KeyEvent.KEYCODE_DPAD_CENTER) {
                    if (e.getAction() == KeyEvent.ACTION_UP) {
                        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(v, 0);
                    }
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && searchEt.getSelectionStart() <= 0) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) rail.requestFocus();
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN) { hideIme(); phoneBtn.requestFocus(); }
                    return true;
                }
                return false;
            }
        });
        phoneBtn.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                boolean first = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) { if (first) rail.requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_UP) { if (first) searchEt.requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) { if (first) sfType.requestFocus(); return true; } // M3: the filter chips sit between the box and the results
                return k == KeyEvent.KEYCODE_DPAD_RIGHT;
            }
        });
        wireTools();
        wireSf();
        rail.setOnFocusChangeListener(fl);
        list.setOnFocusChangeListener(fl);
        rail.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                Rail cur = level == 1 && railSel >= 0 && railSel < l1.size() ? l1.get(railSel) : null;
                if (cur != null && cur.pin && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    // T111c: Search | Settings share one bar row: Right/Left hop between them, Up goes to the last browse row, Down has nowhere to go
                    if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return true;
                    boolean isSearch = cur.id.equals("search");
                    if (k == KeyEvent.KEYCODE_DPAD_UP) {
                        int t = idxOf("search") - 1;
                        while (t > 0 && !l1.get(t).selectable()) t--;
                        railTo(t);
                    } else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { if (isSearch) railTo(idxOf("settings")); else activate(); }
                    else if (k == KeyEvent.KEYCODE_DPAD_LEFT) { if (isSearch) dismiss(true); else railTo(idxOf("search")); }
                    return true;
                }
                if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    if (k == KeyEvent.KEYCODE_DPAD_LEFT) { if (level == 2) exitLevel2(true); else dismiss(true); }
                    else activate();
                }
                return true;
            }
        });
        list.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (k == KeyEvent.KEYCODE_DPAD_UP && phoneBtn.getVisibility() == VISIBLE && list.getSelectedItemPosition() <= 0) { // top of the search results: up to the filter chips (M3)
                    if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) (sfLast != null && sfLast.getVisibility() == VISIBLE ? sfLast : sfType).requestFocus();
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_UP && tools.getVisibility() == VISIBLE && list.getSelectedItemPosition() <= 0) { // M2: top of a channel list: up to Sort / Filter
                    if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) sortChip.requestFocus();
                    return true;
                }
                if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    if (k == KeyEvent.KEYCODE_DPAD_LEFT) rail.requestFocus();
                    else { Channel c = selected(); if (c != null) host.onOptions(c); }
                }
                return true;
            }
        });
    }

    // ---- M2: sort / filter / jump ----

    private TextView toolChip(Context c) {
        TextView t = Ui.tv(c, Ui.CAPTION, Ui.PAPER, true);
        t.setFocusable(true);
        t.setPadding(Ui.u(14), Ui.u(7), Ui.u(14), Ui.u(7));
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, Ui.ring());
        bg.addState(new int[]{}, Ui.chip());
        t.setBackground(bg);
        return t;
    }

    private void wireTools() {
        sortChip.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { cycleSort(); } });
        filterChip.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) {
            if (filterQ.isEmpty()) openFilter(); else { clearFilter(); filterChip.requestFocus(); }
        } });
        sortChip.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                boolean first = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) { if (first) rail.requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { if (first) (filterEt.getVisibility() == VISIBLE ? filterEt : filterChip).requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) { if (first && !items.isEmpty()) list.requestFocus(); return true; }
                return k == KeyEvent.KEYCODE_DPAD_UP;
            }
        });
        filterChip.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                boolean first = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) { if (first) sortChip.requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) { if (first && !items.isEmpty()) list.requestFocus(); return true; }
                return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_RIGHT;
            }
        });
        final Runnable filterR = new Runnable() { @Override public void run() { applyFilter(filterEt.getText().toString()); } };
        filterEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence q, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence q, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable ed) { if (filterEt.getVisibility() == VISIBLE) { h.removeCallbacks(filterR); h.postDelayed(filterR, 150); } }
        });
        filterEt.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                hideIme();
                h.removeCallbacks(filterR);
                applyFilter(filterEt.getText().toString());
                if (!items.isEmpty()) list.requestFocus();
                return true;
            }
        });
        filterEt.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
                if (k == KeyEvent.KEYCODE_DPAD_CENTER) {
                    if (!down) { InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE); if (imm != null) imm.showSoftInput(v, 0); }
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && filterEt.getSelectionStart() <= 0) { if (down && e.getRepeatCount() == 0) sortChip.requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) { if (down) { hideIme(); if (!items.isEmpty()) list.requestFocus(); } return true; }
                return k == KeyEvent.KEYCODE_DPAD_UP;
            }
        });
        filterEt.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) {
                if (f || filterEt.getVisibility() != VISIBLE) return;
                hideIme();
                filterEt.setVisibility(GONE); // the box folds back into the chip, which now shows the text (or plain "Filter")
                filterChip.setVisibility(VISIBLE);
                updateTools();
            }
        });
    }

    private void wireSf() {
        final TextView[] chips = {sfType, sfCountry, sfCat, sfQuality, sfFav, sfClear};
        OnKeyListener nav = new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                boolean first = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
                if (k == KeyEvent.KEYCODE_DPAD_UP) { if (first) phoneBtn.requestFocus(); return true; }
                if (k == KeyEvent.KEYCODE_DPAD_DOWN) { if (first && !items.isEmpty()) list.requestFocus(); return true; }
                if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
                int i = java.util.Arrays.asList(chips).indexOf(v), j = i + (k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                while (j >= 0 && j < chips.length && chips[j].getVisibility() != VISIBLE) j += k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (j < 0) { if (e.getRepeatCount() == 0) rail.requestFocus(); }
                else if (j < chips.length) chips[j].requestFocus();
                return true;
            }
        };
        OnFocusChangeListener rem = new OnFocusChangeListener() { @Override public void onFocusChange(View v, boolean f) { if (f) sfLast = v; } };
        for (TextView t : chips) { t.setOnKeyListener(nav); t.setOnFocusChangeListener(rem); }
        sfType.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { sfilter.type = lk.manus.iptv.data.SearchFilter.nextType(sfilter.type); sfChanged(); } });
        sfQuality.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { sfilter.quality = lk.manus.iptv.data.SearchFilter.nextQuality(sfilter.quality); sfChanged(); } });
        sfFav.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { sfilter.favOnly = !sfilter.favOnly; sfChanged(); } });
        sfClear.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { sfilter.clear(); sfChanged(); sfType.requestFocus(); } });
        sfCountry.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { pickSf(true); } });
        sfCat.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { pickSf(false); } });
    }

    /** Country / category picker: only values that still give results with the other filters, most channels first. */
    private void pickSf(final boolean countries) {
        String q = searchEt.getText().toString();
        List<Channel> base = q.trim().isEmpty() ? host.index().all : host.index().search(q, Integer.MAX_VALUE);
        java.util.Map<String, Integer> opts = sfilter.options(base, new java.util.HashSet<>(host.store().favSet()), countries);
        final List<String> keys = new ArrayList<>(opts.keySet());
        List<String> labels = new ArrayList<>();
        labels.add(countries ? "Any country" : "Any category");
        String cur = countries ? sfilter.country : sfilter.category;
        int sel = 0;
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            String f = countries ? Ui.flag(k) : "";
            labels.add((f.isEmpty() ? "" : f + "  ") + (countries ? Ui.country(k) : k) + "  ·  " + nf.format(opts.get(k)));
            if (k.equals(cur)) sel = i + 1;
        }
        host.openMenu(countries ? "Country" : "Category", labels, sel, new Host.Pick() {
            @Override public void pick(int i) {
                String v = i <= 0 || i > keys.size() ? "" : keys.get(i - 1);
                if (countries) sfilter.country = v; else sfilter.category = v;
                sfChanged();
                (countries ? sfCountry : sfCat).requestFocus();
            }
        });
    }

    private void sfChanged() {
        updateSf();
        h.removeCallbacks(searchR);
        runSearch();
    }

    /** Chip texts follow the filter; an active chip is drawn in PAPER, an idle one MUTED; Clear shows only when something is set. */
    private void updateSf() {
        lk.manus.iptv.data.SearchFilter f = sfilter;
        sfType.setText("Type: " + lk.manus.iptv.data.SearchFilter.typeLabel(f.type));
        sfCountry.setText(f.country.isEmpty() ? "Country: Any" : (Ui.flag(f.country).isEmpty() ? "" : Ui.flag(f.country) + "  ") + Ui.country(f.country));
        sfCat.setText(f.category.isEmpty() ? "Category: Any" : f.category);
        sfQuality.setText("Quality: " + lk.manus.iptv.data.SearchFilter.qualityLabel(f.quality) + (f.quality == lk.manus.iptv.data.SearchFilter.Q_ANY ? "" : "+"));
        sfFav.setText(f.favOnly ? "★  Favourites only" : "☆  Favourites");
        sfType.setTextColor(f.type != lk.manus.iptv.data.SearchFilter.TYPE_ALL ? Ui.PAPER : Ui.MUTED);
        sfCountry.setTextColor(!f.country.isEmpty() ? Ui.PAPER : Ui.MUTED);
        sfCat.setTextColor(!f.category.isEmpty() ? Ui.PAPER : Ui.MUTED);
        sfQuality.setTextColor(f.quality != lk.manus.iptv.data.SearchFilter.Q_ANY ? Ui.PAPER : Ui.MUTED);
        sfFav.setTextColor(f.favOnly ? Ui.GOLD : Ui.MUTED);
        boolean act = f.active();
        if (!act && sfClear.hasFocus()) sfType.requestFocus();
        sfClear.setVisibility(act ? VISIBLE : GONE);
    }

    /** True for lists that are channel lists the user browses (not search results, folders, YouTube). */
    private static boolean sortable(String id) { return id != null && !id.equals("search") && !id.startsWith("src:") && !id.equals("yt") && !id.equals("settings"); }

    /** A browse list as shown: the index list in this list's sort, narrowed by the filter when it was typed on this list. */
    private List<Channel> browse(String id) {
        if (filterFor != null && !filterFor.equals(id)) resetFilter(); // another list: its filter does not follow
        List<Channel> l = host.index().sorted(id, host.store());
        return filterQ.isEmpty() ? l : lk.manus.iptv.data.ListTools.filter(l, filterQ);
    }

    private void resetFilter() {
        filterQ = "";
        filterFor = null;
        if (filterEt.getVisibility() == VISIBLE) { filterEt.setVisibility(GONE); filterChip.setVisibility(VISIBLE); }
        if (filterEt.getText().length() > 0) filterEt.setText("");
    }

    /** Rebuilds the list on screen (sort or filter changed), keeping the cursor on the same channel when it is still there. */
    private void reshape() {
        String id = memoKey();
        if (chanList == null || !sortable(id)) return;
        Channel keep = selected() != null ? selected() : listSel >= 0 && listSel < items.size() && items.get(listSel) instanceof Channel ? (Channel) items.get(listSel) : null;
        chanList = browse(id);
        items.clear();
        items.addAll(chanList);
        int at = keep == null ? 0 : chanList.indexOf(keep);
        applyItems(Math.max(0, at));
    }

    private void cycleSort() {
        String id = memoKey();
        if (!sortable(id)) return;
        int m = lk.manus.iptv.data.ListTools.next(host.store().sortMode(id));
        host.store().setSortMode(id, m);
        reshape();
        updateTools();
        sortChip.requestFocus();
        if (m == lk.manus.iptv.data.ListTools.SORT_AZ) pill("A–Z: press 2 for A B C, 3 for D E F… on your remote");
        else pill("Type a number on your remote to jump to that row");
    }

    private void openFilter() {
        filterChip.setVisibility(GONE);
        filterEt.setVisibility(VISIBLE);
        filterEt.setText(filterQ);
        filterEt.setSelection(filterEt.getText().length());
        filterEt.requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(filterEt, 0);
    }

    private void applyFilter(String q) {
        String t = q == null ? "" : q.trim();
        if (t.length() > 40) t = t.substring(0, 40);
        if (t.equals(filterQ)) return;
        filterQ = t;
        filterFor = t.isEmpty() ? null : memoKey();
        reshape();
        updateTools();
    }

    private void clearFilter() {
        boolean was = !filterQ.isEmpty();
        resetFilter();
        if (was) reshape();
        updateTools();
    }

    /** Header row for the list on screen: shown over browse lists only; chip texts follow the list's sort and filter. */
    private void updateTools() {
        String id = memoKey();
        boolean show = chanList != null && sortable(id) && (!items.isEmpty() || !filterQ.isEmpty() || filterEt.getVisibility() == VISIBLE);
        tools.setVisibility(show ? VISIBLE : GONE);
        if (!show) return;
        sortChip.setText("Sort: " + lk.manus.iptv.data.ListTools.label(host.store().sortMode(id)));
        filterChip.setText(filterQ.isEmpty() ? "Filter" : "Filter: “" + lk.manus.iptv.data.LoadText.name(filterQ) + "”  ✕");
    }

    private final Runnable jumpHideR = new Runnable() { @Override public void run() { jumpTv.setVisibility(GONE); } };
    private final Runnable numGoR = new Runnable() { @Override public void run() { numJump(); } };

    private void showJump(String t, long ms) {
        jumpTv.setText(t);
        jumpTv.setVisibility(VISIBLE);
        h.removeCallbacks(jumpHideR);
        h.postDelayed(jumpHideR, ms);
    }

    /** Names of the rows on screen (channels, countries / folders), for the letter jump. */
    private List<String> rowNames(List<?> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (Object o : rows) out.add(o instanceof Channel ? ((Channel) o).name : o instanceof CC ? ((CC) o).name : o instanceof Rail ? (((Rail) o).selectable() ? ((Rail) o).label : null) : null);
        return out;
    }

    /**
     * M2: a number key while the guide is open. In an A–Z list (and in the categories / countries menu) it is a phone-keypad
     * letter jump (2 = A, again = B…); in any other list it types the row number to jump to. False = not used here.
     */
    public boolean digit(int d) {
        if (!open || rawKeys()) return false;
        long now = SystemClock.uptimeMillis();
        int press = d == lastDigit && now - digitAt < KEY_REPEAT_MS ? digitPress + 1 : 0;
        lastDigit = d; digitPress = press; digitAt = now;
        if (rail.hasFocus() && level == 2) { // categories / countries menu: always letters
            lk.manus.iptv.data.ListTools.Jump j = lk.manus.iptv.data.ListTools.letter(rowNames(l2), d, press);
            if (j.row < 0) { showJump(j.label.isEmpty() ? String.valueOf(d) : "No " + j.label, 900); return true; }
            showJump(j.label, 900);
            railTo(j.row);
            return true;
        }
        if (!list.hasFocus() && !(tools.hasFocus() && !filterEt.hasFocus())) return false;
        if (items.isEmpty()) return true;
        String id = memoKey();
        boolean letters = sortable(id) && chanList != null ? host.store().sortMode(id) == lk.manus.iptv.data.ListTools.SORT_AZ : !(items.get(0) instanceof Channel);
        if (letters) {
            numBuf = "";
            lk.manus.iptv.data.ListTools.Jump j = lk.manus.iptv.data.ListTools.letter(rowNames(items), d, press);
            if (j.row < 0) { showJump(j.label.isEmpty() ? String.valueOf(d) : "No " + j.label, 900); return true; }
            showJump(j.label, 900);
            jumpTo(j.row);
            return true;
        }
        numBuf = (numBuf + d).replaceFirst("^0+(?=\\d)", "");
        if (numBuf.length() > 5) numBuf = numBuf.substring(numBuf.length() - 5);
        showJump(numBuf, NUM_WAIT_MS + 600);
        h.removeCallbacks(numGoR);
        long more = Long.parseLong(numBuf) * 10;
        if (more > items.size()) numJump(); // no longer number fits this list: go now
        else h.postDelayed(numGoR, NUM_WAIT_MS);
        return true;
    }

    private void numJump() {
        h.removeCallbacks(numGoR);
        String n = numBuf;
        numBuf = "";
        int r = lk.manus.iptv.data.ListTools.row(n, items.size());
        if (r < 0) { if (!n.isEmpty()) showJump("No " + n, 900); return; }
        showJump(n, 700);
        jumpTo(r);
    }

    private void jumpTo(int row) {
        if (row < 0 || row >= items.size()) return;
        if (!list.hasFocus()) list.requestFocus();
        list.setSelectionFromTop(row, Ui.u(130));
        listSel = row;
    }

    // ---- public API ----

    public boolean isOpen() { return open; }

    public void touch() { if (open) { h.removeCallbacks(idleR); h.postDelayed(idleR, IDLE_MS); } }

    public void open() {
        host.index().refresh(host.store());
        refreshFavs();
        liveUrl = host.playingUrl();
        buildRails();
        level = 1;
        rails = l1;
        showLevel(1);
        drill = null;
        String act = host.activeSection(), railId = act, kind = null, selId = null, drillId = null;
        if (!homeDone && hasHome) act = railId = "home"; // first open after the app starts
        boolean sameVisit = oUrl == null || oUrl.equals(liveUrl) || (oZap != null && oZap.equals(act)); // zapping inside the list the tile tuned into keeps the way back
        String back = oSec != null && oSec.equals(shownId) && sameVisit ? oSec : null; // Back from what that page started: the page as it was left
        oSec = null; oUrl = null; oZap = null;
        if (back != null) act = railId = back;
        homeDone = true;
        if (act.startsWith("cc:")) { kind = "countries"; selId = act; }
        else if (act.startsWith("cat:")) { kind = "cats"; selId = act; }
        else if (act.startsWith("sf:")) { kind = "srcs"; selId = "src:" + act.substring(3, Math.max(3, act.indexOf('\t'))); drillId = act; }
        if (kind != null) railId = kind.equals("srcs") ? "settings" : kind; // My Sources is a row inside Settings now
        railSel = firstSel(l1);
        for (int i = 0; i < l1.size(); i++) if (l1.get(i).id.equals(railId)) railSel = i;
        railAd.notifyDataSetChanged();
        railTo(railSel);
        shownId = back;
        if (kind != null) enterLevel2(kind, selId, false);
        else showSection(rails.get(railSel));
        if (back != null) showOrigin();
        if (drillId != null && drillList(drillId) != null) enterDrill(drillId);
        for (int i = 0; i < items.size(); i++) {
            Object o = items.get(i);
            if (o instanceof Channel && ((Channel) o).url.equals(liveUrl)) { if (i != listSel) selectList(i); break; }
        }
        open = true;
        h.removeCallbacks(nowR);
        h.postDelayed(nowR, 60000 - System.currentTimeMillis() % 60000 + 200);
        setVisibility(VISIBLE);
        content.animate().cancel();
        scrim.animate().cancel();
        if (Ui.anim()) {
            content.setTranslationX(-Ui.u(48));
            content.setAlpha(0f);
            content.animate().translationX(0).alpha(1f).setDuration(200).setInterpolator(Ui.DEC).start();
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(200).start();
        } else { content.setTranslationX(0); content.setAlpha(1f); scrim.setAlpha(1f); }
        focusContent();
        if ("search".equals(shownId) && drill == null && listSel >= 0 && listSel < items.size() && items.get(listSel) instanceof Channel && ((Channel) items.get(listSel)).url.equals(liveUrl)) list.requestFocus(); // back from a search result: the cursor is on it, not in the text box
        touch();
    }

    /** Closes with the slide-out animation and tells the host (restore = put back the channel that played before). */
    public void dismiss(boolean restore) {
        if (!open) return;
        hide();
        host.onClose(restore);
    }

    public void hide() {
        if (!open) return;
        saveTop();
        open = false;
        detailsOnly = false;
        dlg.setVisibility(GONE);
        kmodal.setVisibility(GONE);
        details.hide();
        which.hide();
        phone.hide();
        grid.hide();
        sgrid.hide();
        home.hide();
        library.hide();
        if (seriesLibrary != null) seriesLibrary.hide();
        if (libDetails != null) libDetails.hide();
        if (seriesDetails != null) seriesDetails.hide();
        settings.hide();
        hideIme();
        h.removeCallbacks(searchR);
        h.removeCallbacks(nowR); h.removeCallbacks(speedR); h.removeCallbacks(railDwell); h.removeCallbacks(cardR); h.removeCallbacks(liveR); stopMini(); h.removeCallbacks(idleR);
        hideCard();
        frame.hideNow();
        if (!Ui.anim()) { content.animate().cancel(); scrim.animate().cancel(); setVisibility(GONE); return; }
        content.animate().translationX(-Ui.u(48)).alpha(0f).setDuration(160).setInterpolator(Ui.DEC).start();
        scrim.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
            @Override public void run() { if (!open) setVisibility(GONE); }
        }).start();
    }

    public void hideNow() {
        saveTop();
        open = false;
        detailsOnly = false;
        dlg.setVisibility(GONE);
        kmodal.setVisibility(GONE);
        details.hide();
        which.hide();
        phone.hide();
        grid.hide();
        sgrid.hide();
        home.hide();
        library.hide();
        if (seriesLibrary != null) seriesLibrary.hide();
        if (libDetails != null) libDetails.hide();
        if (seriesDetails != null) seriesDetails.hide();
        settings.hide();
        frame.hideNow();
        hideIme();
        h.removeCallbacks(nowR); h.removeCallbacks(speedR); h.removeCallbacks(railDwell); h.removeCallbacks(cardR); h.removeCallbacks(liveR); stopMini(); h.removeCallbacks(idleR);
        hideCard();
        content.animate().cancel();
        scrim.animate().cancel();
        setVisibility(GONE);
    }

    /** Short OK. */
    public void activate() {
        if (details.isUp() || which.isUp() || phone.isUp() || (libDetails != null && libDetails.isUp())) return;
        if (seriesDetails != null && seriesDetails.isUp()) {
            seriesDetails.activate();
            return;
        }
        if (phoneBtn.hasFocus()) { openPhone(); return; }
        if (!numBuf.isEmpty()) { numJump(); return; } // M2: OK right after typing a row number goes there now
        if (sortChip.hasFocus()) { cycleSort(); return; }
        if (sfScroll.hasFocus()) { View f = sfScroll.findFocus(); if (f != null) f.performClick(); return; }
        if (filterChip.hasFocus()) { filterChip.performClick(); return; }
        if (grid.getVisibility() == VISIBLE && grid.hasFocus()) { if (grid.focused() != null) openFilm(grid.focused()); return; }
        if (sgrid.getVisibility() == VISIBLE && sgrid.hasFocus()) { if (sgrid.focused() != null) openSeries(sgrid.focused()); return; }
        if (library.getVisibility() == VISIBLE && library.hasFocus()) { library.activate(); return; }
        if (seriesLibrary.getVisibility() == VISIBLE && seriesLibrary.hasFocus()) { seriesLibrary.activate(); return; }
        if (home.getVisibility() == VISIBLE && home.hasFocus()) { home.activate(); return; }
        if (!list.hasFocus()) {
            Rail r = railSel < rails.size() ? rails.get(railSel) : null;
            if (level == 1 && r != null && r.nav) enterLevel2(r.id, null, true); else focusList();
            return;
        }
        if (listSel < 0 || listSel >= items.size()) return;
        Object o = items.get(listSel);
        if (o instanceof LibSearchRow) {
            openLibSearch(((LibSearchRow) o).query);
            return;
        }
        if (o instanceof CC) {
            String cid = ((CC) o).id;
            if (cid.startsWith("yt:")) openYouTube(cid.substring(3)); else enterDrill(cid);
            return;
        }
        String id = drill != null ? drill : shownId;
        String name = "search".equals(id) ? (lastQuery == null || lastQuery.trim().isEmpty() ? "Search: filtered" : "Search: " + lastQuery) : host.index().title(id);
        host.onCommit(id, name, chanList, listSel);
        hide();
    }

    /** OK on a poster: tune that channel (the Movies list, so Up / Down zap through the films) and close the guide. */
    private void openFilm(Films.Entry e) {
        List<Channel> src = host.index().sorted("cat:Movies", host.store()); // M2: the order the guide shows, so row N is channel N
        int pos = e.pos < src.size() && src.get(e.pos) == e.ch ? e.pos : src.indexOf(e.ch);
        if (pos < 0) return;
        markOrigin(e.ch.url, "cat:Movies");
        host.onCommit("cat:Movies", "Movies", src, pos);
        hide();
    }

    /** OK on a series: tune that channel (inside the full channel list) and close the guide; Back from playback returns to this page with the same tile lit. */
    private void openSeries(SeriesNow.Entry e) {
        List<Channel> src = host.index().all; // the list Series on now was built from (the ranked "all" list can drop a channel)
        int pos = src.indexOf(e.ch);
        if (pos < 0) return;
        markOrigin(e.ch.url, "all");
        host.onCommit("all", host.index().title("all"), src, pos);
        hide();
    }

    /** The same film on another channel (the "Also on" list): tune it in the Movies list. */
    private void openFilmCh(Channel ch) {
        List<Channel> src = host.index().sorted("cat:Movies", host.store()); // M2: the order the guide shows, so row N is channel N
        int pos = src.indexOf(ch);
        if (pos < 0) return;
        markOrigin(ch.url, "cat:Movies");
        host.onCommit("cat:Movies", "Movies", src, pos);
        hide();
    }

    private void openSeriesCh(Channel ch) {
        List<Channel> src = host.index().all;
        int pos = src.indexOf(ch);
        if (pos < 0) return;
        markOrigin(ch.url, "all");
        host.onCommit("all", host.index().title("all"), src, pos);
        hide();
    }

    /** Long OK on a film tile that stands for several channels: the "Also on" card. False = one channel only. */
    private boolean alsoFilm(Films.Entry e) {
        if (e == null || e.others.isEmpty()) return false;
        List<Channel> l = new ArrayList<>();
        for (Films.Entry o : e.others) l.add(o.ch);
        showAlso(e.matched() ? e.film.title : e.clean.title, l, true);
        return true;
    }

    private boolean alsoSeries(SeriesNow.Entry e) {
        if (e == null || e.others.isEmpty()) return false;
        List<Channel> l = new ArrayList<>();
        for (SeriesNow.Entry o : e.others) l.add(o.ch);
        showAlso(e.title(), l, false);
        return true;
    }

    /** T110e: small card listing the other channels airing this; OK on a row tunes it, Back closes. Row: name, quality, working / untested / down. */
    private void showAlso(String title, List<Channel> chans, final boolean film) {
        dlgPrev = findFocus();
        dlgAct = null;
        dlgActMid = null;
        alsoTitle.setText(title);
        alsoRows.removeAllViews();
        final List<View> rows = new ArrayList<>();
        Health hl = Health.get(getContext());
        for (final Channel ch : chans) {
            int st = hl.status(ch.url);
            String f = Ui.flag(ch.country);
            final LinearLayout b = new LinearLayout(getContext());
            b.setOrientation(LinearLayout.HORIZONTAL);
            b.setGravity(Gravity.CENTER_VERTICAL);
            TextView nm = Ui.tv(getContext(), Ui.BODY, st == Health.DEAD ? Ui.MUTED : Ui.PAPER, true);
            nm.setText((f.isEmpty() ? "" : f + "  ") + ch.name + (ch.quality.isEmpty() ? "" : "  ·  " + ch.quality));
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView sv = Ui.tv(getContext(), Ui.CAPTION, st == Health.ALIVE ? Ui.PAPER : Ui.MUTED, false);
            sv.setText(st == Health.ALIVE ? "working" : st == Health.LIKELY ? "likely up" : st == Health.DEAD ? "down" : "untested");
            sv.setSingleLine(true);
            sv.setPadding(Ui.u(12), 0, 0, 0);
            b.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
            b.addView(sv, new LinearLayout.LayoutParams(-2, -2));
            b.setPadding(Ui.u(14), 0, Ui.u(14), 0);
            b.setFocusable(true);
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_focused}, Ui.ring());
            sl.addState(new int[]{}, Ui.rect(Ui.INK, 14));
            b.setBackground(sl);
            b.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { closeDlg(); if (film) openFilmCh(ch); else openSeriesCh(ch); } });
            b.setOnKeyListener(new OnKeyListener() { // keep focus inside the card
                @Override public boolean onKey(View v, int k, KeyEvent ev) {
                    if (k != KeyEvent.KEYCODE_DPAD_UP && k != KeyEvent.KEYCODE_DPAD_DOWN && k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                    if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                        int i = rows.indexOf(v), j = k == KeyEvent.KEYCODE_DPAD_UP ? i - 1 : k == KeyEvent.KEYCODE_DPAD_DOWN ? i + 1 : i;
                        if (j != i && j >= 0 && j < rows.size()) rows.get(j).requestFocus();
                    }
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.u(48));
            if (!rows.isEmpty()) lp.topMargin = Ui.u(8);
            alsoRows.addView(b, lp);
            rows.add(b);
        }
        LinearLayout.LayoutParams sp = (LinearLayout.LayoutParams) alsoScroll.getLayoutParams();
        sp.height = Math.min(rows.size(), 5) * Ui.u(56) - Ui.u(8) + Ui.u(4);
        alsoScroll.setLayoutParams(sp);
        dlg.show(alsoBox);
        rows.get(0).requestFocus();
    }

    private void openLibSearch(String q) {
        col2.setVisibility(GONE);
        grid.hide();
        sgrid.hide();
        home.hide();
        settings.hide();
        seriesLibrary.hide();
        library.setVisibility(VISIBLE);
        library.loadSearch(q);
        library.focusTile();
    }

    /** Opens a level-1 section of the rail by id (Home's "All films" tile) and moves into it. */
    public void openSection(String id) {
        if (!open) {
            if (host.index() == null) return;
            open();
        }
        for (int i = 0; i < l1.size(); i++) if (l1.get(i).id.equals(id)) {
            railSel = i;
            railAd.notifyDataSetChanged();
            railTo(i);
            showSection(l1.get(i));
            focusContent();
            return;
        }
    }

    private void showDetails(Films.Entry e) {
        if (e == null) return;
        List<lk.manus.iptv.meta.MetaStore.Choice> l = e.matched() ? null : Films.choices(getContext(), e); // a plain tile whose title two films carry: "Which film?"
        if (l != null && l.isEmpty()) return; // a plain tile with nothing to choose from: INFO does nothing, as before
        hideCard();
        content.setVisibility(INVISIBLE); // the page is full-screen over the dimmed live picture, not over the guide
        if (l == null) details.show(e); else which.show(e, l);
    }

    /** Playback starts from the page on screen: Back (or the guide key) reopens that page with the same tile lit. */
    private void markOrigin(String url, String zapId) {
        oUrl = url;
        oZap = zapId;
        oSec = home.getVisibility() == VISIBLE ? "home" : grid.getVisibility() == VISIBLE ? "films" : sgrid.getVisibility() == VISIBLE ? "seriesnow" : null;
        if (oSec != null && !oSec.equals(shownId)) oSec = null;
    }

    /** A film started straight from Home (no Details page): its Back reopens Home. */
    public boolean hasFilmOrigin() { return oSec != null && oUrl == null; }

    /** Puts the page named by shownId back on screen as it was left (list, tile and row kept) and focuses its tile. */
    private void showOrigin() {
        content.setVisibility(VISIBLE);
        if ("home".equals(shownId)) { home.setVisibility(VISIBLE); home.tick(); home.focusTile(); }
        else if ("films".equals(shownId)) { grid.setVisibility(VISIBLE); grid.focusTile(); }
        else if ("seriesnow".equals(shownId)) { sgrid.setVisibility(VISIBLE); sgrid.focusTile(); }
        else if ("series".equals(shownId)) { seriesLibrary.setVisibility(VISIBLE); seriesLibrary.focusTile(); }
        else { library.setVisibility(VISIBLE); library.focusTile(); }
    }

    /** The library-style page on screen right now (behind a Details page): where its Back must return after a film. */
    private String pageBehind() {
        if (home.getVisibility() == VISIBLE) return "home";
        if (library.getVisibility() == VISIBLE) return library.isSearchMode() ? "libsearch" : "lib";
        if (seriesLibrary != null && seriesLibrary.getVisibility() == VISIBLE) return "series";
        return null;
    }

    /** After hideNow(): makes the page recorded in detFrom visible again (content itself stays hidden until Details closes). Pages keep their list, scroll and tile while hidden. */
    private void restoreBehind() {
        String from = detFrom;
        detFrom = null; // used once: playing again from this Details page records the (now visible) page afresh
        if (from == null) from = "series".equals(shownId) ? "series" : "lib".equals(shownId) ? "lib" : hasHome ? "home" : "lib"; // e.g. a film reopened from a notification
        col2.setVisibility(GONE);
        searchEt.setVisibility(GONE);
        phoneBtn.setVisibility(GONE);
        sfScroll.setVisibility(GONE);
        if ("home".equals(from)) { home.setVisibility(VISIBLE); home.tick(); }
        else if ("series".equals(from) && seriesLibrary != null) seriesLibrary.setVisibility(VISIBLE);
        else library.setVisibility(VISIBLE); // 'lib' and 'libsearch': the library keeps its search mode while hidden
    }

    public void reopenLibDetails(lk.manus.iptv.data.Library.Item item) {
        if (item == null) return;
        if ("episode".equals(item.kind)) {
            Db db = Db.get();
            if (db != null) {
                lk.manus.iptv.data.Library.Item seriesItem = lk.manus.iptv.data.Library.get(db, item.parentId);
                if (seriesItem != null) {
                    reopenSeriesDetails(seriesItem);
                    return;
                }
            }
        }
        if ("series".equals(item.kind)) {
            reopenSeriesDetails(item);
            return;
        }
        hideNow(); // no stale list page behind the details page
        restoreBehind(); // ...but the page it was opened from goes back underneath, so Back on Details lands on the tile, not on a channel list
        detailsOnly = false;
        open = true;
        hideCard();
        content.animate().cancel();
        scrim.animate().cancel();
        scrim.setAlpha(0f);
        content.setVisibility(INVISIBLE);
        setVisibility(VISIBLE);
        if (libDetails != null) libDetails.show(item);
        touch();
    }

    public void reopenSeriesDetails(lk.manus.iptv.data.Library.Item item) {
        if (item == null) return;
        hideNow(); // no stale list page behind the details page
        restoreBehind(); // ...but the page it was opened from goes back underneath, so Back on Details lands on the tile, not on a channel list
        detailsOnly = false;
        open = true;
        hideCard();
        content.animate().cancel();
        scrim.animate().cancel();
        scrim.setAlpha(0f);
        content.setVisibility(INVISIBLE);
        setVisibility(VISIBLE);
        if (seriesDetails != null) seriesDetails.show(item);
        touch();
    }

    private void remindersDone() { onRemindersChanged(); host.remindersChanged(); }

    /** A reminder was set, cancelled or used up: the Details button and Home's facts line follow. */
    public void onRemindersChanged() { details.refresh(); home.tick(); }

    /** True while a dialog, the search box or Settings has focus: OK there keeps its own meaning even if a reminder pill shows. */
    public boolean ownsOk() { return open && (dlg.getVisibility() == VISIBLE || searchEt.hasFocus() || filterEt.hasFocus() || tools.hasFocus() || sfScroll.hasFocus() || (settings.getVisibility() == VISIBLE && settings.hasFocus())); }

    private void closeDetails() { details.hide(); which.hide(); closePhone(); content.setVisibility(VISIBLE); }

    /** OK on "Type on your phone": the QR page, over the live picture. */
    private void openPhone() {
        content.setVisibility(INVISIBLE);
        phone.show();
    }

    private void closePhone() {
        if (!phone.isUp()) return;
        phone.hide();
        content.setVisibility(VISIBLE);
        phoneBtn.requestFocus();
    }

    /** A film page is done with and the film changed or left: back to the lists, or, when the page came from the banner, back to live TV. */
    private void leaveDetails() {
        closeDetails();
        host.filmChanged();
        if (detailsOnly) { dismiss(true); return; }
        grid.redraw();
        home.redraw();
    }

    /** INFO on the banner: Details for the film that is on now, over the live picture, without the lists. */
    public void openDetails(Films.Entry e) {
        if (open || e == null || !e.matched()) return;
        detailsOnly = true;
        open = true;
        hideCard();
        content.animate().cancel();
        scrim.animate().cancel();
        scrim.setAlpha(0f); // the page dims the picture itself
        content.setVisibility(INVISIBLE);
        setVisibility(VISIBLE);
        details.show(e);
        touch();
    }

    /** INFO in the guide: Details for the focused poster. */
    public void onInfo() {
        if (grid.getVisibility() == VISIBLE && grid.hasFocus()) showDetails(grid.focused());
        else if (home.getVisibility() == VISIBLE && home.hasFocus()) showDetails(home.focusedFilm());
    }

    /** True while a text box or the settings screen has focus: the activity then hands every key to the views. */
    public boolean rawKeys() { return open && (dlg.getVisibility() == VISIBLE || details.isUp() || (libDetails != null && libDetails.isUp()) || (seriesDetails != null && seriesDetails.isUp()) || which.isUp() || phone.isUp() || searchEt.hasFocus() || filterEt.hasFocus() || (settings.getVisibility() == VISIBLE && settings.hasFocus())); }

    /** Back: dialog, keyboard, search text, settings page, folder, level 2, then close. Never leaves the app. */
    public void onBack() {
        if (dlg.getVisibility() == VISIBLE) { closeDlg(); return; }
        if (phone.isUp()) { closePhone(); return; }
        if (detailsOnly && (details.isUp() || which.isUp() || (libDetails != null && libDetails.isUp()) || (seriesDetails != null && seriesDetails.isUp()))) {
            if (libDetails != null) libDetails.hide();
            if (seriesDetails != null) seriesDetails.hide();
            dismiss(true);
            return;
        }
        if (details.isUp() || which.isUp()) { closeDetails(); if (home.getVisibility() == VISIBLE) { home.tick(); home.focusTile(); } else grid.focusTile(); return; }
        if (libDetails != null && libDetails.isUp()) { libDetails.hide(); showOrigin(); return; }
        if (seriesDetails != null && seriesDetails.isUp()) {
            seriesDetails.hide();
            showOrigin();
            return;
        }
        if (library != null && library.getVisibility() == VISIBLE && library.hasFocus()) {
            if (library.isSearchMode()) {
                library.hide();
                shownId = null; openSection("search"); // rebuild the results page (it was replaced by the library grid)
                return;
            }
            rail.requestFocus();
            return;
        }
        if (seriesLibrary != null && seriesLibrary.getVisibility() == VISIBLE && seriesLibrary.hasFocus()) {
            if (seriesLibrary.isSearchMode()) {
                seriesLibrary.hide();
                shownId = null; openSection("search"); // rebuild the results page (it was replaced by the library grid)
                return;
            }
            rail.requestFocus();
            return;
        }
        if (imeVisible()) { hideIme(); return; }
        if (searchEt.hasFocus() && searchEt.getText().length() > 0) { searchEt.setText(""); return; }
        if (filterEt.hasFocus()) { if (filterEt.getText().length() > 0) filterEt.setText(""); else { clearFilter(); if (!items.isEmpty()) list.requestFocus(); else filterChip.requestFocus(); } return; } // M2: Back empties the filter box, then leaves it
        if (!filterQ.isEmpty() && (list.hasFocus() || tools.hasFocus())) { clearFilter(); return; } // M2: Back on a filtered list shows the whole list again (cursor kept)
        if (tools.hasFocus()) { if (!items.isEmpty()) list.requestFocus(); else rail.requestFocus(); return; }
        if (settings.getVisibility() == VISIBLE && settings.hasFocus()) { if (!settings.back()) rail.requestFocus(); return; }
        if (drill != null) { saveTop(); drill = null; shownId = null; showSection(rails.get(railSel)); return; }
        if (!rail.hasFocus()) { if (level == 2 || "search".equals(shownId) || "lib".equals(shownId) || "series".equals(shownId)) rail.requestFocus(); else dismiss(true); return; }
        if (level == 2) { exitLevel2(true); return; }
        if (lk.manus.iptv.Config.PLAY) { // Play TV quality: Back from the top level must eventually reach Home
            confirm("Exit Manus TV?", "", "Yes", "No", new Runnable() { @Override public void run() { host.exitApp(); } });
            return;
        }
        dismiss(true);
    }

    /** Long OK / Menu on a country or category row: offer to hide it. False = not applicable (caller favourites instead). */
    public boolean onLongOk() {
        if (open && grid.getVisibility() == VISIBLE && grid.hasFocus()) { if (!alsoFilm(grid.focused())) showDetails(grid.focused()); return true; }
        if (open && sgrid.getVisibility() == VISIBLE && sgrid.hasFocus() && alsoSeries(sgrid.focused())) return true;
        if (open && home.getVisibility() == VISIBLE && home.hasFocus()) {
            if (home.onLongOk()) return true;
            if (alsoFilm(home.focusedFilm()) || alsoSeries(home.focusedSeries())) return true;
            showDetails(home.focusedFilm());
            return true;
        }
        if (!open || !rail.hasFocus() || level != 2 || !(l2Kind.equals("countries") || l2Kind.equals("cats"))) return false;
        if (railSel < rails.size() && !rails.get(railSel).divider) {
            final Rail r = rails.get(railSel);
            confirm("Hide this group?", r.label, "Hide", "Cancel", new Runnable() {
                @Override public void run() {
                    host.store().hide(r.id);
                    int keep = railSel;
                    buildL2();
                    railSel = Math.min(keep, l2.size() - 1);
                    if (railSel >= 0 && l2.get(railSel).divider) railSel = Math.max(0, railSel - 1);
                    railAd.notifyDataSetChanged();
                    rail.setSelection(railSel);
                    shownId = null;
                    if (!l2.isEmpty()) showSection(l2.get(railSel));
                    pill("Hidden. Unhide in Settings > For You");
                }
            });
        }
        return true;
    }

    /** CH+/CH- inside the guide: move the channel list a page (8 rows). */
    public void page(int d) {
        if (!list.hasFocus() || items.isEmpty()) return;
        int p = Math.max(0, Math.min(items.size() - 1, listSel + 8 * d));
        selectList(p);
    }

    // ---- two levels ----

    private void showLevel(int lv) {
        railHead.setVisibility(lv == 2 ? VISIBLE : GONE);
        refreshBar();
        if (lv == 2 && Ui.anim() && open) { // level 2 replaces the rail: it slides in from the left
            railCol.setTranslationX(-Ui.u(24));
            railCol.setAlpha(0f);
            railCol.animate().translationX(0).alpha(1f).setDuration(180).setInterpolator(Ui.DEC).start();
        }
    }

    private void enterLevel2(String kind, String selId, boolean focus) {
        if (level == 1) sel1 = railSel;
        level = 2;
        l2Kind = kind;
        buildL2();
        rails = l2;
        railHead.setText("‹ " + host.index().title(kind));
        showLevel(2);
        int pos = -1;
        if (selId != null) for (int i = 0; i < l2.size(); i++) if (l2.get(i).id.equals(selId)) pos = i;
        if (pos < 0 && memo.containsKey("L2:" + kind)) pos = memo.get("L2:" + kind);
        if (pos < 0 || pos >= l2.size() || l2.get(pos).divider) { pos = 0; while (pos < l2.size() - 1 && l2.get(pos).divider) pos++; }
        railSel = pos;
        shownId = null;
        railAd.notifyDataSetChanged();
        rail.setSelection(pos);
        if (!l2.isEmpty()) showSection(l2.get(pos));
        else { items.clear(); chanList = null; applyItems(0); }
        if (focus) rail.requestFocus();
    }

    private void exitLevel2(boolean focus) {
        level = 1;
        rails = l1;
        showLevel(1);
        railSel = Math.max(0, Math.min(sel1, l1.size() - 1));
        shownId = null;
        drill = null;
        railAd.notifyDataSetChanged();
        railTo(railSel);
        showSection(l1.get(railSel));
        if (focus) rail.requestFocus();
    }

    /** Level-2 rows for l2Kind: countries = pinned, divider, the rest by channel count; categories; sources. Hidden groups left out. */
    private void buildL2() {
        Index ix = host.index();
        Store st = host.store();
        Set<String> hid = st.hidden();
        l2.clear();
        if (l2Kind.equals("countries")) {
            Set<String> pinned = new java.util.LinkedHashSet<>();
            for (String c : st.fyCountries()) if (ix.countries.containsKey(c) && !hid.contains("cc:" + c)) pinned.add(c);
            for (String c : pinned) l2.add(countryRail(ix, st, c));
            if (!pinned.isEmpty()) l2.add(Rail.divider());
            for (String c : ix.countryOrder) if (!pinned.contains(c) && !hid.contains("cc:" + c)) l2.add(countryRail(ix, st, c));
        } else if (l2Kind.equals("cats")) {
            for (String c : ix.catOrder) if (!hid.contains("cat:" + c)) l2.add(new Rail("cat:" + c, c, ix.list("cat:" + c, st).size()));
        } else {
            for (Sources.Source sr : ix.sources) l2.add(new Rail("src:" + sr.id, sr.name, ix.folderOrder(sr.id).size()));
        }
    }

    private Rail countryRail(Index ix, Store st, String c) {
        return new Rail("cc:" + c, Ui.flag(c) + "  " + Ui.country(c), ix.list("cc:" + c, st).size());
    }

    /** Search key / voice search: open the guide on the Search section with the keyboard ready. */
    public void openSearch() {
        if (!open) open();
        if (level == 2) { rails = l1; level = 1; showLevel(1); }
        for (int i = 0; i < rails.size(); i++) if (rails.get(i).id.equals("search")) { railSel = i; break; }
        railAd.notifyDataSetChanged();
        railTo(railSel);
        showSection(rails.get(railSel));
        searchEt.requestFocus();
    }

    /** Green key: open the guide on the Categories list. */
    public void openCategories() {
        if (!open) open();
        if (level == 2) { rails = l1; level = 1; showLevel(1); }
        for (int i = 0; i < l1.size(); i++) if (l1.get(i).id.equals("cats")) { railSel = i; break; }
        railAd.notifyDataSetChanged();
        railTo(railSel);
        shownId = null;
        enterLevel2("cats", null, true);
    }

    /** Settings key: open the guide on Settings. */
    public void openSettings() {
        if (!open) open();
        if (level == 2) { rails = l1; level = 1; showLevel(1); }
        for (int i = 0; i < rails.size(); i++) if (rails.get(i).id.equals("settings")) { railSel = i; break; }
        railAd.notifyDataSetChanged();
        railTo(railSel);
        shownId = null;
        showSection(rails.get(railSel));
        focusContent();
    }

    /** A version choice changed the row of some group: redraw the list on screen from the re-ranked index. */
    public void onVersionsChanged() {
        if (!open) return;
        String id = drill != null ? drill : shownId;
        if (chanList != null && id != null && !"search".equals(id) && !id.startsWith("src:")) {
            chanList = browse(id);
            items.clear();
            items.addAll(chanList);
            applyItems(listSel);
        } else listAd.notifyDataSetChanged();
    }

    /** A remote-keys modal is up: the activity routes every key to {@link #modalKey}. */
    public boolean modalUp() { return open && kmodal.isUp(); }
    public boolean modalKey(KeyEvent e) { return kmodal.key(e); }

    /** Playlists were reloaded (source toggled, refreshed…): rebuild the rail and redraw what is shown. */
    public void onIndexChanged() {
        if (!open) return;
        String keep = railSel < rails.size() ? rails.get(railSel).id : "foryou";
        buildRails();
        if (level == 2) buildL2();
        railSel = firstSel(rails);
        for (int i = 0; i < rails.size(); i++) if (rails.get(i).id.equals(keep)) railSel = i;
        railAd.notifyDataSetChanged();
        railTo(railSel); // the ListView keeps the old index, the rows moved
        settings.refresh();
        if (!keep.equals("settings") && !keep.equals("search") && drill == null) { shownId = null; showSection(rails.get(railSel)); }
    }

    /** Rail counts follow the channels that are visible now (after "Show unavailable" flips). */
    private void refreshCounts() {
        Index ix = host.index();
        for (Rail r : l1) if (r.id.equals("foryou") || r.id.equals("lk")) r.count = ix.list(r.id, host.store()).size();
        railAd.notifyDataSetChanged();
    }

    /** For You picker closed with changes: recompute the list off the UI thread. */
    private void forYouChanged() {
        final Index ix = host.index();
        final Store st = host.store();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Channel> fy = Index.forYou(ix.all, st);
                ix.forYou = fy;
                h.post(new Runnable() { @Override public void run() { refreshCounts(); } });
            }
        }, "foryou").start();
    }

    private boolean imeVisible() {
        if (android.os.Build.VERSION.SDK_INT < 30) return false;
        android.view.WindowInsets wi = getRootWindowInsets();
        return wi != null && wi.isVisible(android.view.WindowInsets.Type.ime());
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    /** Channel under the cursor, or null (rail focused / country row). */
    public Channel selected() {
        if (!list.hasFocus() || listSel < 0 || listSel >= items.size()) return null;
        Object o = items.get(listSel);
        return o instanceof Channel ? (Channel) o : null;
    }

    public void onFavChanged() {
        refreshFavs();
        for (Rail r : l1) if (r.id.equals("fav")) r.count = host.index().list("fav", host.store()).size();
        railAd.notifyDataSetChanged();
        if ("fav".equals(shownId) && drill == null) {
            chanList = browse("fav");
            items.clear();
            items.addAll(chanList);
            applyItems(Math.min(listSel, Math.max(0, items.size() - 1)));
        } else listAd.notifyDataSetChanged();
    }

    /** True when the cursor is on a channel row of the Favourites list: long OK opens the reorder menu there. */
    public boolean inFavourites() { return open && list.hasFocus() && drill == null && "fav".equals(shownId) && selected() != null; }

    /** Favourites menu: how 0 = up, 1 = down, 2 = to the top. The list redraws at once with focus and screen position kept on the moved row. */
    public void moveFav(Channel c, int how) {
        saveTop();
        Integer top = memoTop.get("fav");
        host.store().moveFav(c.url, how);
        if (host.store().sortMode("fav") != lk.manus.iptv.data.ListTools.SORT_NUM || !filterQ.isEmpty()) { // moving only means something in your own order
            host.store().setSortMode("fav", lk.manus.iptv.data.ListTools.SORT_NUM);
            resetFilter();
            pill("Favourites are back in your own order");
        }
        chanList = browse("fav");
        items.clear();
        items.addAll(chanList);
        nextTop = top;
        applyItems(Place.find(chanList, c.url, null));
    }

    public void setPreviewFailed() {
        cStatus.setVisibility(VISIBLE);
        if (card.getAlpha() < 1f) showCard();
    }

    // ---- sections ----

    private void refreshFavs() { favSet.clear(); favSet.addAll(host.store().favs()); }

    private void buildRails() {
        Index ix = host.index();
        Store s = host.store();
        l1.clear();
        int nf = Films.enabled(getContext()) && !ix.list("cat:Movies", s).isEmpty() ? 1 : 0; // only with a movie-database key
        boolean myList = !Config.PLAY && !Db.get().myListIds().isEmpty(); // T111: shown only while My list has items; it opens Home on that row
        hasHome = nf > 0 || myList || !ix.list("fav", s).isEmpty() || !ix.list("recent", s).isEmpty();
        l1.add(Rail.header("Watch"));
        if (hasHome) l1.add(new Rail("home", "Home", -1).ic(R.drawable.ic_menu_home));
        l1.add(new Rail("fav", "Favourites", ix.list("fav", s).size()).ic(R.drawable.ic_menu_star));
        l1.add(new Rail("recent", "Recent", ix.list("recent", s).size()).ic(R.drawable.ic_menu_recent));
        if (myList) l1.add(new Rail("mylist", "My list", -1).ic(R.drawable.ic_menu_mylist));
        l1.add(Rail.header("Discover"));
        if (nf > 0) l1.add(new Rail("films", "Movies on now", -1).ic(R.drawable.ic_menu_films));
        if (Films.enabled(getContext()) && SeriesNow.any(getContext(), ix, s)) l1.add(new Rail("seriesnow", "Series on now", -1).ic(R.drawable.ic_menu_seriesnow));
        if (!Config.PLAY && lk.manus.iptv.data.Library.any(Db.get())) l1.add(new Rail("lib", "Movies", -1).ic(R.drawable.ic_menu_video));
        if (!Config.PLAY && lk.manus.iptv.data.Library.any(Db.get(), "series")) l1.add(new Rail("series", "Series", -1).ic(R.drawable.ic_menu_layers));
        l1.add(new Rail("foryou", "For You", ix.list("foryou", s).size()).ic(R.drawable.ic_menu_foryou));
        l1.add(Rail.header("Browse"));
        l1.add(new Rail("lk", "Sri Lanka", ix.list("lk", s).size()).ic(R.drawable.ic_menu_pin));
        Set<String> hid = s.hidden();
        int nc = 0, nk = 0;
        for (String c : ix.countryOrder) if (!hid.contains("cc:" + c)) nc++;
        for (String c : ix.catOrder) if (!hid.contains("cat:" + c)) nk++;
        l1.add(Rail.nav("countries", "Countries", nc).ic(R.drawable.ic_menu_globe));
        l1.add(Rail.nav("cats", "Categories", nk).ic(R.drawable.ic_menu_grid));
        if (!(lk.manus.iptv.Config.DEBUG && Ui.demoBlur)) l1.add(new Rail("yt", "YouTube Live", YouTubeLive.ALL.size()).ic(R.drawable.ic_menu_live)); // demo recordings hide this rail
        l1.add(new Rail("search", "Search", -1).ic(R.drawable.ic_menu_search).pin());
        l1.add(new Rail("settings", "Settings", -1).ic(R.drawable.ic_menu_settings).pin());
    }

    /** Selects a menu row keeping the group labels above it in view when the list fits; clamps so the row is never pushed below the bottom. */
    private void railTo(int pos) {
        if (rails != l1 || pos < 0 || pos >= l1.size()) { rail.setSelection(pos); return; }
        int y = 0;
        for (int i = 0; i < pos; i++) y += Ui.u(l1.get(i).divider ? 18 : l1.get(i).header ? GROUP_H : ROW_H);
        int h = rail.getHeight() > 0 ? rail.getHeight() : Ui.safeH() - Ui.u(LOGO_H + 8);
        rail.setSelectionFromTop(pos, Math.min(y, Math.max(0, h - Ui.u(ROW_H) - Ui.u(12))));
    }

    /** First row of a list that can take the cursor (the menu opens with group labels above it). */
    private static int firstSel(List<Rail> l) {
        for (int i = 0; i < l.size(); i++) if (l.get(i).selectable()) return i;
        return 0;
    }

    private String memoKey() { return drill != null ? drill : String.valueOf(shownId); }

    /** Channels of a drill id ("cc:xx" or "sf:id TAB folder"), or null if it no longer exists. */
    private List<Channel> drillList(String id) {
        List<Channel> l = host.index().list(id, host.store());
        return l.isEmpty() ? null : l;
    }

    private void showSection(Rail r) {
        h.removeCallbacks(railDwell);
        if (r.id.equals(shownId)) return;
        if (r.id.equals("mylist") && "home".equals(shownId) && home.getVisibility() == VISIBLE) return; // T111: My list is Home, cursor on its row
        saveTop();
        host.index().refresh(host.store()); // new ranking is picked up here, never under the user's cursor
        shownId = r.id.equals("mylist") ? "home" : r.id;
        drill = null;
        boolean isSettings = r.id.equals("settings"), isSearch = r.id.equals("search");
        searchEt.setVisibility(isSearch ? VISIBLE : GONE);
        phoneBtn.setVisibility(isSearch ? VISIBLE : GONE);
        sfScroll.setVisibility(isSearch ? VISIBLE : GONE);
        if (isSearch) updateSf();
        settings.setVisibility(GONE);
        boolean isFilms = r.id.equals("films"), isHome = r.id.equals("home") || r.id.equals("mylist"), isLib = r.id.equals("lib"), isSeries = r.id.equals("series"), isSN = r.id.equals("seriesnow");
        col2.setVisibility(isSettings || isFilms || isHome || isLib || isSeries || isSN ? GONE : VISIBLE);
        if (!isSN) sgrid.hide();
        if (isHome) {
            items.clear(); chanList = null; listAd.notifyDataSetChanged(); hideCard();
            grid.hide();
            library.hide();
            seriesLibrary.hide();
            home.setVisibility(VISIBLE);
            home.load(getContext(), host.index(), host.store());
            return;
        }
        home.hide();
        if (isFilms) {
            items.clear(); chanList = null; listAd.notifyDataSetChanged(); hideCard();
            library.hide();
            seriesLibrary.hide();
            grid.setVisibility(VISIBLE);
            grid.load(getContext(), host.index(), host.store(), liveUrl);
            return;
        }
        grid.hide();
        if (isSN) {
            items.clear(); chanList = null; listAd.notifyDataSetChanged(); hideCard();
            library.hide();
            seriesLibrary.hide();
            sgrid.setVisibility(VISIBLE);
            sgrid.load(getContext(), host.index(), host.store());
            return;
        }
        if (isLib) {
            items.clear(); chanList = null; listAd.notifyDataSetChanged(); hideCard();
            settings.hide();
            seriesLibrary.hide();
            library.setVisibility(VISIBLE);
            library.load();
            return;
        }
        library.hide();
        if (isSeries) {
            items.clear(); chanList = null; listAd.notifyDataSetChanged(); hideCard();
            settings.hide();
            seriesLibrary.setVisibility(VISIBLE);
            seriesLibrary.load();
            return;
        }
        seriesLibrary.hide();
        if (isSettings) { settings.show(); items.clear(); chanList = null; listAd.notifyDataSetChanged(); hideCard(); return; }
        settings.hide();
        if (r.nav) { items.clear(); chanList = null; applyItems(0); return; } // level-1 "›" row: a hint until OK / Right opens it
        if (r.id.equals("yt")) { showYouTube(); return; }
        if (r.id.startsWith("src:")) { showFolders(r.id.substring(4)); return; }
        items.clear();
        chanList = null;
        if (isSearch) {
            items.clear();
            boolean anyLib = !Config.PLAY && (lk.manus.iptv.data.Library.any(Db.get()) || lk.manus.iptv.data.Library.any(Db.get(), "series"));
            if (anyLib && lastQuery != null && !lastQuery.trim().isEmpty()) {
                List<lk.manus.iptv.data.Library.Item> lm = lk.manus.iptv.data.Library.search(Db.get(), lastQuery, 100);
                if (!lm.isEmpty()) {
                    boolean hf = false, hs = false;
                    for (lk.manus.iptv.data.Library.Item it : lm) {
                        if ("series".equals(it.kind)) hs = true; else hf = true;
                    }
                    items.add(new LibSearchRow(lm.size(), lastQuery, hf, hs));
                }
            }
            items.addAll(lastResults);
            chanList = lastResults;
        }
        else {
            chanList = browse(r.id);
            items.addAll(chanList);
        }
        applyItems(restore(r.id));
    }

    /** Remembers the channel under the cursor and its offset from the top, for the list on screen. */
    private void saveTop() {
        if (chanList == null || shownId == null || listSel < 0 || listSel >= items.size() || !(items.get(listSel) instanceof Channel)) return;
        String k = memoKey();
        memoUrl.put(k, ((Channel) items.get(listSel)).url);
        memoList.put(k, chanList);
        View v = list.getChildAt(listSel - list.getFirstVisiblePosition());
        if (v != null) memoTop.put(k, v.getTop() - list.getPaddingTop());
    }

    /** Row to land on when list {@code key} (already in chanList) comes back: its remembered channel, or the nearest row that followed it; sets the offset. */
    private int restore(String key) {
        String u = memoUrl.get(key);
        if (u == null || chanList == null || chanList.isEmpty()) return memo.containsKey(key) ? memo.get(key) : 0;
        nextTop = memoTop.get(key);
        return Place.find(chanList, u, memoList.get(key));
    }

    /** YouTube Live rows are not streams: no preview, no zap. OK hands the channel's live page to the YouTube app. */
    private void showYouTube() {
        items.clear();
        chanList = null;
        for (YouTubeLive.Entry e : YouTubeLive.ALL) items.add(new CC("yt:" + e.channelId, e.name, "▶", "opens YouTube"));
        applyItems(memo.containsKey("yt") ? memo.get("yt") : 0);
    }

    private void openYouTube(String channelId) {
        if (!YouTubeLive.open(getContext(), "https://www.youtube.com/channel/" + channelId + "/live")) { pill("YouTube app not found"); return; }
        hideNow();
    }

    private void pill(String t) { host.pill(t); }

    private void showFolders(String srcId) {
        Index ix = host.index();
        items.clear();
        chanList = null;
        for (String f : ix.folderOrder(srcId)) {
            String id = "sf:" + srcId + "\t" + f;
            items.add(new CC(id, f, "📁", nf.format(ix.list(id, host.store()).size()) + "  ›"));
        }
        applyItems(memo.containsKey("src:" + srcId) ? memo.get("src:" + srcId) : 0);
    }

    private void enterDrill(String id) {
        host.index().refresh(host.store());
        memo.put(shownId, listSel);
        saveTop();
        drill = id;
        chanList = browse(id);
        items.clear();
        items.addAll(chanList);
        applyItems(restore(id));
    }

    private List<Channel> lastResults = new ArrayList<>();

    /** Debounced (150 ms) filter on a background thread; stale results are dropped. */
    private void runSearch() {
        final String q = searchEt.getText().toString();
        final Index ix = host.index();
        final int seq = ++searchSeq;
        final lk.manus.iptv.data.SearchFilter sf = sfilter.copy(); // M3: the filters as they are now, read off the UI thread
        final java.util.Set<String> favs = new java.util.HashSet<>(host.store().favSet());
        new Thread(new Runnable() {
            @Override public void run() {
                long t = SystemClock.elapsedRealtime();
                boolean noWords = q.trim().isEmpty();
                // no words + a filter = browse every channel by filter (no keyboard needed); words = all matches, narrowed, then capped
                List<Channel> base = noWords ? (sf.active() ? ix.all : new ArrayList<Channel>()) : sf.active() ? ix.search(q, Integer.MAX_VALUE) : ix.search(q);
                final List<Channel> r = sf.apply(base, favs, lk.manus.iptv.data.Search.MAX);
                boolean anyLib = !Config.PLAY && sf.type != lk.manus.iptv.data.SearchFilter.TYPE_LIVE && !sf.favOnly
                        && (lk.manus.iptv.data.Library.any(Db.get()) || lk.manus.iptv.data.Library.any(Db.get(), "series"));
                List<lk.manus.iptv.data.Library.Item> lm = (anyLib && !noWords) ? lk.manus.iptv.data.Library.search(Db.get(), q, 100) : null;
                if (lm != null && (sf.type == lk.manus.iptv.data.SearchFilter.TYPE_FILMS || sf.type == lk.manus.iptv.data.SearchFilter.TYPE_SERIES)) {
                    List<lk.manus.iptv.data.Library.Item> keep = new ArrayList<>();
                    for (lk.manus.iptv.data.Library.Item it : lm) if ("series".equals(it.kind) == (sf.type == lk.manus.iptv.data.SearchFilter.TYPE_SERIES)) keep.add(it);
                    lm = keep;
                }
                final List<lk.manus.iptv.data.Library.Item> libMatches = lm;
                Log.i(TAG, "search \"" + q.length() + " chars\" -> " + r.size() + " results in " + (SystemClock.elapsedRealtime() - t) + " ms");
                h.post(new Runnable() {
                    @Override public void run() {
                        if (seq != searchSeq || !"search".equals(shownId)) return;
                        lastQuery = q;
                        lastResults = r;
                        items.clear();
                        if (libMatches != null && !libMatches.isEmpty()) {
                            boolean hf = false, hs = false;
                            for (lk.manus.iptv.data.Library.Item it : libMatches) {
                                if ("series".equals(it.kind)) hs = true; else hf = true;
                            }
                            items.add(new LibSearchRow(libMatches.size(), q, hf, hs));
                        }
                        items.addAll(r);
                        chanList = r;
                        applyItems(0);
                        if (focusResults) { focusResults = false; if (!items.isEmpty()) list.requestFocus(); }
                    }
                });
            }
        }, "search").start();
    }

    private void applyItems(int pos) {
        boolean isSearch = "search".equals(shownId) && drill == null;
        boolean navHint = level == 1 && drill == null && (shownId.equals("countries") || shownId.equals("cats") || shownId.equals("srcs"));
        if (drill != null) {
            title.setText(drill.startsWith("cc:") ? Ui.country(drill.substring(3)) : host.index().title(drill));
            sub.setText((drill.startsWith("cc:") ? Ui.flag(drill.substring(3)) + "  " : "") + nf.format(items.size()) + " channels");
        } else {
            title.setText(host.index().title(shownId));
            sub.setText(shownId.equals("settings") || navHint ? "" : isSearch && items.isEmpty() ? "" : nf.format(items.size())
                    + (shownId.startsWith("src:") ? " folders" : items.size() == 1 ? " channel" : " channels"));
            if (shownId.startsWith("src:")) title.setText(host.index().srcName(shownId.substring(4)));
        }
        String e = "";
        if (items.isEmpty()) {
            if (navHint) e = "Press OK or ›\nto open";
            else if (shownId.equals("fav")) e = "★\n\nLong-press OK on any channel to add it here";
            else if (shownId.equals("recent")) e = "Channels you watch will show up here";
            else if (isSearch) e = sfilter.active() ? "No channels match these filters\n\nChange a filter, or Clear filters" : searchEt.getText().length() == 0 ? "Press OK to open the keyboard\nor use the mic on your remote\n\nor pick a filter above, no typing needed" : "No channels match";
            else e = host.index().all.isEmpty() ? "No channels yet \u2014 add a playlist in Settings \u2192 Sources" : "No channels";
        }
        empty.setText(e);
        empty.setVisibility(items.isEmpty() ? VISIBLE : GONE);
        empty.post(new Runnable() { @Override public void run() { // centred in the space under the headers (search box, filter chips, Sort / Filter), never over them
            int t = list.getTop();
            if (empty.getPaddingTop() != t) empty.setPadding(Ui.u(40), t, Ui.u(40), 0);
        } });
        listSel = Math.max(0, Math.min(pos, items.size() - 1));
        listAd.notifyDataSetChanged();
        list.setSelectionFromTop(listSel, nextTop != null ? nextTop : Ui.u(130));
        nextTop = null;
        h.removeCallbacks(cardR); h.removeCallbacks(liveR); stopMini();
        hideCard();
        if (list.hasFocus()) dwell();
        if (!filterQ.isEmpty() && items.isEmpty() && chanList != null) { empty.setText("No channel here matches “" + lk.manus.iptv.data.LoadText.name(filterQ) + "”\n\nOK on Filter clears it"); empty.setVisibility(VISIBLE); }
        updateTools();
    }

    private void selectList(int pos) { listSel = pos; list.setSelectionFromTop(pos, Ui.u(130)); }

    private void focusList() {
        if (railSel < rails.size()) showSection(rails.get(railSel));
        if (railSel < rails.size() && rails == l1 && rails.get(railSel).id.equals("mylist")) { home.focusMyList(); return; }
        focusContent();
    }

    private void focusContent() {
        if ("home".equals(shownId)) home.focusTile();
        else if ("films".equals(shownId)) grid.focusTile();
        else if ("seriesnow".equals(shownId)) sgrid.focusTile();
        else if ("lib".equals(shownId)) library.focusTile();
        else if ("series".equals(shownId)) seriesLibrary.focusTile();
        else if ("settings".equals(shownId)) settings.focusContent();
        else if ("search".equals(shownId) && drill == null) {
            if (library.getVisibility() == VISIBLE) library.focusTile();
            else searchEt.requestFocus();
        }
        else list.requestFocus();
    }

    // ---- preview ----

    /** Mini preview allowed: setting = Auto, not switched off on this TV, and the TV reports two H.264 hardware decoder instances. */
    private boolean miniOn() {
        if (!host.store().miniAuto() || !host.store().miniBlocked().isEmpty()) return false;
        if (avc < 0) { avc = MiniPreview.avcInstances(); Log.i(TAG, "mini: H.264 hardware decoder instances = " + avc + ", tier " + Ui.tierName() + (avc >= 2 ? " -> preview allowed" : " -> logo only")); }
        return avc >= 2;
    }

    /** The mini window is on for this TV right now (Settings shows it). */
    public static String miniState(Context c, Store st) {
        if (!st.miniAuto()) return "Off";
        if (!st.miniBlocked().isEmpty()) return "Auto (off: " + (st.miniBlocked().startsWith("ERROR_CODE") || st.miniBlocked().startsWith("software") || st.miniBlocked().startsWith("main video") ? miniReason(st.miniBlocked()) : st.miniBlocked()) + ")"; // older installs stored the raw code
        int n = MiniPreview.avcInstances();
        return n >= 2 ? "Auto (on)" : "Auto (off: this TV has one video decoder)";
    }

    private void blockMini(String why) {
        Log.i(TAG, "mini: off for this TV - " + why);
        miniPolicy.reset(); // counting starts again if the viewer switches it back on
        host.store().setMiniBlocked(miniReason(why));
        host.pill("Mini preview turned off for this TV");
    }

    /** What Settings shows after "Auto (off: …)": plain words, never a Media3 error code. */
    static String miniReason(String why) {
        String w = why == null ? "" : why;
        if (w.startsWith("software decoder")) return "this TV plays a second video only in software";
        if (w.startsWith("ERROR_CODE_DECODER")) return "this TV could not start a second video";
        if (w.startsWith("ERROR_CODE_DECODING")) return "several channels could not play in the preview";
        if (w.startsWith("main video dropped")) return "the main picture stuttered during previews";
        return w.length() > 60 ? w.substring(0, 60) : w;
    }

    private void fitMini(int vw, int vh) {
        int cw = cMini.getWidth(), ch = cMini.getHeight();
        if (cw <= 0 || vw <= 0 || vh <= 0) return;
        int w = cw, hh = cw * vh / vw;
        if (hh > ch) { hh = ch; w = ch * vw / vh; }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cTex.getLayoutParams();
        if (lp.width != w || lp.height != hh) { lp.width = w; lp.height = hh; cTex.setLayoutParams(lp); }
    }

    private void stopMini() {
        h.removeCallbacks(miniWatchR);
        mini.stop();
        cTex.animate().cancel();
        cTex.setAlpha(0f);
    }

    private void dwell() {
        h.removeCallbacks(cardR); h.removeCallbacks(liveR); stopMini();
        hideCard();
        cStatus.setVisibility(GONE);
        if (selected() == null) return;
        h.postDelayed(cardR, CARD_MS);
        h.postDelayed(liveR, LIVE_MS);
    }

    /** The playing channel has / has no subtitle tracks: a "CC" chip on the preview card (when it shows that channel). */
    public void setCC(boolean on) {
        cc = on;
        if (on && card.getVisibility() == VISIBLE) addCcChip();
    }

    private void addCcChip() {
        Channel c = selected();
        if (!cc || c == null || !c.url.equals(liveUrl)) return;
        TextView t = Ui.tv(getContext(), Ui.MICRO, Ui.MUTED, true);
        t.setText("CC");
        t.setBackground(Ui.chip());
        t.setPadding(Ui.u(10), Ui.u(5), Ui.u(10), Ui.u(5));
        chips.addView(t);
    }

    /** Now / next on the preview card for the selected channel. */
    private void refreshNow() {
        Channel c = selected();
        long t = System.currentTimeMillis();
        CloudData.Slot n = c == null ? null : CloudData.now(c.id, t), x = c == null ? null : CloudData.next(c.id, t);
        cNow.setVisibility(n == null ? GONE : VISIBLE);
        cBar.setVisibility(n == null ? GONE : VISIBLE);
        cTimes.setVisibility(n == null ? GONE : VISIBLE);
        cNext.setVisibility(x == null ? GONE : VISIBLE);
        if (n != null) { cNow.setText(n.title); cTimes.setText("Now  ·  " + CloudData.hm(n.start) + "–" + CloudData.hm(n.stop)); cBar.set(n.progress(t)); }
        if (x != null) cNext.setText("Next  " + CloudData.hm(x.start) + "  ·  " + x.title);
    }

    private final Runnable nowR = new Runnable() {
        @Override public void run() {
            if (open) {
                listAd.notifyDataSetChanged();
                if (card.getVisibility() == VISIBLE) refreshNow();
                grid.tick();
                sgrid.tick();
                home.tick();
            }
            h.postDelayed(this, 60000 - System.currentTimeMillis() % 60000 + 200); // on the minute: programme changes land within a second
        }
    };

    /** Fresh channel data arrived (UI thread). */
    public void onCloudData() { if (open) { listAd.notifyDataSetChanged(); if (card.getVisibility() == VISIBLE) refreshNow(); } }

    private void hideCard() { stopMini(); h.removeCallbacks(speedR); card.animate().cancel(); card.setAlpha(0f); card.setVisibility(INVISIBLE); }

    private void showCard() {
        Channel c = selected();
        if (c == null || !cardOk) return;
        LogoLoader.get(getContext()).load(c.logo, c.name, cLogo);
        cName.setText(c.name);
        chips.removeAllViews();
        int n = 0;
        for (String g : c.groups) {
            if (n == 3) break;
            TextView t = Ui.tv(getContext(), Ui.MICRO, Ui.MUTED, true);
            t.setText(Index.groupLabel(g));
            t.setBackground(Ui.chip());
            t.setPadding(Ui.u(10), Ui.u(5), Ui.u(10), Ui.u(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.u(6);
            chips.addView(t, lp);
            n++;
        }
        StringBuilder s = new StringBuilder();
        if (!c.country.isEmpty()) s.append(Ui.flag(c.country)).append(' ').append(Ui.country(c.country));
        if (!c.quality.isEmpty()) s.append(s.length() > 0 ? "  ·  " : "").append(c.quality);
        cInfo.setText(s);
        addCcChip();
        refreshNow();
        card.setVisibility(VISIBLE);
        cSpeed.setText(host.netSpeed());
        h.removeCallbacks(speedR);
        h.postDelayed(speedR, 1000);
        if (Ui.anim()) {
            card.setTranslationY(Ui.u(16));
            card.animate().alpha(1f).translationY(0).setDuration(200).setInterpolator(Ui.DEC).start();
        } else { card.setTranslationY(0); card.setAlpha(1f); }
    }

    // ---- rows ----

    /** setText only when the text changed: a bind that changes nothing causes no relayout (rows have a fixed height). */
    private static void put(TextView t, CharSequence s) { if (!android.text.TextUtils.equals(t.getText(), s)) t.setText(s); }

    private static String num(int n) {
        while (NUMS.size() <= n) NUMS.add(String.valueOf(NUMS.size()));
        return NUMS.get(n);
    }

    /** The rail keeps a soft highlight on the open section while the list has focus (state_activated). */
    private void refreshRail() {
        for (int i = 0; i < rail.getChildCount(); i++) {
            if (!(rail.getChildAt(i) instanceof Row)) continue;
            Row r = (Row) rail.getChildAt(i);
            r.setActivated(r.pos == railSel);
        }
        refreshBar();
    }

    /**
     * List rows are highlighted by the ListView's own selected state (no callback, no extra frame). While key-repeat
     * scrolling ({@link #fast}) the 1.03 scale is applied instantly instead of animated.
     */
    private abstract class Row extends LinearLayout {
        int pos = -1;
        Row(Context c, int hDp) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setLayoutParams(new AbsListView.LayoutParams(-1, Ui.u(hDp)));
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_selected}, Ui.ring());
            sl.addState(new int[]{android.R.attr.state_activated}, Ui.rect(Ui.RAISED, 14));
            sl.addState(new int[]{}, new ColorDrawable(0));
            setBackground(sl);
        }
        @Override public void setSelected(boolean s) {
            boolean was = isSelected();
            super.setSelected(s);
            if (s == was || pos < 0) return;
            float sc = s ? 1.03f : 1f;
            animate().cancel();
            if (!Ui.anim()) return; // Low tier: the ring alone marks the focus, no scale
            if (fast || !open) { setScaleX(fast ? 1f : sc); setScaleY(fast ? 1f : sc); } // key-repeat: no scale at all (calmR redraws once settled)
            else animate().scaleX(sc).scaleY(sc).setDuration(160).setInterpolator(Ui.DEC).start();
        }
        /** Called from bind: a recycled view starts in its final state. */
        void settle() {
            if (!Ui.anim()) return;
            animate().cancel();
            float sc = isSelected() && !fast ? 1.03f : 1f;
            setScaleX(sc);
            setScaleY(sc);
        }
    }

    /** T111: quiet group label in the menu (WATCH / DISCOVER / BROWSE): DIM, small, letter-spaced, never focusable. */
    private static TextView groupLabel(Context c) {
        TextView t = Ui.tv(c, Ui.MICRO, Ui.DIM, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.16f);
        t.setPadding(Ui.u(14), Ui.u(12), 0, Ui.u(2));
        t.setLayoutParams(new AbsListView.LayoutParams(-1, Ui.u(GROUP_H)));
        t.setGravity(Gravity.BOTTOM);
        return t;
    }

    private static final int ROW_H = 44, GROUP_H = 30, ICON = 22, LOGO_H = 48;

    /** T111: the one menu focus style. Focused = soft SIGNAL wash + 3dp SIGNAL bar on the left; the open section keeps only the bar. */
    private static Drawable railBg() {
        StateListDrawable rs = new StateListDrawable();
        rs.addState(new int[]{android.R.attr.state_selected}, railLayer(true));
        rs.addState(new int[]{android.R.attr.state_activated}, railLayer(false));
        rs.addState(new int[]{}, new ColorDrawable(0));
        return rs;
    }

    private static Drawable railLayer(boolean wash) {
        android.graphics.drawable.LayerDrawable d = new android.graphics.drawable.LayerDrawable(
                new Drawable[]{wash ? Ui.rect(Ui.alpha(Ui.SIGNAL, 0.18f), 10) : new ColorDrawable(0), new ColorDrawable(Ui.SIGNAL)});
        d.setLayerWidth(1, Ui.u(3));
        d.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
        return d;
    }

    private static ColorStateList railInk(int on, int off) {
        return new ColorStateList(new int[][]{{android.R.attr.state_selected}, {android.R.attr.state_activated}, {}}, new int[]{on, on, off});
    }

    private static ImageView railIcon(Context c, int res) {
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setImageTintList(railInk(Ui.PAPER, Ui.MUTED)); // one colour: PAPER when focused / open, MUTED otherwise
        i.setDuplicateParentStateEnabled(true);
        return i;
    }

    private LinearLayout barBtn(Context c, String text, int res) {
        LinearLayout b = new LinearLayout(c);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(Ui.u(9), 0, Ui.u(4), 0);
        b.setBackground(railBg());
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.u(20), Ui.u(20));
        ip.rightMargin = Ui.u(6);
        b.addView(railIcon(c, res), ip);
        TextView t = Ui.tv(c, Ui.CAPTION, Ui.PAPER, true);
        t.setText(text);
        t.setDuplicateParentStateEnabled(true);
        t.setTextColor(railInk(Ui.PAPER, Ui.MUTED));
        b.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        return b;
    }

    /** The bar mirrors the cursor: wash + bar while the menu has focus on it, bar alone while that section is open. Level 2 replaces the menu, the bar goes with it. */
    private void refreshBar() {
        if (barBox == null) return;
        boolean show = rails == l1;
        barBox.setVisibility(show ? VISIBLE : GONE);
        if (!show) return;
        boolean f = rail.hasFocus();
        for (int k = 0; k < 2; k++) {
            LinearLayout b = k == 0 ? barSearch : barSettings;
            boolean on = railSel < l1.size() && l1.get(railSel).id.equals(k == 0 ? "search" : "settings");
            b.setActivated(on);
            b.setSelected(on && f);
        }
    }

    private int idxOf(String id) {
        for (int i = 0; i < l1.size(); i++) if (l1.get(i).id.equals(id)) return i;
        return -1;
    }

    private final class RailRow extends Row {
        private final TextView label, count;
        private final ImageView icon;
        RailRow(Context c) {
            super(c, ROW_H);
            setPadding(Ui.u(10), 0, Ui.u(8), 0);
            setBackground(railBg());
            icon = new ImageView(c);
            icon.setImageTintList(railInk(Ui.PAPER, Ui.MUTED));
            icon.setDuplicateParentStateEnabled(true);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.u(ICON), Ui.u(ICON));
            ip.rightMargin = Ui.u(10);
            addView(icon, ip);
            label = Ui.tv(c, Ui.BODY, Ui.PAPER, true);
            label.setDuplicateParentStateEnabled(true);
            label.setTextColor(railInk(Ui.PAPER, Ui.MUTED));
            count = Ui.tv(c, Ui.CAPTION, Ui.DIM, false);
            count.setPadding(Ui.u(4), 0, 0, 0);
            addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            addView(count);
        }
        void bind(Rail r, int p) {
            pos = p;
            if (r.icon != 0) { icon.setImageResource(r.icon); icon.setVisibility(VISIBLE); } else icon.setVisibility(GONE);
            put(label, r.label);
            put(count, r.count < 0 ? "" : nf.format(r.count) + (r.nav ? " ›" : ""));
            setActivated(p == railSel);
            settle();
        }
    }

    static final class LibSearchRow {
        final int count;
        final String query;
        final boolean hasFilms;
        final boolean hasSeries;
        LibSearchRow(int count, String query) { this(count, query, true, false); }
        LibSearchRow(int count, String query, boolean hasFilms, boolean hasSeries) {
            this.count = count;
            this.query = query;
            this.hasFilms = hasFilms;
            this.hasSeries = hasSeries;
        }
    }

    private final class LibSearchRowView extends Row {
        private final TextView name;
        LibSearchRowView(Context c) {
            super(c, 56);
            setPadding(Ui.u(18), 0, Ui.u(16), 0);
            name = Ui.tv(c, Ui.BODY, Ui.PAPER, true);
            addView(name, new LinearLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        }
        void bind(LibSearchRow r) {
            if (r.hasFilms && r.hasSeries) {
                name.setText("Films and series in your library (" + r.count + ")");
            } else if (r.hasSeries) {
                name.setText("Series in your library (" + r.count + ")");
            } else {
                name.setText("Films in your library (" + r.count + ")");
            }
            settle();
        }
    }

    private final class CountryRow extends Row {
        private final TextView flag, name, count;
        CountryRow(Context c) {
            super(c, 56);
            setPadding(Ui.u(14), 0, Ui.u(16), 0);
            flag = Ui.tv(c, Ui.TITLE, Ui.PAPER, false);
            name = Ui.tv(c, Ui.BODY, Ui.PAPER, true);
            name.setSingleLine(false); name.setMaxLines(2); // "United States" vs "United Kingdom" must stay readable
            count = Ui.tv(c, Ui.CAPTION, Ui.MUTED, false);
            count.setPadding(Ui.u(6), 0, 0, 0);
            flag.setPadding(0, 0, Ui.u(10), 0);
            addView(flag);
            addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            addView(count);
        }
        void bind(CC c, int p) {
            pos = p;
            put(flag, c.flag);
            put(name, c.name);
            put(count, c.countText);
            settle();
        }
    }

    private final class ChanRow extends Row {
        private final TextView num, name, info, live, star, chQ, chF, chV;
        private final ImageView logo;
        private final NowBar bar;
        private TextView chip(Context c, float sp, boolean outline) {
            TextView t = Ui.tv(c, sp, Ui.MUTED, true);
            t.setPadding(Ui.u(4), Ui.u(2), Ui.u(4), Ui.u(2));
            if (outline) t.setBackground(Ui.chip());
            return t;
        }
        ChanRow(Context c) {
            super(c, 64);
            setPadding(Ui.u(12), 0, Ui.u(12), 0);
            num = Ui.tv(c, Ui.CAPTION, Ui.MUTED, true);
            num.setMinWidth(Ui.u(26));
            logo = new ImageView(c);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setBackground(Ui.rect(Ui.PANEL, 8));
            logo.setPadding(Ui.u(3), Ui.u(3), Ui.u(3), Ui.u(3));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            col.setPadding(Ui.u(12), 0, 0, 0);
            // line 1: name + right-aligned chips (quality, flag, N versions)
            LinearLayout l1 = new LinearLayout(c);
            l1.setGravity(Gravity.CENTER_VERTICAL);
            name = Ui.tv(c, Ui.BODY, Ui.PAPER, true);
            chQ = chip(c, Ui.MICRO, true);
            chF = chip(c, Ui.MICRO, false);
            chV = chip(c, Ui.MICRO, true);
            l1.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.leftMargin = Ui.u(5);
            l1.addView(chV, cp);
            l1.addView(chQ, cp);
            l1.addView(chF, cp);
            col.addView(l1);
            // line 2: now-title (or category) + live / favourite marks, then the progress bar
            LinearLayout l2 = new LinearLayout(c);
            l2.setGravity(Gravity.CENTER_VERTICAL);
            l2.setPadding(0, Ui.u(3), 0, 0);
            info = Ui.tv(c, Ui.CAPTION, Ui.MUTED, false);
            live = Ui.tv(c, Ui.MICRO, Ui.PAPER, true);
            android.text.SpannableString ls = new android.text.SpannableString("LIVE ●"); // PAPER word, SIGNAL dot
            ls.setSpan(new android.text.style.ForegroundColorSpan(Ui.SIGNAL), 5, 6, 0);
            live.setText(ls);
            live.setPadding(Ui.u(6), 0, 0, 0);
            star = Ui.tv(c, Ui.CAPTION, Ui.GOLD, false);
            star.setText("★");
            star.setPadding(Ui.u(6), 0, 0, 0);
            l2.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            l2.addView(live);
            l2.addView(star);
            col.addView(l2);
            bar = new NowBar(c);
            bar.setVisibility(GONE);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Ui.u(2));
            bp.topMargin = Ui.u(4);
            col.addView(bar, bp);
            addView(num);
            addView(logo, new LinearLayout.LayoutParams(Ui.u(56), Ui.u(36)));
            addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        }
        void bind(Channel c, int p) {
            pos = p;
            put(num, num(p + 1));
            if (c.archiveDays > 0) { // T95: catch-up available
                android.text.SpannableString ns = new android.text.SpannableString(c.name + " ⟲");
                ns.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED), c.name.length() + 1, ns.length(), 0);
                name.setText(ns);
            } else put(name, c.name);
            put(chQ, c.quality.isEmpty() ? "—" : c.quality);
            chQ.setAlpha(c.quality.isEmpty() ? 0.5f : 1f);
            String f = Ui.flag(c.country);
            put(chF, f.isEmpty() ? "🌐" : f);
            int nv = 1 + Dedup.alternates(c).size();
            chV.setVisibility(nv > 1 ? VISIBLE : GONE);
            if (nv > 1) put(chV, "×" + nv);
            if (c.info == null) c.info = c.groups.length > 0 ? Index.groupLabel(c.groups[0]) : "Other"; // built once per channel, not once per bind
            boolean dead = Health.get(getContext()).status(c.url) == Health.DEAD;
            CloudData.Slot now = dead ? null : CloudData.now(c.id, System.currentTimeMillis());
            put(info, dead ? "Unavailable" : now != null ? now.title : c.info);
            bar.setVisibility(now != null ? VISIBLE : GONE);
            if (now != null) bar.set(now.progress(System.currentTimeMillis()));
            float al = dead ? 0.45f : 1f;
            if (getAlpha() != al) setAlpha(al);
            h.removeCallbacks(probeR);
            h.postDelayed(probeR, 400);
            live.setVisibility(c.url.equals(liveUrl) ? VISIBLE : GONE);
            star.setVisibility(favSet.contains(c.url) ? VISIBLE : GONE);
            LogoLoader.get(getContext()).load(c.logo, c.name, logo);
            settle();
        }
    }

    /** Live preview for Settings > Screen edges: a frame on the title-safe rectangle. */
    private static final class SafeFrame extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final Runnable off = new Runnable() { @Override public void run() { hideNow(); } };
        SafeFrame(Context c) {
            super(c);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(Ui.u(3));
            p.setColor(Ui.PAPER);
            setVisibility(GONE);
        }
        void show() { removeCallbacks(off); setVisibility(VISIBLE); invalidate(); }
        void flash() { show(); postDelayed(off, 4000); }
        void hideNow() { removeCallbacks(off); setVisibility(GONE); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            c.drawRoundRect(Ui.marginX(), Ui.marginY(), getWidth() - Ui.marginX(), getHeight() - Ui.marginY(), Ui.u(14), Ui.u(14), p);
        }
    }
}
