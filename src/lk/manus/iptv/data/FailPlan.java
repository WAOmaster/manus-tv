package lk.manus.iptv.data;

/** What to do when a live channel will not start: next link of the same channel, or give up. */
public final class FailPlan {
    private FailPlan() {}

    /** @param tried links already used after the first one; @param count how many other links the channel has */
    public static boolean tryAlternate(int tried, int count) { return tried >= 0 && tried < count; }
}
