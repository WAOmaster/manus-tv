import lk.manus.iptv.player.MiniPolicy;

/** Mini preview: one odd channel no longer switches the preview off for the whole TV. */
public class MiniPolicyTest {
    private static int checks, failures;

    private static void eq(String what, Object expected, Object actual) {
        checks++;
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            failures++;
            System.out.println("FAIL " + what + ": expected [" + expected + "] got [" + actual + "]");
        }
    }

    public static void main(String[] args) {
        MiniPolicy p = new MiniPolicy();
        eq("one decoding failure skips only that channel", MiniPolicy.SKIP_CHANNEL, p.onFail("ERROR_CODE_DECODING_FAILED", "a"));
        eq("that channel is skipped", true, p.skip("a"));
        eq("others still preview", false, p.skip("b"));
        eq("same channel again is not a new strike", MiniPolicy.SKIP_CHANNEL, p.onFail("ERROR_CODE_DECODING_FAILED", "a"));
        eq("second channel", MiniPolicy.SKIP_CHANNEL, p.onFail("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED", "b"));
        eq("third different channel -> the TV cannot", MiniPolicy.BLOCK_TV, p.onFail("ERROR_CODE_DECODING_FAILED", "c"));
        p.reset();
        eq("reset clears skips", false, p.skip("a"));
        eq("network errors never block", MiniPolicy.SKIP_CHANNEL, p.onFail("ERROR_CODE_IO_BAD_HTTP_STATUS", "x"));
        p.onFail("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", "y");
        eq("many network errors still never block", MiniPolicy.SKIP_CHANNEL, p.onFail("ERROR_CODE_IO_BAD_HTTP_STATUS", "z"));
        eq("2 network + 1 decoding channel is not 3 decoding channels", MiniPolicy.SKIP_CHANNEL, p.onFail("ERROR_CODE_DECODING_FAILED", "w"));
        p.reset();
        eq("software decoder blocks at once", MiniPolicy.BLOCK_TV, p.onFail("software decoder c2.android.avc.decoder", "a"));
        p.reset();
        eq("one decoder start failure: skip", MiniPolicy.SKIP_CHANNEL, p.onFail("ERROR_CODE_DECODER_INIT_FAILED", "a"));
        eq("second start failure: the TV cannot", MiniPolicy.BLOCK_TV, p.onFail("ERROR_CODE_DECODER_INIT_FAILED", "b"));
        p.reset();
        p.onFail("ERROR_CODE_DECODER_INIT_FAILED", "a");
        eq("init + 1 decoding channel is not 3 decoding channels", MiniPolicy.SKIP_CHANNEL, p.onFail("ERROR_CODE_DECODING_FAILED", "b"));
        System.out.println("MiniPolicyTest: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
