package lk.manus.iptv.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import lk.manus.iptv.Config;
import lk.manus.iptv.data.AddServer;
import lk.manus.iptv.data.Backup;
import lk.manus.iptv.data.BackupOps;
import lk.manus.iptv.data.CloudData;
import lk.manus.iptv.data.Db;
import lk.manus.iptv.data.Index;
import lk.manus.iptv.data.Redact;
import lk.manus.iptv.data.Sources;
import lk.manus.iptv.data.Store;
import lk.manus.iptv.data.XtreamSource;
import lk.manus.iptv.meta.MetaStore;
import lk.manus.iptv.meta.Tmdb;

/** Settings screen shown in the guide's column 2/3: Sources, Playback, For You, About. Big D-pad rows, simple page stack. */
public final class SettingsView extends FrameLayout {
    public interface Host {
        Index index();
        Store store();
        Sources sources();
        void sourcesChanged();   // enabled set or a source changed: reload playlists
        void forYouChanged();    // For You picker closed with changes
        void countsChanged();    // a ranking setting changed: rail counts follow the visible channels
        void focusRail();
        void openSources();      // T111c: My Sources (browse each source's folders) lives here now; the guide shows it in the menu column
        void recheckHidden();    // confirm, then reset TV-side DEAD to UNKNOWN
        void exit();             // ask to quit the app (the guide shows the confirm dialog)
        void pill(String text);  // short message at the bottom of the guide
        void showRemoteKeys();   // centred modals (owned by the guide)
        void showRemoteTest();
        void confirm(String title, String sub, String yes, String no, Runnable onYes); // centred modal; "no" has focus
        void confirm3(String title, String sub, String b1, String b2, String b3, Runnable onB1, Runnable onB2);
        void restoreFavourites(List<String> urls);
        void edgesChanged();     // Screen edges moved: the guide re-lays out (and flashes the frame)
        void frame(boolean on);  // live preview frame of the safe area
        void filmsChanged();     // the movie-database key was added or removed: the rail follows
        void remindersChanged(); // a reminder was removed: the timer re-arms
        void restored();         // a backup was restored: playlists, favourites, reminders and display settings are re-read, the guide opens on Home
    }

    private static final class Item {
        String label, sub = "", value = "";
        boolean focusable = true, header, good, bad, preview, logo;
        Runnable act;
        Item(String l) { label = l; }
    }

    private final Host host;
    private final NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
    private final TextView title, sub;
    private final ListView list;
    private final BaseAdapter ad;
    private final LinearLayout form;
    private final List<Item> items = new ArrayList<>();
    private final List<String[]> stack = new ArrayList<>();
    private String page = "";
    private boolean fyDirty;

    // add / rename page state
    private AddServer server;
    private Sources.Probe pending;
    private int probeGen;
    private EditText urlEt, nameEt;
    private TextView status, btn, codeTv;
    private ImageView qr;
    private String confirmRemove = "";
    private long confirmAt;
    private View downOn;                 // the button that saw a fresh OK key-down: a click without it is ignored (no stray/held-key saves)

