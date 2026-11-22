package lk.manus.iptv.ui.wizard;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lk.manus.iptv.Config;
import lk.manus.iptv.data.AddServer;
import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.Curated;
import lk.manus.iptv.data.Sources;
import lk.manus.iptv.data.Store;
import lk.manus.iptv.ui.Qr;
import lk.manus.iptv.ui.Ui;

/**
 * T107 welcome (first run and Settings > Run setup again): 3 steps over a drifting poster wall.
 * 1 add channels (phone QR + code, or the free channels) · 2 pick countries · 3 all set. Choices are saved on "Start watching".
 */
public final class WizardActivity extends Activity {
    private static final int N = 3;
    private static final String[] FALLBACK = {"lk", "in", "uk", "us", "ca", "au", "pk", "bd", "ae", "fr", "de", "es", "it", "tr", "sa", "sg", "my", "np", "jp", "kr", "za", "nz", "ie", "qa"};

    private Store st;
    private Wiz w;
    private FrameLayout root;
    private WelcomeBg bg;
    private int step;
    private boolean first;

    private List<String> order;       // countries, first = shown first
    private Map<String, Integer> counts;
    private int grabbed = -1;
    private int haveSources;
    private boolean addBusy;
    private String addMsg = "";
    private TextView warn, codeTv;
    private Wiz.Tile nextBtn;
    private LinearLayout orderBox;
    private GridLayout countryGrid;
    private AddServer server;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        st = new Store(getSharedPreferences("iptv", MODE_PRIVATE));
        Ui.init(this);
        w = new Wiz(this);
        first = getIntent().getBooleanExtra("first", false);
        order = new ArrayList<>(first ? Curated.DEFAULT_COUNTRIES : st.fyCountries());
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.INK);
        setContentView(root);
        bg = new WelcomeBg(this);
        haveSources = own();
        loadCounts();
        show(0);
    }

    @Override protected void onDestroy() {
        stopServer();
        if (bg != null) bg.release();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        w.update(this);
        show(step);
    }

    private Sources sources() { return new Sources(getSharedPreferences("iptv", MODE_PRIVATE), getFilesDir(), null); }

    /** Playlists the viewer added (the built-in free list does not count). */
    private int own() { int n = 0; for (Sources.Source x : sources().list()) if (!x.builtin()) n++; return n; }

    private void stopServer() { if (server != null) { server.stop(); server = null; } }

    /** Channel counts per country from the cached playlist (first delivery only); step 2 redraws when they arrive. */
    private void loadCounts() {
        sources().load(new Sources.Callback() {
            @Override public void onLoaded(Sources.Loaded ld) {
                if (counts != null || isFinishing() || ld.merged.isEmpty()) return;
                Map<String, Integer> m = new HashMap<>();
                for (Channel c : ld.merged) if (!c.country.isEmpty()) { Integer n = m.get(c.country); m.put(c.country, n == null ? 1 : n + 1); }
                counts = m;
                if (step == 1) show(1);
            }
            @Override public void onError() { }
        });
    }

    // ---- shell ----

    private void show(int n) {
        step = n;
        grabbed = -1;
        warn = codeTv = null;
        stopServer();
        root.removeAllViews();
        if (bg.getParent() != null) ((ViewGroup) bg.getParent()).removeView(bg);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.marginX(), Ui.marginY(), Ui.marginX(), Ui.marginY());
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        boolean free = n == 0 && haveSources == 0;
        nextBtn = w.button(this, n == 0 ? (free ? "Start with free channels" : "Next") : n == N - 1 ? "Start watching" : "Next", true);
        nextBtn.setId(View.generateViewId());
        nextBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { next(); } });

        View body;
        if (n == 0) body = welcome();
        else {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            TextView title = w.tv(this, n == 1 ? "Which countries come first?" : "You're all set", 34, Ui.PAPER, true);
            title.setPadding(0, w.u(12), 0, 0);
            c.addView(title);
            TextView sub = w.tv(this, n == 1 ? "Tick countries on the left. Your order on the right sets what you see first." : summary(), 18, Ui.MUTED, false);
            sub.setPadding(0, w.u(4), 0, w.u(12));
            c.addView(sub);
            if (n == 1) c.addView(countries());
            body = c;
        }
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body, new ViewGroup.LayoutParams(-1, -2));
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, w.u(12), 0, 0);
        if (n > 0) {
            Wiz.Tile back = w.button(this, "Back", false);
            back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { show(step - 1); } });
            foot.addView(back);
        }
        foot.addView(nextBtn);
        warn = w.tv(this, addMsg, 18, Ui.WARN, true);
        warn.setPadding(w.u(16), 0, w.u(16), 0);
        foot.addView(warn, new LinearLayout.LayoutParams(0, -2, 1));
        foot.addView(dots(n));
        page.addView(foot, new LinearLayout.LayoutParams(-1, -2));
        root.post(new Runnable() { @Override public void run() { nextBtn.requestFocus(); } });
    }

    /** "o * o" step dots and "Step N of 3": the current dot is PAPER, others LINE (red stays reserved for focus). */
    private View dots(int n) {
        LinearLayout d = new LinearLayout(this);
        d.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < N; i++) {
            View v = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(i == n ? Ui.PAPER : Ui.LINE);
            v.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w.u(i == n ? 12 : 10), w.u(i == n ? 12 : 10));
            lp.rightMargin = w.u(8);
            d.addView(v, lp);
        }
        d.addView(w.tv(this, "Step " + (n + 1) + " of " + N, 14, Ui.MUTED, false));
        return d;
    }

    private void next() {
        if (step == 0 && !Config.PLAY && haveSources == 0 && sources().list().isEmpty()) { if (!addBusy) addFreeList(); return; }
        if (step == 1 && order.isEmpty()) { warn.setText("Pick at least one country"); return; }
        if (step == N - 1) { apply(); finish(); return; }
        show(step + 1);
    }

    /** Back goes one step back. On step 1 it leaves only when setup was done before; on a first run it does nothing. */
    @Override public void onBackPressed() {
        if (step > 0) show(step - 1);
        else if (!first) finish();
    }

    private void apply() {
        st.setFyCountries(new LinkedHashSet<>(order));
        st.markSetupDone(true);
    }

    private String summary() {
        int n = order.size();
        return haveSources > 0 ? "Your channels are ready, with " + n + (n == 1 ? " country" : " countries") + " first."
            : "Showing " + n + (n == 1 ? " country" : " countries") + " first. Add your own channels any time in Settings.";
    }

    // ---- step 1 ----

    private View welcome() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(this);
        int id = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (id != 0) ic.setImageDrawable(getDrawable(id));
        c.addView(ic, new LinearLayout.LayoutParams(w.u(64), w.u(64)));
        TextView t = w.tv(this, "Welcome to Manus TV", 34, Ui.PAPER, true);
        t.setPadding(0, w.u(12), 0, w.u(4));
        c.addView(t);
        c.addView(w.tv(this, "Live TV, films and series in one place.", 22, Ui.MUTED, false));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, w.u(24), 0, 0);
        ImageView qr = new ImageView(this);
        qr.setBackground(Ui.rect(Ui.PAPER, 10));
        row.addView(qr, new LinearLayout.LayoutParams(w.u(170), w.u(170)));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(w.u(24), 0, 0, 0);
        right.addView(w.tv(this, "Scan with your phone", 22, Ui.PAPER, true));
        right.addView(w.tv(this, "to add your channels.", 18, Ui.MUTED, false));
        codeTv = w.tv(this, "", 34, Ui.PAPER, true);
        codeTv.setPadding(0, w.u(12), 0, 0);
        right.addView(codeTv);
        row.addView(right);
        c.addView(row);

        server = new AddServer(new AddServer.Listener() {
            @Override public void onCode(String code) { showCode(code); }
            @Override public void onReceived(String url, String name) { addFromPhone(url, name); }
        }, false);
        if (server.start() && server.address() != null) {
            qr.setImageBitmap(Qr.bitmap(server.address(), w.u(170)));
            showCode(server.code());
        } else {
            codeTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(Ui.CAPTION));
            codeTv.setText("Phone page unavailable (no Wi-Fi address)");
        }
        return c;
    }

    private void showCode(String code) {
        if (codeTv == null || code == null || code.length() != 6) return;
        StringBuilder sb = new StringBuilder("Code  ");
        for (int i = 0; i < code.length(); i++) sb.append(i > 0 ? " " : "").append(code.charAt(i));
        codeTv.setText(sb);
    }

    /** The phone sent a playlist link (code already checked): save it and move on to the countries. */
    private void addFromPhone(final String url, final String name) {
        if (url == null || url.isEmpty() || addBusy) return;
        addBusy = true;
        if (warn != null) { warn.setTextColor(Ui.MUTED); warn.setText("Adding your channels…"); }
        final Sources src = sources();
        new Thread(new Runnable() {
            @Override public void run() {
                final Sources.Probe p = src.probe(url);
                final boolean ok = p.error == null;
                if (ok) src.add(name == null || name.isEmpty() ? "My playlist" : name, p);
                runOnUiThread(new Runnable() {
                    @Override public void run() { added(ok, p.error); }
                });
            }
        }, "wiz-phone").start();
    }

    /** Downloads and saves the free list (only on the button press); a failure just says so and nothing is saved. */
    private void addFreeList() {
        addBusy = true;
        warn.setTextColor(Ui.MUTED);
        warn.setText("Getting the free channels…");
        final Sources src = sources();
        new Thread(new Runnable() {
            @Override public void run() {
                final Sources.Probe p = src.probe(Sources.DEFAULT_URL);
                final boolean ok = p.error == null;
                if (ok) src.add(Sources.FREE_NAME, p);
                runOnUiThread(new Runnable() {
                    @Override public void run() { added(ok, p.error); }
                });
            }
        }, "wiz-add").start();
    }

    private void added(boolean ok, String error) {
        addBusy = false;
        if (isFinishing()) return;
        if (ok) { haveSources = own(); addMsg = ""; counts = null; loadCounts(); show(1); }
        else if (warn != null) { warn.setTextColor(Ui.WARN); warn.setText(error); }
    }

    // ---- step 2: countries ----

    private GridLayout grid(int cols) {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(cols);
        return g;
    }

    private void cell(GridLayout g, View v) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL), GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f));
        lp.width = 0;
        lp.setMargins(w.u(4), w.u(4), w.u(4), w.u(4));
        g.addView(v, lp);
    }

    private List<String> topCountries() {
        List<String> src = new ArrayList<>();
        if (counts != null) {
            src.addAll(counts.keySet());
            Collections.sort(src, new Comparator<String>() {
                @Override public int compare(String a, String b) { return counts.get(b) - counts.get(a); }
            });
        } else src.addAll(Arrays.asList(FALLBACK));
        Set<String> t = new LinkedHashSet<>(order);
        for (String c : src) { if (t.size() >= 24) break; t.add(c); }
        List<String> out = new ArrayList<>(t);
        if (counts != null) Collections.sort(out, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                Integer x = counts.get(a), y = counts.get(b);
                return (y == null ? 0 : y) - (x == null ? 0 : x);
            }
        });
        if (out.remove("lk")) out.add(0, "lk"); // Sri Lanka first, like the guide
        return out;
    }

    private View countries() {
        LinearLayout row = new LinearLayout(this);
        countryGrid = grid(3);
        for (final String cc : topCountries()) {
            final Wiz.Tile t = w.new Tile(this, Ui.flag(cc) + " " + Ui.country(cc), counts == null ? null : String.format(Locale.US, "%,d channels", counts.get(cc) == null ? 0 : counts.get(cc)), false);
            t.title.setSingleLine(false);
            t.title.setMaxLines(2); // long names ("United Kingdom") wrap instead of cutting
            t.setOn(order.contains(cc));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!order.remove(cc)) order.add(cc);
                    t.setOn(order.contains(cc));
                    grabbed = -1;
                    drawOrder(-1);
                }
            });
            cell(countryGrid, t);
        }
        row.addView(countryGrid, new LinearLayout.LayoutParams(0, -2, 7));
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(w.u(16), 0, 0, 0);
        side.addView(w.tv(this, "Your order", 22, Ui.PAPER, true));
        TextView hint = w.tv(this, "OK picks a country up, Up / Down moves it, OK drops it.", 14, Ui.MUTED, false);
        hint.setPadding(0, w.u(4), 0, w.u(8));
        side.addView(hint);
        orderBox = new LinearLayout(this);
        orderBox.setOrientation(LinearLayout.VERTICAL);
        side.addView(orderBox);
        row.addView(side, new LinearLayout.LayoutParams(0, -2, 4));
        drawOrder(-1);
        return row;
    }

    /** Rebuild the ordered list on the right; focusIdx >= 0 puts focus back on that row (after a move). */
    private void drawOrder(final int focusIdx) {
        orderBox.removeAllViews();
        for (int i = 0; i < order.size(); i++) {
            final int idx = i;
            String cc = order.get(i);
            Wiz.Tile t = w.new Tile(this, (i + 1) + "  " + Ui.flag(cc) + " " + Ui.country(cc), grabbed == i ? "Moving: Up / Down, OK to drop" : null, false);
            t.setOn(grabbed == i);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { grabbed = grabbed == idx ? -1 : idx; drawOrder(idx); }
            });
            if (i == order.size() - 1) t.setNextFocusDownId(nextBtn.getId());
            t.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int k, android.view.KeyEvent e) {
                    if (grabbed != idx) return false;
                    if (k == android.view.KeyEvent.KEYCODE_BACK) { if (e.getAction() == android.view.KeyEvent.ACTION_UP) { grabbed = -1; drawOrder(idx); } return true; }
                    boolean up = k == android.view.KeyEvent.KEYCODE_DPAD_UP, down = k == android.view.KeyEvent.KEYCODE_DPAD_DOWN;
                    if (!up && !down) return k == android.view.KeyEvent.KEYCODE_DPAD_LEFT || k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
                    if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                        int j = idx + (up ? -1 : 1);
                        if (j >= 0 && j < order.size()) { Collections.swap(order, idx, j); grabbed = j; drawOrder(j); }
                    }
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = w.u(4);
            orderBox.addView(t, lp);
        }
        if (order.isEmpty()) orderBox.addView(w.tv(this, "Nothing picked yet", 18, Ui.MUTED, false));
        if (focusIdx >= 0 && focusIdx < order.size()) { final View v = orderBox.getChildAt(focusIdx); v.post(new Runnable() { @Override public void run() { v.requestFocus(); } }); }
    }
}
