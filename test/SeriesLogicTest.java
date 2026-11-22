import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import lk.manus.iptv.data.FilmResume;
import lk.manus.iptv.data.Library;
import lk.manus.iptv.data.SeriesLogic;
import lk.manus.iptv.data.SeriesSource;

/**
 * Pure Java unit tests for SeriesLogic (T92b):
 * - nextToWatch rules over real episode fixtures (series 7, series 9)
 * - buttonText variations (Start / Continue / Watch again)
 * - Edge cases: numbering gaps, unwatched before watched, season boundary, single episode, empty list, watched with pos 0
 * - nextEpisodeAfter (season crossing, end of series)
 * - seriesProgress aggregation (total, watched count, target, hasAnyProgress)
 * - Episode title formatting and Library.Item episode properties
 */
public final class SeriesLogicTest {
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
        if (!f.exists()) f = new File("test/" + path);
        if (!f.exists()) f = new File("projects/manus-iptv/test/" + path);
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

    private static List<SeriesLogic.Ep> toEpList(List<SeriesSource.ParsedEpisode> parsed) {
        List<SeriesLogic.Ep> list = new ArrayList<>();
        long id = 1000;
        for (SeriesSource.ParsedEpisode pe : parsed) {
            list.add(new SeriesLogic.Ep(id++, pe.season, pe.episodeNum, 0L, pe.durationSec * 1000L, false));
        }
        return list;
    }

