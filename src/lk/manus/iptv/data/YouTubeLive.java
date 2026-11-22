package lk.manus.iptv.data;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Official Sri Lankan YouTube live channels. Not streams: OK hands the channel to the YouTube app. */
public final class YouTubeLive {
    public static final String TV_PACKAGE = "com.google.android.youtube.tv";

    public static final class Entry {
        public final String name, channelId, logo;   // logo: optional URL, null = none
        Entry(String name, String channelId, String logo) { this.name = name; this.channelId = channelId; this.logo = logo; }
        public String liveUrl() { return "https://www.youtube.com/channel/" + channelId + "/live"; }
    }

    public static final List<Entry> ALL = Collections.unmodifiableList(Arrays.asList(
            new Entry("Hiru TV 24x7", "UCfG3Lavv0MFzRP_8-GmusGA", null),
            new Entry("Chat & Music ITN", "UCo7jGFwywOdtesciwW-POqg", null),
            new Entry("Rupavahini", "UCT83ymyAGm7Gnk_4ifxjxIA", null),
            new Entry("ITN", "UCQTcNhAZidy1i9wwmdgf2Lw", null),
            new Entry("Sirasa", "UCn0XmAUFv6d2tofMFEesSNw", null),
            new Entry("Ada Derana", "UCCK3OZi788Ok44K97WAhLKQ", null),
            new Entry("Swarnavahini", "UCaIc6SgS90ud_RgMSC6hW_w", null),
            new Entry("Hiru TV", "UCOtYyt7W5PmPnwQjWWF_Z-Q", null)));

    /** Hands a YouTube link to the YouTube TV app, else to any app that opens it. False = nothing could open it. */
    public static boolean open(android.content.Context c, String url) {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
        try {
            c.startActivity(new android.content.Intent(i).setPackage(TV_PACKAGE));
            return true;
        } catch (android.content.ActivityNotFoundException e) {
            try { c.startActivity(i); return true; } catch (android.content.ActivityNotFoundException e2) { return false; }
        }
    }

    private YouTubeLive() { }
}
