import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lk.manus.iptv.data.SeriesSource;
import lk.manus.iptv.data.XtreamSource;

/**
 * Pure Java unit tests for SeriesSource:
 * - Real fixtures parsing: object-by-season (series 7), array-of-arrays (series 10), empty (series 11)
 * - Missing fields handling & verbatim title retention (series 9)
 * - Stream template construction, absence of credentials, and template join
 */
public final class SeriesSourceTest {
    static int fails = 0, checks = 0;

    static void eq(String what, Object got, Object want) {
        checks++;
        if (!Objects.equals(want, got)) {
            fails++;
            System.out.println("FAIL " + what + ": got [" + got + "], want [" + want + "]");
        }
    }

    private static String readFixture(String path) throws Exception {
        File f = new File(path);
        if (!f.exists()) {
            // Fallback if run from different working dir
            f = new File("test/" + path);
        }
        if (!f.exists()) {
            f = new File("projects/manus-iptv/test/" + path);
        }
        byte[] bytes = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < bytes.length) {
                int r = in.read(bytes, off, bytes.length - off);
                if (r < 0) break;
                off += r;
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        // 1. Series 7 fixture: object-by-season shape with 320 episodes across 8 seasons
        String json7 = readFixture("fixtures/series_7.json");
        List<SeriesSource.ParsedEpisode> eps7 = SeriesSource.parseEpisodesJson(json7);
        eq("series 7 total episodes", eps7.size(), 320);

        Map<Integer, Integer> sCounts7 = new HashMap<>();
        for (SeriesSource.ParsedEpisode ep : eps7) {
            sCounts7.put(ep.season, sCounts7.getOrDefault(ep.season, 0) + 1);
        }
        eq("series 7 season count", sCounts7.size(), 8);
        for (int s = 1; s <= 8; s++) {
            eq("series 7 season " + s + " episode count", sCounts7.get(s), 40);
        }

        SeriesSource.ParsedEpisode first7 = eps7.get(0);
        eq("series 7 ep 1 id", first7.id, "207001");
        eq("series 7 ep 1 season", first7.season, 1);
        eq("series 7 ep 1 episodeNum", first7.episodeNum, 1);
        eq("series 7 ep 1 title", first7.title, "Episode 1");
        eq("series 7 ep 1 container", first7.container, "mkv");
        eq("series 7 ep 1 duration", first7.durationSec, 2530);
        eq("series 7 ep 1 plot non-empty", first7.plot.isEmpty(), false);
        eq("series 7 ep 1 cover non-empty", first7.cover.isEmpty(), false);

        SeriesSource.ParsedEpisode last7 = eps7.get(eps7.size() - 1);
        eq("series 7 last ep id", last7.id, "207320");
        eq("series 7 last ep season", last7.season, 8);
        eq("series 7 last ep episodeNum", last7.episodeNum, 40);

        // 2. Series 10 fixture: array-of-arrays shape (2 seasons x 8 episodes = 16 episodes)
        String json10 = readFixture("fixtures/series_10.json");
        List<SeriesSource.ParsedEpisode> eps10 = SeriesSource.parseEpisodesJson(json10);
        eq("series 10 total episodes", eps10.size(), 16);

        Map<Integer, Integer> sCounts10 = new HashMap<>();
        for (SeriesSource.ParsedEpisode ep : eps10) {
            sCounts10.put(ep.season, sCounts10.getOrDefault(ep.season, 0) + 1);
        }
        eq("series 10 season count", sCounts10.size(), 2);
        eq("series 10 season 1 count", sCounts10.get(1), 8);
        eq("series 10 season 2 count", sCounts10.get(2), 8);

        SeriesSource.ParsedEpisode first10 = eps10.get(0);
        eq("series 10 ep 1 id", first10.id, "210001");
        eq("series 10 ep 1 season", first10.season, 1);
        eq("series 10 ep 1 container", first10.container, "mkv");

        SeriesSource.ParsedEpisode ep9of10 = eps10.get(8);
        eq("series 10 season 2 ep 1 id", ep9of10.id, "210041");
        eq("series 10 season 2 ep 1 season", ep9of10.season, 2);
        eq("series 10 season 2 ep 1 container", ep9of10.container, "webm");

        // 3. Series 11 fixture: empty episodes array
        String json11 = readFixture("fixtures/series_11.json");
        List<SeriesSource.ParsedEpisode> eps11 = SeriesSource.parseEpisodesJson(json11);
        eq("series 11 empty count", eps11.size(), 0);

        // Also test null and empty string inputs
        eq("null json count", SeriesSource.parseEpisodesJson((String) null).size(), 0);
        eq("empty string json count", SeriesSource.parseEpisodesJson("").size(), 0);
        eq("empty object json count", SeriesSource.parseEpisodesJson("{}").size(), 0);

        // 4. Series 9 fixture: missing fields & title kept verbatim
        String json9 = readFixture("fixtures/series_9.json");
        List<SeriesSource.ParsedEpisode> eps9 = SeriesSource.parseEpisodesJson(json9);
        eq("series 9 episode count", eps9.size(), 3);

        SeriesSource.ParsedEpisode ep9_1 = eps9.get(0);
        eq("series 9 ep 1 id", ep9_1.id, "209001");
        eq("series 9 ep 1 plot is empty string", ep9_1.plot, "");
        eq("series 9 ep 1 cover is empty string", ep9_1.cover, "");
        // duration_secs was omitted in ep 1, but "duration": "00:42:10" parsed to 2530 seconds
        eq("series 9 ep 1 duration parsed from duration string", ep9_1.durationSec, 2530);

        SeriesSource.ParsedEpisode ep9_2 = eps9.get(1);
        eq("series 9 ep 2 id", ep9_2.id, "209002");
        // Must keep "S01E02 - Pilot" verbatim
        eq("series 9 ep 2 title kept verbatim", ep9_2.title, "S01E02 - Pilot");

        // 5. Stream template construction & zero credential leak
        String tmpl = SeriesSource.buildEpisodeTemplate("207001", "mp4");
        eq("episode template format", tmpl, "{BASE}/series/{USER}/{PASS}/207001.mp4");
        eq("episode template contains BASE", tmpl.contains("{BASE}"), true);
        eq("episode template contains USER", tmpl.contains("{USER}"), true);
        eq("episode template contains PASS", tmpl.contains("{PASS}"), true);
        eq("episode template no demo user", tmpl.contains("demo"), false);
        eq("episode template no demo pass", tmpl.contains("demo123"), false);

        eq("episode template with leading dot", SeriesSource.buildEpisodeTemplate("207002", ".mkv"), "{BASE}/series/{USER}/{PASS}/207002.mkv");
        eq("episode template empty ext", SeriesSource.buildEpisodeTemplate("207003", ""), "{BASE}/series/{USER}/{PASS}/207003");

        // 6. Template join produces playable URL
        String joined = XtreamSource.joinUrl(tmpl, "http://10.0.2.2:8081", "demo", "demo123");
        eq("joined episode url", joined, "http://10.0.2.2:8081/series/demo/demo123/207001.mp4");

        // Special password chars encoded properly
        String joinedSpecial = XtreamSource.joinUrl(tmpl, "http://10.0.2.2:8081", "user", "p@ss/word#1");
        eq("joined special pass", joinedSpecial, "http://10.0.2.2:8081/series/user/p%40ss%2Fword%231/207001.mp4");
        eq("joined special pass no raw slash", joinedSpecial.split("/").length, 7);
        eq("joined special pass no raw hash", joinedSpecial.contains("#"), false);

        System.out.println("SeriesSourceTest: " + checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
