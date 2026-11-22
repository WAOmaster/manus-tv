package lk.manus.iptv.meta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lk.manus.iptv.data.Search;

/**
 * Pure Java matcher for TMDB movie candidates.
 * Scores candidates by title similarity, release year, and popularity as tie-breaker.
 * Accepts only if score >= THRESHOLD and beats the runner-up by at least MARGIN.
 */
public final class Matcher {

    // Scoring constants
    public static final double THRESHOLD = 0.75;
    public static final double MARGIN = 0.10;
    public static final double TITLE_WEIGHT = 0.80;
    public static final double YEAR_EXACT_BONUS = 0.15;
    public static final double YEAR_CLOSE_BONUS = 0.05;
    public static final double YEAR_DIFF_PENALTY = 0.25;
    public static final double POPULARITY_MAX_BONUS = 0.03;

    public static class Candidate {
        public final int id;
        public final String title;
        public final String originalTitle;
        public final int year;
        public final double popularity;
        public final int voteCount;
        public final double voteAverage;
        public final String posterPath;
        public final String overview;
        public double score;
        public int runtime;

        public Candidate(int id, String title, String originalTitle, int year,
                         double popularity, int voteCount, double voteAverage,
                         String posterPath, String overview) {
            this.id = id;
            this.title = title;
            this.originalTitle = originalTitle;
            this.year = year;
            this.popularity = popularity;
            this.voteCount = voteCount;
            this.voteAverage = voteAverage;
            this.posterPath = posterPath;
            this.overview = overview;
        }

        public Candidate(int id, String title, int year, double popularity, int voteCount) {
            this(id, title, title, year, popularity, voteCount, 0.0, "", "");
        }
    }

    private static final String[] ARTICLES = {
        "el", "la", "los", "las", "le", "les", "il", "lo", "i", "gli",
        "der", "die", "das", "o", "a", "os", "as", "the", "un", "una",
        "une", "ein", "eine"
    };

    private Matcher() {}

