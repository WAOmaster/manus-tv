import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lk.manus.iptv.meta.Matcher;
import lk.manus.iptv.meta.Matcher.Candidate;

/**
 * Tests Matcher with >= 40 hand-built candidate lists.
 */
public class MatcherTest {
    private static int checks = 0, failures = 0;

    private static void eq(String what, Object expected, Object actual) {
        checks++;
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        failures++;
        System.out.println("FAIL " + what + ": expected [" + expected + "] got [" + actual + "]");
    }

    private static Candidate cand(int id, String title, int year, double pop, int votes) {
        return new Candidate(id, title, title, year, pop, votes, 7.5, "/p.jpg", "");
    }

    private static Candidate candOrig(int id, String title, String origTitle, int year, double pop, int votes) {
        return new Candidate(id, title, origTitle, year, pop, votes, 7.5, "/p.jpg", "");
    }

    public static void main(String[] args) {
        // 1. Exact match with exact year
        Candidate c1 = cand(101, "The Matrix", 1999, 80.0, 24000);
        Candidate r1 = Matcher.pick("The Matrix", 1999, Collections.singletonList(c1));
        eq("1. exact match exact year", 101, r1 != null ? r1.id : null);

        // 2. Exact match with year +- 1 (close bonus)
        Candidate c2 = cand(102, "Gladiator", 2000, 70.0, 18000);
        Candidate r2 = Matcher.pick("Gladiator", 2001, Collections.singletonList(c2));
        eq("2. exact match close year", 102, r2 != null ? r2.id : null);

        // 3. Exact match with target year 0 (single candidate)
        Candidate c3 = cand(103, "Insidious 2", 2013, 50.0, 5000);
        Candidate r3 = Matcher.pick("Insidious 2", 0, Collections.singletonList(c3));
        eq("3. exact match year 0", 103, r3 != null ? r3.id : null);

        // 4. Roman numerals normalization ("Insidious: Chapter 2" vs "Insidious 2")
        Candidate c4 = cand(104, "Insidious: Chapter 2", 2013, 50.0, 5000);
        Candidate r4 = Matcher.pick("Insidious 2", 2013, Collections.singletonList(c4));
        eq("4. roman numerals chapter 2", 104, r4 != null ? r4.id : null);

        // 5. Roman numerals in sequel ("The Godfather Part II" vs "The Godfather Part 2")
        Candidate c5 = cand(105, "The Godfather Part II", 1974, 90.0, 12000);
        Candidate r5 = Matcher.pick("The Godfather Part 2", 1974, Collections.singletonList(c5));
        eq("5. roman numerals part II", 105, r5 != null ? r5.id : null);

        // 6. Case insensitivity
        Candidate c6 = cand(106, "Pulp Fiction", 1994, 85.0, 26000);
        Candidate r6 = Matcher.pick("pulp fiction", 1994, Collections.singletonList(c6));
        eq("6. case insensitivity", 106, r6 != null ? r6.id : null);

        // 7. Accent insensitivity
        Candidate c7 = cand(107, "Amélie", 2001, 60.0, 8000);
        Candidate r7 = Matcher.pick("Amelie", 2001, Collections.singletonList(c7));
        eq("7. accent insensitivity", 107, r7 != null ? r7.id : null);

        // 8. Remake with target year 2012 -> 2012 version picked
        Candidate tr1990 = cand(201, "Total Recall", 1990, 45.0, 5500);
        Candidate tr2012 = cand(202, "Total Recall", 2012, 50.0, 4800);
        List<Candidate> trList = Arrays.asList(tr1990, tr2012);
        Candidate r8 = Matcher.pick("Total Recall", 2012, trList);
        eq("8. remake target 2012", 202, r8 != null ? r8.id : null);

        // 9. Remake with target year 1990 -> 1990 version picked
        Candidate r9 = Matcher.pick("Total Recall", 1990, trList);
        eq("9. remake target 1990", 201, r9 != null ? r9.id : null);

        // 10. Remake without year (target year 0) -> null (top two too close!)
        Candidate r10 = Matcher.pick("Total Recall", 0, trList);
        eq("10. remake no year -> null", null, r10);

        // 11. Remake: "The Thing" target 2011
        Candidate tt1982 = cand(210, "The Thing", 1982, 60.0, 6200);
        Candidate tt2011 = cand(211, "The Thing", 2011, 40.0, 3100);
        Candidate r11 = Matcher.pick("The Thing", 2011, Arrays.asList(tt1982, tt2011));
        eq("11. the thing 2011", 211, r11 != null ? r11.id : null);

        // 12. Remake: "The Thing" target 1982
        Candidate r12 = Matcher.pick("The Thing", 1982, Arrays.asList(tt1982, tt2011));
        eq("12. the thing 1982", 210, r12 != null ? r12.id : null);

        // 13. Remake: "The Thing" target 0 -> null
        Candidate r13 = Matcher.pick("The Thing", 0, Arrays.asList(tt1982, tt2011));
        eq("13. the thing year 0 -> null", null, r13);

        // 14. Remake: "Ghostbusters" target 2016
        Candidate gb1984 = cand(220, "Ghostbusters", 1984, 55.0, 8000);
        Candidate gb2016 = cand(221, "Ghostbusters", 2016, 35.0, 4200);
        Candidate r14 = Matcher.pick("Ghostbusters", 2016, Arrays.asList(gb1984, gb2016));
        eq("14. ghostbusters 2016", 221, r14 != null ? r14.id : null);

        // 15. Remake: "Ghostbusters" target 1984
        Candidate r15 = Matcher.pick("Ghostbusters", 1984, Arrays.asList(gb1984, gb2016));
        eq("15. ghostbusters 1984", 220, r15 != null ? r15.id : null);

        // 16. Multiple remakes: "A Star Is Born" (1954, 1976, 2018), target 2018
        Candidate as1954 = cand(230, "A Star Is Born", 1954, 20.0, 900);
        Candidate as1976 = cand(231, "A Star Is Born", 1976, 25.0, 1100);
        Candidate as2018 = cand(232, "A Star Is Born", 2018, 70.0, 13000);
        Candidate r16 = Matcher.pick("A Star Is Born", 2018, Arrays.asList(as1954, as1976, as2018));
        eq("16. a star is born 2018", 232, r16 != null ? r16.id : null);

        // 17. Remake: "Dune" target 2021
        Candidate d1984 = cand(240, "Dune", 1984, 40.0, 2500);
        Candidate d2021 = cand(241, "Dune", 2021, 95.0, 11000);
        Candidate r17 = Matcher.pick("Dune", 2021, Arrays.asList(d1984, d2021));
        eq("17. dune 2021", 241, r17 != null ? r17.id : null);

        // 18. Same-year near-titles: Spider-Man vs Spider-Man: The Movie
        Candidate sm1 = cand(301, "Spider-Man", 2002, 75.0, 17000);
        Candidate sm2 = cand(302, "Spider-Man: The Movie", 2002, 10.0, 200);
        Candidate r18 = Matcher.pick("Spider-Man", 2002, Arrays.asList(sm1, sm2));
        eq("18. same year near titles spider-man", 301, r18 != null ? r18.id : null);

        // 19. Same-year near-titles: Batman vs Batman: The Movie
        Candidate bm1 = cand(310, "Batman", 1989, 65.0, 7200);
        Candidate bm2 = cand(311, "Batman: The Movie", 1989, 8.0, 150);
        Candidate r19 = Matcher.pick("Batman", 1989, Arrays.asList(bm1, bm2));
        eq("19. same year near titles batman", 310, r19 != null ? r19.id : null);

        // 20. Same-year near-titles: Robin Hood vs Robin Hood: Ghosts of Sherwood
        Candidate rh1 = cand(320, "Robin Hood", 2010, 50.0, 4200);
        Candidate rh2 = cand(321, "Robin Hood: Ghosts of Sherwood", 2010, 5.0, 80);
        Candidate r20 = Matcher.pick("Robin Hood", 2010, Arrays.asList(rh1, rh2));
        eq("20. same year near titles robin hood", 320, r20 != null ? r20.id : null);

        // 21. Near-titles different years: The Fast and the Furious (2001) vs Fast & Furious (2009)
        Candidate ff1 = cand(330, "The Fast and the Furious", 2001, 70.0, 9500);
        Candidate ff2 = cand(331, "Fast & Furious", 2009, 65.0, 7800);
        Candidate r21 = Matcher.pick("Fast & Furious", 2009, Arrays.asList(ff1, ff2));
        eq("21. fast & furious 2009", 331, r21 != null ? r21.id : null);

        // 22. Sequels: Toy Story 4 (2019) among all Toy Story films
        Candidate ts1 = cand(341, "Toy Story", 1995, 80.0, 16000);
        Candidate ts2 = cand(342, "Toy Story 2", 1999, 75.0, 12000);
        Candidate ts3 = cand(343, "Toy Story 3", 2010, 78.0, 14000);
        Candidate ts4 = cand(344, "Toy Story 4", 2019, 72.0, 9500);
        Candidate r22 = Matcher.pick("Toy Story 4", 2019, Arrays.asList(ts1, ts2, ts3, ts4));
        eq("22. toy story 4", 344, r22 != null ? r22.id : null);

        // 23. Sequels: Iron Man 3 (2013)
        Candidate im1 = cand(351, "Iron Man", 2008, 90.0, 24000);
        Candidate im2 = cand(352, "Iron Man 2", 2010, 80.0, 19000);
        Candidate im3 = cand(353, "Iron Man 3", 2013, 85.0, 21000);
        Candidate r23 = Matcher.pick("Iron Man 3", 2013, Arrays.asList(im1, im2, im3));
        eq("23. iron man 3", 353, r23 != null ? r23.id : null);

        // 24. Sequels: Die Hard 2 (1990)
        Candidate dh1 = cand(361, "Die Hard", 1988, 70.0, 10500);
        Candidate dh2 = cand(362, "Die Hard 2", 1990, 60.0, 5800);
        Candidate dh3 = cand(363, "Die Hard with a Vengeance", 1995, 65.0, 6200);
        Candidate r24 = Matcher.pick("Die Hard 2", 1990, Arrays.asList(dh1, dh2, dh3));
        eq("24. die hard 2", 362, r24 != null ? r24.id : null);

        // 25. Original title match: Sen to Chihiro no Kamikakushi
        Candidate sa = candOrig(401, "Spirited Away", "Sen to Chihiro no Kamikakushi", 2001, 80.0, 15000);
        Candidate r25 = Matcher.pick("Sen to Chihiro no Kamikakushi", 2001, Collections.singletonList(sa));
        eq("25. original title match spirited away", 401, r25 != null ? r25.id : null);

        // 26. Original title match: El laberinto del fauno
        Candidate pl = candOrig(402, "Pan's Labyrinth", "El laberinto del fauno", 2006, 65.0, 9800);
        Candidate r26 = Matcher.pick("El laberinto del fauno", 2006, Collections.singletonList(pl));
        eq("26. original title match pan's labyrinth", 402, r26 != null ? r26.id : null);

        // 27. Original title match: Cidade de Deus
        Candidate cg = candOrig(403, "City of God", "Cidade de Deus", 2002, 60.0, 7200);
        Candidate r27 = Matcher.pick("Cidade de Deus", 2002, Collections.singletonList(cg));
        eq("27. original title match city of god", 403, r27 != null ? r27.id : null);

        // 28. Original title match: Der Untergang
        Candidate df = candOrig(404, "Downfall", "Der Untergang", 2004, 45.0, 3600);
        Candidate r28 = Matcher.pick("Der Untergang", 2004, Collections.singletonList(df));
        eq("28. original title match downfall", 404, r28 != null ? r28.id : null);

        // 29. Original title match: Das Leben der Anderen
        Candidate lo = candOrig(405, "The Lives of Others", "Das Leben der Anderen", 2006, 50.0, 4100);
        Candidate r29 = Matcher.pick("Das Leben der Anderen", 2006, Collections.singletonList(lo));
        eq("29. original title match lives of others", 405, r29 != null ? r29.id : null);

        // 30. Original title match: Jagten
        Candidate th = candOrig(406, "The Hunt", "Jagten", 2012, 48.0, 3800);
        Candidate r30 = Matcher.pick("Jagten", 2012, Collections.singletonList(th));
        eq("30. original title match the hunt", 406, r30 != null ? r30.id : null);

        // 31. No good candidate: Target "Oppenheimer", wrong candidates
        Candidate bad1 = cand(501, "Barbie", 2023, 90.0, 12000);
        Candidate bad2 = cand(502, "Wonka", 2023, 60.0, 4500);
        Candidate r31 = Matcher.pick("Oppenheimer", 2023, Arrays.asList(bad1, bad2));
        eq("31. no good candidate -> null", null, r31);

        // 32. No good candidate: Target "Avatar", completely different films
        Candidate r32 = Matcher.pick("Avatar", 2009, Arrays.asList(
            cand(510, "Titanic", 1997, 85.0, 24000),
            cand(511, "The Terminator", 1984, 60.0, 12000)
        ));
        eq("32. wrong candidates -> null", null, r32);

        // 33. No good candidate: Target "Inception" vs "Interstellar"
        Candidate r33 = Matcher.pick("Inception", 2010, Collections.singletonList(
            cand(520, "Interstellar", 2014, 90.0, 32000)
        ));
        eq("33. single wrong candidate -> null", null, r33);

        // 34. Top two are too close: Score difference < MARGIN -> returns null
        Candidate close1 = cand(601, "Dark Waters", 2019, 45.0, 2000);
        Candidate close2 = cand(602, "Deep Waters", 2019, 44.0, 1900);
        Candidate r34 = Matcher.pick("Dead Waters", 2019, Arrays.asList(close1, close2));
        eq("34. top two too close -> null", null, r34);

        // 35. Top two nearly identical titles and equal years -> returns null
        Candidate close3 = cand(603, "The Girl with the Dragon Tattoo", 2009, 60.0, 5000);
        Candidate close4 = cand(604, "The Girl with the Dragon Tattoo", 2011, 59.0, 4900);
        Candidate r35 = Matcher.pick("The Girl with the Dragon Tattoo", 0, Arrays.asList(close3, close4));
        eq("35. similar titles no year -> null", null, r35);

        // 36. Year mismatch penalty drops below threshold
        Candidate wrongYr = cand(701, "Interstellar", 1970, 10.0, 50);
        Candidate r36 = Matcher.pick("Interstellar", 2014, Collections.singletonList(wrongYr));
        eq("36. year mismatch penalty -> null", null, r36);

        // 37. Empty candidate list -> returns null
        Candidate r37 = Matcher.pick("Inception", 2010, new ArrayList<Candidate>());
        eq("37. empty candidate list -> null", null, r37);

        // 38. Null candidate list -> returns null
        Candidate r38 = Matcher.pick("Inception", 2010, null);
        eq("38. null candidate list -> null", null, r38);

        // 39. Empty clean title -> returns null
        Candidate r39 = Matcher.pick("", 2010, Collections.singletonList(c1));
        eq("39. empty title -> null", null, r39);

        // 40. Null clean title -> returns null
        Candidate r40 = Matcher.pick(null, 2010, Collections.singletonList(c1));
        eq("40. null title -> null", null, r40);

        // 41. Single candidate with low similarity (below threshold) -> returns null
        Candidate lowSim = cand(702, "Something Completely Different", 2010, 10.0, 50);
        Candidate r41 = Matcher.pick("Inception", 2010, Collections.singletonList(lowSim));
        eq("41. low similarity -> null", null, r41);

        // 42. Sequel with numbers: Deadpool 2
        Candidate dp1 = cand(801, "Deadpool", 2016, 85.0, 29000);
        Candidate dp2 = cand(802, "Deadpool 2", 2018, 80.0, 16000);
        Candidate r42 = Matcher.pick("Deadpool 2", 2018, Arrays.asList(dp1, dp2));
        eq("42. deadpool 2", 802, r42 != null ? r42.id : null);

        // 43. Year-as-title trap: 1917
        Candidate y1917 = cand(901, "1917", 2019, 90.0, 12000);
        Candidate r43 = Matcher.pick("1917", 2019, Collections.singletonList(y1917));
        eq("43. 1917 year-as-title", 901, r43 != null ? r43.id : null);

        // 44. Year-as-title trap: 2012
        Candidate y2012 = cand(902, "2012", 2009, 75.0, 11000);
        Candidate r44 = Matcher.pick("2012", 2009, Collections.singletonList(y2012));
        eq("44. 2012 year-as-title", 902, r44 != null ? r44.id : null);

        // 45. Blade Runner 2049 (2017)
        Candidate br2049 = cand(903, "Blade Runner 2049", 2017, 85.0, 13000);
        Candidate r45 = Matcher.pick("Blade Runner 2049", 2017, Collections.singletonList(br2049));
        eq("45. blade runner 2049", 903, r45 != null ? r45.id : null);

        // 46. Stage 1 Drop rule: You've Got Mail shape (drop 0-year, future-year, and 0-vote candidates when >= 20 vote exact candidate exists)
        Candidate ygm1 = cand(1001, "You've Got Mail", 1998, 40.0, 3830);
        Candidate ygm2 = cand(1002, "You've Got Mail", 0, 1.0, 0);
        Candidate ygm3 = cand(1003, "You've Got Mail", 2028, 1.0, 0);
        Candidate r46 = Matcher.pick("You've Got Mail", 0, Arrays.asList(ygm1, ygm2, ygm3));
        eq("46. drop 0-year, future-year, 0-vote", 1001, r46 != null ? r46.id : null);

        // 47. Stage 1 Drop rule: 0-vote candidate NOT dropped if no candidate with exact match and >= 20 votes exists
        Candidate ngn = cand(1010, "Naach Govinda Naach", 1992, 5.0, 0);
        Candidate r47 = Matcher.pick("Naach Govinda Naach", 0, Collections.singletonList(ngn));
        eq("47. single 0-vote candidate not dropped", 1010, r47 != null ? r47.id : null);

        // 48. Stage 1 Dominance: Oxygen shape (1639 votes vs 102 votes -> >= 50 votes and >= 5x -> accepted without a year)
        Candidate ox2021 = cand(1020, "Oxygen", 2021, 50.0, 1639);
        Candidate ox1999 = cand(1021, "Oxygen", 1999, 20.0, 102);
        Candidate r48 = Matcher.pick("Oxygen", 0, Arrays.asList(ox2021, ox1999));
        eq("48. dominance oxygen 2021 over 1999", 1020, r48 != null ? r48.id : null);

        // 49. Stage 1 Dominance: Wonder Boys shape (703 votes vs 2018 0-vote -> 703 accepted without a year)
        Candidate wb2000 = cand(1030, "Wonder Boys", 2000, 30.0, 703);
        Candidate wb2018 = cand(1031, "Wonder Boys", 2018, 1.0, 0);
        Candidate r49 = Matcher.pick("Wonder Boys", 0, Arrays.asList(wb2000, wb2018));
        eq("49. dominance wonder boys 2000", 1030, r49 != null ? r49.id : null);

        // 50. Stage 1 Must stay unmatched: Poltergeist shape (1982 v3529 vs 2015 v2460 -> not 5x -> null)
        Candidate pol1982 = cand(1040, "Poltergeist", 1982, 45.0, 3529);
        Candidate pol2015 = cand(1041, "Poltergeist", 2015, 35.0, 2460);
        Candidate r50 = Matcher.pick("Poltergeist", 0, Arrays.asList(pol1982, pol2015));
        eq("50. poltergeist shape stays null", null, r50);

        // 51. Stage 1 Must stay unmatched: Red Dawn shape (2012 v1808 vs 1984 v940 -> not 5x -> null)
        Candidate rd2012 = cand(1050, "Red Dawn", 2012, 40.0, 1808);
        Candidate rd1984 = cand(1051, "Red Dawn", 1984, 30.0, 940);
        Candidate r51 = Matcher.pick("Red Dawn", 0, Arrays.asList(rd2012, rd1984));
        eq("51. red dawn shape stays null", null, r51);

        // 52. Stage 1 Must stay unmatched: Madame Bovary shape (2015 v425 vs 1991 v152 -> not 5x -> null)
        Candidate mb2015 = cand(1060, "Madame Bovary", 2015, 25.0, 425);
        Candidate mb1991 = cand(1061, "Madame Bovary", 1991, 15.0, 152);
        Candidate r52 = Matcher.pick("Madame Bovary", 0, Arrays.asList(mb2015, mb1991));
        eq("52. madame bovary shape stays null", null, r52);

        // 53. Stage 1 Must stay unmatched: The Ladies Man shape (2000 v161 vs 1961 v148 -> not 5x -> null)
        Candidate lm2000 = cand(1070, "The Ladies Man", 2000, 15.0, 161);
        Candidate lm1961 = cand(1071, "The Ladies Man", 1961, 12.0, 148);
        Candidate r53 = Matcher.pick("The Ladies Man", 0, Arrays.asList(lm2000, lm1961));
        eq("53. the ladies man shape stays null", null, r53);

        // 54. Stage 1 Must stay unmatched: Thug Life shape (2025 v30 vs 2001 v5 -> top votes < 50 -> null)
        Candidate tl2025 = cand(1080, "Thug Life", 2025, 10.0, 30);
        Candidate tl2001 = cand(1081, "Thug Life", 2001, 3.0, 5);
        Candidate r54 = Matcher.pick("Thug Life", 0, Arrays.asList(tl2025, tl2001));
        eq("54. thug life shape stays null", null, r54);

        // 55. Stage 2: pickWithTitles translation match (Sr. e Sra. Smith -> Mr. & Mrs. Smith)
        Candidate smith2005 = cand(1090, "Mr. & Mrs. Smith", 2005, 80.0, 11684);
        Candidate smith1941 = cand(1091, "Mr. & Mrs. Smith", 1941, 15.0, 268);
        Map<Integer, List<String>> smithTitles = new HashMap<>();
        smithTitles.put(1090, Arrays.asList("Sr. e Sra. Smith", "Sr. y Sra. Smith"));
        smithTitles.put(1091, Arrays.asList("Mr. and Mrs. Smith"));
        Candidate r55 = Matcher.pickWithTitles("Sr e Sra Smith", 0, Arrays.asList(smith2005, smith1941), smithTitles);
        eq("55. stage 2 sr e sra smith", 1090, r55 != null ? r55.id : null);

        // 56. Stage 2: pickWithTitles leading article removal in query (La decisión de Sophie -> Sophie's Choice)
        Candidate sophie = cand(1100, "Sophie's Choice", 1982, 35.0, 978);
        Map<Integer, List<String>> sophieTitles = new HashMap<>();
        sophieTitles.put(1100, Collections.singletonList("Decisión de Sophie"));
        Candidate r56 = Matcher.pickWithTitles("La decisión de Sophie", 0, Collections.singletonList(sophie), sophieTitles);
        eq("56. stage 2 leading article removal", 1100, r56 != null ? r56.id : null);

        // 57. Stage 2: pickWithTitles single search result with < 20 votes accepted
        Candidate indie = cand(1110, "Original Indie Film", 2020, 2.0, 8);
        Map<Integer, List<String>> indieTitles = new HashMap<>();
        indieTitles.put(1110, Collections.singletonList("Film Indie Original"));
        Candidate r57 = Matcher.pickWithTitles("Film Indie Original", 0, Collections.singletonList(indie), indieTitles);
        eq("57. stage 2 only search result accepted", 1110, r57 != null ? r57.id : null);

        // 58. Stage 2: pickWithTitles both top 2 match known titles -> ambiguous -> null
        Candidate dup1 = cand(1120, "Film One", 2020, 10.0, 100);
        Candidate dup2 = cand(1121, "Film Two", 2021, 10.0, 100);
        Map<Integer, List<String>> dupTitles = new HashMap<>();
        dupTitles.put(1120, Collections.singletonList("Same Translated Title"));
        dupTitles.put(1121, Collections.singletonList("Same Translated Title"));
        Candidate r58 = Matcher.pickWithTitles("Same Translated Title", 0, Arrays.asList(dup1, dup2), dupTitles);
        eq("58. stage 2 both match -> null", null, r58);

        // 59. Stage 2 must never override Class C: Poltergeist shape with known titles -> null
        Map<Integer, List<String>> polTitles = new HashMap<>();
        polTitles.put(1040, Collections.singletonList("Poltergeist"));
        polTitles.put(1041, Collections.singletonList("Poltergeist"));
        Candidate r59 = Matcher.pickWithTitles("Poltergeist", 0, Arrays.asList(pol1982, pol2015), polTitles);
        eq("59. stage 2 poltergeist never overridden", null, r59);

        // 60. Stage 2 must never override Class C: Red Dawn shape with known titles -> null
        Map<Integer, List<String>> rdTitles = new HashMap<>();
        rdTitles.put(1050, Collections.singletonList("Red Dawn"));
        rdTitles.put(1051, Collections.singletonList("Red Dawn"));
        Candidate r60 = Matcher.pickWithTitles("Red Dawn", 0, Arrays.asList(rd2012, rd1984), rdTitles);
        eq("60. stage 2 red dawn never overridden", null, r60);

        // 61. Stage 2 must never override Class C: Madame Bovary shape with known titles -> null
        Map<Integer, List<String>> mbTitles = new HashMap<>();
        mbTitles.put(1060, Collections.singletonList("Madame Bovary"));
        mbTitles.put(1061, Collections.singletonList("Madame Bovary"));
        Candidate r61 = Matcher.pickWithTitles("Madame Bovary", 0, Arrays.asList(mb2015, mb1991), mbTitles);
        eq("61. stage 2 madame bovary never overridden", null, r61);

        // 62. Stage 2 Class D: Tyler Perry's Madea Goes to Jail -> Madea Goes to Jail (1 cand, >= 50 votes, in order)
        Candidate madea = cand(1130, "Madea Goes to Jail", 2009, 20.0, 219);
        Candidate r62 = Matcher.pickWithTitles("Tyler Perry's Madea Goes to Jail", 0, Collections.singletonList(madea), Collections.emptyMap());
        eq("62. class D madea goes to jail", 1130, r62 != null ? r62.id : null);

        // 63. Stage 2 Class D: Star Trek VII: Generations -> Star Trek: Generations (1 cand, >= 50 votes)
        Candidate stGen = cand(1140, "Star Trek: Generations", 1994, 30.0, 1590);
        Candidate r63 = Matcher.pickWithTitles("Star Trek VII: Generations", 0, Collections.singletonList(stGen), Collections.emptyMap());
        eq("63. class D star trek generations", 1140, r63 != null ? r63.id : null);

        // 64. Stage 2 Class D: multiple candidates -> null
        Candidate r64 = Matcher.pickWithTitles("Tyler Perry's Madea Goes to Jail", 0, Arrays.asList(madea, cand(1131, "Other", 2010, 5.0, 10)), Collections.emptyMap());
        eq("64. class D multiple candidates -> null", null, r64);

        // 65. Stage 2 Class D: < 50 votes -> null (Recipe for Revenge with 5 votes)
        Candidate recipe = cand(1150, "Recipe for Revenge", 1998, 2.0, 5);
        Candidate r65 = Matcher.pickWithTitles("Harlequin: Recipe for Revenge", 0, Collections.singletonList(recipe), Collections.emptyMap());
        eq("65. class D < 50 votes -> null", null, r65);

        // 66. Stage 2 Class D: < 2 title words -> null
        Candidate oneWord = cand(1160, "Jail", 2000, 20.0, 200);
        Candidate r66 = Matcher.pickWithTitles("Madea Goes to Jail", 0, Collections.singletonList(oneWord), Collections.emptyMap());
        eq("66. class D 1-word title -> null", null, r66);

        // 67. Stage 2 Class D: words not in order -> null
        Candidate outOfOrder = cand(1170, "Jail Goes to Madea", 2009, 20.0, 219);
        Candidate r67 = Matcher.pickWithTitles("Madea Goes to Jail", 0, Collections.singletonList(outOfOrder), Collections.emptyMap());
        eq("67. class D words out of order -> null", null, r67);

        // 68. Punctuation folding: dashes and dots treated as separators
        Candidate st1 = cand(1201, "This Is Spinal Tap", 1984, 20.0, 1738);
        Map<Integer, List<String>> stTitles = new HashMap<>();
        stTitles.put(1201, Collections.singletonList("Это — Spinal Tap"));
        Candidate r68 = Matcher.pickWithTitles("Это – Spinal Tap", 0, Collections.singletonList(st1), stTitles);
        eq("68. punctuation folding en-dash vs em-dash in pickWithTitles", 1201, r68 != null ? r68.id : null);

        // 69. Low-vote exact match: sole candidate accepted
        Candidate soleLowVote = cand(1210, "Sole Low Vote Film", 2010, 1.0, 5);
        Candidate r69 = Matcher.pick("Sole Low Vote Film", 0, Collections.singletonList(soleLowVote));
        eq("69. low-vote sole candidate accepted in stage 1", 1210, r69 != null ? r69.id : null);

        // 70. Low-vote exact match with competitor -> rejected in stage 1
        Candidate lowVote1 = cand(1220, "Low Vote Film", 2010, 1.0, 2);
        Candidate lowVote2 = cand(1221, "Competitor Film", 2015, 2.0, 50);
        Candidate r70 = Matcher.pick("Low Vote Film", 0, Arrays.asList(lowVote1, lowVote2));
        eq("70. low-vote exact match with competitor rejected in stage 1", null, r70);

        // 71. Low-vote exact match goes through stage 2: competitor with matching title wins
        Map<Integer, List<String>> lvTitles = new HashMap<>();
        lvTitles.put(1220, Collections.singletonList("Other Title"));
        lvTitles.put(1221, Collections.singletonList("Low Vote Film"));
        Candidate r71 = Matcher.pickWithTitles("Low Vote Film", 0, Arrays.asList(lowVote1, lowVote2), lvTitles);
        eq("71. low-vote competitor wins in stage 2 if translated title matches", 1221, r71 != null ? r71.id : null);

        // 71b. Rule 3: candidate's own title/original_title count as known titles in stage 2 disambiguation
        Candidate lowVoteHombre = candOrig(1225, "Hombre o demonio", "Hombre o demonio", 1940, 5.0, 5);
        Candidate compMoney = candOrig(1226, "All That Money Can Buy", "The Devil and Daniel Webster", 1941, 10.0, 104);
        Candidate r71b = Matcher.pickWithTitles("Hombre o demonio", 0, Arrays.asList(lowVoteHombre, compMoney), Collections.emptyMap());
        eq("71b. rule 3: candidate own title counts as known title", 1225, r71b != null ? r71b.id : null);

        // 72. Rule 1: No veto on unambiguous pick. Solo candidate accepted whatever slot says
        Candidate longSolo = cand(1235, "Birth of the Dragon", 2017, 20.0, 344);
        Candidate r72 = Matcher.breakSlotTie(longSolo, null, 85, 103, 0);
        eq("72. rule 1: solo candidate accepted whatever slot says", 1235, r72 != null ? r72.id : null);
        eq("72a. fits: unknown slot -> true", true, Matcher.fits(120, 0));
        eq("72b. fits: unknown runtime -> true", true, Matcher.fits(0, 95));
        eq("72c. fits: runtime <= slot + 5 -> true", true, Matcher.fits(100, 95));
        eq("72d. fits: runtime > slot + 5 -> false", false, Matcher.fits(101, 95));

        // 73. Rule 2 branch 1: Exactly one fits -> second candidate fits slot
        Candidate oxy2021 = cand(1230, "Oxygen", 2021, 50.0, 1639);
        Candidate oxy1999 = cand(1231, "Oxygen", 1999, 10.0, 102);
        Candidate r73 = Matcher.breakSlotTie(oxy2021, oxy1999, 95, 101, 92);
        eq("73. rule 2 branch 1: second fits slot -> accept second", 1231, r73 != null ? r73.id : null);

        // 74. Rule 2 branch 1: Exactly one fits -> first candidate fits slot
        Candidate r74 = Matcher.breakSlotTie(oxy1999, oxy2021, 95, 92, 105);
        eq("74. rule 2 branch 1: first fits slot -> accept first", 1231, r74 != null ? r74.id : null);

        // 75. Rule 2 branch 2: Several fit -> vote dominance among those (>= 50 votes and >= 5x)
        Candidate gj = cand(1240, "G.I. Jane", 1997, 40.0, 1961);
        Candidate rush = cand(1241, "Rush", 1991, 20.0, 170);
        Candidate r75 = Matcher.breakSlotTie(gj, rush, 121, 125, 120);
        eq("75. rule 2 branch 2: several fit with dominance -> dominant candidate", 1240, r75 != null ? r75.id : null);

        // 76. Rule 2 branch 2: Several fit -> no dominance (< 5x) -> null
        Candidate cClose1 = cand(1270, "Film A", 2010, 10.0, 200);
        Candidate cClose2 = cand(1271, "Film B", 2012, 10.0, 100);
        Candidate r76 = Matcher.breakSlotTie(cClose1, cClose2, 120, 90, 95);
        eq("76. rule 2 branch 2: several fit without dominance -> null", null, r76);

        // 77. Rule 2 branch 3: None fits -> vote dominance among all title-matching candidates
        Candidate longDom = cand(1260, "Long Dominant", 2010, 30.0, 5000);
        Candidate longNext = cand(1261, "Long Runner", 2012, 10.0, 500);
        Candidate r77 = Matcher.breakSlotTie(longDom, longNext, 90, 110, 120);
        eq("77. rule 2 branch 3: none fits with dominance -> dominant candidate", 1260, r77 != null ? r77.id : null);

        // 78. Rule 2 branch 3: None fits -> no dominance -> null
        Candidate r78 = Matcher.breakSlotTie(cClose1, cClose2, 80, 110, 120);
        eq("78. rule 2 branch 3: none fits without dominance -> null", null, r78);

        // 79. Stage 2 dominance: votes too close (< 5x) -> null
        Map<Integer, List<String>> closeTitles = new HashMap<>();
        closeTitles.put(1270, Collections.singletonList("Matching Title"));
        closeTitles.put(1271, Collections.singletonList("Matching Title"));
        Candidate r79 = Matcher.pickWithTitles("Matching Title", 0, Arrays.asList(cClose1, cClose2), closeTitles);
        eq("79. stage 2 dominance not met (< 5x) -> null", null, r79);

        // 80. Stage 2 dominance: top has < 50 votes -> null
        Candidate lowDom1 = cand(1280, "Film C", 2010, 10.0, 40);
        Candidate lowDom2 = cand(1281, "Film D", 2012, 10.0, 5);
        Map<Integer, List<String>> lowDomTitles = new HashMap<>();
        lowDomTitles.put(1280, Collections.singletonList("Matching Title 2"));
        lowDomTitles.put(1281, Collections.singletonList("Matching Title 2"));
        Candidate r80 = Matcher.pickWithTitles("Matching Title 2", 0, Arrays.asList(lowDom1, lowDom2), lowDomTitles);
        eq("80. stage 2 dominance < 50 votes -> null", null, r80);

        // 81. Which film?: same title twice -> both offered, most votes first, at most 4; a single exact title or none -> nothing to choose
        Candidate pg1 = cand(1290, "Poltergeist", 1982, 30.0, 2900), pg2 = cand(1291, "Poltergeist", 2015, 20.0, 2700), pg3 = cand(1292, "Poltergeist", 2000, 5.0, 40);
        Candidate pg4 = cand(1293, "Poltergeist", 1999, 5.0, 30), pg5 = cand(1294, "Poltergeist", 1990, 5.0, 20), pgFuture = cand(1295, "Poltergeist", 2999, 5.0, 10);
        List<Candidate> pgl = Matcher.ambiguousChoices("Poltergeist", 0, Arrays.asList(pg3, pg2, pg1, pg4, pg5, pgFuture));
        eq("81a. which film: 4 offered", 4, pgl.size());
        eq("81b. which film: most votes first", 1290, pgl.get(0).id);
        eq("81c. which film: unreleased left out", false, pgl.contains(pgFuture));
        eq("81d. which film: one exact title -> nothing to choose", 0, Matcher.ambiguousChoices("Poltergeist", 0, Arrays.asList(pg1, cand(1296, "Poltergeist II", 1986, 5.0, 500))).size());
        eq("81e. which film: no candidates", 0, Matcher.ambiguousChoices("Poltergeist", 0, new ArrayList<Candidate>()).size());

        // T110d: TV shows. Exact title wins whatever the votes (real TMDB answers for these four guide titles)
        List<Candidate> hh = Arrays.asList(cand(8106, "Homes Under the Hammer", 2003, 20, 6), cand(35627, "Best of Homes Under the Hammer", 2010, 1, 0));
        eq("T110d-1. Homes Under The Hammer (6 votes) is picked", 8106, Matcher.pickShow("Homes Under The Hammer", hh).id);
        eq("T110d-1b. the film rule still refuses it", null, Matcher.pick("Homes Under The Hammer", 0, hh));
        List<Candidate> pi = Arrays.asList(cand(7734, "Police Interceptors", 2008, 20, 13), cand(129423, "Police Interceptors: Unleashed", 2015, 3, 1), cand(57496, "Ultimate Police Interceptors", 2011, 2, 0));
        eq("T110d-2. Police Interceptors (13 votes) is picked", 7734, Matcher.pickShow("Police Interceptors", pi).id);
        List<Candidate> av = Arrays.asList(cand(82452, "Avatar: The Last Airbender", 2024, 50, 1417), cand(246, "Avatar: The Last Airbender", 2005, 80, 5244));
        eq("T110d-3. Avatar remake pair: the one with most votes", 246, Matcher.pickShow("Avatar: The Last Airbender", av).id);
        eq("T110d-4. no candidates", null, Matcher.pickShow("Dateline", new ArrayList<Candidate>()));
        eq("T110d-5. no exact title and nothing close: no match", null, Matcher.pickShow("Dateline", Arrays.asList(cand(1, "Dateline NBC Special", 2001, 5, 3), cand(2, "Date Night", 2010, 5, 9))));

        System.out.println("MatcherTest: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
