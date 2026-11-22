import java.util.Objects;
import lk.manus.iptv.data.XtreamSource;

/**
 * Pure Java unit tests for XtreamSource pure functions:
 * address normalisation, URL template generation, template join with awkward and unicode passwords,
 * template login absence, and account state mapping.
 */
public final class XtreamTest {
    static int fails, checks;

    static void eq(String what, Object got, Object want) {
        checks++;
        if (!Objects.equals(want, got)) {
            fails++;
            System.out.println("FAIL " + what + ": got [" + got + "], want [" + want + "]");
        }
    }

    public static void main(String[] args) {
        // 1. Address normalisation
        eq("simple address", XtreamSource.normaliseAddress("http://10.0.2.2:8081"), "http://10.0.2.2:8081");
        eq("trailing slash", XtreamSource.normaliseAddress("http://10.0.2.2:8081/"), "http://10.0.2.2:8081");
        eq("multiple trailing slashes", XtreamSource.normaliseAddress("http://10.0.2.2:8081///"), "http://10.0.2.2:8081");
        eq("player_api.php suffix", XtreamSource.normaliseAddress("http://10.0.2.2:8081/player_api.php"), "http://10.0.2.2:8081");
        eq("player_api.php with slash", XtreamSource.normaliseAddress("http://10.0.2.2:8081/player_api.php/"), "http://10.0.2.2:8081");
        eq("whitespace trimmed", XtreamSource.normaliseAddress("   http://10.0.2.2:8081   "), "http://10.0.2.2:8081");
        eq("https default port", XtreamSource.normaliseAddress("https://iptv.provider.com"), "https://iptv.provider.com");
        eq("https with port", XtreamSource.normaliseAddress("https://iptv.provider.com:8443/player_api.php/"), "https://iptv.provider.com:8443");
        eq("case normalisation", XtreamSource.normaliseAddress("HTTP://EXAMPLE.COM:8080/PLAYER_API.PHP"), "http://example.com:8080");

        // Invalid addresses -> null (BAD_ADDRESS)
        eq("null address", XtreamSource.normaliseAddress(null), null);
        eq("empty address", XtreamSource.normaliseAddress(""), null);
        eq("whitespace only", XtreamSource.normaliseAddress("   "), null);
        eq("ftp scheme", XtreamSource.normaliseAddress("ftp://10.0.2.2:8081"), null);
        eq("missing host", XtreamSource.normaliseAddress("http://"), null);
        eq("missing scheme", XtreamSource.normaliseAddress("10.0.2.2:8081"), null);
        eq("has path", XtreamSource.normaliseAddress("http://10.0.2.2:8081/custom/path"), null);
        eq("has query", XtreamSource.normaliseAddress("http://10.0.2.2:8081?foo=bar"), null);
        eq("has fragment", XtreamSource.normaliseAddress("http://10.0.2.2:8081#frag"), null);

        // 2. Stream URL template & template contains no login
        String tmpl = XtreamSource.buildTemplate("1001", "mp4");
        eq("template format", tmpl, "{BASE}/movie/{USER}/{PASS}/1001.mp4");
        eq("template contains BASE", tmpl.contains("{BASE}"), true);
        eq("template contains USER", tmpl.contains("{USER}"), true);
        eq("template contains PASS", tmpl.contains("{PASS}"), true);
        eq("template has no demo user", tmpl.contains("demo"), false);
        eq("template has no demo pass", tmpl.contains("demo123"), false);
        eq("template with leading dot ext", XtreamSource.buildTemplate("1002", ".mkv"), "{BASE}/movie/{USER}/{PASS}/1002.mkv");
        eq("template empty ext", XtreamSource.buildTemplate("1003", ""), "{BASE}/movie/{USER}/{PASS}/1003");

        // 3. Template join with normal and awkward credentials
        String joinedNormal = XtreamSource.joinUrl(tmpl, "http://10.0.2.2:8081", "demo", "demo123");
        eq("join normal", joinedNormal, "http://10.0.2.2:8081/movie/demo/demo123/1001.mp4");

        // Awkward password: p@ss/w:rd #1
        String joinedAwkward = XtreamSource.joinUrl(tmpl, "http://10.0.2.2:8081", "demo", "p@ss/w:rd #1");
        eq("join awkward password", joinedAwkward, "http://10.0.2.2:8081/movie/demo/p%40ss%2Fw%3Ard%20%231/1001.mp4");
        // Verify no raw slash or hash in password segment to break path parsing
        eq("no raw slash in joined pass", joinedAwkward.split("/").length, 7);
        eq("no raw hash in joined pass", joinedAwkward.contains("#"), false);
        eq("no raw space in joined pass", joinedAwkward.contains(" "), false);

        // Unicode password: සිංහල & 日本語
        String joinedUnicode = XtreamSource.joinUrl(tmpl, "http://10.0.2.2:8081", "user", "සඳගල#1 2024!");
        eq("join unicode password valid", joinedUnicode.startsWith("http://10.0.2.2:8081/movie/user/"), true);
        eq("no raw hash in unicode pass", joinedUnicode.contains("#"), false);
        eq("no raw space in unicode pass", joinedUnicode.contains(" "), false);

        // Awkward username: user@site.com
        String joinedAwkwardUser = XtreamSource.joinUrl(tmpl, "http://10.0.2.2:8081", "u/ser@1", "pass");
        eq("join awkward user", joinedAwkwardUser, "http://10.0.2.2:8081/movie/u%2Fser%401/pass/1001.mp4");

        // Join handles nulls safely
        eq("join null template", XtreamSource.joinUrl(null, "http://a", "u", "p"), null);
        eq("join null base", XtreamSource.joinUrl(tmpl, null, "u", "p"), null);
        eq("join null user", XtreamSource.joinUrl(tmpl, "http://a", null, "p"), null);
        eq("join null pass", XtreamSource.joinUrl(tmpl, "http://a", "u", null), null);

        // 4. Account-state mapping from parsed values
        XtreamSource.Account accOk = XtreamSource.mapAccount(1, "Active");
        eq("map active OK", accOk.state, XtreamSource.Account.OK);
        eq("map active message", accOk.message, "OK");
        eq("map active stateName", accOk.stateName(), "OK");

        XtreamSource.Account accOkCase = XtreamSource.mapAccount(1, "active");
        eq("map active case-insensitive", accOkCase.state, XtreamSource.Account.OK);

        XtreamSource.Account accWrongAuth0 = XtreamSource.mapAccount(0, "Active");
        eq("map auth 0 WRONG_LOGIN", accWrongAuth0.state, XtreamSource.Account.WRONG_LOGIN);
        eq("map auth 0 message", accWrongAuth0.message, "That login was not accepted.");
        eq("map auth 0 stateName", accWrongAuth0.stateName(), "WRONG_LOGIN");

        XtreamSource.Account accWrongAuthNeg = XtreamSource.mapAccount(0, null);
        eq("map auth 0 null status", accWrongAuthNeg.state, XtreamSource.Account.WRONG_LOGIN);

        XtreamSource.Account accExpired = XtreamSource.mapAccount(1, "Expired");
        eq("map expired state", accExpired.state, XtreamSource.Account.EXPIRED);
        eq("map expired message", accExpired.message, "This account has expired.");
        eq("map expired stateName", accExpired.stateName(), "EXPIRED");

        XtreamSource.Account accBanned = XtreamSource.mapAccount(1, "Banned");
        eq("map other status EXPIRED", accBanned.state, XtreamSource.Account.EXPIRED);

        XtreamSource.Account accNullStatus = XtreamSource.mapAccount(1, null);
        eq("map null status EXPIRED", accNullStatus.state, XtreamSource.Account.EXPIRED);

        // 5. parseAccountResponse JSON parsing
        String jsonWrong = "{\"user_info\":{\"auth\":0}}";
        XtreamSource.Account parsedWrong = XtreamSource.parseAccountResponse(jsonWrong);
        eq("json auth 0", parsedWrong.state, XtreamSource.Account.WRONG_LOGIN);
        eq("json auth 0 msg", parsedWrong.message, "That login was not accepted.");

        String jsonActive = "{\"user_info\":{\"username\":\"demo\",\"auth\":1,\"status\":\"Active\",\"exp_date\":\"1893456000\"}}";
        XtreamSource.Account parsedActive = XtreamSource.parseAccountResponse(jsonActive);
        eq("json active", parsedActive.state, XtreamSource.Account.OK);
        eq("json active msg", parsedActive.message, "OK");

        String jsonExpired = "{\"user_info\":{\"username\":\"expired\",\"auth\":1,\"status\":\"Expired\"}}";
        XtreamSource.Account parsedExpired = XtreamSource.parseAccountResponse(jsonExpired);
        eq("json expired", parsedExpired.state, XtreamSource.Account.EXPIRED);
        eq("json expired msg", parsedExpired.message, "This account has expired.");

        String jsonStringAuth = "{\"user_info\":{\"auth\":\"0\"}}";
        XtreamSource.Account parsedStrAuth = XtreamSource.parseAccountResponse(jsonStringAuth);
        eq("json string auth 0", parsedStrAuth.state, XtreamSource.Account.WRONG_LOGIN);

        String jsonNoAuth = "{\"error\":\"not found\"}";
        XtreamSource.Account parsedNoAuth = XtreamSource.parseAccountResponse(jsonNoAuth);
        eq("json no auth key", parsedNoAuth.state, XtreamSource.Account.WRONG_LOGIN);

        String jsonEmpty = "";
        XtreamSource.Account parsedEmpty = XtreamSource.parseAccountResponse(jsonEmpty);
        eq("json empty UNREACHABLE", parsedEmpty.state, XtreamSource.Account.UNREACHABLE);

        // 6. Credentials newline rejection (cannot contain \n or \r)
        eq("valid creds", XtreamSource.validCredentials("user", "pass"), true);
        eq("user null", XtreamSource.validCredentials(null, "pass"), false);
        eq("pass null", XtreamSource.validCredentials("user", null), false);
        eq("user newline LF", XtreamSource.validCredentials("user\n", "pass"), false);
        eq("user newline CR", XtreamSource.validCredentials("user\r", "pass"), false);
        eq("user newline CRLF", XtreamSource.validCredentials("user\r\nname", "pass"), false);
        eq("pass newline LF", XtreamSource.validCredentials("user", "pass\n"), false);
        eq("pass newline CR", XtreamSource.validCredentials("user", "pass\r"), false);
        eq("pass newline CRLF", XtreamSource.validCredentials("user", "p\r\nass"), false);

        // 7. Cheap title cleaner
        XtreamSource.CheapTitle ct1 = XtreamSource.cheapClean("COSMIC DAWN");
        eq("cheap title plain", ct1.cleanTitle, "cosmic dawn");
        eq("cheap year plain", ct1.year, 0);

        XtreamSource.CheapTitle ct2 = XtreamSource.cheapClean("The Matrix (1999)");
        eq("cheap title with year", ct2.cleanTitle, "the matrix");
        eq("cheap year extracted", ct2.year, 1999);

        XtreamSource.CheapTitle ct3 = XtreamSource.cheapClean("  IRON   MAN   (2008)  ");
        eq("cheap title spaces collapsed", ct3.cleanTitle, "iron man");
        eq("cheap year trimmed", ct3.year, 2008);

        XtreamSource.CheapTitle ct4 = XtreamSource.cheapClean("(1917)");
        eq("cheap title only year in parens", ct4.cleanTitle, "(1917)");
        eq("cheap year 0 for single year", ct4.year, 0);

        XtreamSource.CheapTitle ct5 = XtreamSource.cheapClean("Blade Runner 2049");
        eq("cheap title no parens year", ct5.cleanTitle, "blade runner 2049");
        eq("cheap year 0 no parens", ct5.year, 0);

        // 8. unescapeJson & parseVodInfo
        eq("unescape plain", XtreamSource.unescapeJson("hello world"), "hello world");
        eq("unescape quotes and newlines", XtreamSource.unescapeJson("line1\\nline2\\\"quoted\\\""), "line1\nline2\"quoted\"");

        String mockVodJson = "{\"info\":{" +
                "\"name\":\"COSMIC DAWN\"," +
                "\"plot\":\"The compelling journey of Cosmic Dawn.\"," +
                "\"genre\":\"Action, Sci-Fi\"," +
                "\"cast\":\"Lead Star, Co-Star\"," +
                "\"duration_secs\":5400," +
                "\"rating\":\"7.8\"," +
                "\"tmdb_id\":\"603\"," +
                "\"backdrop_path\":[\"http://10.0.2.2:8081/posters/poster_1.jpg\"]" +
                "}}";
        XtreamSource.VodInfo vi = XtreamSource.parseVodInfo(mockVodJson);
        eq("parseVodInfo not null", vi != null, true);
        if (vi != null) {
            eq("vod plot", vi.plot, "The compelling journey of Cosmic Dawn.");
            eq("vod genre", vi.genres, "Action, Sci-Fi");
            eq("vod cast", vi.cast, "Lead Star, Co-Star");
            eq("vod runtime mins", vi.runtime, 90);
            eq("vod rating", vi.rating, 7.8);
            eq("vod tmdbId", vi.tmdbId, 603L);
            eq("vod backdrop", vi.backdrop, "http://10.0.2.2:8081/posters/poster_1.jpg");
        }

        System.out.println("XtreamTest: " + checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