    public SettingsView(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        setBackground(Ui.card(0.94f, 18));
        setPadding(Ui.u(14), Ui.u(14), Ui.u(14), Ui.u(10));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        title = Ui.tv(ctx, Ui.TITLE, Ui.PAPER, true);
        sub = Ui.tv(ctx, Ui.CAPTION, Ui.MUTED, false);
        title.setPadding(Ui.u(14), Ui.u(4), 0, 0);
        sub.setPadding(Ui.u(14), Ui.u(3), 0, Ui.u(6));
        col.addView(title);
        col.addView(sub);

        list = new ListView(ctx);
        list.setSelector(new ColorDrawable(0));
        list.setDivider(null);
        list.setCacheColorHint(0);
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        list.setVerticalScrollBarEnabled(false);
        ad = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public int getViewTypeCount() { return 3; }
            @Override public int getItemViewType(int i) { return items.get(i).header ? 1 : items.get(i).logo ? 2 : 0; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int i) { return items.get(i).focusable && !items.get(i).header; }
            @Override public View getView(int i, View v, ViewGroup p) {
                Item it = items.get(i);
                if (it.logo) { // the approved TMDB logo, unaltered, much smaller than our own banner
                    if (v != null) return v;
                    FrameLayout f = new FrameLayout(getContext());
                    f.setLayoutParams(new AbsListView.LayoutParams(-1, Ui.u(34)));
                    ImageView iv = new ImageView(getContext());
                    iv.setImageResource(lk.manus.iptv.R.drawable.tmdb_logo);
                    iv.setScaleType(ImageView.ScaleType.FIT_START);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.u(110), Ui.u(110 * 35.52f / 273.42f), Gravity.CENTER_VERTICAL | Gravity.START);
                    lp.leftMargin = Ui.u(16);
                    f.addView(iv, lp);
                    return f;
                }
                if (it.header) {
                    TextView t = v instanceof TextView ? (TextView) v : Ui.tv(getContext(), Ui.MICRO, Ui.MUTED, true);
                    t.setLayoutParams(new AbsListView.LayoutParams(-1, Ui.u(34)));
                    t.setPadding(Ui.u(16), Ui.u(14), 0, 0);
                    t.setText(it.label.toUpperCase(Locale.ROOT));
                    return t;
                }
                SRow r = v instanceof SRow ? (SRow) v : new SRow(getContext());
                r.bind(it);
                return r;
            }
        };
        list.setAdapter(ad);
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { SettingsView.this.host.frame(pos < items.size() && items.get(pos).preview); }
            @Override public void onNothingSelected(AdapterView<?> p) { SettingsView.this.host.frame(false); }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Item it = items.get(pos);
                if (it.act != null) it.act.run();
            }
        });
        list.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && k == KeyEvent.KEYCODE_DPAD_LEFT) leftOut();
                return true;
            }
        });
        col.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.HORIZONTAL);
        form.setPadding(Ui.u(14), Ui.u(8), Ui.u(14), 0);
        col.addView(form, new LinearLayout.LayoutParams(-1, 0, 1));
        addView(col, new LayoutParams(-1, -1));
        setVisibility(GONE);
    }

    private int wizardRev;
    private boolean waitWizard;

    /** Back from the setup wizard: if it saved new choices, rebuild For You and the ranked lists (mechanism: Store.setupRev counter). */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || !waitWizard) return;
        waitWizard = false;
        Store st = host.store();
        if (st.setupRev() == wizardRev) return;
        host.index().refresh(st);
        host.forYouChanged();
        host.countsChanged();
        if (getVisibility() == VISIBLE) render();
    }

    // ---- public API ----

    public void show() {
        stack.clear();
        setVisibility(VISIBLE);
        go("root", "");
    }

    /** Guide closes or another rail section is shown. */
    public void hide() {
        host.frame(false);
        leave();
        setVisibility(GONE);
    }

    /** Playlists were reloaded: redraw the current page (counts changed). */
    public void refresh() { if (getVisibility() == VISIBLE && !isForm()) render(); }

    private boolean isForm() { return page.equals("add") || page.equals("rename") || page.equals("feed") || page.equals("filmkey") || page.equals("addxtream") || isBackupForm(); }

    private boolean isBackupForm() { return page.equals("bkup") || page.equals("rest") || page.equals("restpass"); }

    /** True while the guide must not time out: a text box has focus, or a phone page is waiting for the phone (QR + code on screen). */
    public boolean hasTextFocus() { return findFocus() instanceof EditText || server != null; }

    public void focusContent() {
        if (page.equals("filmkey") || page.equals("addxtream") || isBackupForm()) btn.requestFocus();
        else if (isForm()) { if (urlEt != null) urlEt.requestFocus(); else nameEt.requestFocus(); }
        else list.requestFocus();
    }

    /** Back inside settings: true if it went up a page, false at the top. */
    public boolean back() {
        if (stack.size() <= 1) return false;
        boolean had = hasFocus();
        leave();
        stack.remove(stack.size() - 1);
        String[] p = stack.get(stack.size() - 1);
        page = p[0];
        render();
        if (!isForm() && p.length > 2 && !p[2].isEmpty()) { // T101: back lands on the row the viewer left, not the top
            int at = Integer.parseInt(p[2]);
            if (at >= 0 && at < items.size() && items.get(at).focusable) list.setSelection(at);
        }
        if (had) focusContent();
        return true;
    }

    private void leftOut() { if (!back()) host.focusRail(); }

    // ---- navigation ----

    private void go(String p, String arg) {
        boolean had = hasFocus(); // only pull focus in when the user is already inside settings
        leave();
        if (!stack.isEmpty()) stack.get(stack.size() - 1)[2] = String.valueOf(list.getSelectedItemPosition()); // T101: remember the row we leave
        stack.add(new String[]{p, arg, ""});
        page = p;
        render();
        if (had) focusContent();
    }

    private String arg() { return stack.isEmpty() ? "" : stack.get(stack.size() - 1)[1]; }

    /** Tears down whatever the current page started (phone server, unsaved download, pending For You change). */
    private void leave() {
        if (server != null) { server.stop(); server = null; }
        probeGen++;
        if (pending != null) { host.sources().discard(pending); pending = null; }
        confirmRemove = "";
        if (fyDirty) { fyDirty = false; host.forYouChanged(); }
        hideIme();
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    // ---- pages ----

    private void render() {
        items.clear();
        boolean formPage = isForm();
        list.setVisibility(formPage ? GONE : VISIBLE);
        form.setVisibility(formPage ? VISIBLE : GONE);
        switch (page) {
            case "root": root(); break;
            case "sources": sourcesPage(); break;
            case "src": srcPage(); break;
            case "xsrc": xtreamSourcePage(); break;
            case "add": addPage(); break;
            case "addxtream": addXtreamPage(); break;
            case "rename": renamePage(); break;
            case "feed": feedPage(); break;
            case "film": filmPage(); break;
            case "reminders": remindersPage(); break;
            case "filmkey": filmKeyPage(); break;
            case "foryou": forYouPage(); break;
            case "fycountries": pickPage(true); break;
            case "fycats": pickPage(false); break;
            case "about": aboutPage(); break;
            case "trmodels": trModelsPage(); break;
            case "favsnaps": favSnapsPage(); break;
            case "favhist": favHistPage(); break;
            case "backup": backupPage(); break;
            case "bkup": backupFormPage(); break;
            case "rest": restoreFormPage(""); break;
            case "restfile": restoreFilePage(); break;
            case "restpass": restoreFormPage(arg()); break;
            default: break;
        }
        ad.notifyDataSetChanged();
        if (!formPage) list.setSelection(first());
    }

    private int first() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).focusable && !items.get(i).header) return i;
        return 0;
    }

    private void head(String t, String s) { title.setText(t); sub.setText(s); }

    private Item header(String t) { Item i = new Item(t); i.header = true; i.focusable = false; items.add(i); return i; }

    private Item note(String t) { Item i = new Item(t); i.focusable = false; i.sub = ""; items.add(i); return i; }

    private Item row(String label, String value, Runnable act) { Item i = new Item(label); i.value = value; i.act = act; items.add(i); return i; }

    private void root() {
        final Store st = host.store();
        head("Settings", "Sources, playback and For You");
        header("Sources");
        if (host.index().sources.size() > 1) row("My Sources", host.index().sources.size() + "  \u203a", new Runnable() { @Override public void run() { host.openSources(); } });
        List<Sources.Source> all = host.sources().list();
        int on = 0;
        for (Sources.Source s : all) if (s.enabled) on++;
        row("Playlist sources", on + " of " + all.size() + " on  ›", new Runnable() { @Override public void run() { go("sources", ""); } });
        final Tmdb tm = Tmdb.get(getContext());
        row("Film information", filmState(), new Runnable() { @Override public void run() { go("film", ""); } }).good = tm.hasKey();
        int nrem = lk.manus.iptv.data.Reminders.list().size();
        if (nrem > 0) row("Reminders", nrem + "  \u203a", new Runnable() { @Override public void run() { go("reminders", ""); } });
        header("Playback");
        final Item q = row("Picture quality", qualityName(st.quality()), null);
        q.act = new Runnable() {
            @Override public void run() {
                String n = st.quality().equals("auto") ? "saver" : st.quality().equals("saver") ? "best" : "auto";
                st.setQuality(n);
                q.value = qualityName(n);
                ad.notifyDataSetChanged();
            }
        };
        final Item m = row("Show video in mirror (uses more GPU)", st.mirror() ? "On" : "Off", null);
        m.good = st.mirror();
        m.act = new Runnable() {
            @Override public void run() {
                st.setMirror(!st.mirror());
                m.value = st.mirror() ? "On" : "Off";
                m.good = st.mirror();
                ad.notifyDataSetChanged();
            }
        };
        Item n = note("Applies from the next channel");
        n.sub = "";
        final Item lp = row("Mini preview in guide", Guide.miniState(getContext(), st), null);
        lp.act = new Runnable() {
            @Override public void run() {
                st.setMiniAuto(!st.miniAuto());
                lp.value = Guide.miniState(getContext(), st);
                ad.notifyDataSetChanged();
            }
        };
        final Item u = row("Show unavailable channels", st.showUnavail() ? "On" : "Off", null);
        u.good = st.showUnavail();
        u.act = new Runnable() {
            @Override public void run() {
                st.setShowUnavail(!st.showUnavail());
                u.value = st.showUnavail() ? "On" : "Off";
                u.good = st.showUnavail();
                host.index().refresh(st); // new setting: lists rebuild at once
                host.countsChanged();
                ad.notifyDataSetChanged();
            }
        };
        final Item sk = row("Skip channels that don't start", st.autoSkip() ? "On" : "Off", null);
        sk.good = st.autoSkip();
        sk.sub = st.autoSkip() ? "Jumps to the next channel after 15 s" : "Waits 15 s, then asks what to do";
        sk.act = new Runnable() {
            @Override public void run() {
                st.setAutoSkip(!st.autoSkip());
                sk.value = st.autoSkip() ? "On" : "Off";
                sk.good = st.autoSkip();
                sk.sub = st.autoSkip() ? "Jumps to the next channel after 15 s" : "Waits 15 s, then asks what to do";
                ad.notifyDataSetChanged();
            }
        };
        final Item ev = row("Even out volume", st.evenVolume() ? "On" : "Off", null);
        ev.good = st.evenVolume();
        ev.sub = "Quiet and loud channels sound alike";
        ev.act = new Runnable() {
            @Override public void run() {
                st.setEvenVolume(!st.evenVolume());
                ev.value = st.evenVolume() ? "On" : "Off";
                ev.good = st.evenVolume();
                ad.notifyDataSetChanged();
            }
        };
        final Item sd = row("Subtitles by default", st.subsDefault() ? "On" : "Off", null);
        sd.good = st.subsDefault();
        sd.sub = "On when a channel has them";
        sd.act = new Runnable() {
            @Override public void run() {
                st.setSubsDefault(!st.subsDefault());
                sd.value = st.subsDefault() ? "On" : "Off";
                sd.good = st.subsDefault();
                ad.notifyDataSetChanged();
            }
        };
        final Item stl = row("Subtitle translation", st.subTranslate() ? "Auto" : "Off", null);
        stl.good = st.subTranslate();
        stl.sub = "Auto: subtitles are always shown in English, translated on this TV";
        stl.act = new Runnable() {
            @Override public void run() {
                st.setSubTranslate(!st.subTranslate());
                stl.value = st.subTranslate() ? "Auto" : "Off";
                stl.good = st.subTranslate();
                ad.notifyDataSetChanged();
            }
        };
        row("Downloaded languages", "›", new Runnable() { @Override public void run() { trModels = null; go("trmodels", ""); } }).sub = "Offline translation models; delete to free space";
        final Item cl = row("Merge regional versions across languages", st.crossLang() ? "On" : "Off", null);
        cl.good = st.crossLang();
        cl.sub = "Same channel name in another country becomes one entry";
        cl.act = new Runnable() {
            @Override public void run() {
                st.setCrossLang(!st.crossLang());
                cl.value = st.crossLang() ? "On" : "Off";
                cl.good = st.crossLang();
                host.index().refresh(st); // new setting: lists rebuild at once
                host.countsChanged();
                ad.notifyDataSetChanged();
            }
        };
        final Item sp = row("Hide plots of unwatched episodes", st.hideUnwatchedPlots() ? "On" : "Off", null);
        sp.good = st.hideUnwatchedPlots();
        sp.act = new Runnable() {
            @Override public void run() {
                st.setHideUnwatchedPlots(!st.hideUnwatchedPlots());
                sp.value = st.hideUnwatchedPlots() ? "On" : "Off";
                sp.good = st.hideUnwatchedPlots();
                ad.notifyDataSetChanged();
            }
        };
        row("Re-check hidden channels", "", new Runnable() { @Override public void run() { host.recheckHidden(); } });
        header("Display");
        final String[] pv = {"off", "tl", "tr", "bl", "br"}, pn = {"Off", "Top-left", "Top-right", "Bottom-left", "Bottom-right"};
        final Item w = row("Corner logo & title", pn[Math.max(0, java.util.Arrays.asList(pv).indexOf(st.wmPos()))], null);
        w.act = new Runnable() {
            @Override public void run() {
                int i = (java.util.Arrays.asList(pv).indexOf(st.wmPos()) + 1) % pv.length;
                st.setWmPos(pv[i]);
                w.value = pn[i];
                ad.notifyDataSetChanged();
            }
        };
        final Item wm = row("Show", st.wmMode().equals("always") ? "Always" : "After channel change (8 s)", null);
        wm.act = new Runnable() {
            @Override public void run() {
                String nm = st.wmMode().equals("always") ? "change" : "always";
                st.setWmMode(nm);
                wm.value = nm.equals("always") ? "Always" : "After channel change (8 s)";
                ad.notifyDataSetChanged();
            }
        };
        final Item ed = row("Screen edges", Ui.edge() + "%", null);
        ed.sub = "Margin kept clear of text. OK adds 1% (10 wraps to 0); a frame shows the area";
        ed.preview = true;
        ed.act = new Runnable() {
            @Override public void run() {
                int n = (Ui.edge() + 1) % 11;
                st.setEdge(n);
                Ui.setEdge(n);
                ed.value = n + "%";
                ad.notifyDataSetChanged();
                host.edgesChanged();
            }
        };
        final String[] fv = {"auto", "smooth", "rich"}, fn = {"Auto", "Smooth", "Rich"};
        final Item pf = row("Performance", fn[Math.max(0, java.util.Arrays.asList(fv).indexOf(st.perf()))], null);
        pf.sub = "This TV counts as " + Ui.tierName() + ". Smooth = no animations, Rich = all effects";
        pf.act = new Runnable() {
            @Override public void run() {
                int i = (java.util.Arrays.asList(fv).indexOf(st.perf()) + 1) % fv.length;
                st.setPerf(fv[i]);
                Ui.setPerf(fv[i]);
                pf.value = fn[i];
                pf.sub = "This TV counts as " + Ui.tierName() + ". Smooth = no animations, Rich = all effects";
                ad.notifyDataSetChanged();
            }
        };
        header("Remote");
        row("Remote keys", "›", new Runnable() { @Override public void run() { host.showRemoteKeys(); } });
        row("Remote test", "›", new Runnable() { @Override public void run() { host.showRemoteTest(); } });
        header("Favourites");
        row("Restore favourites", "›", new Runnable() { @Override public void run() { go("favsnaps", ""); } });
        row("Backup", "›", new Runnable() { @Override public void run() { go("backup", ""); } }).sub = "Favourites, playlists and settings in one file";
        header("For You");
        row("Run setup again", "›", new Runnable() {
            @Override public void run() { wizardRev = st.setupRev(); waitWizard = true; lk.manus.iptv.ui.wizard.Wizard.start(getContext()); }
        });
        row("For You and hidden groups", "›", new Runnable() { @Override public void run() { go("foryou", ""); } });
        header("About");
        row("About Manus TV", "›", new Runnable() { @Override public void run() { go("about", ""); } });
        row("Exit Manus TV", "", new Runnable() { @Override public void run() { host.exit(); } }).bad = true;
    }

    private static String qualityName(String q) { return q.equals("saver") ? "Data saver" : q.equals("best") ? "Best" : "Auto"; }

    private String ago(long ts) {
        if (ts == 0) return "not refreshed yet";
        long m = (System.currentTimeMillis() - ts) / 60000;
        if (m < 1) return "refreshed just now";
        if (m < 60) return "refreshed " + m + " min ago";
        if (m < 1440) return "refreshed " + (m / 60) + " h ago";
        return "refreshed " + (m / 1440) + " days ago";
    }

    private String counts(Sources.Source s) { return s.count < 0 ? "not loaded yet" : nf.format(s.count) + " channels"; }

    private void sourcesPage() {
        head("Sources", Config.IPTVORG ? "iptv-org is built in. Add your own playlist links." : "Add your own playlist links.");
        for (final Sources.Source s : host.sources().list()) {
            Item i = row(s.name, s.enabled ? "ON" : "OFF", new Runnable() { @Override public void run() { go("src", s.id); } });
            i.sub = counts(s) + "  ·  " + ago(s.ts);
            i.good = s.enabled;
        }
        final Db db = Db.get();
        List<XtreamSource.SourceInfo> xlist = (!Config.PLAY && db != null) ? XtreamSource.list(db) : java.util.Collections.<XtreamSource.SourceInfo>emptyList();
        if (!Config.PLAY) {
            for (final XtreamSource.SourceInfo xs : xlist) {
                Item i = row(xs.name, "›", new Runnable() { @Override public void run() { go("xsrc", String.valueOf(xs.id)); } });
                i.sub = xs.filmCount + " " + (xs.filmCount == 1 ? "film" : "films") + "  ·  " + XtreamSource.syncStatus(xs.id, xs.lastSync);
            }
        }
        if (host.sources().list().isEmpty() && xlist.isEmpty()) note("No channels yet \u2014 add a playlist below.");
        row("+ Add source", "", new Runnable() { @Override public void run() { go("add", ""); } }).good = true;
        if (!Config.PLAY) {
            row("Add an Xtream login", "", new Runnable() { @Override public void run() { go("addxtream", ""); } }).good = true;
        }
    }

    private void srcPage() {
        final Sources src = host.sources();
        final Sources.Source s = src.get(arg());
        if (s == null) { back(); return; }
        head(s.name, (s.builtin() ? "Built in  ·  " : Redact.host(s.url) + "  ·  ") + counts(s) + "  ·  " + ago(s.ts));
        final Item en = row("Enabled", s.enabled ? "ON" : "OFF", null);
        en.good = s.enabled;
        en.act = new Runnable() {
            @Override public void run() {
                Sources.Source cur = src.get(s.id);
                if (cur.enabled && src.enabledCount() <= 1) { en.sub = "Keep at least one source on"; ad.notifyDataSetChanged(); return; }
                src.setEnabled(s.id, !cur.enabled);
                en.sub = "";
                en.value = !cur.enabled ? "ON" : "OFF";
                en.good = !cur.enabled;
                ad.notifyDataSetChanged();
                host.sourcesChanged();
            }
        };
        final Item rf = row("Refresh now", "", null);
        rf.act = new Runnable() { @Override public void run() { rf.value = "Refreshing…"; ad.notifyDataSetChanged(); host.sourcesChanged(); } };
        if (!s.builtin() || !Config.IPTVORG) {
            row("Rename", "›", new Runnable() { @Override public void run() { go("rename", s.id); } });
            final Item rm = row("Remove this source", "", null);
            rm.bad = true;
            rm.act = new Runnable() {
                @Override public void run() {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (!confirmRemove.equals(s.id) || now - confirmAt > 4000) { confirmRemove = s.id; confirmAt = now; rm.label = "Press OK again to remove"; ad.notifyDataSetChanged(); return; }
                    src.remove(s.id);
                    host.sourcesChanged();
                    back();
                }
            };
        }
    }

    private void xtreamSourcePage() {
        final Db db = Db.get();
        long id = -1;
        try { id = Long.parseLong(arg()); } catch (Exception ignored) {}
        final XtreamSource.SourceInfo xs = (db != null && id >= 0) ? XtreamSource.get(db, id) : null;
        if (xs == null) { back(); return; }

        final String syncText = XtreamSource.syncStatus(xs.id, xs.lastSync);
        head(xs.name, Redact.host(xs.address) + "  ·  " + xs.filmCount + " " + (xs.filmCount == 1 ? "film" : "films") + "  ·  " + syncText);

        final Item syncItem = row("Sync now", "", null);
        syncItem.act = new Runnable() {
            @Override public void run() {
                syncItem.value = "Syncing…";
                ad.notifyDataSetChanged();
                XtreamSource.sync(appCtx(), xs.id, new XtreamSource.Listener() {
                    @Override public void onProgress(final int films) {
                        post(new Runnable() {
                            @Override public void run() {
                                if (page.equals("xsrc")) {
                                    syncItem.sub = "Syncing films… " + films + " so far";
                                    ad.notifyDataSetChanged();
                                }
                            }
                        });
                    }
                    @Override public void onDone(final int films, final String err) {
                        post(new Runnable() {
                            @Override public void run() {
                                host.filmsChanged();
                                if (page.equals("xsrc")) render();
                            }
                        });
                    }
                });
            }
        };

        final Item rmItem = row("Remove", "", null);
        rmItem.bad = true;
        rmItem.act = new Runnable() {
            @Override public void run() {
                final boolean hasProg = XtreamSource.hasProgress(db, xs.id);
                if (!hasProg) {
                    host.confirm("Remove this source? Its films will be deleted.", "", "Remove", "Cancel", new Runnable() {
                        @Override public void run() {
                            XtreamSource.remove(db, xs.id, false);
                            host.filmsChanged();
                            back();
                        }
                    });
                } else {
                    host.confirm3("You have watched films from this source. Keep your place in them?", "", "Keep my progress", "Delete progress", "Cancel",
                            new Runnable() {
                                @Override public void run() {
                                    XtreamSource.remove(db, xs.id, false);
                                    host.filmsChanged();
                                    back();
                                }
                            },
                            new Runnable() {
                                @Override public void run() {
                                    XtreamSource.remove(db, xs.id, true);
                                    host.filmsChanged();
                                    back();
                                }
                            });
                }
            }
        };
    }

    private void addXtreamPage() {
        head("Add an Xtream login", "Open the address on your phone (same Wi-Fi)");
        form.removeAllViews();
        urlEt = null;
        nameEt = null;
        LinearLayout left = new LinearLayout(getContext());
        left.setOrientation(LinearLayout.VERTICAL);
        btn = button("Cancel");
        left.addView(btn, new LinearLayout.LayoutParams(-1, Ui.u(52)));
        btn.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (fresh(v)) back(); } });
        form.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        final Context ctx = appCtx();
        pane(AddServer.forXtream(ctx, new AddServer.Listener() {
            @Override public void onCode(String code) {
                if (codeTv != null) codeTv.setText(code.substring(0, 3) + " " + code.substring(3));
            }
            @Override public void onReceived(String url, String name) {
                host.pill("Added. Your films are loading on the TV.");
                host.filmsChanged();
                if (page.equals("addxtream")) back();
            }
        }));
    }

    private void forYouPage() {
        final Store st = host.store();
        head("For You", "Which channels make up the For You list");
        row("Pinned countries", st.fyCountries().size() + " pinned  ›", new Runnable() { @Override public void run() { go("fycountries", ""); } });
        row("Categories", st.fyCats().size() + " chosen  ›", new Runnable() { @Override public void run() { go("fycats", ""); } });
        row("Reset to default", "", new Runnable() {
            @Override public void run() { st.resetFy(); fyDirty = true; render(); }
        });
        note("For You = pinned countries' channels in the chosen categories. Pinned countries also come first in the Countries list.");
        final java.util.Set<String> hid = st.hidden();
        if (!hid.isEmpty()) {
            header("Hidden groups");
            for (final String id : hid) {
                row(id.startsWith("cc:") ? Ui.flag(id.substring(3)) + "  " + Ui.country(id.substring(3)) : id.substring(id.indexOf(':') + 1), "Unhide", new Runnable() {
                    @Override public void run() { st.unhide(id); render(); }
                }).good = true;
            }
        }
    }

    private void pickPage(final boolean countries) {
        final Store st = host.store();
        final Index ix = host.index();
        final Set<String> sel = countries ? st.fyCountries() : st.fyCats();
        head(countries ? "Pinned countries" : "For You: categories", "OK ticks or unticks. Changes apply when you go back.");
        List<String> keys = countries ? ix.countryOrder : ix.catOrder;
        for (final String k : keys) {
            final Item i = row(countries ? Ui.flag(k) + "  " + Ui.country(k) : k, sel.contains(k) ? "✓" : "", null);
            i.good = true;
            i.act = new Runnable() {
                @Override public void run() {
                    if (!sel.remove(k)) sel.add(k);
                    if (countries) st.setFyCountries(sel); else st.setFyCats(sel);
                    i.value = sel.contains(k) ? "✓" : "";
                    fyDirty = true;
                    ad.notifyDataSetChanged();
                }
            };
        }
    }

    private void aboutPage() {
        head("About", "Manus TV");
        String v = "?";
        try {
            android.content.pm.PackageInfo pi = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            v = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception ignored) { }
        row("App version", v, null);
        row("Favourites history", "›", new Runnable() { @Override public void run() { go("favhist", ""); } });
        Index ix = host.index();
        row("Unique channels", ix == null ? "?" : nf.format(ix.all.size()), null);
        for (Sources.Source s : host.sources().list()) {
            row(s.name, (s.enabled ? "" : "off  ·  ") + counts(s), null);
        }
        header("Film information");
        Item lg = new Item(""); lg.logo = true; lg.focusable = false; items.add(lg);
        note("This product uses the TMDB API but is not endorsed or certified by TMDB.");
        final CloudData cloud = CloudData.get(getContext());
        Item fd = row("Channel data feed", cloud.hasFeed() ? cloud.feedHost() + "  ›" : "Not set  ›", new Runnable() { @Override public void run() { go("feed", ""); } });
        if (!cloud.hasFeed()) { fd.sub = "Optional: add a channel data feed \u2014 see the guide"; return; }
        long up = CloudData.updatedAt();
        Item cd = row("Channel data", "", null);
        cd.sub = up == 0 ? "not downloaded yet"
                : "updated " + agoShort(up) + ", " + nf.format(CloudData.aliveCount()) + " alive, guide for " + nf.format(CloudData.epgChannels()) + " channels";
        final Item rf = row("Refresh channel data now", "", null);
        rf.act = new Runnable() {
            @Override public void run() {
                if (rf.value.startsWith("Refresh")) return;
                rf.value = "Refreshing…";
                ad.notifyDataSetChanged();
                CloudData.get(getContext()).refreshNow(new java.util.function.Consumer<String>() {
                    @Override public void accept(final String msg) {
                        post(new Runnable() {
                            @Override public void run() {
                                rf.value = "";
                                host.index().rebuildNow(); // re-index: the lists re-rank with the new statuses
                                host.countsChanged();
                                host.pill(msg);
                                if (page.equals("about")) render();
                            }
                        });
                    }
                });
            }
        };
    }

    private static final java.text.SimpleDateFormat WHEN = new java.text.SimpleDateFormat("d MMM, HH:mm", Locale.US);

    /** T33: the saved copies of the favourites list (newest first). OK asks, with Cancel focused; nothing changes until Restore. */
    private List<String> trModels; // null = not loaded yet

    /** T40: translation models on this TV, each deletable (they download again when needed). */
    private void trModelsPage() {
        head("Downloaded languages", "Subtitle translation models stored on this TV (about 30 MB each)");
        if (trModels == null) {
            note("Checking downloaded languages…");
            lk.manus.iptv.subs.SubTranslator.downloaded(getContext(), new lk.manus.iptv.subs.SubTranslator.ListCb() {
                @Override public void done(List<String> l) { trModels = l == null ? new ArrayList<String>() : l; if (page.equals("trmodels")) render(); }
            });
            return;
        }
        if (trModels.isEmpty()) note("None yet. A model downloads the first time a non-English subtitle track needs it.");
        for (final String lg : trModels) {
            Item i = row(lk.manus.iptv.subs.SubTranslator.langName(lg) + " → English", "Delete", null);
            i.bad = true;
            i.act = new Runnable() {
                @Override public void run() {
                    lk.manus.iptv.subs.SubTranslator.delete(lg, new Runnable() { @Override public void run() { trModels = null; host.pill("Deleted " + lk.manus.iptv.subs.SubTranslator.langName(lg) + " model"); if (page.equals("trmodels")) render(); } });
                }
            };
        }
    }

    private void favSnapsPage() {
        head("Restore favourites", "Copies are saved before every change (last " + lk.manus.iptv.data.FavGuard.MAX_SNAPS + ")");
        List<lk.manus.iptv.data.FavGuard.Snap> sn = lk.manus.iptv.data.FavGuard.snaps();
        if (sn.isEmpty()) note("No saved copies yet. One is made the first time you add or remove a favourite.");
        for (final lk.manus.iptv.data.FavGuard.Snap x : sn) {
            Item i = row(WHEN.format(new java.util.Date(x.time)), x.urls.size() + (x.urls.size() == 1 ? " favourite" : " favourites"), null);
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < Math.min(3, x.names.size()); k++) b.append(k > 0 ? ", " : "").append(x.names.get(k).isEmpty() ? "?" : x.names.get(k));
            if (x.names.size() > 3) b.append(", …");
            i.sub = b.length() == 0 ? "empty list" : b.toString();
            i.act = new Runnable() {
                @Override public void run() {
                    host.confirm("Restore these " + x.urls.size() + " favourites?", "Your current list is saved first, so you can come back.", "Restore", "Cancel", new Runnable() {
                        @Override public void run() { host.restoreFavourites(x.urls); host.pill("Favourites restored (" + x.urls.size() + ")"); back(); }
                    });
                }
            };
        }
    }

    /** T33: the last 200 adds / removes, newest first. */
    /** Settings > Reminders: soonest first; OK asks before removing. */
    private void remindersPage() {
        head("Reminders", "Films you asked to be told about");
        for (final lk.manus.iptv.data.Reminders.Item r : lk.manus.iptv.data.Reminders.list()) {
            final String label = Films.join(CloudData.hm(r.startSec), r.title, r.channel);
            row(label, "", new Runnable() {
                @Override public void run() {
                    host.confirm("Remove this reminder?", label, "Remove", "Keep", new Runnable() {
                        @Override public void run() {
                            lk.manus.iptv.data.Reminders.remove(r.key, r.startSec);
                            host.remindersChanged();
                            if (lk.manus.iptv.data.Reminders.list().isEmpty()) { back(); return; }
                            int at = list.getSelectedItemPosition();
                            render();
                            list.setSelection(Math.max(first(), Math.min(at, items.size() - 1))); // keeps its place
                        }
                    });
                }
            });
        }
    }

    private void favHistPage() {
        head("Favourites history", "Last " + lk.manus.iptv.data.FavGuard.MAX_HIST + " changes, newest first");
        List<lk.manus.iptv.data.FavGuard.Event> ev = lk.manus.iptv.data.FavGuard.history();
        if (ev.isEmpty()) note("Nothing yet.");
        for (lk.manus.iptv.data.FavGuard.Event e : ev) {
            Item i = row(e.name, e.added ? "Added" : "Removed", null);
            i.sub = WHEN.format(new java.util.Date(e.time));
            i.good = e.added;
        }
    }

    private static String agoShort(long ts) {
        long m = (System.currentTimeMillis() - ts) / 60000;
        return m < 1 ? "just now" : m < 60 ? m + " min ago" : m < 1440 ? (m / 60) + " h ago" : (m / 1440) + " days ago";
    }

    // ---- add / rename forms ----

    private EditText edit(String hint, int type) {
        final EditText e = new EditText(getContext());
        e.setSingleLine(true);
        Ui.size(e, Ui.BODY);
        e.setTextColor(Ui.PAPER);
        e.setHintTextColor(Ui.MUTED);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | type);
        e.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        e.setPadding(Ui.u(16), Ui.u(10), Ui.u(16), Ui.u(10));
        android.graphics.drawable.GradientDrawable ib = Ui.rect(Ui.INK, 14);
        ib.setStroke(Math.max(1, Ui.u(1)), Ui.LINE);
        e.setBackground(focusBg(ib));
        e.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent ev) {
                if (k == KeyEvent.KEYCODE_DPAD_CENTER) {
                    if (ev.getAction() == KeyEvent.ACTION_UP) {
                        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(v, 0);
                    }
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) { // one-line field: move between rows, not the caret
                    if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                        View n = v.focusSearch(k == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);
                        if (n != null) n.requestFocus();
                    }
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && e.getSelectionStart() <= 0) {
                    if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) leftOut();
                    return true;
                }
                return false;
            }
        });
        return e;
    }

    private static StateListDrawable focusBg(android.graphics.drawable.Drawable idle) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Ui.ring());
        s.addState(new int[]{}, idle);
        return s;
    }

    private TextView label(String t) {
        TextView l = Ui.tv(getContext(), Ui.CAPTION, Ui.MUTED, false);
        l.setText(t);
        l.setPadding(Ui.u(4), Ui.u(10), 0, Ui.u(4));
        return l;
    }

    private TextView button(String t) {
        TextView b = Ui.tv(getContext(), Ui.BODY, Ui.PAPER, true);
        b.setText(t);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setBackground(focusBg(Ui.card(1f, 14)));
        b.setOnKeyListener(new OnKeyListener() {
            @Override public boolean onKey(View v, int k, KeyEvent ev) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) { if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) leftOut(); return true; }
                if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) downOn = v;
                return false;
            }
        });
        return b;
    }

    private void addPage() {
        head("Add source", "Type or paste a playlist link (M3U), or send it from your phone");
        form.removeAllViews();
        LinearLayout left = new LinearLayout(getContext());
        left.setOrientation(LinearLayout.VERTICAL);
        if (!Config.PLAY) {
            left.addView(label("Suggested"));
            if (host.sources().get(Sources.DEFAULT_ID) == null) left.addView(suggest("iptv-org free public list (community-maintained)", Sources.DEFAULT_URL, Sources.FREE_NAME), new LinearLayout.LayoutParams(-1, Ui.u(60)));
            left.addView(suggest("Free-TV (official free broadcasters, mostly Europe)", Sources.FREE_TV_URL, "Free-TV"), new LinearLayout.LayoutParams(-1, Ui.u(60)));
        }
        left.addView(label("Playlist link"));
        urlEt = edit("https://example.com/playlist.m3u", InputType.TYPE_TEXT_VARIATION_URI);
        left.addView(urlEt, new LinearLayout.LayoutParams(-1, -2));
        left.addView(label("Name (optional)"));
        nameEt = edit("My playlist", 0);
        nameEt.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        left.addView(nameEt, new LinearLayout.LayoutParams(-1, -2));
        nameEt.setOnEditorActionListener(new TextView.OnEditorActionListener() { // Done on the keyboard: hide it, land on the button
            @Override public boolean onEditorAction(TextView v, int a, KeyEvent e) { hideIme(); btn.requestFocus(); return true; }
        });
        btn = button("Check link");
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.u(52));
        bl.topMargin = Ui.u(18);
        left.addView(btn, bl);
        status = Ui.tv(getContext(), Ui.BODY, Ui.MUTED, false);
        status.setSingleLine(false);
        status.setPadding(Ui.u(4), Ui.u(12), 0, 0);
        left.addView(status);
        form.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        urlEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                if (pending != null) { host.sources().discard(pending); pending = null; btn.setText("Check link"); status.setText(""); }
            }
        });
        btn.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (fresh(v)) onButton(); } });

        phonePane(new AddServer.Listener() {
            @Override public void onCode(String code) { if (codeTv != null) codeTv.setText(code.substring(0, 3) + " " + code.substring(3)); }
            @Override public void onReceived(String url, String name) {
                urlEt.setText(url);
                nameEt.setText(name);
                check();
            }
        }, false, null);
    }

    private TextView suggest(String text, final String url, final String name) {
        TextView t = button(text);
        Ui.size(t, Ui.CAPTION);
        t.setMaxLines(2);
        t.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { urlEt.setText(url); nameEt.setText(name); check(); }
        });
        return t;
    }

    /** The phone side of a form page: QR + address + one-time code on the right, and the phone server that feeds {@code l}. */
    private void phonePane(AddServer.Listener l, boolean feed, AddServer.KeyCheck kc) { pane(kc != null ? new AddServer(l, kc) : new AddServer(l, feed)); }

    private void pane(AddServer s) {
        LinearLayout right = new LinearLayout(getContext());
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        right.setPadding(Ui.u(24), 0, 0, 0);
        right.addView(label("From your phone (same Wi-Fi)"));
        qr = new ImageView(getContext());
        qr.setBackground(Ui.rect(Ui.PAPER, 10));
        right.addView(qr, new LinearLayout.LayoutParams(Ui.u(170), Ui.u(170)));
        final TextView addr = Ui.tv(getContext(), Ui.CAPTION, Ui.PAPER, true);
        addr.setPadding(0, Ui.u(8), 0, 0);
        right.addView(addr);
        TextView cl = label("Code to type on the phone");
        right.addView(cl);
        codeTv = Ui.tv(getContext(), Ui.DISPLAY, Ui.PAPER, true);
        right.addView(codeTv);
        form.addView(right, new LinearLayout.LayoutParams(Ui.u(250), -2));

        server = s;
        if (server.start() && server.address() != null) {
            addr.setText(server.address());
            qr.setImageBitmap(Qr.bitmap(server.address(), Ui.u(170)));
        } else {
            addr.setText("Phone page unavailable (no Wi-Fi address)");
        }
    }

    /** True only if this press started on the button itself (key-down seen here), so focus landing under a held or repeated OK never fires it. */
    private boolean fresh(View v) { boolean ok = downOn == v; downOn = null; return ok; }

    private void onButton() {
        if (pending == null) { check(); return; }
        Sources.Probe p = pending;
        pending = null;
        host.sources().add(nameEt.getText().toString(), p);
        host.sourcesChanged();
        back();
    }

    private void check() {
        final String u = urlEt.getText().toString().trim();
        if (u.isEmpty()) { status.setTextColor(Ui.WARN); status.setText("Type or paste a link first."); return; }
        hideIme();
        status.setTextColor(Ui.MUTED);
        status.setText("Checking…");
        final int g = ++probeGen;
        final Sources src = host.sources();
        new Thread(new Runnable() {
            @Override public void run() {
                final Sources.Probe p = src.probe(u);
                post(new Runnable() {
                    @Override public void run() {
                        if (g != probeGen || !page.equals("add")) { src.discard(p); return; }
                        if (p.error != null) { status.setTextColor(Ui.WARN); status.setText(p.error); return; }
                        pending = p;
                        status.setTextColor(Ui.PAPER);
                        status.setText(nf.format(p.count) + " channels found");
                        btn.setText("Save");
                        status.setText(nf.format(p.count) + " channels found. Move down to Save, or press Back to cancel.");
                    }
                });
            }
        }, "probe").start();
    }

    private void renamePage() {
        final Sources.Source s = host.sources().get(arg());
        if (s == null) { back(); return; }
        head("Rename source", "");
        form.removeAllViews();
        LinearLayout left = new LinearLayout(getContext());
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(label("Name"));
        urlEt = null;
        nameEt = edit("Name", 0);
        nameEt.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        nameEt.setText(s.name);
        left.addView(nameEt, new LinearLayout.LayoutParams(-1, -2));
        btn = button("Save");
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.u(52));
        bl.topMargin = Ui.u(18);
        left.addView(btn, bl);
        btn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (!fresh(v)) return;
                host.sources().rename(s.id, nameEt.getText().toString());
                host.sourcesChanged();
                back();
            }
        });
        form.addView(left, new LinearLayout.LayoutParams(Ui.u(460), -2));
    }

    /** Optional channel data feed: the address of a feed you host (see the README). Checked by fetching its meta.json before it is saved. */
    private void feedPage() {
        final CloudData cloud = CloudData.get(getContext());
        head("Channel data feed", "Optional: shows which channels work and what is on now. Paste your feed link, or send it from your phone.");
        form.removeAllViews();
        LinearLayout left = new LinearLayout(getContext());
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(label("Feed link"));
        nameEt = null;
        urlEt = edit("https://example.com/manus-iptv-data/", InputType.TYPE_TEXT_VARIATION_URI);
        urlEt.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        left.addView(urlEt, new LinearLayout.LayoutParams(-1, -2));
        btn = button("Check and save");
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.u(52));
        bl.topMargin = Ui.u(18);
        left.addView(btn, bl);
        status = Ui.tv(getContext(), Ui.BODY, Ui.MUTED, false);
        status.setSingleLine(false);
        status.setPadding(Ui.u(4), Ui.u(12), 0, 0);
        urlEt.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, KeyEvent e) { hideIme(); btn.requestFocus(); return true; }
        });
        btn.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (fresh(v)) saveFeed(); } });
        if (cloud.hasFeed()) {
            TextView clear = button("Clear (use no feed)");
            clear.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    if (!fresh(v)) return;
                    status.setTextColor(Ui.MUTED);
                    status.setText("Clearing\u2026");
                    cloud.clearFeed(new Runnable() { @Override public void run() { post(new Runnable() { @Override public void run() { feedDone("Channel data feed cleared"); } }); } });
                }
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, Ui.u(52));
            cl.topMargin = Ui.u(10);
            left.addView(clear, cl);
        }
        left.addView(status);
        form.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        phonePane(new AddServer.Listener() {
            @Override public void onCode(String code) { if (codeTv != null) codeTv.setText(code.substring(0, 3) + " " + code.substring(3)); }
            @Override public void onReceived(String url, String name) { urlEt.setText(url); saveFeed(); }
        }, true, null);
    }

    private void saveFeed() {
        String u = urlEt.getText().toString().trim();
        if (u.isEmpty()) { status.setTextColor(Ui.WARN); status.setText("Type or paste a link first."); return; }
        hideIme();
        status.setTextColor(Ui.MUTED);
        status.setText("Checking\u2026");
        CloudData.get(getContext()).setFeed(u, new java.util.function.Consumer<String>() {
            @Override public void accept(final String err) {
                post(new Runnable() {
                    @Override public void run() {
                        if (!page.equals("feed")) return;
                        if (err != null) { status.setTextColor(Ui.WARN); status.setText(err); return; }
                        feedDone("Channel data feed saved");
                    }
                });
            }
        });
    }

    private void feedDone(String msg) {
        if (!page.equals("feed")) return;
        host.index().rebuildNow(); // the lists re-rank with (or without) the feed's statuses
        host.countsChanged();
        host.pill(msg);
        back();
    }

    // ---- film information (movie-database key) ----

    private String filmState() {
        Tmdb t = Tmdb.get(getContext());
        return t.hasOwnKey() ? "Your key" : t.hasKey() ? "Built in" : "Off";
    }

    private void filmPage() {
        final Tmdb t = Tmdb.get(getContext());
        head("Film information", filmState());
        row("Add from phone", "›", new Runnable() { @Override public void run() { go("filmkey", ""); } });
        if (t.hasOwnKey()) {
            row("Remove key", "", new Runnable() {
                @Override public void run() {
                    host.confirm("Remove the key and delete saved film details?", "", "Remove", "Keep", new Runnable() {
                        @Override public void run() {
                            t.clearKey();
                            MetaStore.get(getContext()).wipe();
                            host.filmsChanged();
                            if (page.equals("film")) render();
                        }
                    });
                }
            }).bad = true;
        }
    }

    /** The phone page for the key: QR, address and one-time code. The key is saved only after TMDB accepts it (one request, on the server thread). */
    private void filmKeyPage() {
        head("Add from phone", "");
        form.removeAllViews();
        urlEt = null;
        nameEt = null;
        LinearLayout left = new LinearLayout(getContext());
        left.setOrientation(LinearLayout.VERTICAL);
        btn = button("Cancel");
        left.addView(btn, new LinearLayout.LayoutParams(-1, Ui.u(52)));
        btn.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (fresh(v)) back(); } });
        form.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        final Context ctx = getContext().getApplicationContext();
        phonePane(new AddServer.Listener() {
            @Override public void onCode(String code) { if (codeTv != null) codeTv.setText(code.substring(0, 3) + " " + code.substring(3)); }
            @Override public void onReceived(String url, String name) {
                host.filmsChanged();
                if (page.equals("filmkey")) back();
            }
        }, false, new AddServer.KeyCheck() {
            @Override public String accept(String key) {
                Tmdb t = Tmdb.get(ctx);
                int r = t.verify(key);
                if (r == 0) return "That key was not accepted.";
                if (r < 0) return "Could not reach the movie database. Try again.";
                t.setKey(key);
                return null;
            }
        });
    }

    // ---- backup and restore (T72) ----

    private Context appCtx() { return getContext().getApplicationContext(); }

    private void backupPage() {
        head("Backup", "Favourites, playlists and settings, in one file only you can open");
        row("Back up", "\u203a", new Runnable() { @Override public void run() { go("bkup", ""); } });
        row("Restore", "\u203a", new Runnable() { @Override public void run() { go("rest", ""); } });
        if (!BackupOps.localCopies(appCtx()).isEmpty()) row("Restore from this TV", "\u203a", new Runnable() { @Override public void run() { go("restfile", ""); } });
        long t = BackupOps.lastBackupMs(appCtx());
        note(t == 0 ? "No backup yet" : "Last backup: " + BackupOps.when(t));
    }

    private void restoreFilePage() {
        head("Restore from this TV", "Copies kept on this TV");
        for (final File f : BackupOps.localCopies(appCtx())) {
            row(BackupOps.when(f.lastModified()), (f.length() + 1023) / 1024 + " KB  \u203a", new Runnable() { @Override public void run() { go("restpass", f.getName()); } });
        }
    }

    /** What the phone page asks the TV to do. Runs on the phone server's thread. */
    private AddServer.BackupHooks hooks(final String localName) {
        final Context ctx = appCtx();
        final SharedPreferences p = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE);
        final Store st = host.store();
        final Sources src = host.sources();
        return new AddServer.BackupHooks() {
            @Override public AddServer.Download backup(char[] pass) throws AddServer.Refused { working(); return BackupOps.backup(ctx, p, st, src, pass); }
            @Override public void restore(byte[] file, char[] pass) throws AddServer.Refused {
                working();
                final Backup.Data d = BackupOps.open(file != null ? file : BackupOps.readLocal(ctx, localName), pass); // decrypted and checked here; nothing is applied yet
                post(new Runnable() { @Override public void run() { offerRestore(d); } });
            }
        };
    }

    /** The key derivation takes seconds on a slow TV: say so (quietly) instead of looking frozen. Server thread to main thread. */
    private void working() { post(new Runnable() { @Override public void run() { host.pill("Working\u2026"); } }); }

    private void backupFormShell(String t, String s, AddServer srv) {
        head(t, s);
        form.removeAllViews();
        urlEt = null;
        nameEt = null;
        LinearLayout left = new LinearLayout(getContext());
        left.setOrientation(LinearLayout.VERTICAL);
        btn = button("Cancel");
        left.addView(btn, new LinearLayout.LayoutParams(-1, Ui.u(52)));
        btn.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (fresh(v)) back(); } });
        form.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        pane(srv);
    }

    private AddServer.Listener codeOnly(final AddServer.Listener inner) {
        return new AddServer.Listener() {
            @Override public void onCode(String code) { if (codeTv != null) codeTv.setText(code.substring(0, 3) + " " + code.substring(3)); }
            @Override public void onReceived(String a, String b) { if (inner != null) inner.onReceived(a, b); }
        };
    }

    private void backupFormPage() {
        backupFormShell("Back up", "On your phone: choose a passphrase. You will need it to restore.", AddServer.forBackup(codeOnly(new AddServer.Listener() {
            @Override public void onCode(String code) { }
            @Override public void onReceived(String file, String info) { // the phone has the file; info = "kept:N" or ":N"
                int n = Integer.parseInt(info.substring(info.indexOf(':') + 1));
                host.pill((info.startsWith("kept") ? "Backup saved." : "Backup made, but this TV could not keep a copy.") + (n == 0 ? "" : " " + n + (n == 1 ? " item" : " items") + " could not be included."));
                if (page.equals("bkup")) toBackupPage();
            }
        }), hooks(null)));
    }

    /** localName "" = a file from the phone; else the name of a copy kept on this TV. */
    private void restoreFormPage(String localName) {
        boolean local = !localName.isEmpty();
        backupFormShell("Restore", local ? "On your phone: type the passphrase of the backup from " + BackupOps.when(new File(appCtx().getFilesDir(), "backup/" + localName).lastModified()) + "."
                : "On your phone: choose the backup file and type its passphrase.", AddServer.forRestore(codeOnly(null), hooks(local ? localName : null), local));
    }

    private void toBackupPage() { while (!page.equals("backup") && back()) { } }

    /** A backup was opened (right passphrase, valid file). Nothing changes until Restore is pressed; Cancel is focused. */
    private void offerRestore(final Backup.Data d) {
        if (getVisibility() != VISIBLE || !(page.equals("rest") || page.equals("restpass"))) return; // left the page meanwhile: nothing is offered
        toBackupPage();
        int f = d.favs.size(), s = d.sources.size();
        host.confirm("Restore " + f + (f == 1 ? " favourite" : " favourites") + " and " + s + (s == 1 ? " playlist" : " playlists") + "?",
                "This replaces what is on this TV.", "Restore", "Cancel", new Runnable() { @Override public void run() { applyRestore(d); } });
    }

    private void applyRestore(final Backup.Data d) {
        host.pill("Restoring\u2026");
        final Context ctx = appCtx();
        final SharedPreferences p = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE);
        final Store st = host.store();
        final Sources src = host.sources();
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                try { BackupOps.restore(ctx, p, st, src, d); ok = true; }
                catch (Exception e) { android.util.Log.w("IPTV", "restore failed: " + e.getClass().getSimpleName()); } // class only
                final boolean done = ok;
                post(new Runnable() {
                    @Override public void run() {
                        if (done) { host.restored(); host.pill("Restored."); if (getVisibility() == VISIBLE && !isForm()) render(); }
                        else host.pill("Could not restore. Nothing was changed.");
                    }
                });
            }
        }, "restore").start();
    }

    // ---- row ----

    private final class SRow extends LinearLayout {
        private final TextView label, subT, value;
        SRow(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setLayoutParams(new AbsListView.LayoutParams(-1, Ui.u(62)));
            setPadding(Ui.u(16), 0, Ui.u(18), 0);
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_selected}, Ui.ring());
            sl.addState(new int[]{}, new ColorDrawable(0));
            setBackground(sl);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            label = Ui.tv(c, Ui.BODY, Ui.PAPER, true);
            subT = Ui.tv(c, Ui.CAPTION, Ui.MUTED, false);
            subT.setPadding(0, Ui.u(3), 0, 0);
            col.addView(label);
            col.addView(subT);
            value = Ui.tv(c, Ui.BODY, Ui.MUTED, true);
            addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            addView(value);
        }
        void bind(Item it) {
            label.setText(it.label);
            label.setTextColor(it.bad ? Ui.WARN : Ui.PAPER);
            subT.setText(it.sub);
            subT.setVisibility(it.sub.isEmpty() ? GONE : VISIBLE);
            subT.setTextColor(it.sub.startsWith("Keep") ? Ui.WARN : Ui.MUTED);
            value.setText(it.value);
            value.setTextColor(it.good && !it.value.equals("OFF") && !it.value.equals("Off") ? Ui.PAPER : Ui.MUTED);
            label.setTextColor(it.focusable ? (it.bad ? Ui.WARN : Ui.PAPER) : Ui.MUTED);
            label.setTypeface(it.focusable ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            label.setSingleLine(it.focusable);
            Ui.size(label, it.focusable ? Ui.BODY : Ui.CAPTION);
        }
    }
}
