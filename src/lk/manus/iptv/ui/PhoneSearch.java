package lk.manus.iptv.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import lk.manus.iptv.data.AddServer;

/** "Type on your phone": the QR / address / one-time code of the phone page (data/AddServer in search mode) over the dimmed live picture. A word sent from the phone reaches {@link Host#got}. Back is the guide's. */
public final class PhoneSearch extends FrameLayout {
    public interface Host { void got(String text); }

    private final Host host;
    private final ImageView qr;
    private final TextView addr, codeTv;
    private AddServer server;

    public PhoneSearch(Context c, Host host) {
        super(c);
        this.host = host;
        setClickable(true);
        setFocusable(true);
        setBackgroundColor(Ui.alpha(Ui.INK, 0.8f));
        setVisibility(GONE);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView head = Ui.tv(c, Ui.DISPLAY, Ui.PAPER, true);
        head.setText("Type on your phone");
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, 0, 0, Ui.u(14));
        TextView how = Ui.tv(c, Ui.BODY, Ui.MUTED, false);
        how.setText("From your phone (same Wi-Fi)");
        how.setGravity(Gravity.CENTER);
        how.setPadding(0, 0, 0, Ui.u(12));
        qr = new ImageView(c);
        qr.setBackground(Ui.rect(Ui.PAPER, 10));
        addr = Ui.tv(c, Ui.BODY, Ui.PAPER, true);
        addr.setGravity(Gravity.CENTER);
        addr.setPadding(0, Ui.u(10), 0, 0);
        TextView cl = Ui.tv(c, Ui.CAPTION, Ui.MUTED, false);
        cl.setText("Code to type on the phone");
        cl.setGravity(Gravity.CENTER);
        cl.setPadding(0, Ui.u(14), 0, Ui.u(2));
        codeTv = Ui.tv(c, Ui.DISPLAY, Ui.PAPER, true);
        codeTv.setGravity(Gravity.CENTER);
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));
        col.addView(how, new LinearLayout.LayoutParams(-1, -2));
        col.addView(qr, new LinearLayout.LayoutParams(Ui.u(190), Ui.u(190)));
        col.addView(addr, new LinearLayout.LayoutParams(-2, -2));
        col.addView(cl, new LinearLayout.LayoutParams(-1, -2));
        col.addView(codeTv, new LinearLayout.LayoutParams(-2, -2));
        addView(col, new LayoutParams(-1, -2, Gravity.CENTER));
        setOnKeyListener(new OnKeyListener() { // nothing to move to: swallow the arrows so they never reach the lists underneath
            @Override public boolean onKey(View v, int k, KeyEvent e) {
                return k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
            }
        });
    }

    public boolean isUp() { return getVisibility() == VISIBLE; }

    /** Starts the phone page and shows its address and code. */
    public void show() {
        stop();
        codeTv.setText("");
        server = AddServer.forSearch(new AddServer.Listener() {
            @Override public void onCode(String code) { codeTv.setText(code.substring(0, 3) + " " + code.substring(3)); }
            @Override public void onReceived(String text, String unused) { host.got(text); }
        });
        if (server.start() && server.address() != null) {
            addr.setText(server.address());
            qr.setImageBitmap(Qr.bitmap(server.address(), Ui.u(190)));
        } else {
            addr.setText("Phone page unavailable (no Wi-Fi address)");
            qr.setImageDrawable(null);
        }
        setVisibility(VISIBLE);
        requestFocus();
    }

    private void stop() {
        if (server != null) { server.stop(); server = null; }
    }

    /** Closes the phone page too: it only runs while this screen is up. */
    public void hide() {
        stop();
        setVisibility(GONE);
        qr.setImageDrawable(null);
    }
}
