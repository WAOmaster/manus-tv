package lk.manus.iptv.data;

import java.util.List;
import java.util.Map;

/** Pure channel-change rules (no Android): the previous channel and typed channel numbers. */
public final class ZapLogic {
    private ZapLogic() {}

    /** Result codes of {@link #resolve}: a list position is >= 0. */
    public static final int PREV = -1, NONE = -2, SAME = -3, EMPTY = -4;
    public static final int MAX_DIGITS = 4;

    /** The channel watched before the current one: newest entry of {@code recents} that is not {@code cur} and still exists in {@code byUrl}; null if none. */
    public static <T> T previous(List<String> recents, String cur, Map<String, T> byUrl) {
        for (String u : recents) { T c; if (!u.equals(cur) && (c = byUrl.get(u)) != null) return c; }
        return null;
    }

    /** Typed digits to a 0-based list position, or PREV ("0"), NONE (no such channel), SAME (already tuned), EMPTY (nothing typed). */
    public static int resolve(String digits, int listSize, int cur) {
        if (digits.isEmpty()) return EMPTY;
        if (digits.equals("0")) return PREV;
        int n = Integer.parseInt(digits);
        if (n < 1 || n > listSize) return NONE;
        return n - 1 == cur ? SAME : n - 1;
    }
}
