import lk.manus.iptv.data.FailPlan;

public class FailPlanTest {
    static void ck(boolean b, String m) { if (!b) { System.out.println("FAIL " + m); System.exit(1); } }
    public static void main(String[] a) {
        ck(!FailPlan.tryAlternate(0, 0), "single link gives up");
        ck(FailPlan.tryAlternate(0, 2) && FailPlan.tryAlternate(1, 2), "two others tried in turn");
        ck(!FailPlan.tryAlternate(2, 2), "all tried gives up");
        System.out.println("FailPlanTest ok");
    }
}
