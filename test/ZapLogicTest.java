import java.util.*;
import lk.manus.iptv.data.ZapLogic;

public class ZapLogicTest {
    static int fails = 0;
    static void eq(Object a, Object b, String m) { if (!Objects.equals(a, b)) { fails++; System.out.println("FAIL " + m + ": " + a + " != " + b); } }
    public static void main(String[] args) {
        Map<String, String> by = new HashMap<>();
        by.put("a", "A"); by.put("b", "B"); by.put("c", "C");
        eq(ZapLogic.previous(Arrays.asList("b", "a"), "b", by), "A", "prev skips current");
        eq(ZapLogic.previous(Arrays.asList("a", "b"), "b", by), "A", "prev when current is not first");
        eq(ZapLogic.previous(Arrays.asList("b", "gone", "c"), "b", by), "C", "prev skips removed channel");
        eq(ZapLogic.previous(Arrays.asList("b"), "b", by), null, "no previous");
        eq(ZapLogic.previous(new ArrayList<String>(), "b", by), null, "empty recents");
        // flip: A then B -> prev is A; after tuning A the recents are [a,b] -> prev is B
        eq(ZapLogic.previous(Arrays.asList("a", "b"), "a", by), "B", "flip back");
        eq(ZapLogic.resolve("", 50, 0), ZapLogic.EMPTY, "empty");
        eq(ZapLogic.resolve("0", 50, 0), ZapLogic.PREV, "zero");
        eq(ZapLogic.resolve("12", 50, 0), 11, "12 -> index 11");
        eq(ZapLogic.resolve("12", 50, 11), ZapLogic.SAME, "same channel");
        eq(ZapLogic.resolve("99", 50, 0), ZapLogic.NONE, "99 of 50");
        eq(ZapLogic.resolve("50", 50, 0), 49, "last");
        eq(ZapLogic.resolve("51", 50, 0), ZapLogic.NONE, "one past");
        eq(ZapLogic.resolve("007", 50, 0), 6, "leading zeros");
        eq(ZapLogic.resolve("00", 50, 0), ZapLogic.NONE, "00");
        System.out.println(fails == 0 ? "ZapLogicTest ok" : fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
