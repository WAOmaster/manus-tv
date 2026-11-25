import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lk.manus.iptv.data.Channel;
import lk.manus.iptv.data.SearchFilter;

/** M3: search filters (type, country, category, quality, favourites) and picker options. */
public class SearchFilterTest {
    private static int checks, failures;

    private static void eq(String what, Object expected, Object actual) {
        checks++;
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            failures++;
            System.out.println("FAIL " + what + ": expected [" + expected + "] got [" + actual + "]");
        }
    }

    static Channel ch(String name, String q, String cc, String... groups) { return new Channel("", name, q, "", groups, cc, "http://x/" + name, "", ""); }

    static String names(List<Channel> l) { StringBuilder b = new StringBuilder(); for (Channel c : l) b.append(b.length() > 0 ? "|" : "").append(c.name); return b.toString(); }

    public static void main(String[] args) {
        Channel sirasa = ch("Sirasa HD", "", "lk", "General"), hbo = ch("HBO", "1080p", "us", "Movies"), cine = ch("Cine 4K", "", "in", "Movies"),
                bbc = ch("BBC News", "720p", "gb", "News"), drama = ch("Drama Box", "480p", "lk", "Series"), plain = ch("Plain", "", "", "");
        List<Channel> all = Arrays.asList(sirasa, hbo, cine, bbc, drama, plain);
        Set<String> favs = new HashSet<>(Arrays.asList(hbo.url, drama.url));
        // lines
        eq("lines tag", 1080, SearchFilter.lines(hbo));
        eq("lines 4K word", 2160, SearchFilter.lines(cine));
        eq("lines HD word", 720, SearchFilter.lines(sirasa));
        eq("lines unknown", 0, SearchFilter.lines(plain));
        eq("lines 1080i", 1080, SearchFilter.lines(ch("X", "1080i", "")));
        eq("lines 'hdtv' is not hd", 0, SearchFilter.lines(ch("HDTV Shop", "", "")));
        SearchFilter f = new SearchFilter();
        eq("inactive passes all", 6, f.apply(all, favs, 200).size());
        eq("inactive cap", 2, f.apply(all, favs, 2).size());
        f.type = SearchFilter.TYPE_FILMS;
        eq("films", "HBO|Cine 4K", names(f.apply(all, favs, 200)));
        f.type = SearchFilter.TYPE_SERIES;
        eq("series (Series group = TV Series label)", "Drama Box", names(f.apply(all, favs, 200)));
        f.clear();
        f.country = "lk";
        eq("country", "Sirasa HD|Drama Box", names(f.apply(all, favs, 200)));
        f.clear();
        f.quality = SearchFilter.Q_HD;
        eq("HD and up", "Sirasa HD|HBO|Cine 4K|BBC News", names(f.apply(all, favs, 200)));
        f.quality = SearchFilter.Q_FHD;
        eq("Full HD and up", "HBO|Cine 4K", names(f.apply(all, favs, 200)));
        f.quality = SearchFilter.Q_4K;
        eq("4K", "Cine 4K", names(f.apply(all, favs, 200)));
        f.clear();
        f.favOnly = true;
        eq("favourites", "HBO|Drama Box", names(f.apply(all, favs, 200)));
        f.category = "Movies";
        eq("favourites + category", "HBO", names(f.apply(all, favs, 200)));
        eq("active", true, f.active());
        f.clear();
        eq("cleared", false, f.active());
        // options: counts with the other filters applied
        f.type = SearchFilter.TYPE_FILMS;
        Map<String, Integer> cs = f.options(all, favs, true);
        eq("country options under Films", "{us=1, in=1}".length(), cs.toString().length());
        eq("country options keys", true, cs.containsKey("us") && cs.containsKey("in") && !cs.containsKey("lk"));
        f.clear();
        f.country = "lk";
        Map<String, Integer> cats = f.options(all, favs, false);
        eq("category options under lk", "{General=1, TV Series=1}", cats.toString());
        f.clear();
        eq("category options count order", "Movies", f.options(all, favs, false).keySet().iterator().next());
        f.country = "zz";
        eq("own filter ignored for its options", true, f.options(all, favs, true).containsKey("lk"));
        // labels
        eq("type cycle", SearchFilter.TYPE_ALL, SearchFilter.nextType(SearchFilter.TYPE_SERIES));
        eq("quality label", "Full HD", SearchFilter.qualityLabel(SearchFilter.Q_FHD));
        // one United Kingdom: "gb" from a tvg-id (BBCOne.gb) or a source is stored as the app's "uk"
        eq("gb -> uk", "uk", ch("BBC One", "", "gb").country);
        eq("GB -> uk", "uk", ch("BBC Two", "", "GB").country);
        eq("uk stays", "uk", ch("ITV", "", "uk").country);
        eq("others untouched", "lk", ch("Hiru", "", "lk").country);
        try {
            java.util.List<Channel> p = lk.manus.iptv.data.M3uParser.parse(new java.io.StringReader("#EXTM3U\n#EXTINF:-1 tvg-id=\"bbcone.gb\",BBC One\nhttp://x/1\n#EXTINF:-1 tvg-id=\"itv.uk\",ITV\nhttp://x/2\n"));
            eq("parser: .gb and .uk ids are one country", "uk uk", p.get(0).country + " " + p.get(1).country);
        } catch (Exception e) { eq("parser threw", "", e.toString()); }
        System.out.println("SearchFilterTest: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
