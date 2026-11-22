import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lk.manus.iptv.data.Library;

/**
 * Pure Java unit tests for Library pure helpers:
 * sort clauses, category name fallback to "Other", display title cleaning, search folding.
 */
public final class LibraryTest {
    static int fails, checks;

    static void eq(String what, Object got, Object want) {
        checks++;
        if (!Objects.equals(want, got)) {
            fails++;
            System.out.println("FAIL " + what + ": got [" + got + "], want [" + want + "]");
        }
    }

    public static void main(String[] args) {
        // 1. Sort clauses
        eq("sort 0 Recently added", Library.sortClause(0), "i.added_at DESC, i.id DESC");
        eq("sort 1 A-Z", Library.sortClause(1), "i.clean_title ASC, i.id ASC");
        eq("sort 2 Rating nulls last", Library.sortClause(2), "CASE WHEN m.rating IS NULL THEN 1 ELSE 0 END, m.rating DESC, i.id DESC");
        eq("sort default Recently added", Library.sortClause(99), "i.added_at DESC, i.id DESC");

        // 2. Category name and "Other" bucket
        eq("null category -> Other", Library.categoryName(null), "Other");
        eq("empty category -> Other", Library.categoryName(""), "Other");
        eq("whitespace category -> Other", Library.categoryName("   "), "Other");
        eq("normal category", Library.categoryName("Action"), "Action");
        eq("trimmed category", Library.categoryName("  Sci-Fi  "), "Sci-Fi");

        // 3. Display title cleaning and casing
        eq("clean bracketed year", Library.displayTitle("[4K] COSMIC DAWN (2021)", "cosmic dawn"), "COSMIC DAWN");
        eq("clean messy tags", Library.displayTitle("EN - NEON SHADOWS 2021 1080p", "neon shadows"), "NEON SHADOWS");
        eq("simple title casing kept", Library.displayTitle("COSMIC DAWN", "cosmic dawn"), "COSMIC DAWN");
        eq("null raw title uses clean title", Library.displayTitle(null, "cosmic dawn"), "cosmic dawn");
        eq("empty raw title uses clean title", Library.displayTitle("", "cosmic dawn"), "cosmic dawn");

        // 4. Search fold
        eq("fold lowercase", Library.fold("COSMIC DAWN"), "cosmic dawn");
        eq("fold accents removed", Library.fold("Café Étoile"), "cafe etoile");
        eq("fold already clean", Library.fold("iron horizon"), "iron horizon");

        // 5. Kind-aware page/any on in-memory fixture
        List<Library.Item> fixture = new ArrayList<>();
        fixture.add(new Library.Item(1, "movie", "Film One", 2021, "poster1.jpg", 7.5, "Action"));
        fixture.add(new Library.Item(2, "movie", "Film Two", 2022, "poster2.jpg", 8.0, "Drama"));
        fixture.add(new Library.Item(3, "series", "Series Alpha", 2020, "coverA.jpg", 9.1, "Drama"));
        fixture.add(new Library.Item(4, "series", "Series Beta", 2023, "coverB.jpg", 6.8, "Sci-Fi"));
        fixture.add(new Library.Item(5, "series", "Series Gamma", 2019, "coverC.jpg", 8.4, "Drama"));

        eq("any movie in fixture", Library.any(fixture, "movie"), true);
        eq("any series in fixture", Library.any(fixture, "series"), true);
        eq("no series in movies only fixture", Library.any(fixture.subList(0, 2), "series"), false);
        eq("no movie in series only fixture", Library.any(fixture.subList(2, 5), "movie"), false);
        eq("any empty fixture", Library.any(new ArrayList<Library.Item>(), "series"), false);

        List<Library.Item> seriesAll = Library.page(fixture, "series", "All", 0, 0, 10);
        eq("series count in page", seriesAll.size(), 3);
        eq("series 1 title", seriesAll.get(0).title, "Series Alpha");
        eq("series 1 cover", seriesAll.get(0).poster, "coverA.jpg");
        eq("series 1 rating", seriesAll.get(0).rating, 9.1);

        List<Library.Item> seriesDrama = Library.page(fixture, "series", "Drama", 0, 0, 10);
        eq("series Drama category filter count", seriesDrama.size(), 2);
        eq("series Drama 1", seriesDrama.get(0).title, "Series Alpha");
        eq("series Drama 2", seriesDrama.get(1).title, "Series Gamma");

        List<Library.Item> seriesByRating = Library.page(fixture, "series", "All", 2, 0, 10);
        eq("series sort by rating top", seriesByRating.get(0).title, "Series Alpha");
        eq("series sort by rating 2nd", seriesByRating.get(1).title, "Series Gamma");
        eq("series sort by rating 3rd", seriesByRating.get(2).title, "Series Beta");

        List<Library.Item> moviesAll = Library.page(fixture, "movie", "All", 0, 0, 10);
        eq("movies count in page", moviesAll.size(), 2);
        eq("movie 1 title", moviesAll.get(0).title, "Film One");

        // Sort clause for series rating
        eq("sort 2 series rating clause", Library.sortClause(2, "series"), "CASE WHEN COALESCE(ss.rating, m.rating) IS NULL THEN 1 ELSE 0 END, COALESCE(ss.rating, m.rating) DESC, i.id DESC");

        // 6. Series search by title and accent-folded title
        fixture.add(new Library.Item(6, "series", "Orbit Watch", 2022, "coverO.jpg", 8.0, "Sci-Fi"));
        fixture.add(new Library.Item(7, "movie", "Silent Orbit", 2021, "posterO.jpg", 7.2, "Sci-Fi"));
        fixture.add(new Library.Item(8, "series", "Café Étoile", 2021, "coverCafe.jpg", 8.8, "Drama"));
        fixture.add(new Library.Item(9, "series", "Château Mystère", 2020, "coverChateau.jpg", 7.9, "Mystery"));

        // Search series by exact / partial title
        List<Library.Item> sTitleExact = Library.search(fixture, "series", "Series Beta", 10);
        eq("series search exact title count", sTitleExact.size(), 1);
        eq("series search exact title item", sTitleExact.get(0).title, "Series Beta");

        List<Library.Item> sTitlePart = Library.search(fixture, "series", "Beta", 10);
        eq("series search partial title count", sTitlePart.size(), 1);
        eq("series search partial title item", sTitlePart.get(0).title, "Series Beta");

        // Search series by accent-folded title
        List<Library.Item> sAccent1 = Library.search(fixture, "series", "cafe", 10);
        eq("series search accent fold 'cafe' count", sAccent1.size(), 1);
        eq("series search accent fold 'cafe' item", sAccent1.get(0).title, "Café Étoile");

        List<Library.Item> sAccent2 = Library.search(fixture, "series", "etoile", 10);
        eq("series search accent fold 'etoile' count", sAccent2.size(), 1);
        eq("series search accent fold 'etoile' item", sAccent2.get(0).title, "Café Étoile");

        List<Library.Item> sAccent3 = Library.search(fixture, "series", "chateau", 10);
        eq("series search accent fold 'chateau' count", sAccent3.size(), 1);
        eq("series search accent fold 'chateau' item", sAccent3.get(0).title, "Château Mystère");

        List<Library.Item> sAccent4 = Library.search(fixture, "series", "mystere", 10);
        eq("series search accent fold 'mystere' count", sAccent4.size(), 1);
        eq("series search accent fold 'mystere' item", sAccent4.get(0).title, "Château Mystère");

        // Search series with accented query against folded title
        List<Library.Item> sAccentQuery = Library.search(fixture, "series", "Étoile", 10);
        eq("series search accented query count", sAccentQuery.size(), 1);
        eq("series search accented query item", sAccentQuery.get(0).title, "Café Étoile");

        // Search all: series returned next to films, exact / starts-with first
        List<Library.Item> mixedOrbit = Library.search(fixture, "all", "Orbit", 10);
        eq("mixed search 'Orbit' count", mixedOrbit.size(), 2);
        eq("mixed search 1st (starts-with)", mixedOrbit.get(0).title, "Orbit Watch");
        eq("mixed search 1st kind", mixedOrbit.get(0).kind, "series");
        eq("mixed search 2nd", mixedOrbit.get(1).title, "Silent Orbit");
        eq("mixed search 2nd kind", mixedOrbit.get(1).kind, "movie");

        // SQL queries check
        String anySeriesSql = Library.anySql("series");
        eq("any series SQL has series kind", anySeriesSql.contains("i.kind = 'series'"), true);
        String pageSeriesSql = Library.pageSql("series", "All", 0);
        eq("page series SQL joins series_state", pageSeriesSql.contains("LEFT JOIN series_state ss"), true);
        eq("page series SQL selects ss.cover fallback", pageSeriesSql.contains("ss.cover"), true);

        String searchAllSql = Library.searchSql("all");
        eq("search all SQL joins series_state", searchAllSql.contains("LEFT JOIN series_state ss"), true);
        eq("search all SQL checks movie and series kind", searchAllSql.contains("i.kind IN ('movie', 'series')") || searchAllSql.contains("i.kind = 'movie' OR i.kind = 'series'"), true);
        eq("search all SQL has starts-with priority order", searchAllSql.contains("ORDER BY CASE WHEN i.clean_title LIKE ? THEN 0 ELSE 1 END"), true);

        String searchSeriesSql = Library.searchSql("series");
        eq("search series SQL has kind = 'series'", searchSeriesSql.contains("i.kind = 'series'"), true);

        System.out.println("LibraryTest: " + checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
