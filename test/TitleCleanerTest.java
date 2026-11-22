import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import lk.manus.iptv.meta.TitleCleaner;

/**
 * Tests TitleCleaner against 200 messy guide/VOD titles from test/titles.tsv.
 */
public class TitleCleanerTest {

    public static void main(String[] args) throws Exception {
        int checks = 0, failures = 0;
        File tsv = new File("test/titles.tsv");
        if (!tsv.exists()) {
            tsv = new File("projects/manus-iptv/test/titles.tsv");
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(tsv), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length < 3) continue;

                String raw = parts[0];
                String expectedTitle = parts[1].trim();
                int expectedYear = Integer.parseInt(parts[2].trim());
                checks++;

                TitleCleaner.Cleaned clean = TitleCleaner.clean(raw);
                if (expectedTitle.isEmpty()) {
                    if (clean != null) {
                        failures++;
                        System.out.println("FAIL [" + raw + "]: expected series/null, got [" + clean.title + "|" + clean.year + "]");
                    }
                } else {
                    if (clean == null) {
                        failures++;
                        System.out.println("FAIL [" + raw + "]: expected [" + expectedTitle + "|" + expectedYear + "], got null");
                    } else if (!expectedTitle.equals(clean.title) || expectedYear != clean.year) {
                        failures++;
                        System.out.println("FAIL [" + raw + "]: expected [" + expectedTitle + "|" + expectedYear + "], got [" + clean.title + "|" + clean.year + "]");
                    }
                }
            }
        }

        System.out.println("TitleCleanerTest: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
