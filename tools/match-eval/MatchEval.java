import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lk.manus.iptv.meta.Matcher;
import lk.manus.iptv.meta.TitleCleaner;

/** PC-side harness for tools/match-eval/run.py: runs the app's own TitleCleaner and Matcher on real guide titles + real TMDB candidates. */
public class MatchEval {
    public static void main(String[] a) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(a[2]), true, "UTF-8");
        List<String> in = Files.readAllLines(Paths.get(a[1]), StandardCharsets.UTF_8);
        if (a[0].equals("clean")) { // in: raw \t minutes \t channel   out: idx \t clean \t year   (clean empty = not a film)
            int i = 0;
            for (String l : in) {
                TitleCleaner.Cleaned c = TitleCleaner.clean(l.split("\t", -1)[0]);
                out.println(i++ + "\t" + (c == null ? "" : c.title) + "\t" + (c == null ? 0 : c.year));
            }
        } else { // pick. in: idx \t clean \t year \t slotMinutes \t id \t title \t orig \t year \t pop \t votes   out: idx \t id \t score \t stage \t runtime
            Map<Integer, Integer> runtimes = new HashMap<>();
            Map<Integer, List<String>> knownTitles = new HashMap<>();
            if (a.length > 3 && Files.exists(Paths.get(a[3]))) {
                List<String> altLines = Files.readAllLines(Paths.get(a[3]), StandardCharsets.UTF_8);
                for (String l : altLines) {
                    String[] parts = l.split("\t", 3);
                    if (parts.length >= 2 && !parts[0].isEmpty()) {
                        try {
                            int cid = Integer.parseInt(parts[0]);
                            int rt = Integer.parseInt(parts[1]);
                            runtimes.put(cid, rt);
                            if (parts.length > 2 && !parts[2].trim().isEmpty()) {
                                knownTitles.computeIfAbsent(cid, k -> new ArrayList<>()).add(parts[2].trim());
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            Map<String, List<Matcher.Candidate>> m = new LinkedHashMap<>();
            Map<String, String[]> q = new HashMap<>();
            for (String l : in) {
                String[] f = l.split("\t", -1);
                if (f.length < 10) continue;
                q.put(f[0], f);
                m.computeIfAbsent(f[0], k -> new ArrayList<>()).add(new Matcher.Candidate(
                        Integer.parseInt(f[4]), f[5], f[6], Integer.parseInt(f[7]),
                        Double.parseDouble(f[8]), Integer.parseInt(f[9]), 0, "", ""));
            }
            for (Map.Entry<String, List<Matcher.Candidate>> e : m.entrySet()) {
                String[] f = q.get(e.getKey());
                String cleanTitle = f[1];
                int year = Integer.parseInt(f[2]);
                int slotMinutes = Integer.parseInt(f[3]);
                List<Matcher.Candidate> candidates = e.getValue();
                Matcher.Candidate p = Matcher.pick(cleanTitle, year, candidates);
                int stage = 1;
                if (p != null) {
                    Matcher.Candidate next = Matcher.nextExactCandidate(cleanTitle, p, candidates);
                    int pRt = runtimes.getOrDefault(p.id, 0);
                    int nextRt = (next != null) ? runtimes.getOrDefault(next.id, 0) : 0;
                    p = Matcher.breakSlotTie(p, next, slotMinutes, pRt, nextRt);
                    if (p == next && next != null) {
                        p.score = Matcher.score(cleanTitle, year, next);
                    }
                } else {
                    p = Matcher.pickWithTitles(cleanTitle, year, candidates, knownTitles);
                    if (p != null) {
                        stage = 2;
                        Matcher.Candidate other = Matcher.nextStage2Candidate(cleanTitle, p, candidates, knownTitles);
                        int pRt = runtimes.getOrDefault(p.id, 0);
                        int otherRt = (other != null) ? runtimes.getOrDefault(other.id, 0) : 0;
                        p = Matcher.breakSlotTie(p, other, slotMinutes, pRt, otherRt);
                    }
                }
                int winId = (p == null ? 0 : p.id);
                double winScore = (p == null ? 0.0 : p.score);
                int winStage = (p == null ? 0 : stage);
                int winRt = (p == null ? 0 : runtimes.getOrDefault(winId, 0));
                int amb = (p == null ? Matcher.ambiguousChoices(cleanTitle, year, candidates).size() : 0); // "Which film?" candidates for a no-match
                out.println(e.getKey() + "\t" + winId + "\t" + winScore + "\t" + winStage + "\t" + winRt + "\t" + amb);
            }
        }
    }
}
