import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lk.manus.iptv.data.FilmResume;
import lk.manus.iptv.data.Library;

/**
 * Pure Java unit tests for Continue watching (T84):
 * eligibility rules, progress ratio, newest-first sorting, limit truncation,
 * and SQL query generation.
 */
public final class ContinueWatchingTest {
    static int fails, checks;

    static void eq(String what, Object got, Object want) {
        checks++;
        if (!Objects.equals(want, got)) {
            fails++;
            System.out.println("FAIL " + what + ": got [" + got + "], want [" + want + "]");
        }
    }

    public static void main(String[] args) {
        // 1. Eligibility rules
        eq("position at 0s not eligible", Library.isContinueWatching(0, 100000, false), false);
        eq("position at 5s not eligible", Library.isContinueWatching(5000, 100000, false), false);
        eq("position at 5.001s eligible", Library.isContinueWatching(5001, 100000, false), true);
        eq("midway position eligible", Library.isContinueWatching(45000, 100000, false), true);
        eq("just under 95% eligible", Library.isContinueWatching(94999, 100000, false), true);
        eq("exact 95% finished/not eligible", Library.isContinueWatching(95000, 100000, false), false);
        eq("over 95% not eligible", Library.isContinueWatching(96000, 100000, false), false);
        eq("watched flag set not eligible", Library.isContinueWatching(50000, 100000, true), false);
        eq("unknown duration (>0 pos > 5s) eligible", Library.isContinueWatching(60000, 0, false), true);

        // 2. Progress calculation
        Library.Item item1 = new Library.Item(1, "Film 1", 2021, "poster1.jpg", 8.0, "Action");
        Library.ContinueItem ci1 = new Library.ContinueItem(item1, 50000, 100000, 1000L);
        eq("50% progress", Math.round(ci1.progress() * 100), 50);

        Library.ContinueItem ciZeroDur = new Library.ContinueItem(item1, 50000, 0, 1000L);
        eq("0 duration progress is 0", ciZeroDur.progress(), 0.0f);

        Library.ContinueItem ciClamped = new Library.ContinueItem(item1, 150000, 100000, 1000L);
        eq("over duration clamped to 1.0", ciClamped.progress(), 1.0f);

        // 3. Filter and newest-first ordering
        List<Library.ContinueItem> candidates = new ArrayList<>();
        // Item A: pos 6s, updated at 100
        candidates.add(new Library.ContinueItem(new Library.Item(1, "Film A", 2020, "", 7.0, "Sci-Fi"), 6000, 60000, 100L));
        // Item B: pos 4s (should be filtered out <= 5s), updated at 500
        candidates.add(new Library.ContinueItem(new Library.Item(2, "Film B", 2020, "", 7.0, "Sci-Fi"), 4000, 60000, 500L));
        // Item C: pos 58s of 60s (96.6% -> should be filtered out >= 95%), updated at 400
        candidates.add(new Library.ContinueItem(new Library.Item(3, "Film C", 2020, "", 7.0, "Sci-Fi"), 58000, 60000, 400L));
        // Item D: pos 30s, updated at 300 (newest valid)
        candidates.add(new Library.ContinueItem(new Library.Item(4, "Film D", 2020, "", 7.0, "Sci-Fi"), 30000, 60000, 300L));
        // Item E: pos 20s, updated at 200
        candidates.add(new Library.ContinueItem(new Library.Item(5, "Film E", 2020, "", 7.0, "Sci-Fi"), 20000, 60000, 200L));

        List<Library.ContinueItem> filtered = Library.filterContinueWatching(candidates, 20);
        eq("filtered count (B and C excluded)", filtered.size(), 3);
        eq("first item is Film D (newest updatedAt=300)", filtered.get(0).item.title, "Film D");
        eq("second item is Film E (updatedAt=200)", filtered.get(1).item.title, "Film E");
        eq("third item is Film A (updatedAt=100)", filtered.get(2).item.title, "Film A");

        // Limit test
        List<Library.ContinueItem> limited = Library.filterContinueWatching(candidates, 2);
        eq("limit 2 count", limited.size(), 2);
        eq("limit first", limited.get(0).item.title, "Film D");
        eq("limit second", limited.get(1).item.title, "Film E");

        // 4. SQL query structure checks
        String sql = Library.continueWatchingSql();
        eq("sql contains profile_id = 1", sql.contains("p.profile_id = 1"), true);
        eq("sql contains watched = 0", sql.contains("p.watched = 0"), true);
        eq("sql contains position_ms > 5000", sql.contains("p.position_ms > 5000"), true);
        eq("sql contains 95% check", sql.contains("p.duration_ms * 95 / 100"), true);
        eq("sql contains movie kind", sql.contains("i.kind = 'movie'"), true);
        eq("sql contains xtream source", sql.contains("s.kind = 'xtream'"), true);
        eq("sql orders by updated_at DESC", sql.contains("ORDER BY p.updated_at DESC"), true);
        eq("sql has LIMIT ?", sql.contains("LIMIT ?"), true);

        // 5. One tile per series (T93b)
        List<Library.ContinueItem> films = new ArrayList<>();
        films.add(new Library.ContinueItem(item1, 50000, 100000, 300L));
        List<Library.ContinueItem> tiles = new ArrayList<>();
        tiles.add(new Library.ContinueItem(new Library.Item(11, "episode", "Show · S1 E2", 0, "", 0, "", 7, 1, 2), 0, 0, 500L, 7));
        tiles.add(new Library.ContinueItem(new Library.Item(12, "episode", "Show · S1 E3", 0, "", 0, "", 7, 1, 3), 0, 0, 900L, 7));
        tiles.add(new Library.ContinueItem(new Library.Item(21, "episode", "Other · S2 E1", 0, "", 0, "", 8, 2, 1), 0, 0, 100L, 8));
        List<Library.ContinueItem> merged = Library.mergeContinue(films, tiles, 20);
        eq("merged: film + 2 series", merged.size(), 3);
        eq("merged newest first", merged.get(0).item.id, 12L);
        eq("series 7 appears once", merged.get(0).seriesId == 7 && merged.get(1).seriesId != 7, true);
        eq("merged limit", Library.mergeContinue(films, tiles, 2).size(), 2);
        eq("tile title", Library.seriesTileTitle("Show", 2, 4), "Show · S2 E4");

        System.out.println("ContinueWatchingTest: " + checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
