import lk.manus.iptv.data.AddServer;

/** What the TV accepts as search text from the phone page: data only, controls stripped, 60 characters at most. */
public final class AddServerTest {
    static int fails, checks;
    static void eq(String what, Object got, Object want) { checks++; if (!want.equals(got)) { fails++; System.out.println("FAIL " + what + ": got [" + got + "], want [" + want + "]"); } }

    public static void main(String[] a) {
        eq("plain", AddServer.clean("Red Dawn"), "Red Dawn");
        eq("null", AddServer.clean(null), "");
        eq("trim and tidy", AddServer.clean("   Red \t\n  Dawn  "), "Red Dawn");
        eq("controls out", AddServer.clean("Red\u0000 Da\u0007wn\u001b[31m"), "Red Dawn[31m");
        eq("line separators out", AddServer.clean("a b c"), "abc");
        eq("direction marks out", AddServer.clean("a‮b"), "ab");
        eq("html is only text", AddServer.clean("<b>Red</b> & Dawn"), "<b>Red</b> & Dawn"); // never rendered: the TV puts it in a text box, the phone page never shows it back
        StringBuilder l = new StringBuilder();
        for (int i = 0; i < 100; i++) l.append('x');
        eq("capped at 60", AddServer.clean(l.toString()).length(), 60);
        StringBuilder e = new StringBuilder();
        for (int i = 0; i < 70; i++) e.appendCodePoint(0x1F3AC);
        eq("capped by characters, not units", AddServer.clean(e.toString()).codePointCount(0, AddServer.clean(e.toString()).length()), 60);
        eq("only controls", AddServer.clean("\u0000\u0001"), "");
        System.out.println("AddServerTest: " + checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
