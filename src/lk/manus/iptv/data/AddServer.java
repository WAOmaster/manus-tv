package lk.manus.iptv.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import lk.manus.iptv.Config;

/**
 * Tiny LAN web page so a phone can send a playlist link to the TV. Runs only while the Add-source screen is open.
 * A 6-digit one-time code (shown on the TV) guards the form; 5 wrong tries regenerate it. Bodies are capped at 8 KB
 * (2 MB in the restore mode, which carries a backup file as base64 in an ordinary form field). Nothing the phone sends is ever logged.
 * Backup modes (T72): the phone page asks for a passphrase; {@link BackupHooks} does the work on the TV. The code is checked before any other field is decoded.
 */
public final class AddServer {
    public interface Listener {
        void onCode(String code);                 // code (re)generated
        void onReceived(String url, String name); // correct code, main thread
    }

    private static final int MAX_BODY = 8192, MAX_RESTORE_BODY = 2 * 1024 * 1024, MAX_WRONG = 5;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom rnd = new SecureRandom();
    private volatile ServerSocket server;
    private volatile String code = "";
    private int wrong;
    private int port;

    /** Key mode: the phone sends a movie database key. Runs on the server thread; null = verified and saved, else the text the phone page shows. */
    public interface KeyCheck { String accept(String key); }

    private final boolean feed; // true = the page sends a channel data feed address instead of a playlist
    private KeyCheck keyCheck;  // non-null = the page sends a movie database key (never logged, never echoed)
    private boolean search;     // true = the page sends one line of text to search for (data only: never shown back, never rendered)
    private boolean xtream;     // true = the page sends an Xtream Codes login
    private Context appCtx;

    /** The phone page sends an Xtream Codes login. */
    public static AddServer forXtream(Context ctx, Listener l) {
        AddServer s = new AddServer(l, false);
        s.xtream = true;
        s.appCtx = ctx != null ? ctx.getApplicationContext() : null;
        return s;
    }

    public static final class XtreamForm {
        public final String name;
        public final String server;
        public final String user;
        public final String pass;
        public final String code;

        public XtreamForm(String name, String server, String user, String pass, String code) {
            this.name = name != null ? name : "";
            this.server = server != null ? server : "";
            this.user = user != null ? user : "";
            this.pass = pass != null ? pass : "";
            this.code = code != null ? code : "";
        }
    }

    public static XtreamForm parseXtreamForm(String body) {
        return parseXtreamMap(form(body));
    }

    public static XtreamForm parseXtreamMap(Map<String, String> m) {
        if (m == null) return new XtreamForm("", "", "", "", "");
        String s = m.get("server");
        if (s == null) s = m.get("address");
        if (s == null) s = m.get("url");

        String u = m.get("username");
        if (u == null) u = m.get("user");

        String p = m.get("password");
        if (p == null) p = m.get("pass");

        String n = m.get("name");
        String c = m.get("code");

        return new XtreamForm(n != null ? n.trim() : "",
                              s != null ? s.trim() : "",
                              u != null ? u.trim() : "",
                              p != null ? p.trim() : "",
                              c != null ? c.trim() : "");
    }

    public static String defaultXtreamName(String name, String serverAddress) {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        if (serverAddress == null) return "";
        try {
            java.net.URI u = new java.net.URI(serverAddress.trim());
            String h = u.getHost();
            if (h != null && !h.isEmpty()) return h;
        } catch (Exception ignored) {}
        String s = serverAddress.trim();
        int slashSlash = s.indexOf("://");
        if (slashSlash >= 0) s = s.substring(slashSlash + 3);
        int colon = s.indexOf(':');
        int slash = s.indexOf('/');
        int end = s.length();
        if (colon >= 0 && (slash < 0 || colon < slash)) end = colon;
        else if (slash >= 0) end = slash;
        return s.substring(0, end);
    }

