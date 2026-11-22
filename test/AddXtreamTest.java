import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lk.manus.iptv.data.AddServer;
import lk.manus.iptv.data.XtreamSource;

/**
 * Pure Java unit tests for Xtream phone page form parsing, default name extraction,
 * and message mappings (T85 requirement 5).
 */
public final class AddXtreamTest {
    static int fails, checks;

    static void eq(String what, Object got, Object want) {
        checks++;
        if (!Objects.equals(want, got)) {
            fails++;
            System.out.println("FAIL " + what + ": got [" + got + "], want [" + want + "]");
        }
    }

    public static void main(String[] args) {
        // 1. Standard form field names
        String body1 = "name=My+Server&server=http%3A%2F%2F10.0.2.2%3A8081&username=demo&password=demo123&code=123456";
        AddServer.XtreamForm f1 = AddServer.parseXtreamForm(body1);
        eq("standard name", f1.name, "My Server");
        eq("standard server", f1.server, "http://10.0.2.2:8081");
        eq("standard user", f1.user, "demo");
        eq("standard pass", f1.pass, "demo123");
        eq("standard code", f1.code, "123456");

        // 2. Alias field names: address, user, pass
        String body2 = "address=http%3A%2F%2Fexample.com%3A8000&user=alice&pass=secret456&code=654321";
        AddServer.XtreamForm f2 = AddServer.parseXtreamForm(body2);
        eq("alias name empty", f2.name, "");
        eq("alias address -> server", f2.server, "http://example.com:8000");
        eq("alias user -> username", f2.user, "alice");
        eq("alias pass -> password", f2.pass, "secret456");
        eq("alias code", f2.code, "654321");

        // 3. Alias url -> server
        String body3 = "url=https%3A%2F%2Fprovider.tv&username=bob&password=pass789&code=112233";
        AddServer.XtreamForm f3 = AddServer.parseXtreamForm(body3);
        eq("alias url -> server", f3.server, "https://provider.tv");

        // 4. Awkward and unicode credentials in form body
        String body4 = "server=http%3A%2F%2F10.0.2.2%3A8081&user=u%2Fser%401&pass=p%40ss%2Fw%3Ard%20%231&code=998877";
        AddServer.XtreamForm f4 = AddServer.parseXtreamForm(body4);
        eq("awkward user decoded", f4.user, "u/ser@1");
        eq("awkward pass decoded", f4.pass, "p@ss/w:rd #1");

        String body5 = "server=http%3A%2F%2F10.0.2.2%3A8081&user=demo&pass=%E0%B7%83%E0%B6%B6%E0%B6%AF%231+2024%21&code=554433";
        AddServer.XtreamForm f5 = AddServer.parseXtreamForm(body5);
        eq("unicode pass decoded", f5.pass, "සබද#1 2024!");

        // 5. Empty and null bodies handled without exception
        AddServer.XtreamForm fEmpty = AddServer.parseXtreamForm("");
        eq("empty body name", fEmpty.name, "");
        eq("empty body server", fEmpty.server, "");
        eq("empty body user", fEmpty.user, "");
        eq("empty body pass", fEmpty.pass, "");
        eq("empty body code", fEmpty.code, "");

        AddServer.XtreamForm fNull = AddServer.parseXtreamForm(null);
        eq("null body server", fNull.server, "");

        // 6. Direct map parsing
        Map<String, String> map = new HashMap<>();
        map.put("name", "Custom");
        map.put("server", "http://10.0.2.2:8081");
        map.put("username", "user1");
        map.put("password", "pass1");
        map.put("code", "123123");
        AddServer.XtreamForm fMap = AddServer.parseXtreamMap(map);
        eq("map parse name", fMap.name, "Custom");
        eq("map parse server", fMap.server, "http://10.0.2.2:8081");

        // 7. Default name logic: default = host from server address
        eq("explicit name kept", AddServer.defaultXtreamName("Custom Server", "http://10.0.2.2:8081"), "Custom Server");
        eq("empty name uses host", AddServer.defaultXtreamName("", "http://10.0.2.2:8081"), "10.0.2.2");
        eq("whitespace name uses host", AddServer.defaultXtreamName("   ", "http://10.0.2.2:8081"), "10.0.2.2");
        eq("null name uses host", AddServer.defaultXtreamName(null, "http://10.0.2.2:8081"), "10.0.2.2");
        eq("domain host", AddServer.defaultXtreamName(null, "https://xtream.provider.com:8443/player_api.php"), "xtream.provider.com");
        eq("host with trailing slash", AddServer.defaultXtreamName("", "http://my-iptv.net/"), "my-iptv.net");
        eq("null server returns empty", AddServer.defaultXtreamName(null, null), "");
        eq("malformed server fallback", AddServer.defaultXtreamName(null, "somehost:8080/"), "somehost");

        // 8. Message mappings per T85 spec
        eq("OK message", AddServer.mapXtreamMessage(XtreamSource.Account.OK), "Added. Your films are loading on the TV.");
        eq("WRONG_LOGIN message", AddServer.mapXtreamMessage(XtreamSource.Account.WRONG_LOGIN), "That login was not accepted.");
        eq("EXPIRED message", AddServer.mapXtreamMessage(XtreamSource.Account.EXPIRED), "This account has expired.");
        eq("UNREACHABLE message", AddServer.mapXtreamMessage(XtreamSource.Account.UNREACHABLE), "Can't reach that server.");
        eq("BAD_ADDRESS message", AddServer.mapXtreamMessage(XtreamSource.Account.BAD_ADDRESS), "That address doesn't look right.");
        eq("UNKNOWN state message", AddServer.mapXtreamMessage(99), "That login was not accepted.");

        System.out.println("AddXtreamTest: " + checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
