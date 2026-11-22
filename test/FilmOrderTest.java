import lk.manus.iptv.data.FilmOrder;
import lk.manus.iptv.data.FilmOrder.Key;
import java.util.*;

public class FilmOrderTest {
    static int fails;
    static void check(boolean ok, String m) { if (!ok) { fails++; System.out.println("FAIL " + m); } }
    static String order(Object... spec) { // name, Key pairs
        List<Object[]> l = new ArrayList<>();
        for (int i = 0; i < spec.length; i += 2) l.add(new Object[]{spec[i], spec[i + 1]});
        Collections.sort(l, (x, y) -> FilmOrder.compare((Key) x[1], (Key) y[1]));
        StringBuilder b = new StringBuilder();
        for (Object[] o : l) b.append(o[0]);
        return b.toString();
    }
    public static void main(String[] a) {
        check(order("A", new Key(false, 2030, 0, 0), "P", new Key(true, 1990, 5, 100)).equals("PA"), "poster beats no poster");
        check(order("O", new Key(true, 2020, 9, 900), "N", new Key(true, 2025, 5, 900)).equals("NO"), "newer year beats older");
        check(order("L", new Key(true, 2025, 7.2, 500), "H", new Key(true, 2025, 7.6, 500)).equals("HL"), "same year higher rating first");
        check(order("X", new Key(true, 2025, 9.9, 10), "R", new Key(true, 2025, 6.0, 50)).equals("RX"), "<50 votes ignored");
        check(order("1", new Key(true, 2025, 7, 100), "2", new Key(true, 2025, 7, 100), "3", new Key(true, 2025, 7, 100)).equals("123"), "ties stable");
        check(order("A", new Key(false, 0, 0, 0), "B", new Key(false, 2000, 9, 999), "C", new Key(false, 0, 0, 0)).equals("ABC"), "letter tiles keep order");
        check(order("D", new Key(true, 2025, 9, 900, true), "L", new Key(true, 2015, 5, 900, false)).equals("LD"), "newer poster on dead channel after older on live");
        check(order("D", new Key(true, 2025, 9, 900, true), "P", new Key(false, 0, 0, 0, false)).equals("PD"), "letter tile on live beats poster on dead");
        check(order("a", new Key(true, 2020, 5, 900, true), "b", new Key(true, 2025, 5, 900, true)).equals("ba"), "inside dead group same order");
        // T110a series: same order, dead last, fixed (a stable sort twice gives the same list)
        List<Object[]> sr = new ArrayList<>();
        Object[][] rows = {{"deadNew", new Key(true, 2025, 9, 900, true)}, {"tile", new Key(false, 0, 0, 0, false)}, {"old", new Key(true, 2005, 8, 900, false)},
            {"new8", new Key(true, 2024, 8, 900, false)}, {"new7", new Key(true, 2024, 7, 900, false)}, {"newFew", new Key(true, 2024, 9.5, 10, false)}};
        for (Object[] r : rows) sr.add(r);
        Collections.sort(sr, (x, y) -> FilmOrder.compare((Key) x[1], (Key) y[1]));
        StringBuilder sb = new StringBuilder();
        for (Object[] r : sr) sb.append(r[0]).append(",");
        check(sb.toString().equals("new8,new7,newFew,old,tile,deadNew,"), "series order: " + sb);
        List<Object[]> again = new ArrayList<>(sr);
        Collections.sort(again, (x, y) -> FilmOrder.compare((Key) x[1], (Key) y[1]));
        check(again.equals(sr), "series order is stable when re-sorted");
        if (fails > 0) System.exit(1);
        System.out.println("FilmOrderTest ok");
    }
}
