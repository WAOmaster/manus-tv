import java.util.Objects;
import lk.manus.iptv.data.FilmResume;

/**
 * Pure Java unit tests for FilmResume (T83):
 * time formatting (m:ss vs h:mm:ss), 95% completion rule, resume eligibility,
 * button text, and DPAD seek step sizing.
 */
public final class FilmResumeTest {
    static int fails, checks;

    static void eq(String what, Object got, Object want) {
        checks++;
        if (!Objects.equals(want, got)) {
            fails++;
            System.out.println("FAIL " + what + ": got [" + got + "], want [" + want + "]");
        }
    }

    public static void main(String[] args) {
        // 1. Time formatting: m:ss under an hour, h:mm:ss at or above an hour
        eq("0 ms", FilmResume.formatTime(0), "0:00");
        eq("negative ms clamped", FilmResume.formatTime(-500), "0:00");
        eq("5 seconds", FilmResume.formatTime(5000), "0:05");
        eq("59 seconds", FilmResume.formatTime(59000), "0:59");
        eq("1 minute", FilmResume.formatTime(60000), "1:00");
        eq("9 minutes 8 seconds", FilmResume.formatTime(548000), "9:08");
        eq("42 minutes 10 seconds", FilmResume.formatTime(2530000), "42:10");
        eq("59 minutes 59 seconds", FilmResume.formatTime(3599000), "59:59");
        eq("exact 1 hour", FilmResume.formatTime(3600000), "1:00:00");
        eq("1 hour 5 seconds", FilmResume.formatTime(3605000), "1:00:05");
        eq("1 hour 42 minutes 10 seconds", FilmResume.formatTime(6130000), "1:42:10");
        eq("12 hours 34 minutes 56 seconds", FilmResume.formatTime(45296000), "12:34:56");

        // 2. 95% completion rule
        // duration = 100,000 ms -> 95% is 95,000 ms
        eq("under 95%", FilmResume.isFinished(94999, 100000), false);
        eq("exact 95%", FilmResume.isFinished(95000, 100000), true);
        eq("over 95%", FilmResume.isFinished(96000, 100000), true);
        eq("0 duration", FilmResume.isFinished(50000, 0), false);
        eq("negative duration", FilmResume.isFinished(50000, -100), false);
        eq("0 position", FilmResume.isFinished(0, 100000), false);

        // 3. Resume eligibility (5 s < position < 95%, watched=false)
        eq("position at 0s cannot resume", FilmResume.canResume(0, 100000, false), false);
        eq("position at 5s cannot resume", FilmResume.canResume(5000, 100000, false), false);
        eq("position at 5.001s can resume", FilmResume.canResume(5001, 100000, false), true);
        eq("position at 50% can resume", FilmResume.canResume(50000, 100000, false), true);
        eq("position at 94.9% can resume", FilmResume.canResume(94900, 100000, false), true);
        eq("position at 95% cannot resume", FilmResume.canResume(95000, 100000, false), false);
        eq("position at 96% cannot resume", FilmResume.canResume(96000, 100000, false), false);
        eq("watched film cannot resume even in mid position", FilmResume.canResume(50000, 100000, true), false);
        eq("unknown duration (<=0) but pos > 5s can resume", FilmResume.canResume(60000, 0, false), true);

        // 4. Button text
        eq("button text under hour", FilmResume.resumeButtonText(2530000), "Resume from 42:10");
        eq("button text over hour", FilmResume.resumeButtonText(6130000), "Resume from 1:42:10");

        // 5. Seek steps
        eq("initial press step 10s", FilmResume.seekStep(0), 10000L);
        eq("hold press step 1 min (repeat 1)", FilmResume.seekStep(1), 60000L);
        eq("hold press step 1 min (repeat 5)", FilmResume.seekStep(5), 60000L);

        System.out.println("FilmResumeTest: " + checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