    public static void main(String[] args) throws Exception {
        // 1. Format episode titles
        eq("title with name", SeriesLogic.formatEpisodeTitle(2, 4, "The Spoils of War"), "S2 E4 · The Spoils of War");
        eq("title empty name", SeriesLogic.formatEpisodeTitle(2, 4, ""), "S2 E4 · Episode 4");
        eq("title whitespace name", SeriesLogic.formatEpisodeTitle(2, 4, "   "), "S2 E4 · Episode 4");
        eq("title null name", SeriesLogic.formatEpisodeTitle(2, 4, null), "S2 E4 · Episode 4");
        eq("title Episode 4 verbatim", SeriesLogic.formatEpisodeTitle(2, 4, "Episode 4"), "S2 E4 · Episode 4");
        eq("title already formatted", SeriesLogic.formatEpisodeTitle(2, 4, "S2 E4 · Pilot"), "S2 E4 · Pilot");
        eq("title season 0", SeriesLogic.formatEpisodeTitle(0, 4, ""), "Episode 4");
        eq("title season 0 with name", SeriesLogic.formatEpisodeTitle(0, 4, "Special"), "Special");

        // 2. Library.Item episode support
        Library.Item epItem = new Library.Item(501, "episode", "S2 E4 · Pilot", 0, "http://cover.jpg", 0.0, "Deep Sky", 9, 2, 4);
        eq("item kind", epItem.kind, "episode");
        eq("item id", epItem.id, 501L);
        eq("item title", epItem.title, "S2 E4 · Pilot");
        eq("item poster", epItem.poster, "http://cover.jpg");
        eq("item groupName series title", epItem.groupName, "Deep Sky");
        eq("item parentId", epItem.parentId, 9L);
        eq("item season", epItem.season, 2);
        eq("item episode", epItem.episode, 4);

        // Existing movie Item constructor still defaults kind to movie and parent/season/ep to 0
        Library.Item movieItem = new Library.Item(1, "Movie Title", 2024, "poster.jpg", 8.0, "Action");
        eq("movie item kind", movieItem.kind, "movie");
        eq("movie item parentId", movieItem.parentId, 0L);
        eq("movie item season", movieItem.season, 0);
        eq("movie item episode", movieItem.episode, 0);

        // 3. Series 7 fixture (320 episodes: 8 seasons x 40 episodes)
        String json7 = readFixture("fixtures/series_7.json");
        List<SeriesSource.ParsedEpisode> parsed7 = SeriesSource.parseEpisodesJson(json7);
        eq("series 7 parsed count", parsed7.size(), 320);

        List<SeriesLogic.Ep> eps7 = toEpList(parsed7);
        eq("eps7 size", eps7.size(), 320);

        // (a) Clean start: no progress
        SeriesLogic.Ep targetClean = SeriesLogic.nextToWatch(eps7);
        eq("clean target not null", targetClean != null, true);
        eq("clean target season", targetClean.season, 1);
        eq("clean target episode", targetClean.episode, 1);
        eq("clean buttonText", SeriesLogic.buttonText(targetClean, false), "Start S1 E1");

        SeriesLogic.SeriesProgress spClean = SeriesLogic.seriesProgress(eps7);
        eq("clean sp total", spClean.totalEpisodes, 320);
        eq("clean sp watched", spClean.watchedEpisodes, 0);
        eq("clean sp hasAny", spClean.hasAnyProgress, false);
        eq("clean sp target season", spClean.target.season, 1);
        eq("clean sp target episode", spClean.target.episode, 1);

        // (b) S1 E1–E3 watched
        eps7.set(0, eps7.get(0).withProgress(0L, 2530000L, true));
        eps7.set(1, eps7.get(1).withProgress(0L, 2530000L, true));
        eps7.set(2, eps7.get(2).withProgress(0L, 2530000L, true));

        SeriesLogic.Ep targetWatched3 = SeriesLogic.nextToWatch(eps7);
        eq("target after 3 watched not null", targetWatched3 != null, true);
        eq("target after 3 watched season", targetWatched3.season, 1);
        eq("target after 3 watched episode", targetWatched3.episode, 4);
        eq("buttonText after 3 watched", SeriesLogic.buttonText(targetWatched3, true), "Continue S1 E4");

        SeriesLogic.SeriesProgress spWatched3 = SeriesLogic.seriesProgress(eps7);
        eq("spWatched3 total", spWatched3.totalEpisodes, 320);
        eq("spWatched3 watched", spWatched3.watchedEpisodes, 3);
        eq("spWatched3 hasAny", spWatched3.hasAnyProgress, true);

        // (c) Partial progress on S2 E2 (S2 is index 40..79; S2 E2 is index 41)
        int idxS2E2 = 41;
        eq("index 41 is S2 E2", eps7.get(idxS2E2).season == 2 && eps7.get(idxS2E2).episode == 2, true);
        eps7.set(idxS2E2, eps7.get(idxS2E2).withProgress(120000L, 2530000L, false));

        SeriesLogic.Ep targetPartial = SeriesLogic.nextToWatch(eps7);
        eq("targetPartial not null", targetPartial != null, true);
        eq("targetPartial season", targetPartial.season, 2);
        eq("targetPartial episode", targetPartial.episode, 2);
        eq("targetPartial buttonText", SeriesLogic.buttonText(targetPartial, true), "Continue S2 E2");

        // (d) Resumable progress takes priority over higher watched episodes
        // Say S2 E1 is watched, but S1 E5 has resumable progress -> returns S1 E5
        eps7.set(4, eps7.get(4).withProgress(60000L, 2530000L, false)); // S1 E5 resumable
        // If multiple resumable, returns the last-started unfinished one
        // Here S2 E2 is later than S1 E5 -> returns S2 E2
        eq("last-started resumable wins", SeriesLogic.nextToWatch(eps7).episode, 2);
        eq("last-started resumable season", SeriesLogic.nextToWatch(eps7).season, 2);

        // Remove S2 E2 progress
        eps7.set(idxS2E2, eps7.get(idxS2E2).withProgress(0L, 2530000L, false));
        // Now S1 E5 is the only resumable -> returns S1 E5
        eq("only resumable S1 E5", SeriesLogic.nextToWatch(eps7).episode, 5);

        // Reset S1 E5
        eps7.set(4, eps7.get(4).withProgress(0L, 2530000L, false));

        // (e) Season boundary crossing: S1 E40 (index 39) is watched
        // Mark all of season 1 watched (indexes 0..39)
        for (int i = 0; i < 40; i++) {
            eps7.set(i, eps7.get(i).withProgress(0L, 2530000L, true));
        }
        SeriesLogic.Ep targetS2E1 = SeriesLogic.nextToWatch(eps7);
        eq("season boundary next season", targetS2E1.season, 2);
        eq("season boundary next ep", targetS2E1.episode, 1);
        eq("season boundary buttonText", SeriesLogic.buttonText(targetS2E1, true), "Continue S2 E1");

        // (f) All episodes watched -> returns null, buttonText = "Watch again from S1 E1"
        for (int i = 0; i < eps7.size(); i++) {
            eps7.set(i, eps7.get(i).withProgress(0L, 2530000L, true));
        }
        SeriesLogic.Ep targetAllWatched = SeriesLogic.nextToWatch(eps7);
        eq("all watched target is null", targetAllWatched == null, true);
        eq("all watched buttonText", SeriesLogic.buttonText(targetAllWatched, true), "Watch again from S1 E1");

        SeriesLogic.SeriesProgress spAll = SeriesLogic.seriesProgress(eps7);
        eq("spAll total", spAll.totalEpisodes, 320);
        eq("spAll watched", spAll.watchedEpisodes, 320);
        eq("spAll target is null", spAll.target == null, true);

        // 4. Edge cases
        // (a) Empty list
        List<SeriesLogic.Ep> emptyList = new ArrayList<>();
        eq("empty list nextToWatch", SeriesLogic.nextToWatch(emptyList), null);
        eq("null list nextToWatch", SeriesLogic.nextToWatch(null), null);
        eq("empty list buttonText", SeriesLogic.buttonText(null, false), "Watch again from S1 E1");

        // (b) Single episode
        List<SeriesLogic.Ep> singleEpList = new ArrayList<>();
        singleEpList.add(new SeriesLogic.Ep(1, 1, 1, 0L, 100000L, false));
        eq("single ep unwatched", SeriesLogic.nextToWatch(singleEpList).episode, 1);
        eq("single ep unwatched btn", SeriesLogic.buttonText(SeriesLogic.nextToWatch(singleEpList), false), "Start S1 E1");

        singleEpList.set(0, singleEpList.get(0).withProgress(50000L, 100000L, false));
        eq("single ep partial", SeriesLogic.nextToWatch(singleEpList).episode, 1);
        eq("single ep partial btn", SeriesLogic.buttonText(SeriesLogic.nextToWatch(singleEpList), true), "Continue S1 E1");

        singleEpList.set(0, singleEpList.get(0).withProgress(0L, 100000L, true));
        eq("single ep watched target null", SeriesLogic.nextToWatch(singleEpList), null);
        eq("single ep watched btn", SeriesLogic.buttonText(null, true), "Watch again from S1 E1");

        // (c) Gaps in numbering: S1 E1, S1 E5, S1 E10
        List<SeriesLogic.Ep> gapList = new ArrayList<>();
        gapList.add(new SeriesLogic.Ep(1, 1, 1, 0L, 100000L, true));
        gapList.add(new SeriesLogic.Ep(2, 1, 5, 0L, 100000L, false));
        gapList.add(new SeriesLogic.Ep(3, 1, 10, 0L, 100000L, false));
        SeriesLogic.Ep targetGap = SeriesLogic.nextToWatch(gapList);
        eq("gap numbering next is S1 E5", targetGap.episode, 5);
        eq("gap numbering season is 1", targetGap.season, 1);

        // (d) Unwatched episode before a watched one (DO NOT SKIP BACK, go after highest watched)
        List<SeriesLogic.Ep> skipList = new ArrayList<>();
        skipList.add(new SeriesLogic.Ep(10, 1, 1, 0L, 100000L, false)); // unwatched
        skipList.add(new SeriesLogic.Ep(11, 1, 2, 0L, 100000L, true));  // watched
        skipList.add(new SeriesLogic.Ep(12, 1, 3, 0L, 100000L, false)); // unwatched
        SeriesLogic.Ep targetSkip = SeriesLogic.nextToWatch(skipList);
        eq("does not skip back, goes to E3", targetSkip.episode, 3);

        // If highest watched is the last episode (E3 is watched, E1 still unwatched)
        skipList.set(2, skipList.get(2).withProgress(0L, 100000L, true));
        eq("highest watched is last -> null", SeriesLogic.nextToWatch(skipList), null);

        // (e) Watched flag with position 0
        List<SeriesLogic.Ep> pos0List = new ArrayList<>();
        pos0List.add(new SeriesLogic.Ep(20, 1, 1, 0L, 0L, true));
        pos0List.add(new SeriesLogic.Ep(21, 1, 2, 0L, 0L, false));
        eq("watched flag with pos 0 advances", SeriesLogic.nextToWatch(pos0List).episode, 2);

        // (f) 95% position treated as finished/watched
        List<SeriesLogic.Ep> ninetyFiveList = new ArrayList<>();
        ninetyFiveList.add(new SeriesLogic.Ep(30, 1, 1, 95000L, 100000L, false));
        ninetyFiveList.add(new SeriesLogic.Ep(31, 1, 2, 0L, 100000L, false));
        eq("95% treated as watched", SeriesLogic.nextToWatch(ninetyFiveList).episode, 2);

        // 5. nextEpisodeAfter
        List<SeriesLogic.Ep> flowList = new ArrayList<>();
        flowList.add(new SeriesLogic.Ep(101, 1, 1, 0, 0, false));
        flowList.add(new SeriesLogic.Ep(102, 1, 2, 0, 0, false));
        flowList.add(new SeriesLogic.Ep(201, 2, 1, 0, 0, false));

        eq("after 101 is 102", SeriesLogic.nextEpisodeAfter(flowList, 101L).itemId, 102L);
        eq("after 102 crosses season to 201", SeriesLogic.nextEpisodeAfter(flowList, 102L).itemId, 201L);
        eq("after 201 is null (end)", SeriesLogic.nextEpisodeAfter(flowList, 201L), null);
        eq("after unknown id is null", SeriesLogic.nextEpisodeAfter(flowList, 999L), null);

        // 6. Series 9 fixture (missing fields & S01E02 - Pilot)
        String json9 = readFixture("fixtures/series_9.json");
        List<SeriesSource.ParsedEpisode> parsed9 = SeriesSource.parseEpisodesJson(json9);
        eq("series 9 count", parsed9.size(), 3);
        List<SeriesLogic.Ep> eps9 = toEpList(parsed9);

        eq("series 9 ep 1 clean target", SeriesLogic.nextToWatch(eps9).episode, 1);
        eq("series 9 ep 2 formatted title keeps pilot", SeriesLogic.formatEpisodeTitle(1, 2, parsed9.get(1).title), "S1 E2 · S01E02 - Pilot");
        eq("series 9 ep 1 duration non-zero", eps9.get(0).durationMs, 2530000L);

        // Mark ep 1 watched in series 9 -> target is ep 2
        eps9.set(0, eps9.get(0).withProgress(0L, 2530000L, true));
        eq("series 9 target after ep 1", SeriesLogic.nextToWatch(eps9).episode, 2);
        eq("series 9 buttonText after ep 1", SeriesLogic.buttonText(SeriesLogic.nextToWatch(eps9), true), "Continue S1 E2");

        // 7. T93a: Next episode pill countdown and still-watching tracker
        // (a) Countdown text formatting
        eq("countdown 10", SeriesLogic.formatNextEpisodeCountdown(10), "Next episode in 10");
        eq("countdown 9", SeriesLogic.formatNextEpisodeCountdown(9), "Next episode in 9");
        eq("countdown 1", SeriesLogic.formatNextEpisodeCountdown(1), "Next episode in 1");
        eq("countdown 0", SeriesLogic.formatNextEpisodeCountdown(0), "Next episode in 0");
        eq("still watching text verbatim", SeriesLogic.STILL_WATCHING_TEXT, "Still watching?");

        // (b) Trigger condition checks
        // Non-episode never triggers
        eq("non-episode no trigger", SeriesLogic.shouldShowNextEpisodePill(70000L, 100000L, false, false, true, false), false);
        // No next episode never triggers
        eq("no next ep no trigger", SeriesLogic.shouldShowNextEpisodePill(70000L, 100000L, false, true, false, false), false);
        // Dismissed never triggers
        eq("dismissed no trigger", SeriesLogic.shouldShowNextEpisodePill(70000L, 100000L, false, true, true, true), false);
        // Stream ended triggers if not dismissed
        eq("stream ended triggers", SeriesLogic.shouldShowNextEpisodePill(0L, 0L, true, true, true, false), true);
        eq("stream ended dismissed no trigger", SeriesLogic.shouldShowNextEpisodePill(0L, 0L, true, true, true, true), false);
        // 31s remaining does NOT trigger (threshold 30s)
        eq("31s remaining no trigger", SeriesLogic.shouldShowNextEpisodePill(69000L, 100000L, false, true, true, false), false);
        // 30s remaining triggers
        eq("30s remaining triggers", SeriesLogic.shouldShowNextEpisodePill(70000L, 100000L, false, true, true, false), true);
        // 10s remaining triggers
        eq("10s remaining triggers", SeriesLogic.shouldShowNextEpisodePill(90000L, 100000L, false, true, true, false), true);
        // Invalid duration / pos
        eq("zero dur no trigger", SeriesLogic.shouldShowNextEpisodePill(0L, 0L, false, true, true, false), false);
        eq("negative dur no trigger", SeriesLogic.shouldShowNextEpisodePill(100L, -1L, false, true, true, false), false);

        // (c) Still-watching counter
        SeriesLogic.StillWatchingTracker tracker = new SeriesLogic.StillWatchingTracker();
        eq("initial count is 0", tracker.getCount(), 0);
        eq("initial should not prompt", tracker.shouldPromptStillWatching(), false);

        // 1st auto-play
        tracker.onAutoPlay();
        eq("after 1 auto-play count is 1", tracker.getCount(), 1);
        eq("count 1 should not prompt", tracker.shouldPromptStillWatching(), false);

        // Key press resets
        tracker.onKeyPress();
        eq("key press resets to 0", tracker.getCount(), 0);
        eq("reset should not prompt", tracker.shouldPromptStillWatching(), false);

        // 3 consecutive auto-plays with no key press
        tracker.onAutoPlay(); // count 1
        tracker.onAutoPlay(); // count 2
        eq("count 2 should not prompt", tracker.shouldPromptStillWatching(), false);
        tracker.onAutoPlay(); // count 3
        eq("count 3", tracker.getCount(), 3);
        eq("after 3 auto-plays should prompt", tracker.shouldPromptStillWatching(), true);

        // 4th auto-play also prompts
        tracker.onAutoPlay();
        eq("count 4 should prompt", tracker.shouldPromptStillWatching(), true);

        // Any key press resets to 0
        tracker.onKeyPress();
        eq("key press resets count", tracker.getCount(), 0);
        eq("after key press should not prompt", tracker.shouldPromptStillWatching(), false);

        // Explicit reset()
        tracker.onAutoPlay();
        tracker.reset();
        eq("reset() resets to 0", tracker.getCount(), 0);

        // T93b: 95% boundary and continue target
        eq("94.9% not watched", SeriesLogic.isWatched(new SeriesLogic.Ep(1, 1, 1, 94900, 100000, false)), false);
        eq("95% watched", SeriesLogic.isWatched(new SeriesLogic.Ep(1, 1, 1, 95000, 100000, false)), true);
        List<SeriesLogic.Ep> ce = new ArrayList<>();
        for (int i = 1; i <= 3; i++) ce.add(new SeriesLogic.Ep(i, 1, i, 0, 0, false));
        eq("not started: no tile", SeriesLogic.continueTarget(ce), null);
        ce.set(0, new SeriesLogic.Ep(1, 1, 1, 0, 0, true));
        eq("E1 watched: next is E2", SeriesLogic.continueTarget(ce).itemId, 2L);
        ce.set(1, new SeriesLogic.Ep(2, 1, 2, 30000, 100000, false));
        eq("E2 in progress wins", SeriesLogic.continueTarget(ce).itemId, 2L);
        ce.set(1, new SeriesLogic.Ep(2, 1, 2, 94900, 100000, false));
        eq("E2 at 94.9% still resumes", SeriesLogic.continueTarget(ce).itemId, 2L);
        ce.set(1, new SeriesLogic.Ep(2, 1, 2, 95000, 100000, false));
        eq("E2 at 95% watched: next is E3", SeriesLogic.continueTarget(ce).itemId, 3L);
        ce.set(2, new SeriesLogic.Ep(3, 1, 3, 0, 0, true));
        ce.set(1, new SeriesLogic.Ep(2, 1, 2, 0, 0, true));
        eq("finished series: no tile", SeriesLogic.continueTarget(ce), null);

        System.out.println("SeriesLogicTest: " + checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