    public static String mapXtreamMessage(int state) {
        switch (state) {
            case XtreamSource.Account.OK:
                return "Added. Your films are loading on the TV.";
            case XtreamSource.Account.WRONG_LOGIN:
                return "That login was not accepted.";
            case XtreamSource.Account.EXPIRED:
                return "This account has expired.";
            case XtreamSource.Account.UNREACHABLE:
                return "Can't reach that server.";
            case XtreamSource.Account.BAD_ADDRESS:
                return "That address doesn't look right.";
            default:
                return "That login was not accepted.";
        }
    }

    /** The phone page sends text for the guide's Search (listener.onReceived(text, "")). */
    public static AddServer forSearch(Listener l) { AddServer s = new AddServer(l, false); s.search = true; return s; }

    /** What the search box gets from a phone: control characters removed, spaces tidied, at most 60 characters. */
    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT || cp == 0x2028 || cp == 0x2029) { if (cp == '\t' || cp == '\n') b.append(' '); continue; }
            b.appendCodePoint(cp);
        }
        String t = b.toString().trim().replaceAll(" {2,}", " ");
        return t.codePointCount(0, t.length()) <= 60 ? t : t.substring(0, t.offsetByCodePoints(0, 60)).trim();
    }

    // ---- backup modes (T72) ----

    /** A refusal with the exact text the phone page shows. guess = the passphrase was wrong (counts like a wrong code: the 5th regenerates the code). */
    public static final class Refused extends Exception {
        public final boolean guess;
        public Refused(String text, boolean guess) { super(text); this.guess = guess; }
    }

    /** The file the phone downloads. kept = a copy was also kept on the TV. */
    public static final class Download {
        public final String name; public final byte[] bytes; public final boolean kept; public final int dropped; // dropped = items left out because a restore would refuse them
        public Download(String name, byte[] bytes, boolean kept, int dropped) { this.name = name; this.bytes = bytes; this.kept = kept; this.dropped = dropped; }
    }

    /** Called on the server thread after the code was accepted. This class clears {@code pass} afterwards. */
    public interface BackupHooks {
        /** Back-up mode: the file to download. */
        Download backup(char[] pass) throws Refused;
        /** Restore modes: decrypt and check {@code file} (null = the copy kept on this TV) and hand it to the TV's confirm screen. */
        void restore(byte[] file, char[] pass) throws Refused;
    }

    private BackupHooks hooks;
    private int bmode; // 0 = not a backup page, 1 = back up, 2 = restore a file from the phone, 3 = restore a copy kept on this TV

    public static AddServer forBackup(Listener l, BackupHooks h) { AddServer s = new AddServer(l, false); s.hooks = h; s.bmode = 1; return s; }

    public static AddServer forRestore(Listener l, BackupHooks h, boolean fromTv) { AddServer s = new AddServer(l, false); s.hooks = h; s.bmode = fromTv ? 3 : 2; return s; }

    public AddServer(Listener l) { this(l, false); }

    public AddServer(Listener l, boolean feed) { listener = l; this.feed = feed; }

    public AddServer(Listener l, KeyCheck k) { this(l, false); keyCheck = k; }

    public String code() { return code; }

    /** "http://192.168.x.x:8765/" or null when the TV has no LAN address. */
    public String address() {
        String ip = lanIp();
        return ip == null || port == 0 ? null : "http://" + ip + ":" + port + "/";
    }

    public synchronized boolean start() {
        if (server != null) return true;
        newCode();
        try {
            ServerSocket s;
            try { s = new ServerSocket(8765); } catch (IOException e) { s = new ServerSocket(0); }
            server = s;
            port = s.getLocalPort();
        } catch (IOException e) {
            Log.w("IPTV", "add server not started: " + e.getClass().getSimpleName());
            return false;
        }
        final ServerSocket ss = server;
        new Thread(new Runnable() {
            @Override public void run() {
                while (!ss.isClosed()) {
                    try (Socket c = ss.accept()) {
                        c.setSoTimeout(5000);
                        handle(c);
                    } catch (Exception e) {
                        if (ss.isClosed()) break;
                    }
                }
            }
        }, "add-server").start();
        Log.i("IPTV", "add server listening on port " + port);
        return true;
    }

    public synchronized void stop() {
        ServerSocket s = server;
        server = null;
        port = 0;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    private synchronized void newCode() {
        code = String.format("%06d", rnd.nextInt(1000000));
        wrong = 0;
        if (Config.DEBUG) Log.i("IPTV", "DEBUG add-server code " + code); // the one-time code only; Config.DEBUG is a compile-time constant, so this is not in a release build
        final String c = code;
        main.post(new Runnable() { @Override public void run() { listener.onCode(c); } });
    }

    private void handle(Socket c) throws IOException {
        InputStream in = c.getInputStream();
        // read headers (cap 16 KB), then the body
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0, b;
        while (state < 4 && head.size() < 16384 && (b = in.read()) >= 0) {
            head.write(b);
            state = (b == '\r' && (state == 0 || state == 2)) ? state + 1 : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
        }
        if (state < 4) { reply(c, 400, "Bad request"); return; }
        String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
        String[] req = lines[0].split(" ");
        if (req.length < 2) { reply(c, 400, "Bad request"); return; }
        int len = 0;
        for (String l : lines) {
            if (l.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                try { len = Integer.parseInt(l.substring(15).trim()); } catch (NumberFormatException e) { len = -1; }
            }
        }
        String path = req[1];
        if (req[0].equals("GET") && (path.equals("/") || path.startsWith("/?"))) {
            if (bmode != 0) { reply(c, 200, bmode == 1 ? BACKUP_FORM : bmode == 2 ? RESTORE_FORM : RESTORE_TV_FORM); return; }
            if (xtream) {
                if (Config.PLAY) { reply(c, 403, page("Not available", "Xtream logins are not available in this build.")); return; }
                reply(c, 200, XTREAM_FORM);
                return;
            }
            reply(c, 200, keyCheck != null ? KEY_FORM : search ? SEARCH_FORM : feed ? FORM.replace("Add a playlist to Manus TV", "Set the channel data feed").replace("Playlist link (M3U)", "Feed address (the link to your data feed)").replace("<label>Name (optional)<input name=name maxlength=60 style='width:100%;font-size:18px;padding:10px;box-sizing:border-box;background:#121110;color:#F4F1EA;border:1px solid #36322E'></label>", "") : FORM);
            return;
        }
        if (!req[0].equals("POST") || !path.equals("/")) { reply(c, 404, page("Not found", "Nothing here.")); return; }
        if (len < 0 || len > (bmode == 2 ? MAX_RESTORE_BODY : MAX_BODY)) { rejectBig(c, in, len); return; }
        byte[] body = new byte[len];
        int got = 0;
        while (got < len) { int n = in.read(body, got, len - got); if (n < 0) break; got += n; }
        if (bmode != 0) { backupPost(c, new String(body, 0, got, StandardCharsets.ISO_8859_1)); return; } // urlencoded text is plain ASCII
        if (xtream) {
            if (Config.PLAY) { reply(c, 403, page("Not available", "Xtream logins are not available in this build.")); return; }
            xtreamPost(c, new String(body, 0, got, StandardCharsets.UTF_8));
            return;
        }
        Map<String, String> f = form(new String(body, 0, got, StandardCharsets.UTF_8));
        String key = f.get("key") == null ? "" : f.get("key").trim();
        String url = f.get("url") == null ? "" : f.get("url").trim();
        String name = f.get("name") == null ? "" : f.get("name").trim();
        if (badCode(c, f.get("code"))) return;
        if (search) {
            String q = clean(f.get("q"));
            if (q.isEmpty()) { reply(c, 400, page("Nothing sent", "Type something to search for, then try again.")); return; }
            newCode(); // one-time
            main.post(new Runnable() { @Override public void run() { listener.onReceived(q, ""); } });
            reply(c, 200, page("Sent to the TV", "Done. Look at the TV."));
            return;
        }
        if (keyCheck != null) {
            if (!key.matches("[A-Za-z0-9._-]{16,400}")) { reply(c, 400, page("Not a key", "That is not a movie database key. Check it and try again.")); return; }
            String err = keyCheck.accept(key);
            if (err != null) { reply(c, 400, page("Not saved", err)); return; }
            newCode(); // one-time
            main.post(new Runnable() { @Override public void run() { listener.onReceived("", ""); } });
            reply(c, 200, page("Sent to the TV", "Done. Look at the TV."));
            return;
        }
        if (!(url.startsWith("http://") || url.startsWith("https://")) || url.length() > 2000) {
            reply(c, 400, page("Not a link", "The link must start with http:// or https://. Go back and try again."));
            return;
        }
        newCode(); // one-time
        final String u = url, n = name.length() > 60 ? name.substring(0, 60) : name;
        main.post(new Runnable() { @Override public void run() { listener.onReceived(u, n); } });
        reply(c, 200, page("Sent to the TV", "Done. Look at the TV: it is checking the link and will ask you to press Save."));
    }

    /** True (the 403 page is sent) when {@code given} is not the current code. A wrong code is a wrong try; the 5th regenerates the code. */
    private boolean badCode(Socket c, String given) throws IOException {
        boolean regen;
        synchronized (this) {
            if (code.equals(given)) return false;
            regen = wrongTry();
        }
        reply(c, 403, page("Wrong code", "That code is not right. Check the 6 digits on the TV." + (regen ? TOO_MANY : "")));
        return true;
    }

    private static final String TOO_MANY = " Too many tries: the TV now shows a new code.";

    /** Counts a wrong try (code or passphrase). True when this was the 5th and a new code was made. */
    private synchronized boolean wrongTry() {
        boolean regen = ++wrong >= MAX_WRONG;
        if (regen) newCode();
        return regen;
    }

    /** Too big: answered without reading the body; what the phone still sends is drained (bounded) so the answer is not lost to a connection reset. */
    private void rejectBig(Socket c, InputStream in, int len) throws IOException {
        reply(c, 413, page("Too big", "That request is too large."));
        try {
            c.shutdownOutput();
            byte[] junk = new byte[8192];
            long left = Math.min(len < 0 ? 0 : len, 4L * 1024 * 1024);
            while (left > 0) { int n = in.read(junk); if (n < 0) break; left -= n; }
        } catch (IOException ignored) { } // the answer was already sent; a phone that stops sending is fine
    }

    /** One field of an urlencoded body, decoded; null if absent. Only the asked-for field is decoded. */
    private static String field(String body, String name) {
        int i = 0;
        while (i <= body.length()) {
            int amp = body.indexOf('&', i);
            if (amp < 0) amp = body.length();
            int eq = body.indexOf('=', i);
            if (eq >= i && eq < amp && eq - i == name.length() && body.startsWith(name, i)) {
                try { return URLDecoder.decode(body.substring(eq + 1, amp), "UTF-8"); } catch (Exception e) { return null; }
            }
            i = amp + 1;
        }
        return null;
    }

    /** POST of a backup page. Order: code first (nothing else is decoded before it), then the passphrase rules, then the work. */
    private void backupPost(Socket c, String body) throws IOException {
        if (badCode(c, field(body, "code"))) return;
        String p1 = field(body, "pass"), p2 = field(body, "pass2");
        if (bmode == 1) {
            String bad = Backup.checkPassphrases(p1, p2);
            if (bad != null) { reply(c, 400, page("Not made", bad)); return; }
        }
        char[] pass = p1 == null ? new char[0] : p1.toCharArray();
        try {
            if (bmode == 1) {
                Download dl = hooks.backup(pass);
                newCode(); // one-time
                final String n = dl.name, k = (dl.kept ? "kept" : "") + ":" + dl.dropped;
                main.post(new Runnable() { @Override public void run() { listener.onReceived(n, k); } }); // backup mode: (file name, "kept:N" or ":N", N = items left out)
                download(c, dl);
                stop();
                return;
            }
            byte[] file = null;
            if (bmode == 2) {
                String data = field(body, "data");
                try { file = data == null || data.isEmpty() ? null : java.util.Base64.getDecoder().decode(data); } catch (IllegalArgumentException e) { file = null; }
                if (file == null) { reply(c, 400, page("Not restored", Backup.TEXT[Backup.NOT_BACKUP])); return; }
                if (file.length > Backup.MAX_FILE) { reply(c, 400, page("Not restored", TOO_BIG_FILE)); return; }
            }
            hooks.restore(file, pass);
            newCode(); // one-time
            reply(c, 200, page("Sent to the TV", "Done. Look at the TV: it will ask you to confirm."));
            stop();
        } catch (Refused e) {
            boolean regen = e.guess && wrongTry();
            reply(c, 400, page(bmode == 1 ? "Not made" : "Not restored", e.getMessage() + (regen ? TOO_MANY : "")));
        } catch (RuntimeException e) {
            Log.w("IPTV", "backup request failed: " + e.getClass().getSimpleName()); // class only: never a message that could hold file content
            reply(c, 500, page("Nothing changed", "Something went wrong on the TV. Nothing was changed."));
        } finally {
            Arrays.fill(pass, '\0');
        }
    }

    private void xtreamPost(Socket c, String body) throws IOException {
        XtreamForm f = parseXtreamForm(body);
        if (badCode(c, f.code)) return;
        char[] pass = f.pass.toCharArray();
        try {
            if (f.server.isEmpty()) {
                Log.i("IPTV", "xtream add: BAD_ADDRESS ?");
                reply(c, 400, page("Not accepted", mapXtreamMessage(XtreamSource.Account.BAD_ADDRESS)));
                return;
            }
            String name = defaultXtreamName(f.name, f.server);
            XtreamSource.Account acc = XtreamSource.verify(f.server, f.user, f.pass);
            Log.i("IPTV", "xtream add: " + acc.stateName() + " " + Redact.host(f.server));
            if (acc.state == XtreamSource.Account.OK) {
                long id = XtreamSource.add(Db.get(), name, f.server, f.user, f.pass);
                if (id > 0) {
                    if (appCtx != null) XtreamSource.sync(appCtx, id, null);
                    newCode();
                    final String n = name, u = f.server;
                    main.post(new Runnable() { @Override public void run() { listener.onReceived(u, n); } });
                    reply(c, 200, page("Sent to the TV", "Added. Your films are loading on the TV."));
                } else {
                    reply(c, 500, page("Nothing changed", "Something went wrong on the TV. Nothing was changed."));
                }
            } else {
                reply(c, 400, page("Not accepted", mapXtreamMessage(acc.state)));
            }
        } finally {
            Arrays.fill(pass, '\0');
        }
    }

    static final String TOO_BIG_FILE = "That file is too big to be a Manus TV backup.";

    /** The only binary reply: the backup file. */
    private static void download(Socket c, Download d) throws IOException {
        if (!d.name.matches("[A-Za-z0-9._-]{1,80}")) throw new IOException("bad file name");
        String h = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + d.bytes.length
                + "\r\nContent-Disposition: attachment; filename=\"" + d.name + "\"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream o = c.getOutputStream();
        o.write(h.getBytes(StandardCharsets.ISO_8859_1));
        o.write(d.bytes);
        o.flush();
    }

    public static Map<String, String> form(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null) return m;
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            try { m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8")); }
            catch (Exception ignored) { }
        }
        return m;
    }

    private static void reply(Socket c, int status, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + status + " X\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream o = c.getOutputStream();
        o.write(h.getBytes(StandardCharsets.ISO_8859_1));
        o.write(b);
        o.flush();
    }

    private static String page(String title, String msg) {
        return "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><body style='font:18px sans-serif;"
                + "margin:0 auto;padding:24px;max-width:480px;box-sizing:border-box;background:#050505;color:#F4F1EA'><h2>" + title + "</h2><p>" + msg + "</p><p><a style='color:#F4F1EA' href='/'>Back</a></p>";
    }

    private static final String IN = "style='width:100%;font-size:18px;padding:10px;box-sizing:border-box;background:#121110;color:#F4F1EA;border:1px solid #36322E'";
    private static final String BUTTON = "<button style='width:100%;box-sizing:border-box;font-size:20px;padding:12px;background:#E3262E;color:#fff;border:0;border-radius:12px'>";
    private static final String PAGE_HEAD = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<body style='font:18px sans-serif;margin:0 auto;padding:24px;max-width:480px;box-sizing:border-box;background:#050505;color:#F4F1EA'>";
    private static final String CODE_FIELD = "<label>6-digit code shown on the TV<input name=code required inputmode=numeric pattern='[0-9]{6}' maxlength=6 " + IN + "></label>";
    private static final String PASS_FIELD = "<label>Passphrase<input name=pass type=password required autocomplete=off maxlength=200 " + IN + "></label>";
    private static final String FORM_OPEN = "<form method=post action=/ autocomplete=off style='display:flex;flex-direction:column;gap:14px'>";

    private static final String BACKUP_FORM = PAGE_HEAD + "<h2>Back up Manus TV</h2>" + FORM_OPEN
            + PASS_FIELD
            + "<label>Passphrase again<input name=pass2 type=password required autocomplete=off maxlength=200 " + IN + "></label>"
            + CODE_FIELD + BUTTON + "Back up</button></form>";

    private static final String RESTORE_TV_FORM = PAGE_HEAD + "<h2>Restore Manus TV</h2>" + FORM_OPEN + PASS_FIELD + CODE_FIELD + BUTTON + "Restore</button></form>";

    // The file is read in the phone's browser and sent as the form field "data" (base64): no multipart parser on the TV. A file whose encoded form
    // would not fit the 2 MB body cap is refused here, before anything is posted.
    private static final String RESTORE_FORM = PAGE_HEAD + "<h2>Restore Manus TV</h2>"
            + "<form id=f method=post action=/ autocomplete=off style='display:flex;flex-direction:column;gap:14px'>"
            + "<label>Backup file (.mtvb)<input id=file type=file accept='.mtvb' required " + IN + "></label>"
            + PASS_FIELD + CODE_FIELD + "<input type=hidden name=data id=data>" + BUTTON + "Restore</button><p id=msg></p></form>"
            + "<script>var f=document.getElementById('f'),m=document.getElementById('msg'),TOO='" + TOO_BIG_FILE + "';"
            + "f.onsubmit=function(e){e.preventDefault();var x=document.getElementById('file').files[0];if(!x)return;"
            + "if(x.size>" + Backup.MAX_FILE + "){m.textContent=TOO;return;}"
            + "var r=new FileReader();r.onload=function(){var b=String(r.result).split(',')[1]||'';"
            + "if(encodeURIComponent(b).length>" + (MAX_RESTORE_BODY - 4096) + "){m.textContent=TOO;return;}"
            + "document.getElementById('data').value=b;f.submit();};r.readAsDataURL(x);};</script>";

    private static final String XTREAM_FORM = PAGE_HEAD + "<h2>Add an Xtream login</h2>" + FORM_OPEN
            + "<label>Name (optional)<input name=name maxlength=60 " + IN + "></label>"
            + "<label>Server address<input name=server type=url required placeholder='http://...' " + IN + "></label>"
            + "<label>Username<input name=username required autocomplete=off maxlength=100 " + IN + "></label>"
            + "<label>Password<input name=password type=password required autocomplete=off maxlength=200 " + IN + "></label>"
            + CODE_FIELD
            + BUTTON + "Add login</button></form>";

    private static final String FORM = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<body style='font:18px sans-serif;margin:0 auto;padding:24px;max-width:480px;box-sizing:border-box;background:#050505;color:#F4F1EA'><h2>Add a playlist to Manus TV</h2>"
            + "<form method=post action=/ style='display:flex;flex-direction:column;gap:14px'>"
            + "<label>Playlist link (M3U)<input name=url type=url required placeholder='https://...' style='width:100%;font-size:18px;padding:10px;box-sizing:border-box;background:#121110;color:#F4F1EA;border:1px solid #36322E'></label>"
            + "<label>Name (optional)<input name=name maxlength=60 style='width:100%;font-size:18px;padding:10px;box-sizing:border-box;background:#121110;color:#F4F1EA;border:1px solid #36322E'></label>"
            + "<label>6-digit code shown on the TV<input name=code required inputmode=numeric pattern='[0-9]{6}' maxlength=6 style='width:100%;font-size:18px;padding:10px;box-sizing:border-box;background:#121110;color:#F4F1EA;border:1px solid #36322E'></label>"
            + "<button style='width:100%;box-sizing:border-box;font-size:20px;padding:12px;background:#E3262E;color:#fff;border:0;border-radius:12px'>Send to TV</button></form>";

    private static final String SEARCH_FORM = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<body style='font:18px sans-serif;margin:0 auto;padding:24px;max-width:480px;box-sizing:border-box;background:#050505;color:#F4F1EA'><h2>Search on Manus TV</h2>"
            + "<form method=post action=/ autocomplete=off style='display:flex;flex-direction:column;gap:14px'>"
            + "<label>What do you want to watch?<input name=q required maxlength=60 autocomplete=off style='width:100%;font-size:18px;padding:10px;box-sizing:border-box;background:#121110;color:#F4F1EA;border:1px solid #36322E'></label>"
            + "<label>6-digit code shown on the TV<input name=code required inputmode=numeric pattern='[0-9]{6}' maxlength=6 style='width:100%;font-size:18px;padding:10px;box-sizing:border-box;background:#121110;color:#F4F1EA;border:1px solid #36322E'></label>"
            + "<button style='width:100%;box-sizing:border-box;font-size:20px;padding:12px;background:#E3262E;color:#fff;border:0;border-radius:12px'>Search</button></form>";

    private static final String KEY_FORM = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<body style='font:18px sans-serif;margin:0 auto;padding:24px;max-width:480px;box-sizing:border-box;background:#050505;color:#F4F1EA'><h2>Add a movie database key</h2>"
            + "<form method=post action=/ autocomplete=off style='display:flex;flex-direction:column;gap:14px'>"
            + "<label>Movie database key (TMDB)<input name=key type=password required autocomplete=off pattern='[A-Za-z0-9._-]{16,400}' style='width:100%;font-size:18px;padding:10px;box-sizing:border-box;background:#121110;color:#F4F1EA;border:1px solid #36322E'></label>"
            + "<label>6-digit code shown on the TV<input name=code required inputmode=numeric pattern='[0-9]{6}' maxlength=6 style='width:100%;font-size:18px;padding:10px;box-sizing:border-box;background:#121110;color:#F4F1EA;border:1px solid #36322E'></label>"
            + "<button style='width:100%;box-sizing:border-box;font-size:20px;padding:12px;background:#E3262E;color:#fff;border:0;border-radius:12px'>Send to TV</button></form>";

    private static String lanIp() {
        try {
            String best = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        if (ni.getName().startsWith("wlan") || ni.getName().startsWith("eth")) return a.getHostAddress();
                        if (best == null) best = a.getHostAddress();
                    }
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }
}
