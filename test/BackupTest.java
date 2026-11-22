import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lk.manus.iptv.data.Backup;

/** T72: the encrypted backup file (pure Java). Round trip, tampering, limits, the preference allow-list. */
public final class BackupTest {
    static int fails, checks;
    static void eq(String what, Object got, Object want) { checks++; if (!java.util.Objects.equals(want, got)) { fails++; System.out.println("FAIL " + what + ": got [" + got + "], want [" + want + "]"); } }

    static Backup.Data sample() {
        Backup.Data d = new Backup.Data();
        d.made = 1763320800L; d.app = "1.0";
        d.sources.add(new String[]{"My list", "https://user:pw@example.com/list.m3u"});
        d.sources.add(new String[]{"Zwei \"quotes\" \\ back\u0001slash", "http://example.org/b.m3u8"});
        d.favs.addAll(Arrays.asList("http://a.example/1.m3u8", "https://b.example/2.m3u8", "rtmp://c.example/live/3"));
        d.recents.addAll(Arrays.asList("https://b.example/2.m3u8", "http://a.example/1.m3u8"));
        d.plays.add(new Backup.Play("http://a.example/1.m3u8", 7, 1763300000000L));
        d.choices.put("subs", Arrays.asList(new String[]{"a9993e364706816a", "en"}, new String[]{"279bc321ae08c2c5", "fr"}));
        d.choices.put("vols", Arrays.<String[]>asList(new String[]{"a9993e364706816a", "70"}));
        d.choices.put("vers", Arrays.<String[]>asList(new String[]{"nbbc", "https://b.example/2.m3u8"}));
        d.settings.put("quality", "saver"); d.settings.put("edge_pct", 3L); d.settings.put("mirror_mode", true);
        d.settings.put("hidden", Arrays.asList("cc:lk", "cat:News")); d.settings.put("feed", "https://feed.example/data/");
        d.reminders.add(new Backup.Rem("a9993e364706816a", "Red Dawn", 1763400000L, "Movies One"));
        d.xt.add(new Backup.Xtream("Xtream 1", "http://xtream.example:8080", "user_ünicöde", "pass_pässwörd🔑123"));
        d.fp.add(new Backup.FilmProgress(0, "vod_101", 150000L, 7200000L, false, 1763310000000L));
        d.ml.add(new Backup.MyListFilm(0, "vod_101", 0, 1763305000000L));
        d.ep.add(new Backup.EpProgress(0, "7", 1, 3, 90000L, 2700000L, false, 1763311000000L));
        d.sl.add(new Backup.SeriesListEntry(0, "7", 0, 1763306000000L));
        return d;
    }

    static void bad(String what, byte[] file, char[] pass, int kind) {
        try { Backup.open(file, pass); eq(what, "accepted", "rejected"); }
        catch (Backup.BackupException e) { eq(what, e.kind, kind); }
    }

    static void badJson(String what, String json, int kind) {
        try { Backup.fromJson(json); eq(what, "accepted", "rejected"); }
        catch (Backup.BackupException e) { eq(what, e.kind, kind); }
    }

