package lk.manus.iptv.data;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * T72: the encrypted backup file. Pure Java (no Android classes) so it is unit-tested.
 * File = ASCII "MTVB1" + salt(16) + nonce(12) + AES-256-GCM ciphertext (tag 128 bit appended). Key = PBKDF2WithHmacSHA256(passphrase, salt, 210000).
 * The payload is UTF-8 JSON (see {@link Data}). A file from outside is untrusted: {@link #fromJson} checks every type and size and ignores unknown fields.
 * No logging here; nothing in this class prints a passphrase, the JSON or an address.
 */
public final class Backup {
    private Backup() {}

    // ---- every crypto parameter, by name ----
    static final byte[] MAGIC = {'M', 'T', 'V', 'B', '1'};
    private static final int SALT_BYTES = 16, NONCE_BYTES = 12, TAG_BITS = 128, KEY_BITS = 256, PBKDF2_ITERATIONS = 210000;
    private static final int TAG_BYTES = TAG_BITS / 8, HEADER_BYTES = 5 + SALT_BYTES + NONCE_BYTES;
    private static final String KDF = "PBKDF2WithHmacSHA256", CIPHER = "AES/GCM/NoPadding", KEY_ALGORITHM = "AES";
    private static final SecureRandom RANDOM = new SecureRandom();

    // ---- limits for a file from outside ----
    public static final int VERSION = 3; // 3 (T96) adds "ep" and "sl"; v1 and v2 files read unchanged (those sets are just empty)
    public static final int MAX_FILE = 1500000;       // bytes of the whole file (the phone page refuses bigger ones too)
    public static final int MAX_SOURCES = 200, MAX_ENTRIES = 5000, MAX_STRING = 2048, MIN_PASSPHRASE = 8, MAX_DEPTH = 8;
    public static final String[] CHOICE_KINDS = {"subs", "audio", "vols", "vers"}; // same names as Db's "choice" table

    /** What went wrong, mapped to the exact text the phone page shows. */
    public static final int NOT_BACKUP = 0, BAD_PASSPHRASE = 1, NEWER = 2;
    public static final String[] TEXT = {
        "That is not a Manus TV backup.",
        "That passphrase did not open the backup.",
        "This backup was made by a newer version of Manus TV."};

    public static final class BackupException extends Exception {
        public final int kind;
        BackupException(int kind) { super(TEXT[kind]); this.kind = kind; } // the message is the fixed text above: never any file content
    }

    /** One play-count row. */
    public static final class Play {
        public final String url; public final long n, lastMs;
        public Play(String url, long n, long lastMs) { this.url = url; this.n = n; this.lastMs = lastMs; }
    }

    /** One reminder row. */
    public static final class Rem {
        public final String key, title, channel; public final long startSec;
        public Rem(String key, String title, long startSec, String channel) { this.key = key; this.title = title; this.startSec = startSec; this.channel = channel; }
    }

    /** One Xtream source row (T86: login inside encrypted payload only). */
    public static final class Xtream {
        public final String name, base, user, pass;
        public Xtream(String name, String base, String user, String pass) {
            this.name = name != null ? name : "";
            this.base = base != null ? base : "";
            this.user = user != null ? user : "";
            this.pass = pass != null ? pass : "";
        }
    }

    /** One film progress row (T86). */
    public static final class FilmProgress {
        public final int xtIndex;
        public final String providerId;
        public final long positionMs, durationMs;
        public final boolean watched;
        public final long updatedAt;
        public FilmProgress(int xtIndex, String providerId, long positionMs, long durationMs, boolean watched, long updatedAt) {
            this.xtIndex = xtIndex;
            this.providerId = providerId != null ? providerId : "";
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.watched = watched;
            this.updatedAt = updatedAt;
        }
    }

    /** One My-list film row (T86). */
    public static final class MyListFilm {
        public final int xtIndex;
        public final String providerId;
        public final int order;
        public final long addedAt;
        public MyListFilm(int xtIndex, String providerId, int order, long addedAt) {
            this.xtIndex = xtIndex;
            this.providerId = providerId != null ? providerId : "";
            this.order = order;
            this.addedAt = addedAt;
        }
    }

    /** One episode progress row (T96). Keyed by source + series provider id + season + episode, never by a local item id. */
    public static final class EpProgress {
        public final int xtIndex;
        public final String seriesId;
        public final int season, episode;
        public final long positionMs, durationMs, updatedAt;
        public final boolean watched;
        public EpProgress(int xtIndex, String seriesId, int season, int episode, long positionMs, long durationMs, boolean watched, long updatedAt) {
            this.xtIndex = xtIndex; this.seriesId = seriesId != null ? seriesId : ""; this.season = season; this.episode = episode;
            this.positionMs = positionMs; this.durationMs = durationMs; this.watched = watched; this.updatedAt = updatedAt;
        }
    }

    /** One series in My list (T96), keyed by source + series provider id. */
    public static final class SeriesListEntry {
        public final int xtIndex;
        public final String seriesId;
        public final int order;
        public final long addedAt;
        public SeriesListEntry(int xtIndex, String seriesId, int order, long addedAt) {
            this.xtIndex = xtIndex; this.seriesId = seriesId != null ? seriesId : ""; this.order = order; this.addedAt = addedAt;
        }
    }

    /** Everything a backup holds. Lists are in order; settings only ever hold allow-listed names (see {@link #SETTINGS}). */
    public static final class Data {
        public long made;
        public String app = "";
        public final List<String[]> sources = new ArrayList<>();            // {name, url}
        public final List<String> favs = new ArrayList<>(), recents = new ArrayList<>();
        public final List<Play> plays = new ArrayList<>();
        public final Map<String, List<String[]>> choices = new LinkedHashMap<>(); // kind -> [{key, value}], oldest first
        public final Map<String, Object> settings = new LinkedHashMap<>();   // Boolean | Long | String | List<String>
        public final List<Rem> reminders = new ArrayList<>();
        public final List<Xtream> xt = new ArrayList<>();
        public final List<FilmProgress> fp = new ArrayList<>();
        public final List<MyListFilm> ml = new ArrayList<>();
        public final List<EpProgress> ep = new ArrayList<>();         // T96
        public final List<SeriesListEntry> sl = new ArrayList<>();    // T96
    }

    // ---- the preference allow-list ----
    // Never here: tmdb_key, tmdb_bad_key, tmdb_base, tmdb_img_base, db_migrated, demo_blur, any other DEBUG key, health data, the film cache, the undo key.
    private static final String B = "b", I = "i", L = "l", U = "u";
    private static final String[][] SETTING_DEFS = {
        {"quality", "e", "auto", "saver", "best"}, {"perf", "e", "auto", "smooth", "rich"}, {"mini_mode", "e", "auto", "off"},
        {"wm_pos", "e", "off", "tl", "tr", "bl", "br"}, {"wm_mode", "e", "change", "always"},
        {"edge_pct", I}, {"mirror_mode", B}, {"show_unavail", B}, {"auto_skip", B}, {"cross_lang", B}, {"even_volume", B}, {"subs_default", B},
        {"sub_translate", B}, {"hide_unwatched_plots", B}, {"setup_done", B},
        {"fy_countries", L}, {"fy_cats", L}, {"hidden", L}, {"langs", L},   // pinned countries and categories, hidden groups, languages watched
        {"feed", U}};                                                         // channel data feed address ("" = none)
    public static final List<String> SETTING_NAMES = new ArrayList<>();
    static { for (String[] d : SETTING_DEFS) SETTING_NAMES.add(d[0]); }

    private static String[] def(String name) { for (String[] d : SETTING_DEFS) if (d[0].equals(name)) return d; return null; }

    /** A value read from a preference (as {@code SharedPreferences.getAll()} returns it) turned into the typed value a backup stores, or null if it is not allow-listed or has the wrong type. */
    public static Object settingFromPref(String name, Object raw) {
        String[] d = def(name);
        if (d == null || raw == null) return null;
        Object v;
        switch (d[1]) {
            case "b": v = raw instanceof Boolean ? raw : null; break;
            case "i": v = raw instanceof Integer ? (Object) Long.valueOf((Integer) raw) : null; break;
            case "l": {
                if (!(raw instanceof String)) return null;
                List<String> l = new ArrayList<>();
                for (String s : ((String) raw).split("\n")) if (!s.isEmpty()) l.add(s);
                v = l;
                break;
            }
            default: v = raw instanceof String ? raw : null; // enum and url
        }
        return v != null && validSetting(d, v) ? v : null;
    }

    /** The other way: what to write into the preference ("l" lists are stored newline-joined, ints as Integer). */
    public static Object settingToPref(String name, Object v) {
        String[] d = def(name);
        if (d == null) throw new IllegalArgumentException("not an allow-listed setting");
        if (d[1].equals("l")) { StringBuilder b = new StringBuilder(); for (Object s : (List<?>) v) b.append(b.length() == 0 ? "" : "\n").append((String) s); return b.toString(); }
        if (v instanceof Long) return Integer.valueOf((int) (long) (Long) v);
        return v;
    }

    private static boolean validSetting(String[] d, Object v) {
        switch (d[1]) {
            case "b": return v instanceof Boolean;
            case "i": return v instanceof Long && (Long) v >= 0 && (Long) v <= 10;
            case "e": return v instanceof String && Arrays.asList(d).subList(2, d.length).contains(v);
            case "u": return v instanceof String && (((String) v).isEmpty() || (isUrl((String) v, false) && ((String) v).length() <= MAX_STRING));
            case "l": {
                if (!(v instanceof List) || ((List<?>) v).size() > MAX_ENTRIES) return false;
                for (Object s : (List<?>) v) if (!(s instanceof String) || ((String) s).isEmpty() || ((String) s).length() > MAX_STRING || ((String) s).indexOf('\n') >= 0) return false;
                return true;
            }
            default: return false;
        }
    }

    /** The allow-listed, valid settings out of a preferences dump (name -> raw value). Everything else is dropped. */
    public static Map<String, Object> settingsFromPrefs(Map<String, ?> all) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String n : SETTING_NAMES) { Object v = settingFromPref(n, all.get(n)); if (v != null) out.put(n, v); }
        return out;
    }

    // ---- passphrase rules (phone page) ----

    /** null = fine, else the text to show. */
    public static String checkPassphrases(String a, String b) {
        if (a == null || b == null || !a.equals(b)) return "The two passphrases are not the same.";
        if (a.codePointCount(0, a.length()) < MIN_PASSPHRASE) return "Use at least 8 characters.";
        return null;
    }

    // ---- encryption ----

    /** Fresh random bytes (salt, nonce, the on-device undo key). */
    public static byte[] random(int n) { byte[] b = new byte[n]; RANDOM.nextBytes(b); return b; }

    private static byte[] deriveKey(char[] pass, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(pass, salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("key derivation unavailable"); // a broken platform, never "wrong passphrase"
        } finally { spec.clearPassword(); }
    }

    /** Encrypts the JSON text. The caller clears {@code pass} afterwards. */
    public static byte[] encrypt(String json, char[] pass) {
        byte[] salt = random(SALT_BYTES), nonce = random(NONCE_BYTES), key = deriveKey(pass, salt), plain = json.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher c = Cipher.getInstance(CIPHER);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[HEADER_BYTES + ct.length];
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
            System.arraycopy(salt, 0, out, MAGIC.length, SALT_BYTES);
            System.arraycopy(nonce, 0, out, MAGIC.length + SALT_BYTES, NONCE_BYTES);
            System.arraycopy(ct, 0, out, HEADER_BYTES, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption unavailable");
        } finally { Arrays.fill(key, (byte) 0); Arrays.fill(plain, (byte) 0); }
    }

    /** Decrypts a whole file to its JSON text. NOT_BACKUP: no magic. BAD_PASSPHRASE: wrong passphrase, or damaged/truncated (the two cannot be told apart). */
    public static String decrypt(byte[] file, char[] pass) throws BackupException {
        if (file == null || file.length < MAGIC.length || file.length > MAX_FILE) throw new BackupException(NOT_BACKUP);
        for (int i = 0; i < MAGIC.length; i++) if (file[i] != MAGIC[i]) throw new BackupException(NOT_BACKUP);
        if (file.length < HEADER_BYTES + TAG_BYTES) throw new BackupException(BAD_PASSPHRASE);
        byte[] salt = Arrays.copyOfRange(file, MAGIC.length, MAGIC.length + SALT_BYTES);
        byte[] nonce = Arrays.copyOfRange(file, MAGIC.length + SALT_BYTES, HEADER_BYTES);
        if (pass == null || pass.length == 0) throw new BackupException(BAD_PASSPHRASE);
        byte[] key = deriveKey(pass, salt), plain = null;
        try {
            Cipher c = Cipher.getInstance(CIPHER);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM), new GCMParameterSpec(TAG_BITS, nonce));
            try {
                plain = c.doFinal(file, HEADER_BYTES, file.length - HEADER_BYTES);
            } catch (BadPaddingException | IllegalBlockSizeException e) {
                throw new BackupException(BAD_PASSPHRASE); // the tag did not match: wrong key or changed bytes
            }
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString();
            } catch (CharacterCodingException e) {
                throw new BackupException(NOT_BACKUP); // authentic but not our text
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption unavailable"); // a broken platform, never "wrong passphrase"
        } finally { Arrays.fill(key, (byte) 0); if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    // ---- the JSON: writing ----

    private static void q(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        b.append('"');
    }

    private static void pair(StringBuilder b, String k, String v) { b.append('[');  q(b, k); b.append(','); q(b, v); b.append(']'); }

    public static String toJson(Data d) {
        StringBuilder b = new StringBuilder(4096);
        b.append("{\"v\":").append(VERSION).append(",\"made\":").append(d.made).append(",\"app\":");
        q(b, d.app);
        b.append(",\"sources\":[");
        for (int i = 0; i < d.sources.size(); i++) { b.append(i == 0 ? "" : ",").append("{\"name\":"); q(b, d.sources.get(i)[0]); b.append(",\"url\":"); q(b, d.sources.get(i)[1]); b.append('}'); }
        b.append("],\"favs\":");
        strings(b, d.favs);
        b.append(",\"recents\":");
        strings(b, d.recents);
        b.append(",\"plays\":[");
        for (int i = 0; i < d.plays.size(); i++) { Play p = d.plays.get(i); b.append(i == 0 ? "[" : ",["); q(b, p.url); b.append(',').append(p.n).append(',').append(p.lastMs).append(']'); }
        b.append("],\"choices\":{");
        boolean first = true;
        for (String kind : CHOICE_KINDS) {
            List<String[]> l = d.choices.get(kind);
            if (l == null) continue;
            b.append(first ? "" : ","); first = false;
            q(b, kind); b.append(":[");
            for (int i = 0; i < l.size(); i++) { b.append(i == 0 ? "" : ","); pair(b, l.get(i)[0], l.get(i)[1]); }
            b.append(']');
        }
        b.append("},\"settings\":{");
        first = true;
        for (String n : SETTING_NAMES) {
            Object v = d.settings.get(n);
            if (v == null) continue;
            b.append(first ? "" : ","); first = false;
            q(b, n); b.append(':');
            if (v instanceof String) q(b, (String) v);
            else if (v instanceof List) strings(b, castStrings(v));
            else b.append(v.toString()); // Boolean or Long
        }
        b.append("},\"reminders\":[");
        for (int i = 0; i < d.reminders.size(); i++) { Rem r = d.reminders.get(i); b.append(i == 0 ? "[" : ",["); q(b, r.key); b.append(','); q(b, r.title); b.append(',').append(r.startSec).append(','); q(b, r.channel); b.append(']'); }
        b.append("],\"xt\":[");
        for (int i = 0; i < d.xt.size(); i++) {
            Xtream x = d.xt.get(i);
            b.append(i == 0 ? "[" : ",[");
            q(b, x.name); b.append(',');
            q(b, x.base); b.append(',');
            q(b, x.user); b.append(',');
            q(b, x.pass); b.append(']');
        }
        b.append("],\"fp\":[");
        for (int i = 0; i < d.fp.size(); i++) {
            FilmProgress p = d.fp.get(i);
            b.append(i == 0 ? "[" : ",[");
            b.append(p.xtIndex).append(',');
            q(b, p.providerId); b.append(',');
            b.append(p.positionMs).append(',').append(p.durationMs).append(',');
            b.append(p.watched ? 1 : 0).append(',').append(p.updatedAt).append(']');
        }
        b.append("],\"ml\":[");
        for (int i = 0; i < d.ml.size(); i++) {
            MyListFilm m = d.ml.get(i);
            b.append(i == 0 ? "[" : ",[");
            b.append(m.xtIndex).append(',');
            q(b, m.providerId); b.append(',');
            b.append(m.order).append(',').append(m.addedAt).append(']');
        }
        b.append("],\"ep\":[");
        for (int i = 0; i < d.ep.size(); i++) {
            EpProgress e = d.ep.get(i);
            b.append(i == 0 ? "[" : ",[");
            b.append(e.xtIndex).append(',');
            q(b, e.seriesId); b.append(',');
            b.append(e.season).append(',').append(e.episode).append(',').append(e.positionMs).append(',').append(e.durationMs).append(',');
            b.append(e.watched ? 1 : 0).append(',').append(e.updatedAt).append(']');
        }
        b.append("],\"sl\":[");
        for (int i = 0; i < d.sl.size(); i++) {
            SeriesListEntry m = d.sl.get(i);
            b.append(i == 0 ? "[" : ",[");
            b.append(m.xtIndex).append(',');
            q(b, m.seriesId); b.append(',');
            b.append(m.order).append(',').append(m.addedAt).append(']');
        }
        b.append("]}");
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStrings(Object o) { return (List<String>) o; }

    private static void strings(StringBuilder b, List<String> l) {
        b.append('[');
        for (int i = 0; i < l.size(); i++) { b.append(i == 0 ? "" : ","); q(b, l.get(i)); }
        b.append(']');
    }

    // ---- the JSON: reading (a small strict parser; integers only, depth-limited) ----

    private static final Object NULL = new Object();

    private static final class Parser {
        final String s; int i;
        Parser(String s) { this.s = s; }

        Object value(int depth) throws BackupException {
            if (depth > MAX_DEPTH) throw bad();
            ws();
            if (i >= s.length()) throw bad();
            char ch = s.charAt(i);
            if (ch == '{') return object(depth);
            if (ch == '[') return array(depth);
            if (ch == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return NULL; }
            return number();
        }

        Map<String, Object> object(int depth) throws BackupException {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek('}')) { i++; return m; }
            while (true) {
                ws();
                if (!peek('"')) throw bad();
                String k = string();
                ws();
                if (!peek(':')) throw bad();
                i++;
                m.put(k, value(depth + 1));
                ws();
                if (peek(',')) { i++; continue; }
                if (peek('}')) { i++; return m; }
                throw bad();
            }
        }

        List<Object> array(int depth) throws BackupException {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (peek(']')) { i++; return l; }
            while (true) {
                l.add(value(depth + 1));
                ws();
                if (peek(',')) { i++; continue; }
                if (peek(']')) { i++; return l; }
                throw bad();
            }
        }

        String string() throws BackupException {
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw bad();
                char ch = s.charAt(i++);
                if (ch == '"') return b.toString();
                if (ch < 0x20) throw bad();
                if (ch != '\\') { b.append(ch); continue; }
                if (i >= s.length()) throw bad();
                char e = s.charAt(i++);
                switch (e) {
                    case '"': case '\\': case '/': b.append(e); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u': {
                        if (i + 4 > s.length()) throw bad();
                        int cp = 0;
                        for (int k = 0; k < 4; k++) { int dgt = Character.digit(s.charAt(i + k), 16); if (dgt < 0) throw bad(); cp = cp * 16 + dgt; }
                        i += 4;
                        b.append((char) cp);
                        break;
                    }
                    default: throw bad();
                }
            }
        }

        Long number() throws BackupException {
            int st = i;
            if (peek('-')) i++;
            int digits = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            if (i == digits || i - digits > 18) throw bad(); // no digits, or too long for a long
            return Long.valueOf(s.substring(st, i));
        }

        boolean peek(char ch) { return i < s.length() && s.charAt(i) == ch; }
        void ws() { while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')) i++; }
        BackupException bad() { return new BackupException(NOT_BACKUP); }
    }

    /** http / https always; channel addresses may also be rtmp / rtsp (the playlist reader accepts those). */
    static boolean isUrl(String u, boolean channel) {
        return u.regionMatches(true, 0, "http://", 0, 7) || u.regionMatches(true, 0, "https://", 0, 8)
                || (channel && (u.regionMatches(true, 0, "rtmp://", 0, 7) || u.regionMatches(true, 0, "rtsp://", 0, 7)));
    }

    // ---- the JSON: checking ----

    private static String str(Object o) throws BackupException {
        if (!(o instanceof String) || ((String) o).length() > MAX_STRING) throw new BackupException(NOT_BACKUP);
        return (String) o;
    }

    private static String nonEmpty(Object o) throws BackupException { String s = str(o); if (s.isEmpty()) throw new BackupException(NOT_BACKUP); return s; }

    private static String url(Object o, boolean channel) throws BackupException { String s = nonEmpty(o); if (!isUrl(s, channel)) throw new BackupException(NOT_BACKUP); return s; }

    private static long num(Object o, long min, long max) throws BackupException {
        if (!(o instanceof Long) || (Long) o < min || (Long) o > max) throw new BackupException(NOT_BACKUP);
        return (Long) o;
    }

    private static List<?> list(Object o, int max) throws BackupException {
        if (o == null) return new ArrayList<Object>();
        if (!(o instanceof List) || ((List<?>) o).size() > max) throw new BackupException(NOT_BACKUP);
        return (List<?>) o;
    }

    private static List<?> row(Object o, int size) throws BackupException {
        if (!(o instanceof List) || ((List<?>) o).size() != size) throw new BackupException(NOT_BACKUP);
        return (List<?>) o;
    }

    private static List<String> urls(Object o) throws BackupException {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (Object x : list(o, MAX_ENTRIES)) { String u = url(x, true); if (seen.add(u)) out.add(u); } // duplicates dropped, first wins
        return out;
    }

    /** Parses and checks a decrypted payload. NOT_BACKUP for anything malformed or out of limits; NEWER when its "v" is above {@link #VERSION}. */
    public static Data fromJson(String json) throws BackupException {
        if (json == null || json.length() > MAX_FILE) throw new BackupException(NOT_BACKUP);
        Parser p = new Parser(json);
        Object top = p.value(0);
        p.ws();
        if (p.i != json.length() || !(top instanceof Map)) throw new BackupException(NOT_BACKUP);
        Map<?, ?> m = (Map<?, ?>) top;
        Object v = m.get("v");
        if (!(v instanceof Long) || (Long) v < 1) throw new BackupException(NOT_BACKUP);
        if ((Long) v > VERSION) throw new BackupException(NEWER);
        Data d = new Data();
        d.made = m.get("made") == null ? 0 : num(m.get("made"), 0, Long.MAX_VALUE);
        d.app = m.get("app") == null ? "" : str(m.get("app"));
        for (Object o : list(m.get("sources"), MAX_SOURCES)) {
            if (!(o instanceof Map)) throw new BackupException(NOT_BACKUP);
            d.sources.add(new String[]{str(((Map<?, ?>) o).get("name")), url(((Map<?, ?>) o).get("url"), false)});
        }
        d.favs.addAll(urls(m.get("favs")));
        d.recents.addAll(urls(m.get("recents")));
        Set<String> seen = new HashSet<>();
        for (Object o : list(m.get("plays"), MAX_ENTRIES)) {
            List<?> r = row(o, 3);
            String u = url(r.get(0), true);
            if (seen.add(u)) d.plays.add(new Play(u, num(r.get(1), 0, 1000000000L), num(r.get(2), 0, Long.MAX_VALUE)));
        }
        Object ch = m.get("choices");
        if (ch != null && !(ch instanceof Map)) throw new BackupException(NOT_BACKUP);
        if (ch != null) for (String kind : CHOICE_KINDS) {
            Object o = ((Map<?, ?>) ch).get(kind);
            if (o == null) continue;
            Map<String, String> uniq = new LinkedHashMap<>(); // a repeated key keeps its last value, in the newest position
            for (Object e : list(o, MAX_ENTRIES)) {
                List<?> r = row(e, 2);
                String k = nonEmpty(r.get(0)), val = nonEmpty(r.get(1));
                if (kind.equals("vols")) { long pct = parseDigits(val); if (pct < 1 || pct > 100) throw new BackupException(NOT_BACKUP); }
                if (kind.equals("vers") && !isUrl(val, true)) throw new BackupException(NOT_BACKUP);
                uniq.remove(k);
                uniq.put(k, val);
            }
            List<String[]> l = new ArrayList<>();
            for (Map.Entry<String, String> e : uniq.entrySet()) l.add(new String[]{e.getKey(), e.getValue()});
            d.choices.put(kind, l);
        }
        Object st = m.get("settings");
        if (st != null && !(st instanceof Map)) throw new BackupException(NOT_BACKUP);
        if (st != null) for (String n : SETTING_NAMES) {
            Object val = ((Map<?, ?>) st).get(n);
            if (val == null || val == NULL) continue;
            if (!validSetting(def(n), val)) throw new BackupException(NOT_BACKUP);
            d.settings.put(n, val);
        } // any other name in the file is ignored, never applied
        seen.clear();
        for (Object o : list(m.get("reminders"), MAX_ENTRIES)) {
            List<?> r = row(o, 4);
            String key = nonEmpty(r.get(0));
            long start = num(r.get(2), 0, Long.MAX_VALUE);
            if (seen.add(key + "\t" + start)) d.reminders.add(new Rem(key, str(r.get(1)), start, str(r.get(3))));
        }
        for (Object o : list(m.get("xt"), MAX_SOURCES)) {
            List<?> r = row(o, 4);
            d.xt.add(new Xtream(str(r.get(0)), url(r.get(1), false), nonEmpty(r.get(2)), str(r.get(3))));
        }
        seen.clear();
        for (Object o : list(m.get("fp"), MAX_ENTRIES)) {
            List<?> r = row(o, 6);
            if (d.xt.isEmpty()) throw new BackupException(NOT_BACKUP);
            int xtIndex = (int) num(r.get(0), 0, d.xt.size() - 1);
            String providerId = nonEmpty(r.get(1));
            long pos = num(r.get(2), 0, Long.MAX_VALUE);
            long dur = num(r.get(3), 0, Long.MAX_VALUE);
            Object w = r.get(4);
            boolean watched;
            if (w instanceof Boolean) watched = (Boolean) w;
            else if (w instanceof Long && ((Long) w == 0 || (Long) w == 1)) watched = (Long) w == 1;
            else throw new BackupException(NOT_BACKUP);
            long updated = num(r.get(5), 0, Long.MAX_VALUE);
            if (seen.add(xtIndex + "\t" + providerId)) {
                d.fp.add(new FilmProgress(xtIndex, providerId, pos, dur, watched, updated));
            }
        }
        seen.clear();
        for (Object o : list(m.get("ml"), MAX_ENTRIES)) {
            List<?> r = row(o, 4);
            if (d.xt.isEmpty()) throw new BackupException(NOT_BACKUP);
            int xtIndex = (int) num(r.get(0), 0, d.xt.size() - 1);
            String providerId = nonEmpty(r.get(1));
            int order = (int) num(r.get(2), 0, Long.MAX_VALUE);
            long added = num(r.get(3), 0, Long.MAX_VALUE);
            if (seen.add(xtIndex + "\t" + providerId)) {
                d.ml.add(new MyListFilm(xtIndex, providerId, order, added));
            }
        }
        seen.clear();
        for (Object o : list(m.get("ep"), MAX_ENTRIES)) {
            List<?> r = row(o, 8);
            if (d.xt.isEmpty()) throw new BackupException(NOT_BACKUP);
            int xtIndex = (int) num(r.get(0), 0, d.xt.size() - 1);
            String seriesId = nonEmpty(r.get(1));
            int season = (int) num(r.get(2), 0, 100000), episode = (int) num(r.get(3), 0, 100000);
            long pos = num(r.get(4), 0, Long.MAX_VALUE), dur = num(r.get(5), 0, Long.MAX_VALUE);
            long w = num(r.get(6), 0, 1);
            long updated = num(r.get(7), 0, Long.MAX_VALUE);
            if (seen.add(xtIndex + "\t" + seriesId + "\t" + season + "\t" + episode)) d.ep.add(new EpProgress(xtIndex, seriesId, season, episode, pos, dur, w == 1, updated));
        }
        seen.clear();
        for (Object o : list(m.get("sl"), MAX_ENTRIES)) {
            List<?> r = row(o, 4);
            if (d.xt.isEmpty()) throw new BackupException(NOT_BACKUP);
            int xtIndex = (int) num(r.get(0), 0, d.xt.size() - 1);
            String seriesId = nonEmpty(r.get(1));
            int order = (int) num(r.get(2), 0, Integer.MAX_VALUE);
            long added = num(r.get(3), 0, Long.MAX_VALUE);
            if (seen.add(xtIndex + "\t" + seriesId)) d.sl.add(new SeriesListEntry(xtIndex, seriesId, order, added));
        }
        return d;
    }

    private static long parseDigits(String s) throws BackupException {
        if (s.isEmpty() || s.length() > 3) throw new BackupException(NOT_BACKUP);
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') throw new BackupException(NOT_BACKUP);
        return Long.parseLong(s);
    }

    // ---- building side: drop what a restore would refuse, so a backup never fails because of the owner's own data ----

    private static boolean okStr(String s, boolean nonEmpty) { return s != null && s.length() <= MAX_STRING && !(nonEmpty && s.isEmpty()); }
    private static boolean okUrl(String u, boolean channel) { return okStr(u, true) && isUrl(u, channel); }

    /** Removes entries that restore would reject and trims lists to the caps (favourites and recents keep order and the first entries; plays and choices keep the newest). Returns how many items were dropped. */
    public static int sanitise(Data d) {
        int dropped = 0;
        List<String[]> src = new ArrayList<>();
        for (String[] s : d.sources) if (okStr(s[0], false) && okUrl(s[1], false) && src.size() < MAX_SOURCES) src.add(s); else dropped++;
        d.sources.clear(); d.sources.addAll(src);
        dropped += cleanUrls(d.favs);
        dropped += cleanUrls(d.recents);
        List<Play> pl = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Play p : d.plays) if (okUrl(p.url, true) && p.n >= 0 && p.n <= 1000000000L && p.lastMs >= 0 && seen.add(p.url) && pl.size() < MAX_ENTRIES) pl.add(p); else dropped++;
        d.plays.clear(); d.plays.addAll(pl); // callers list plays newest first
        for (String kind : CHOICE_KINDS) {
            List<String[]> l = d.choices.get(kind);
            if (l == null) continue;
            List<String[]> ok = new ArrayList<>();
            for (String[] kv : l) {
                boolean good = okStr(kv[0], true) && okStr(kv[1], true);
                if (good && kind.equals("vers")) good = isUrl(kv[1], true);
                if (good && kind.equals("vols")) { try { long x = parseDigits(kv[1]); good = x >= 1 && x <= 100; } catch (BackupException e) { good = false; } }
                if (good) ok.add(kv); else dropped++;
            }
            while (ok.size() > MAX_ENTRIES) { ok.remove(0); dropped++; } // oldest first: keep the newest
            d.choices.put(kind, ok);
        }
        for (String n : new ArrayList<>(d.settings.keySet())) { String[] df = def(n); if (df == null || !validSetting(df, d.settings.get(n))) { d.settings.remove(n); dropped++; } }
        List<Rem> rm = new ArrayList<>();
        for (Rem r : d.reminders) if (okStr(r.key, true) && okStr(r.title, false) && okStr(r.channel, false) && r.startSec >= 0 && rm.size() < MAX_ENTRIES) rm.add(r); else dropped++;
        d.reminders.clear(); d.reminders.addAll(rm);
        int[] xtMap = new int[d.xt.size()];
        Arrays.fill(xtMap, -1);
        List<Xtream> okXt = new ArrayList<>();
        for (int i = 0; i < d.xt.size(); i++) {
            Xtream x = d.xt.get(i);
            boolean good = okStr(x.name, false)
                    && okUrl(x.base, false)
                    && okStr(x.user, true)
                    && okStr(x.pass, false);
            if (good && okXt.size() < MAX_SOURCES) {
                xtMap[i] = okXt.size();
                okXt.add(x);
            } else {
                dropped++;
            }
        }
        d.xt.clear();
        d.xt.addAll(okXt);
        List<FilmProgress> okFp = new ArrayList<>();
        Set<String> seenFp = new HashSet<>();
        for (FilmProgress p : d.fp) {
            int newXt = (p.xtIndex >= 0 && p.xtIndex < xtMap.length) ? xtMap[p.xtIndex] : -1;
            boolean good = newXt >= 0
                    && okStr(p.providerId, true)
                    && p.positionMs >= 0
                    && p.durationMs >= 0
                    && p.updatedAt >= 0;
            if (good && seenFp.add(newXt + "\t" + p.providerId) && okFp.size() < MAX_ENTRIES) {
                okFp.add(newXt == p.xtIndex ? p : new FilmProgress(newXt, p.providerId, p.positionMs, p.durationMs, p.watched, p.updatedAt));
            } else {
                dropped++;
            }
        }
        d.fp.clear();
        d.fp.addAll(okFp);
        List<MyListFilm> okMl = new ArrayList<>();
        Set<String> seenMl = new HashSet<>();
        for (MyListFilm m : d.ml) {
            int newXt = (m.xtIndex >= 0 && m.xtIndex < xtMap.length) ? xtMap[m.xtIndex] : -1;
            boolean good = newXt >= 0
                    && okStr(m.providerId, true)
                    && m.order >= 0
                    && m.addedAt >= 0;
            if (good && seenMl.add(newXt + "\t" + m.providerId) && okMl.size() < MAX_ENTRIES) {
                okMl.add(newXt == m.xtIndex ? m : new MyListFilm(newXt, m.providerId, m.order, m.addedAt));
            } else {
                dropped++;
            }
        }
        d.ml.clear();
        d.ml.addAll(okMl);
        List<EpProgress> okEp = new ArrayList<>();
        Set<String> seenEp = new HashSet<>();
        for (EpProgress e : d.ep) {
            int newXt = (e.xtIndex >= 0 && e.xtIndex < xtMap.length) ? xtMap[e.xtIndex] : -1;
            boolean good = newXt >= 0 && okStr(e.seriesId, true) && e.season >= 0 && e.season <= 100000 && e.episode >= 0 && e.episode <= 100000
                    && e.positionMs >= 0 && e.durationMs >= 0 && e.updatedAt >= 0;
            if (good && seenEp.add(newXt + "\t" + e.seriesId + "\t" + e.season + "\t" + e.episode) && okEp.size() < MAX_ENTRIES) {
                okEp.add(newXt == e.xtIndex ? e : new EpProgress(newXt, e.seriesId, e.season, e.episode, e.positionMs, e.durationMs, e.watched, e.updatedAt));
            } else dropped++;
        }
        d.ep.clear(); d.ep.addAll(okEp);
        List<SeriesListEntry> okSl = new ArrayList<>();
        Set<String> seenSl = new HashSet<>();
        for (SeriesListEntry m : d.sl) {
            int newXt = (m.xtIndex >= 0 && m.xtIndex < xtMap.length) ? xtMap[m.xtIndex] : -1;
            boolean good = newXt >= 0 && okStr(m.seriesId, true) && m.order >= 0 && m.addedAt >= 0;
            if (good && seenSl.add(newXt + "\t" + m.seriesId) && okSl.size() < MAX_ENTRIES) {
                okSl.add(newXt == m.xtIndex ? m : new SeriesListEntry(newXt, m.seriesId, m.order, m.addedAt));
            } else dropped++;
        }
        d.sl.clear(); d.sl.addAll(okSl);
        return dropped;
    }

    private static int cleanUrls(List<String> l) {
        int dropped = 0;
        List<String> ok = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String u : l) if (okUrl(u, true) && seen.add(u) && ok.size() < MAX_ENTRIES) ok.add(u); else dropped++;
        l.clear(); l.addAll(ok);
        return dropped;
    }

    /** Checks a payload we are about to encrypt: the same rules a restore applies, so a backup that cannot be restored is never handed out. */
    public static String checkedJson(Data d) throws BackupException {
        String json = toJson(d);
        fromJson(json);
        return json;
    }

    /** Whole pipeline for a file from outside: decrypt, parse, check. */
    public static Data open(byte[] file, char[] pass) throws BackupException { return fromJson(decrypt(file, pass)); }

    /** Whole pipeline for a file we make: check, then encrypt. */
    public static byte[] make(Data d, char[] pass) throws BackupException { return encrypt(checkedJson(d), pass); }
}
