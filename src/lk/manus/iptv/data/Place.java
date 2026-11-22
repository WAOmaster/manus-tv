package lk.manus.iptv.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Where the cursor goes when a list comes back: on the same channel, or if it is gone the nearest row that followed it. */
public final class Place {
    private Place() {}

    /** Row in {@code now} for {@code url}; {@code before} is the list the url was last seen in (null = unknown). */
    public static int find(List<Channel> now, String url, List<Channel> before) {
        if (now.isEmpty()) return 0;
        Map<String, Integer> at = new HashMap<>(now.size() * 2);
        for (int i = 0; i < now.size(); i++) if (!at.containsKey(now.get(i).url)) at.put(now.get(i).url, i);
        Integer i = at.get(url);
        if (i != null) return i;
        if (before != null) {
            int p = -1;
            for (int j = 0; j < before.size(); j++) if (before.get(j).url.equals(url)) { p = j; break; }
            for (int j = p + 1; p >= 0 && j < before.size(); j++) { i = at.get(before.get(j).url); if (i != null) return i; }
        }
        return now.size() - 1;
    }
}
