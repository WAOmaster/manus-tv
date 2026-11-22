import java.util.Arrays;
import java.util.Objects;

import lk.manus.iptv.data.Db;
import lk.manus.iptv.data.Keys;
import lk.manus.iptv.data.OldState;

/**
 * T54: the old preference strings -> rows mapping (pure Java; SQLite itself cannot run here) and the stable item key.
 * Expected keys were produced by the cloud job's own url_hash() in manus-iptv-data/pipeline/common.py.
 */
public class DbMigrationTest {
    static int fails = 0, checks = 0;

    static void eq(String what, Object e, Object a) {
        checks++;
        if (!Objects.equals(e, a)) { fails++; System.out.println("FAIL " + what + ": expected [" + e + "] got [" + a + "]"); }
    }

    public static void main(String[] args) {
        // key = first 16 hex of sha1(utf-8 url) = the cloud job's `h`
        eq("key abc", "a9993e364706816a", Keys.of("abc"));
        eq("key url", "279bc321ae08c2c5", Keys.of("http://user:pw@host.example/live/ch1.m3u8"));
        eq("key utf-8", "8000c27d5ddc903d", Keys.of("http://ex.com/café?x=1&y=中"));

        String favs = "u1\nu2\n\nu3\nu2\n";                    // empty line + a duplicate
        String recents = "u3\nu1";
        String plays = "u1\t5\t" + Long.toString(1700000000L, 36) + "\nbad line\nu2\tx\t1\nu3\t2\t" + Long.toString(1700000500L, 36);
        String subs = "u1\ten\nu1\tfr\nnotab\nu2\tde";         // first line for a url wins; a line without a tab is dropped
        String audio = "u3\tspa";
        String vols = "u1\t70\nu2\t30";
        String vers = "nA\tu1\ni9\tu2\nnA\tu3";                // last line for a key wins
        OldState o = OldState.parse(favs, recents, plays, subs, audio, vols, vers);

        eq("favs order, deduped", Arrays.asList("u1", "u2", "u3"), o.favs);
        eq("recents order", Arrays.asList("u3", "u1"), o.recents);
        eq("plays kept", 2, o.plays.size());
        eq("play count", 5L, o.plays.get("u1")[0]);
        eq("play last ms", 1700000000L * 1000, o.plays.get("u1")[1]);
        eq("play u3", 2L, o.plays.get("u3")[0]);
        eq("subs first wins, keyed by hash", "en", o.subs.get(Keys.of("u1")));
        eq("subs count", 2, o.subs.size());
        eq("audio", "spa", o.audio.get(Keys.of("u3")));
        eq("vols", "30", o.vols.get(Keys.of("u2")));
        eq("vers last wins, key as is", "u3", o.vers.get("nA"));
        eq("vers count", 2, o.vers.size());
        eq("counts", Arrays.toString(new int[]{3, 2, 2, 2, 1, 2, 2}), Arrays.toString(o.counts()));
        eq("dropped = dup fav + 2 bad plays + dup sub + no-tab sub", 5, o.dropped);

        OldState e = OldState.parse();
        eq("empty counts", Arrays.toString(new int[7]), Arrays.toString(e.counts()));
        eq("empty dropped", 0, e.dropped);
        eq("null strings", Arrays.toString(new int[7]), Arrays.toString(OldState.parse(null, null, null, null, null, null, null).counts()));

        // backup: raw strings verbatim, in a fixed order, nothing masked
        String b = OldState.backupText("t", favs, recents, plays, subs, audio, vols, vers);
        eq("backup keeps raw favs", true, b.contains("[favs]\n" + favs + "\n[recents]\n"));
        eq("backup keeps raw vers", true, b.endsWith("[vers]\n" + vers + "\n"));
        eq("backup starts with comment", true, b.startsWith("# manus-iptv"));

        // T95: schema v6 adds item.archive_days with ALTER (rows kept, default 0); no table is dropped or rebuilt
        eq("schema version", 6, Db.VERSION);
        eq("v6 is an additive ALTER", true, Db.ADD_ARCHIVE_DAYS.startsWith("ALTER TABLE item ADD COLUMN archive_days") && Db.ADD_ARCHIVE_DAYS.endsWith("DEFAULT 0")
                && !Db.ADD_ARCHIVE_DAYS.toUpperCase().contains("DROP"));

        System.out.println(checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