    public static String foldKey(String s) {
        if (s == null) return "";
        String f = Search.fold(s);
        StringBuilder sb = new StringBuilder(f.length());
        for (int i = 0; i < f.length(); i++) {
            char c = f.charAt(i);
            if (isSeparator(c)) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static boolean isSeparator(char c) {
        switch (c) {
            case '\u2013': // – en dash
            case '\u2014': // — em dash
            case '-':
            case ':':
            case '.':
            case ',':
            case '!':
            case '?':
            case '\'':
            case '\u2018': // ‘ left single quote
            case '\u2019': // ’ right single quote
            case '"':
            case '\u201c': // “ left double quote
            case '\u201d': // ” right double quote
            case '\u00bf': // ¿ inverted question mark
            case '\u00a1': // ¡ inverted exclamation mark
            case '&':
            case '_':
                return true;
            default:
                return Character.isWhitespace(c);
        }
    }

    /**
     * Rule 2: fits(c) = slot unknown OR runtime unknown OR runtime <= slotMinutes + 5.
     */
    public static boolean fits(int runtime, int slotMinutes) {
        if (slotMinutes <= 0 || runtime <= 0) return true;
        return runtime <= slotMinutes + 5;
    }

    /**
     * Rule 1 & Rule 2:
     * 1. No veto on an unambiguous pick. If exactly one candidate matches on title -> accept it whatever the slot says.
     * 2. Slot only breaks ties. When two or more candidates match on title:
     *    - exactly one fits -> accept it;
     *    - several fit -> vote dominance among those (>= 50 votes and >= 5x the next) else no match;
     *    - none fits -> vote dominance among all title-matching candidates, else no match.
     */
    public static Candidate breakSlotTie(Candidate c1, Candidate c2, int slotMinutes, int rt1, int rt2) {
        if (c1 == null) return c2;
        if (c2 == null) return c1;

        boolean fit1 = fits(rt1, slotMinutes);
        boolean fit2 = fits(rt2, slotMinutes);

        // Exactly one fits -> accept it
        if (fit1 && !fit2) return c1;
        if (fit2 && !fit1) return c2;

        // Several fit (both fit) or none fits: vote dominance among those
        Candidate top = (c1.voteCount >= c2.voteCount) ? c1 : c2;
        Candidate runnerUp = (top == c1) ? c2 : c1;

        if (top.voteCount >= 50 && top.voteCount >= 5 * runnerUp.voteCount) {
            return top;
        }
        return null;
    }

    public static Candidate applySlotTieBreak(Candidate pick, Candidate next, int slotMinutes,
                                              int pickRuntime, int nextRuntime) {
        return breakSlotTie(pick, next, slotMinutes, pickRuntime, nextRuntime);
    }

    public static Candidate nextExactCandidate(String cleanTitle, Candidate first, List<Candidate> candidates) {
        if (cleanTitle == null || first == null || candidates == null) return null;
        String q = foldKey(cleanTitle);
        for (Candidate c : candidates) {
            if (c != null && c.id != first.id && isExact(q, c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * "Which film?": when the title ends without a match because two or more candidates carry exactly this title (Poltergeist 1982 / 2015),
     * the viewer can choose. Up to 4, most votes first; films without a release year or not yet released are left out. Fewer than 2 = nothing to choose from.
     */
    public static List<Candidate> ambiguousChoices(String cleanTitle, int year, List<Candidate> candidates) {
        List<Candidate> exact = new ArrayList<>();
        if (cleanTitle == null || candidates == null) return exact;
        String q = foldKey(cleanTitle);
        int currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        for (Candidate c : candidates) if (c != null && isExact(q, c) && c.year > 0 && c.year <= currentYear) exact.add(c);
        if (exact.size() < 2) return new ArrayList<>();
        Collections.sort(exact, new Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) { return Integer.compare(b.voteCount, a.voteCount); }
        });
        return new ArrayList<>(exact.subList(0, Math.min(4, exact.size())));
    }

    public static Candidate otherTop2Candidate(Candidate first, List<Candidate> candidates) {
        if (first == null || candidates == null || candidates.size() < 2) return null;
        if (candidates.get(0).id == first.id) return candidates.get(1);
        if (candidates.get(1).id == first.id) return candidates.get(0);
        return null;
    }

    public static Candidate nextStage2Candidate(String cleanTitle, Candidate first, List<Candidate> candidates, Map<Integer, List<String>> knownTitles) {
        Candidate other = otherTop2Candidate(first, candidates);
        if (other == null || cleanTitle == null || knownTitles == null) return null;
        String q = foldKey(cleanTitle);
        String qNoArticle = stripLeadingArticle(q);
        if (hasMatchingKnownTitle(other, q, qNoArticle, knownTitles)) {
            return other;
        }
        return null;
    }

    @Deprecated
    public static Candidate applySlotVeto(Candidate pick, Candidate next, int slotMinutes,
                                          int pickRuntime, int nextRuntime) {
        return breakSlotTie(pick, next, slotMinutes, pickRuntime, nextRuntime);
    }

    private static boolean isExact(String q, Candidate c) {
        if (c == null) return false;
        String t1 = foldKey(c.title);
        if (!t1.isEmpty() && q.equals(t1)) return true;
        String t2 = foldKey(c.originalTitle);
        return !t2.isEmpty() && q.equals(t2);
    }

    private static String stripLeadingArticle(String s) {
        if (s == null) return null;
        for (String a : ARTICLES) {
            if (s.startsWith(a + " ")) {
                return s.substring(a.length() + 1).trim();
            }
        }
        return null;
    }

    private static List<String> extractWords(String s) {
        List<String> list = new ArrayList<>();
        if (s == null) return list;
        String[] parts = s.split("[^\\p{L}\\p{Nd}]+");
        for (String p : parts) {
            if (!p.isEmpty()) list.add(p);
        }
        return list;
    }

    private static boolean wordsInOrder(String title, String query) {
        if (title == null || query == null) return false;
        List<String> tw = extractWords(foldKey(title));
        if (tw.size() < 2) return false;
        List<String> qw = extractWords(foldKey(query));
        int qi = 0;
        for (String w : tw) {
            int found = -1;
            for (int j = qi; j < qw.size(); j++) {
                if (qw.get(j).equals(w)) {
                    found = j;
                    break;
                }
            }
            if (found == -1) return false;
            qi = found + 1;
        }
        return true;
    }

    private static boolean matchesFoldedTitle(String t, String q, String qNoArticle) {
        if (t == null || t.trim().isEmpty()) return false;
        String ft = foldKey(t);
        if (ft.equals(q)) return true;
        if (qNoArticle != null && ft.equals(qNoArticle)) return true;
        String ftNoArticle = stripLeadingArticle(ft);
        if (ftNoArticle != null && ftNoArticle.equals(q)) return true;
        if (qNoArticle != null && ftNoArticle != null && ftNoArticle.equals(qNoArticle)) return true;
        return false;
    }

    public static boolean hasMatchingKnownTitle(Candidate c, String q, String qNoArticle, Map<Integer, List<String>> knownTitles) {
        if (c == null) return false;
        // Known titles always include the candidate's own title and original_title
        if (matchesFoldedTitle(c.title, q, qNoArticle)) return true;
        if (matchesFoldedTitle(c.originalTitle, q, qNoArticle)) return true;

        if (knownTitles != null) {
            List<String> titles = knownTitles.get(c.id);
            if (titles != null && !titles.isEmpty()) {
                for (String t : titles) {
                    if (matchesFoldedTitle(t, q, qNoArticle)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isAmbiguousExact(String cleanTitle, int year, List<Candidate> candidates) {
        if (year > 0 || candidates == null || candidates.isEmpty()) return false;
        String q = foldKey(cleanTitle);
        int currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        boolean hasExactWith20 = false;
        for (Candidate c : candidates) {
            if (c != null && isExact(q, c) && c.voteCount >= 20 && c.year > 0 && c.year <= currentYear) {
                hasExactWith20 = true;
                break;
            }
        }
        List<Candidate> filtered = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            if (c == null) continue;
            if (hasExactWith20) {
                if (c.year <= 0 || c.year > currentYear || c.voteCount <= 0) {
                    continue;
                }
            }
            filtered.add(c);
        }
        List<Candidate> exact = new ArrayList<>();
        for (Candidate c : filtered) {
            if (isExact(q, c)) {
                exact.add(c);
            }
        }
        if (exact.size() >= 2) {
            Collections.sort(exact, new Comparator<Candidate>() {
                @Override public int compare(Candidate a, Candidate b) {
                    return Integer.compare(b.voteCount, a.voteCount);
                }
            });
            Candidate top = exact.get(0);
            Candidate next = exact.get(1);
            if (!(top.voteCount >= 50 && top.voteCount >= 5 * next.voteCount)) {
                return true;
            }
        }
        return false;
    }

    private static double scoreWithKnown(String cleanTitle, int targetYear, Candidate c) {
        double titleScore = 1.0 * TITLE_WEIGHT;
        double yearScore = 0.0;
        if (targetYear > 0) {
            if (c.year == targetYear) {
                yearScore = YEAR_EXACT_BONUS;
            } else if (c.year > 0 && Math.abs(c.year - targetYear) == 1) {
                yearScore = YEAR_CLOSE_BONUS;
            } else if (c.year > 0) {
                yearScore = -YEAR_DIFF_PENALTY;
            }
        }
        double popBonus = 0.0;
        if (c.voteCount > 0) {
            double p = Math.min(1.0, Math.log10(c.voteCount) / 4.0);
            popBonus = Math.max(0.0, p * POPULARITY_MAX_BONUS);
        } else if (c.popularity > 0) {
            double p = Math.min(1.0, c.popularity / 100.0);
            popBonus = Math.max(0.0, p * (POPULARITY_MAX_BONUS / 2.0));
        }
        return titleScore + yearScore + popBonus;
    }

    /**
     * Stage 1: Scores and selects the best candidate, or returns null if no confident match.
     */
    public static Candidate pick(String cleanTitle, int year, List<Candidate> candidates) {
        if (cleanTitle == null || cleanTitle.trim().isEmpty() || candidates == null || candidates.isEmpty()) {
            return null;
        }

        String q = foldKey(cleanTitle);
        int currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);

        // Drop candidates with no release year or a release year in the future, and candidates with 0 votes,
        // WHEN at least one remaining candidate has an exact folded title match and >= 20 votes.
        boolean hasExactWith20 = false;
        for (Candidate c : candidates) {
            if (c != null && isExact(q, c) && c.voteCount >= 20 && c.year > 0 && c.year <= currentYear) {
                hasExactWith20 = true;
                break;
            }
        }

        List<Candidate> filtered = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            if (c == null) continue;
            if (hasExactWith20) {
                if (c.year <= 0 || c.year > currentYear || c.voteCount <= 0) {
                    continue;
                }
            }
            filtered.add(c);
        }

        if (filtered.isEmpty()) return null;

        // Dominance: among exact-title candidates, if the top one has >= 50 votes and >= 5x the votes
        // of the next exact-title candidate, accept it even without a year.
        if (year <= 0) {
            List<Candidate> exact = new ArrayList<>();
            for (Candidate c : filtered) {
                if (isExact(q, c)) {
                    exact.add(c);
                }
            }
            if (exact.size() >= 2) {
                Collections.sort(exact, new Comparator<Candidate>() {
                    @Override public int compare(Candidate a, Candidate b) {
                        return Integer.compare(b.voteCount, a.voteCount);
                    }
                });
                Candidate top = exact.get(0);
                Candidate next = exact.get(1);
                if (top.voteCount >= 50 && top.voteCount >= 5 * next.voteCount) {
                    top.score = score(cleanTitle, year, top);
                    return top;
                }
            }
        }

        // Otherwise keep today's threshold + margin rule.
        List<Candidate> scored = new ArrayList<>(filtered.size());
        for (Candidate c : filtered) {
            c.score = score(cleanTitle, year, c);
            scored.add(c);
        }

        Collections.sort(scored, new Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) {
                return Double.compare(b.score, a.score);
            }
        });

        Candidate best = scored.get(0);
        if (best.score < THRESHOLD) {
            return null;
        }

        if (scored.size() > 1) {
            Candidate runnerUp = scored.get(1);
            if ((best.score - runnerUp.score) < MARGIN) {
                return null; // Top two are too close; avoid confident wrong match
            }
        }

        // Low-vote exact match: a stage-1 exact-title winner with < 20 votes is accepted directly only if it is the sole candidate
        if (best.voteCount < 20 && candidates.size() > 1) {
            return null;
        }

        return best;
    }

    /**
     * T110d: pick for a TV show title (guide titles are series names, not films). Films' vote/margin gates do not apply:
     * UK/factual shows have 5-15 votes, and remakes share one name (Avatar: The Last Airbender 2005 / 2024).
     * Exactly one show with this exact title -> it, whatever its votes. Several -> the one with most votes (ties: popularity).
     * No exact title -> the film rule {@link #pick} (fuzzy, still needs a clear winner).
     */
    public static Candidate pickShow(String title, List<Candidate> candidates) {
        if (title == null || title.trim().isEmpty() || candidates == null || candidates.isEmpty()) return null;
        String q = foldKey(title);
        Candidate best = null;
        for (Candidate c : candidates) {
            if (c == null || !isExact(q, c)) continue;
            if (best == null || c.voteCount > best.voteCount || (c.voteCount == best.voteCount && c.popularity > best.popularity)) best = c;
        }
        if (best != null) { best.score = score(title, 0, best); return best; }
        return pick(title, 0, candidates);
    }

    /**
     * Stage 2: Pure method for disambiguating via translated/alternative titles or Class D containment.
     * Stage 2 must never override a stage-1 "ambiguous between exact-title films" outcome (class C).
     */
    public static Candidate pickWithTitles(String cleanTitle, int year, List<Candidate> candidates, Map<Integer, List<String>> knownTitles) {
        if (cleanTitle == null || cleanTitle.trim().isEmpty() || candidates == null || candidates.isEmpty()) {
            return null;
        }

        // Stage 2 must never override a stage-1 "ambiguous between exact-title films" outcome (class C).
        if (isAmbiguousExact(cleanTitle, year, candidates)) {
            return null;
        }

        String q = foldKey(cleanTitle);
        String qNoArticle = stripLeadingArticle(q);

        int checkCount = Math.min(2, candidates.size());
        boolean m0 = checkCount > 0 && hasMatchingKnownTitle(candidates.get(0), q, qNoArticle, knownTitles);
        boolean m1 = checkCount > 1 && hasMatchingKnownTitle(candidates.get(1), q, qNoArticle, knownTitles);

        if (m0 && !m1) {
            Candidate win = candidates.get(0);
            if (win.voteCount >= 20 || candidates.size() == 1 || (isExact(q, win) && win.year > 0 && win.voteCount > 0)) {
                win.score = scoreWithKnown(cleanTitle, year, win);
                return win;
            }
        } else if (m1 && !m0) {
            Candidate win = candidates.get(1);
            if (win.voteCount >= 20 || candidates.size() == 1 || (isExact(q, win) && win.year > 0 && win.voteCount > 0)) {
                win.score = scoreWithKnown(cleanTitle, year, win);
                return win;
            }
        } else if (m0 && m1) {
            Candidate c0 = candidates.get(0);
            Candidate c1 = candidates.get(1);
            c0.score = scoreWithKnown(cleanTitle, year, c0);
            c1.score = scoreWithKnown(cleanTitle, year, c1);
            if (c0.voteCount >= 50 && c0.voteCount >= 5 * c1.voteCount) {
                return c0;
            } else if (c1.voteCount >= 50 && c1.voteCount >= 5 * c0.voteCount) {
                return c1;
            }
            return null;
        }

        // Class D: accept when the search returned exactly ONE candidate with >= 50 votes whose folded title's words (>= 2 words) all appear, in order, in the folded query.
        if (candidates.size() == 1) {
            Candidate only = candidates.get(0);
            if (only.voteCount >= 50) {
                if (wordsInOrder(only.title, cleanTitle) || (only.originalTitle != null && wordsInOrder(only.originalTitle, cleanTitle))) {
                    only.score = scoreWithKnown(cleanTitle, year, only);
                    return only;
                }
            }
        }

        return null;
    }

    /**
     * Computes the match score for a single candidate.
     */
    public static double score(String cleanTitle, int targetYear, Candidate c) {
        String q = foldKey(cleanTitle);
        String t1 = foldKey(c.title != null ? c.title : "");
        String t2 = foldKey(c.originalTitle != null ? c.originalTitle : "");

        double sim1 = titleSimilarity(q, t1);
        double sim2 = t2.isEmpty() ? 0.0 : titleSimilarity(q, t2);
        double bestSim = Math.max(sim1, sim2);

        double titleScore = bestSim * TITLE_WEIGHT;

        // Year scoring
        double yearScore = 0.0;
        if (targetYear > 0) {
            if (c.year == targetYear) {
                yearScore = YEAR_EXACT_BONUS;
            } else if (c.year > 0 && Math.abs(c.year - targetYear) == 1) {
                yearScore = YEAR_CLOSE_BONUS;
            } else if (c.year > 0) {
                yearScore = -YEAR_DIFF_PENALTY;
            }
        }

        // Popularity / vote count tie-break only (capped at POPULARITY_MAX_BONUS)
        double popBonus = 0.0;
        if (c.voteCount > 0) {
            double p = Math.min(1.0, Math.log10(c.voteCount) / 4.0);
            popBonus = Math.max(0.0, p * POPULARITY_MAX_BONUS);
        } else if (c.popularity > 0) {
            double p = Math.min(1.0, c.popularity / 100.0);
            popBonus = Math.max(0.0, p * (POPULARITY_MAX_BONUS / 2.0));
        }

        return titleScore + yearScore + popBonus;
    }

    private static double titleSimilarity(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;

        // Normalize Roman numerals and chapter words: e.g. "Chapter 2" -> "2", "Part II" -> "Part 2"
        String n1 = normalizeRomanAndChapters(s1);
        String n2 = normalizeRomanAndChapters(s2);
        if (n1.equals(n2)) return 1.0;

        int dist = editDistance(n1, n2);
        int maxLen = Math.max(n1.length(), n2.length());
        if (maxLen == 0) return 1.0;
        return Math.max(0.0, 1.0 - (double) dist / maxLen);
    }

    private static String normalizeRomanAndChapters(String s) {
        String n = s.replaceAll("[:\\-,]", " ")
                    .replaceAll("(?i)\\b(?:chapter|part|vol|volume)\\s*(\\d+)\\b", "$1")
                    .replaceAll("(?i)\\b(?:chapter|part|vol|volume)\\s+ii\\b", "2")
                    .replaceAll("(?i)\\b(?:chapter|part|vol|volume)\\s+iii\\b", "3")
                    .replaceAll("(?i)\\b(?:chapter|part|vol|volume)\\s+iv\\b", "4")
                    .replaceAll("(?i)\\b(?:chapter|part|vol|volume)\\s+v\\b", "5")
                    .replaceAll("(?i)\\bii\\b", "2")
                    .replaceAll("(?i)\\biii\\b", "3")
                    .replaceAll("(?i)\\biv\\b", "4")
                    .replaceAll("(?i)\\bv\\b", "5")
                    .replaceAll("\\s+", " ").trim();
        return n;
    }

    private static int editDistance(String a, String b) {
        int lenA = a.length(), lenB = b.length();
        int[] prev = new int[lenB + 1];
        int[] curr = new int[lenB + 1];

        for (int j = 0; j <= lenB; j++) prev[j] = j;

        for (int i = 1; i <= lenA; i++) {
            curr[0] = i;
            char cA = a.charAt(i - 1);
            for (int j = 1; j <= lenB; j++) {
                int cost = (cA == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            System.arraycopy(curr, 0, prev, 0, lenB + 1);
        }
        return prev[lenB];
    }
}
