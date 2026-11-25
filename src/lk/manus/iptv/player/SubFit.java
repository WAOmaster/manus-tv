package lk.manus.iptv.player;

/**
 * Sizing for the subtitle box so a cue is never cut: the text shrinks (in steps) until its estimated
 * wrapped height fits the line budget, and the box itself never ellipsizes. Pure Java, testable.
 */
public final class SubFit {
    /** Lines the box aims for; past the smallest step the box just grows (never "…"). */
    public static final int BUDGET = 4;
    /** Text size steps, largest first. The smallest is still readable from the sofa (~12 px at 1080p role 18). */
    static final float[] STEPS = {1f, 0.88f, 0.78f, 0.7f};

    private SubFit() {}

    /** Cleans a cue for display: trims each line, drops blank lines, collapses runs of spaces. */
    public static String clean(String cue) {
        if (cue == null) return "";
        StringBuilder b = new StringBuilder(cue.length());
        for (String ln : cue.split("\r?\n")) {
            String t = ln.replaceAll("[ \\t\\u00A0]+", " ").trim();
            if (t.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(t);
        }
        return b.toString();
    }

    /** Estimated wrapped lines for {@code cue} when {@code cpl} characters fit on one line. */
    public static int lines(String cue, float cpl) {
        if (cue == null || cue.isEmpty()) return 0;
        int n = 0, max = Math.max(1, (int) cpl);
        for (String ln : cue.split("\n")) n += Math.max(1, (ln.length() + max - 1) / max);
        return n;
    }

    /** Text-size factor for {@code cue}; {@code cpl} = characters per line at full size in the box's max width. */
    public static float scale(String cue, float cpl) {
        for (float s : STEPS) if (lines(cue, cpl / s) <= BUDGET) return s;
        return STEPS[STEPS.length - 1];
    }
}
