package lk.manus.iptv.data;

import java.util.Locale;

/**
 * Pure logic for film resume, duration/position time formatting,
 * and completion rules (T83). Free of android.* dependencies.
 */
public final class FilmResume {

    public static final long MIN_RESUME_MS = 5000L; // 5 seconds
    public static final double FINISHED_RATIO = 0.95; // 95% of duration

    private FilmResume() {}

    /**
     * Formats milliseconds into position/duration text.
     * m:ss when under an hour, h:mm:ss otherwise.
     */
    public static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long hours = totalSec / 3600;
        long mins = (totalSec % 3600) / 60;
        long secs = totalSec % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs);
        } else {
            return String.format(Locale.US, "%d:%02d", mins, secs);
        }
    }

    /**
     * Returns true when position has passed 95% of duration.
     */
    public static boolean isFinished(long positionMs, long durationMs) {
        if (durationMs <= 0 || positionMs <= 0) return false;
        return positionMs >= (long) (durationMs * FINISHED_RATIO);
    }

    /**
     * Returns true if saved progress should offer a resume option:
     * 5 s < position < 95% and not already marked finished.
     */
    public static boolean canResume(long positionMs, long durationMs, boolean watched) {
        if (watched) return false;
        if (positionMs <= MIN_RESUME_MS) return false;
        if (durationMs > 0 && isFinished(positionMs, durationMs)) return false;
        return true;
    }

    /**
     * Generates button text for resume button: "Resume from 42:10"
     */
    public static String resumeButtonText(long positionMs) {
        return "Resume from " + formatTime(positionMs);
    }

    /**
     * Seek step size: ±10 s on first press, ±1 min on hold.
     */
    public static long seekStep(int repeatCount) {
        return repeatCount == 0 ? 10000L : 60000L;
    }
}