    public static void main(String[] a) throws Exception {
        char[] pw = "correct horse".toCharArray();
        Backup.Data d = sample();
        byte[] f1 = Backup.make(d, pw), f2 = Backup.make(d, pw);

        // round trip
        Backup.Data r = Backup.open(f1, pw);
        eq("round trip json", Backup.toJson(r), Backup.toJson(d));
        eq("sources", r.sources.size(), 2);
        eq("odd name kept", r.sources.get(1)[0], d.sources.get(1)[0]);
        eq("favs order", r.favs, d.favs);
        eq("plays", r.plays.get(0).n, 7L);
        eq("choices order", r.choices.get("subs").get(1)[1], "fr");
        eq("settings list", r.settings.get("hidden"), Arrays.asList("cc:lk", "cat:News"));
        eq("settings int", r.settings.get("edge_pct"), 3L);
        eq("reminder channel name", r.reminders.get(0).channel, "Movies One");
        eq("xtream count", r.xt.size(), 1);
        eq("xtream name", r.xt.get(0).name, "Xtream 1");
        eq("xtream base", r.xt.get(0).base, "http://xtream.example:8080");
        eq("xtream user", r.xt.get(0).user, "user_ünicöde");
        eq("xtream pass unicode", r.xt.get(0).pass, "pass_pässwörd🔑123");
        eq("fp count", r.fp.size(), 1);
        eq("fp xtIndex", r.fp.get(0).xtIndex, 0);
        eq("fp providerId", r.fp.get(0).providerId, "vod_101");
        eq("fp positionMs", r.fp.get(0).positionMs, 150000L);
        eq("fp durationMs", r.fp.get(0).durationMs, 7200000L);
        eq("fp watched", r.fp.get(0).watched, false);
        eq("fp updatedAt", r.fp.get(0).updatedAt, 1763310000000L);
        eq("ml count", r.ml.size(), 1);
        eq("ml xtIndex", r.ml.get(0).xtIndex, 0);
        eq("ml providerId", r.ml.get(0).providerId, "vod_101");
        eq("ml order", r.ml.get(0).order, 0);
        eq("ml addedAt", r.ml.get(0).addedAt, 1763305000000L);

        eq("ep count", r.ep.size(), 1);
        eq("ep series", r.ep.get(0).seriesId, "7");
        eq("ep season", r.ep.get(0).season, 1);
        eq("ep episode", r.ep.get(0).episode, 3);
        eq("ep pos", r.ep.get(0).positionMs, 90000L);
        eq("ep dur", r.ep.get(0).durationMs, 2700000L);
        eq("ep watched", r.ep.get(0).watched, false);
        eq("ep updated", r.ep.get(0).updatedAt, 1763311000000L);
        eq("sl count", r.sl.size(), 1);
        eq("sl series", r.sl.get(0).seriesId, "7");
        eq("sl order", r.sl.get(0).order, 0);
        eq("sl added", r.sl.get(0).addedAt, 1763306000000L);
        eq("version 3", Backup.VERSION, 3);
        eq("json says v3", Backup.toJson(d).startsWith("{\"v\":3,"), true);

        // header and nothing readable
        eq("magic", new String(f1, 0, 5, StandardCharsets.US_ASCII), "MTVB1");
        eq("length = 5+16+12+json+16", f1.length, 5 + 16 + 12 + Backup.toJson(d).getBytes(StandardCharsets.UTF_8).length + 16);
        String asText = new String(f1, 5, f1.length - 5, StandardCharsets.ISO_8859_1);
        eq("no readable url after the header", asText.contains("example.com") || asText.contains("favs") || asText.contains("My list"), false);
        byte[] passBytes = "pass_pässwörd🔑123".getBytes(StandardCharsets.UTF_8);
        boolean foundPass = false;
        for (int i = 0; i <= f1.length - passBytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < passBytes.length; j++) { if (f1[i + j] != passBytes[j]) { match = false; break; } }
            if (match) { foundPass = true; break; }
        }
        eq("plaintext password absent from encrypted bytes", foundPass, false);
        byte[] userBytes = "user_ünicöde".getBytes(StandardCharsets.UTF_8);
        boolean foundUser = false;
        for (int i = 0; i <= f1.length - userBytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < userBytes.length; j++) { if (f1[i + j] != userBytes[j]) { match = false; break; } }
            if (match) { foundUser = true; break; }
        }
        eq("plaintext user absent from encrypted bytes", foundUser, false);

        // two backups of the same state differ (random salt and nonce)
        eq("salt differs", Arrays.equals(Arrays.copyOfRange(f1, 5, 21), Arrays.copyOfRange(f2, 5, 21)), false);
        eq("nonce differs", Arrays.equals(Arrays.copyOfRange(f1, 21, 33), Arrays.copyOfRange(f2, 21, 33)), false);
        eq("whole file differs", Arrays.equals(f1, f2), false);

        // wrong passphrase, empty passphrase
        bad("wrong passphrase", f1, "correct horsf".toCharArray(), Backup.BAD_PASSPHRASE);
        bad("empty passphrase", f1, new char[0], Backup.BAD_PASSPHRASE);

        // one flipped byte anywhere after the magic: header, salt, nonce, ciphertext, tag
        int[] spots = {5, 20, 21, 32, 33, 40, f1.length - 1, f1.length / 2};
        for (int s : spots) { byte[] x = f1.clone(); x[s] ^= 1; bad("flipped byte " + s, x, pw, Backup.BAD_PASSPHRASE); }

        // truncated
        bad("truncated by 1", Arrays.copyOf(f1, f1.length - 1), pw, Backup.BAD_PASSPHRASE);
        bad("truncated to header", Arrays.copyOf(f1, 33), pw, Backup.BAD_PASSPHRASE);
        bad("truncated inside header", Arrays.copyOf(f1, 20), pw, Backup.BAD_PASSPHRASE);
        bad("extended by 1", Arrays.copyOf(f1, f1.length + 1), pw, Backup.BAD_PASSPHRASE);

        // wrong magic / not a backup
        byte[] wm = f1.clone(); wm[4] = '2';
        bad("wrong magic", wm, pw, Backup.NOT_BACKUP);
        bad("plain text", "hello, this is not a backup".getBytes(StandardCharsets.UTF_8), pw, Backup.NOT_BACKUP);
        bad("empty", new byte[0], pw, Backup.NOT_BACKUP);
        bad("null", null, pw, Backup.NOT_BACKUP);
        bad("only magic", "MTVB1".getBytes(StandardCharsets.US_ASCII), pw, Backup.BAD_PASSPHRASE);
        bad("too big", new byte[Backup.MAX_FILE + 1], pw, Backup.NOT_BACKUP);

        // authentic but wrong content (a file made by us from other text)
        bad("encrypted text that is not json", Backup.encrypt("not json at all", pw), pw, Backup.NOT_BACKUP);
        bad("encrypted json that is not an object", Backup.encrypt("[1,2]", pw), pw, Backup.NOT_BACKUP);

        // newer version
        bad("newer v", Backup.encrypt("{\"v\":4,\"favs\":[\"http://x/\"]}", pw), pw, Backup.NEWER);
        bad("newer v with a field this version would refuse", Backup.encrypt("{\"v\":9,\"favs\":5}", pw), pw, Backup.NEWER);
        badJson("no v", "{\"favs\":[]}", Backup.NOT_BACKUP);
        badJson("v 0", "{\"v\":0}", Backup.NOT_BACKUP);
        badJson("v text", "{\"v\":\"1\"}", Backup.NOT_BACKUP);
        eq("minimal ok", Backup.fromJson("{\"v\":2}").favs.size(), 0);
        Backup.Data v1 = Backup.fromJson("{\"v\":1,\"favs\":[\"http://x/\"]}");
        eq("v1 parses: favs", v1.favs.size(), 1);
        eq("v1 parses: empty xt", v1.xt.size(), 0);
        eq("v1 parses: empty fp", v1.fp.size(), 0);
        eq("v1 parses: empty ml", v1.ml.size(), 0);
        // v2 file fixture (no ep / sl) and v1 file fixture still restore
        Backup.Data v2 = Backup.fromJson("{\"v\":2,\"made\":5,\"favs\":[\"http://x/\"],\"xt\":[[\"n\",\"http://x/\",\"u\",\"p\"]],\"fp\":[[0,\"101\",10,20,1,100]],\"ml\":[[0,\"101\",0,100]]}");
        eq("v2 parses: xt", v2.xt.size(), 1);
        eq("v2 parses: fp", v2.fp.size(), 1);
        eq("v2 parses: ml", v2.ml.size(), 1);
        eq("v2 parses: no ep", v2.ep.size(), 0);
        eq("v2 parses: no sl", v2.sl.size(), 0);
        eq("v1 parses: no ep", v1.ep.size(), 0);
        eq("v1 parses: no sl", v1.sl.size(), 0);
        Backup.Data v2f = Backup.open(Backup.encrypt("{\"v\":2,\"favs\":[\"http://x/\"]}", pw), pw);
        eq("v2 file opens through the whole pipeline", v2f.favs.size(), 1);
        Backup.Data v3a = Backup.fromJson("{\"v\":3,\"xt\":[[\"n\",\"http://x/\",\"u\",\"p\"]],\"ep\":[[0,\"7\",1,2,5,10,true,100],[0,\"7\",1,2,6,10,0,101]],\"sl\":[[0,\"7\",0,100],[0,\"7\",1,100]]}".replace("true", "1"));
        eq("v3 duplicate ep dropped", v3a.ep.size(), 1);
        eq("v3 duplicate sl dropped", v3a.sl.size(), 1);
        eq("v3 ep watched flag", v3a.ep.get(0).watched, true);
        eq("unknown fields ignored", Backup.fromJson("{\"v\":2,\"zzz\":{\"a\":[1,2,{\"b\":null}]},\"favs\":[\"http://x/\"]}").favs.size(), 1);

        // invalid and oversized fields
        badJson("trailing junk", "{\"v\":1} x", Backup.NOT_BACKUP);
        badJson("favs not a list", "{\"v\":1,\"favs\":\"http://x/\"}", Backup.NOT_BACKUP);
        badJson("fav not a url", "{\"v\":1,\"favs\":[\"ftp://x/\"]}", Backup.NOT_BACKUP);
        badJson("fav javascript", "{\"v\":1,\"favs\":[\"javascript:alert(1)\"]}", Backup.NOT_BACKUP);
        badJson("fav number", "{\"v\":1,\"favs\":[5]}", Backup.NOT_BACKUP);
        badJson("source rtmp not allowed", "{\"v\":1,\"sources\":[{\"name\":\"x\",\"url\":\"rtmp://x/y\"}]}", Backup.NOT_BACKUP);
        badJson("source without url", "{\"v\":1,\"sources\":[{\"name\":\"x\"}]}", Backup.NOT_BACKUP);
        badJson("plays wrong shape", "{\"v\":1,\"plays\":[[\"http://x/\",1]]}", Backup.NOT_BACKUP);
        badJson("plays negative", "{\"v\":1,\"plays\":[[\"http://x/\",-1,5]]}", Backup.NOT_BACKUP);
        badJson("float number", "{\"v\":1,\"plays\":[[\"http://x/\",1.5,5]]}", Backup.NOT_BACKUP);
        badJson("reminder 3 fields", "{\"v\":1,\"reminders\":[[\"k\",\"t\",5]]}", Backup.NOT_BACKUP);
        badJson("vols not a number", "{\"v\":1,\"choices\":{\"vols\":[[\"k\",\"loud\"]]}}", Backup.NOT_BACKUP);
        badJson("vols out of range", "{\"v\":1,\"choices\":{\"vols\":[[\"k\",\"500\"]]}}", Backup.NOT_BACKUP);
        badJson("vers not a url", "{\"v\":1,\"choices\":{\"vers\":[[\"k\",\"nope\"]]}}", Backup.NOT_BACKUP);
        badJson("setting wrong type", "{\"v\":1,\"settings\":{\"mirror_mode\":\"yes\"}}", Backup.NOT_BACKUP);
        badJson("setting not in its set", "{\"v\":1,\"settings\":{\"quality\":\"ultra\"}}", Backup.NOT_BACKUP);
        badJson("setting out of range", "{\"v\":1,\"settings\":{\"edge_pct\":11}}", Backup.NOT_BACKUP);
        badJson("feed not a url", "{\"v\":1,\"settings\":{\"feed\":\"file:///x\"}}", Backup.NOT_BACKUP);
        badJson("hidden has a newline", "{\"v\":1,\"settings\":{\"hidden\":[\"a\\nb\"]}}", Backup.NOT_BACKUP);
        badJson("fp xtIndex negative", "{\"v\":2,\"xt\":[[\"n\",\"http://x/\",\"u\",\"p\"]],\"fp\":[[-1,\"101\",10,10,0,100]]}", Backup.NOT_BACKUP);
        badJson("fp xtIndex too high", "{\"v\":2,\"xt\":[[\"n\",\"http://x/\",\"u\",\"p\"]],\"fp\":[[1,\"101\",10,10,0,100]]}", Backup.NOT_BACKUP);
        badJson("fp xtIndex empty xt", "{\"v\":2,\"xt\":[],\"fp\":[[0,\"101\",10,10,0,100]]}", Backup.NOT_BACKUP);
        badJson("ml xtIndex negative", "{\"v\":2,\"xt\":[[\"n\",\"http://x/\",\"u\",\"p\"]],\"ml\":[[-1,\"101\",0,100]]}", Backup.NOT_BACKUP);
        badJson("ml xtIndex too high", "{\"v\":2,\"xt\":[[\"n\",\"http://x/\",\"u\",\"p\"]],\"ml\":[[1,\"101\",0,100]]}", Backup.NOT_BACKUP);
        badJson("ml xtIndex empty xt", "{\"v\":2,\"xt\":[],\"ml\":[[0,\"101\",0,100]]}", Backup.NOT_BACKUP);
        badJson("xt bad base", "{\"v\":2,\"xt\":[[\"n\",\"ftp://x/\",\"u\",\"p\"]]}", Backup.NOT_BACKUP);
        badJson("xt empty user", "{\"v\":2,\"xt\":[[\"n\",\"http://x/\",\"\",\"p\"]]}", Backup.NOT_BACKUP);
        badJson("fp negative pos", "{\"v\":2,\"xt\":[[\"n\",\"http://x/\",\"u\",\"p\"]],\"fp\":[[0,\"101\",-1,10,0,100]]}", Backup.NOT_BACKUP);
        badJson("fp negative dur", "{\"v\":2,\"xt\":[[\"n\",\"http://x/\",\"u\",\"p\"]],\"fp\":[[0,\"101\",10,-1,0,100]]}", Backup.NOT_BACKUP);
        badJson("ml negative order", "{\"v\":2,\"xt\":[[\"n\",\"http://x/\",\"u\",\"p\"]],\"ml\":[[0,\"101\",-1,100]]}", Backup.NOT_BACKUP);
        badJson("ml negative added", "{\"v\":2,\"xt\":[[\"n\",\"http://x/\",\"u\",\"p\"]],\"ml\":[[0,\"101\",0,-1]]}", Backup.NOT_BACKUP);
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 40; i++) deep.append('[');
        badJson("nesting too deep", "{\"v\":1,\"x\":" + deep + "}", Backup.NOT_BACKUP);
        badJson("bad escape", "{\"v\":1,\"app\":\"\\q\"}", Backup.NOT_BACKUP);
        badJson("raw control char in string", "{\"v\":1,\"app\":\"a\u0001b\"}", Backup.NOT_BACKUP);
        badJson("unterminated", "{\"v\":1,\"app\":\"abc", Backup.NOT_BACKUP);

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < Backup.MAX_STRING + 1; i++) big.append('x');
        badJson("string too long", "{\"v\":1,\"app\":\"" + big + "\"}", Backup.NOT_BACKUP);
        badJson("url too long", "{\"v\":1,\"favs\":[\"http://" + big + "\"]}", Backup.NOT_BACKUP);
        StringBuilder many = new StringBuilder("{\"v\":1,\"favs\":[");
        for (int i = 0; i <= Backup.MAX_ENTRIES; i++) many.append(i == 0 ? "" : ",").append("\"http://x/").append(i).append('"');
        badJson("too many favs", many + "]}", Backup.NOT_BACKUP);
        StringBuilder src = new StringBuilder("{\"v\":1,\"sources\":[");
        for (int i = 0; i <= Backup.MAX_SOURCES; i++) src.append(i == 0 ? "" : ",").append("{\"name\":\"n\",\"url\":\"http://x/").append(i).append("\"}");
        badJson("too many sources", src + "]}", Backup.NOT_BACKUP);
        StringBuilder manyXt = new StringBuilder("{\"v\":2,\"xt\":[");
        for (int i = 0; i <= Backup.MAX_SOURCES; i++) manyXt.append(i == 0 ? "" : ",").append("[\"n\",\"http://x/\",\"u\",\"p\"]");
        badJson("too many xt", manyXt + "]}", Backup.NOT_BACKUP);
        StringBuilder manyFp = new StringBuilder("{\"v\":2,\"xt\":[[\"n\",\"http://x/\",\"u\",\"p\"]],\"fp\":[");
        for (int i = 0; i <= Backup.MAX_ENTRIES; i++) manyFp.append(i == 0 ? "" : ",").append("[0,\"id").append(i).append("\",0,0,0,0]");
        badJson("too many fp", manyFp + "]}", Backup.NOT_BACKUP);
        StringBuilder manyMl = new StringBuilder("{\"v\":2,\"xt\":[[\"n\",\"http://x/\",\"u\",\"p\"]],\"ml\":[");
        for (int i = 0; i <= Backup.MAX_ENTRIES; i++) manyMl.append(i == 0 ? "" : ",").append("[0,\"id").append(i).append("\",0,0]");
        badJson("too many ml", manyMl + "]}", Backup.NOT_BACKUP);
        String XT = "\"xt\":[[\"n\",\"http://x/\",\"u\",\"p\"]]";
        badJson("ep xtIndex empty xt", "{\"v\":3,\"xt\":[],\"ep\":[[0,\"7\",1,1,0,0,0,0]]}", Backup.NOT_BACKUP);
        badJson("ep xtIndex too high", "{\"v\":3," + XT + ",\"ep\":[[1,\"7\",1,1,0,0,0,0]]}", Backup.NOT_BACKUP);
        badJson("ep wrong shape", "{\"v\":3," + XT + ",\"ep\":[[0,\"7\",1,1,0,0,0]]}", Backup.NOT_BACKUP);
        badJson("ep negative position", "{\"v\":3," + XT + ",\"ep\":[[0,\"7\",1,1,-1,0,0,0]]}", Backup.NOT_BACKUP);
        badJson("ep negative season", "{\"v\":3," + XT + ",\"ep\":[[0,\"7\",-1,1,0,0,0,0]]}", Backup.NOT_BACKUP);
        badJson("ep watched 2", "{\"v\":3," + XT + ",\"ep\":[[0,\"7\",1,1,0,0,2,0]]}", Backup.NOT_BACKUP);
        badJson("ep empty series id", "{\"v\":3," + XT + ",\"ep\":[[0,\"\",1,1,0,0,0,0]]}", Backup.NOT_BACKUP);
        badJson("ep series id a number", "{\"v\":3," + XT + ",\"ep\":[[0,7,1,1,0,0,0,0]]}", Backup.NOT_BACKUP);
        badJson("ep not a list", "{\"v\":3,\"ep\":5}", Backup.NOT_BACKUP);
        badJson("sl xtIndex empty xt", "{\"v\":3,\"xt\":[],\"sl\":[[0,\"7\",0,0]]}", Backup.NOT_BACKUP);
        badJson("sl wrong shape", "{\"v\":3," + XT + ",\"sl\":[[0,\"7\",0]]}", Backup.NOT_BACKUP);
        badJson("sl negative order", "{\"v\":3," + XT + ",\"sl\":[[0,\"7\",-1,0]]}", Backup.NOT_BACKUP);
        badJson("sl series id too long", "{\"v\":3," + XT + ",\"sl\":[[0,\"" + big + "\",0,0]]}", Backup.NOT_BACKUP);
        StringBuilder manyEp = new StringBuilder("{\"v\":3," + XT + ",\"ep\":[");
        for (int i = 0; i <= Backup.MAX_ENTRIES; i++) manyEp.append(i == 0 ? "" : ",").append("[0,\"s").append(i).append("\",1,1,0,0,0,0]");
        badJson("too many ep", manyEp + "]}", Backup.NOT_BACKUP);
        StringBuilder manySl = new StringBuilder("{\"v\":3," + XT + ",\"sl\":[");
        for (int i = 0; i <= Backup.MAX_ENTRIES; i++) manySl.append(i == 0 ? "" : ",").append("[0,\"s").append(i).append("\",0,0]");
        badJson("too many sl", manySl + "]}", Backup.NOT_BACKUP);
        StringBuilder ok = new StringBuilder("{\"v\":1,\"favs\":[");
        for (int i = 0; i < Backup.MAX_ENTRIES; i++) ok.append(i == 0 ? "" : ",").append("\"http://x/").append(i).append('"');
        eq("exactly the limit is fine", Backup.fromJson(ok + "]}").favs.size(), Backup.MAX_ENTRIES);

        // duplicates are dropped
        eq("duplicate favs dropped", Backup.fromJson("{\"v\":1,\"favs\":[\"http://x/\",\"http://y/\",\"http://x/\"]}").favs, Arrays.asList("http://x/", "http://y/"));
        eq("repeated choice keeps the last value", Backup.fromJson("{\"v\":1,\"choices\":{\"subs\":[[\"k\",\"en\"],[\"j\",\"de\"],[\"k\",\"fr\"]]}}").choices.get("subs").get(1)[1], "fr");

        // only allow-listed preferences are ever written, and the excluded ones never reach the JSON
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("quality", "best"); prefs.put("edge_pct", 4); prefs.put("hidden", "cc:lk\ncat:News"); prefs.put("setup_done", true); prefs.put("feed", "");
        prefs.put("tmdb_key", "SECRETKEY0123456789abcdef"); prefs.put("tmdb_bad_key", "BADKEY0123456789abcdef"); prefs.put("tmdb_base", "http://10.0.2.2:8080/");
        prefs.put("tmdb_img_base", "http://10.0.2.2:8081/"); prefs.put("db_migrated", true); prefs.put("demo_blur", true); prefs.put("last_tmdb_key", "LASTKEY0123456789abcdef");
        prefs.put("undo_key", "UNDOKEY0123456789abcdef"); prefs.put("last", "http://secret.example/live.m3u8"); prefs.put("sources", "[{\"url\":\"http://u:p@h/\"}]");
        prefs.put("etag_x", "W/\"abc\""); prefs.put("health", "dead-list");
        Backup.Data p = new Backup.Data();
        p.settings.putAll(Backup.settingsFromPrefs(prefs));
        String json = Backup.toJson(p);
        for (String n : new String[]{"tmdb_key", "tmdb_bad_key", "tmdb_base", "tmdb_img_base", "db_migrated", "demo_blur", "last_tmdb_key", "undo_key", "SECRETKEY", "BADKEY", "LASTKEY", "UNDOKEY", "secret.example", "etag_x", "health", "\"sources\":[{\"url\""})
            eq("excluded: " + n, json.contains(n), false);
        eq("allowed quality", p.settings.get("quality"), "best");
        eq("allowed int", p.settings.get("edge_pct"), 4L);
        eq("allowed list", p.settings.get("hidden"), Arrays.asList("cc:lk", "cat:News"));
        eq("settings count", p.settings.size(), 5);
        eq("wrong-typed pref dropped", Backup.settingFromPref("quality", 5), null);
        eq("out-of-set pref dropped", Backup.settingFromPref("quality", "ultra"), null);
        eq("pref to storage: list", Backup.settingToPref("hidden", Arrays.asList("a", "b")), "a\nb");
        eq("pref to storage: int", Backup.settingToPref("edge_pct", 4L), 4);
        // a file that names an excluded preference: it is ignored, not applied
        Backup.Data x = Backup.fromJson("{\"v\":1,\"settings\":{\"tmdb_key\":\"EVIL\",\"quality\":\"saver\",\"db_migrated\":false}}");
        eq("excluded names ignored when reading", x.settings.size(), 1);
        eq("allowed name read", x.settings.get("quality"), "saver");

        // passphrase rules
        eq("short", Backup.checkPassphrases("abc", "abc"), "Use at least 8 characters.");
        eq("differ", Backup.checkPassphrases("abcdefgh", "abcdefgH"), "The two passphrases are not the same.");
        eq("differ beats short", Backup.checkPassphrases("abc", "abd"), "The two passphrases are not the same.");
        eq("ok", Backup.checkPassphrases("abcdefgh", "abcdefgh"), null);
        eq("null", Backup.checkPassphrases(null, null), "The two passphrases are not the same.");

        // a state that cannot be restored is refused at backup time, not later
        Backup.Data tooMany = new Backup.Data();
        for (int i = 0; i < 5; i++) tooMany.sources.add(new String[]{"n", "not-a-url"});
        try { Backup.make(tooMany, pw); eq("invalid state refused", "made", "refused"); } catch (Backup.BackupException e) { eq("invalid state refused", e.kind, Backup.NOT_BACKUP); }

        // sanitising: a backup is never refused because of the owner's own data
        Backup.Data s = sample();
        s.favs.add("udp://239.1.1.1:1234"); s.favs.add("http://" + big); s.favs.add("http://ok.example/z");
        for (int i = 0; i < Backup.MAX_ENTRIES + 10; i++) s.recents.add("http://r.example/" + i);
        s.choices.put("vers", new ArrayList<>(s.choices.get("vers"))); s.choices.get("vers").add(new String[]{"k2", "mms://x/y"});
        s.plays.add(new Backup.Play("ftp://nope", 1, 1));
        s.xt.add(new Backup.Xtream("BadBase", "ftp://bad/", "u", "p"));
        s.xt.add(new Backup.Xtream("EmptyUser", "http://ok/", "", "p"));
        s.xt.add(new Backup.Xtream("ValidXt", "http://valid.example/", "user2", "pass2"));
        s.fp.add(new Backup.FilmProgress(99, "m1", 10, 10, false, 100));
        s.fp.add(new Backup.FilmProgress(0, "m2", -5, 10, false, 100));
        s.fp.add(new Backup.FilmProgress(3, "m3", 10, 10, false, 100));
        s.ml.add(new Backup.MyListFilm(99, "m1", 0, 100));
        s.ml.add(new Backup.MyListFilm(0, "m2", -1, 100));
        s.ml.add(new Backup.MyListFilm(3, "m3", 5, 100));
        s.ep.add(new Backup.EpProgress(99, "7", 1, 1, 1, 1, false, 1));
        s.ep.add(new Backup.EpProgress(0, "7", 1, 1, -1, 1, false, 1));
        s.ep.add(new Backup.EpProgress(3, "8", 2, 5, 10, 20, true, 5));
        s.sl.add(new Backup.SeriesListEntry(99, "7", 0, 1));
        s.sl.add(new Backup.SeriesListEntry(3, "8", 4, 5));
        int drop = Backup.sanitise(s);
        eq("dropped count", drop, 2 + 12 + 1 + 1 + 2 + 2 + 2 + 2 + 1); // 2 favs, 12 recents over cap, 1 vers, 1 play, 2 xt, 2 fp, 2 ml, 2 ep, 1 sl
        Backup.Data back = Backup.open(Backup.make(s, pw), pw);
        eq("sanitised file opens: favs", back.favs, Arrays.asList("http://a.example/1.m3u8", "https://b.example/2.m3u8", "rtmp://c.example/live/3", "http://ok.example/z"));
        eq("recents capped", back.recents.size(), Backup.MAX_ENTRIES);
        eq("bad vers dropped", back.choices.get("vers").size(), 1);
        eq("bad play dropped", back.plays.size(), 1);
        eq("xt kept count", back.xt.size(), 2);
        eq("valid xt kept", back.xt.get(1).name, "ValidXt");
        eq("fp kept count", back.fp.size(), 2);
        eq("fp remapped xtIndex", back.fp.get(1).xtIndex, 1);
        eq("ml kept count", back.ml.size(), 2);
        eq("ml remapped xtIndex", back.ml.get(1).xtIndex, 1);
        eq("ep kept: sample + remapped", back.ep.size(), 2);
        eq("ep remapped xtIndex", back.ep.get(1).xtIndex, 1);
        eq("sl kept: sample + remapped", back.sl.size(), 2);
        eq("sl remapped xtIndex", back.sl.get(1).xtIndex, 1);
        eq("checkedJson after sanitise", Backup.checkedJson(s).length() > 0, true);

        Arrays.fill(pw, '\0');
        System.out.println("BackupTest: " + checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
