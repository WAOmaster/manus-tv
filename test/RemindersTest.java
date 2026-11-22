import java.util.ArrayList;
import java.util.List;
import lk.manus.iptv.data.Reminders;
import lk.manus.iptv.data.Reminders.Item;

/** Due / late / next-timer arithmetic of the film reminders (pure Java). */
public final class RemindersTest {
    static int fails, checks;
    static void eq(String what, Object got, Object want) { checks++; if (!want.equals(got)) { fails++; System.out.println("FAIL " + what + ": got " + got + ", want " + want); } }

    public static void main(String[] a) {
        long start = 1000000L, t = start * 1000; // start in s, now in ms
        eq("far", Reminders.due(start, t - 31000), false);
        eq("30 s away", Reminders.due(start, t - 30000), true);
        eq("at start", Reminders.due(start, t), true);
        eq("9m59 late", Reminders.due(start, t + 599000), true);
        eq("10m late is not due", Reminders.due(start, t + 600000), false);
        eq("10m late is expired", Reminders.expired(start, t + 600000), true);
        eq("9m59 not expired", Reminders.expired(start, t + 599000), false);
        List<Item> l = new ArrayList<>();
        eq("empty", Reminders.nextDelay(l, t), -1L);
        l.add(new Item("a", "A", "X", start + 600)); // 10 min after start
        l.add(new Item("b", "B", "X", start));
        eq("soonest wins", Reminders.nextDelay(l, t - 100000), 70000L);
        eq("one is due now", Reminders.nextDelay(l, t - 10000), 0L);
        eq("late but unexpired", Reminders.nextDelay(l, t + 599000), 0L);
        eq("expired ignored, the next one waits", Reminders.nextDelay(l, t + 600000), 0L); // a is already due (start + 600 s - 30 s < now)
        eq("next far away", Reminders.nextDelay(l, t - 3600000), 3600000L - 30000L);
        List<Item> e = new ArrayList<>();
        e.add(new Item("c", "C", "X", start));
        eq("only expired", Reminders.nextDelay(e, t + 700000), -1L);
        System.out.println("RemindersTest: " + checks + " checks, " + fails + " failures");
        if (fails > 0) System.exit(1);
    }
}
