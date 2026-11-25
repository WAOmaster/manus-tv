package lk.manus.iptv.player;

import java.util.HashSet;
import java.util.Set;

/**
 * What a mini-preview failure means. Before: any decoder error switched the preview off for the TV for good, so one channel with
 * an odd stream (ERROR_CODE_DECODING_FAILED) killed it until the user turned it on again, and the next odd channel killed it again.
 * Now a stream-level failure only skips that channel (its logo shows); the TV-level verdict needs evidence that the TV cannot run
 * a second decoder: a software decoder picked, or the hardware decoder failing to start twice, or stream failures on several
 * different channels in one session. Pure Java, tested in MiniPolicyTest. UI thread only.
 */
public final class MiniPolicy {
    public static final int SKIP_CHANNEL = 1, BLOCK_TV = 2;
    /** Different channels that must fail in one session before the TV is judged unable. */
    static final int CHANNELS_TO_BLOCK = 3;
    /** Decoder start failures (e.g. the main player briefly holding both instances while tuning) before the TV is judged unable. */
    static final int INITS_TO_BLOCK = 2;

    private final Set<String> skipped = new HashSet<>(), decodeFailed = new HashSet<>();
    private int inits;

    /** True when this channel failed in the preview this session: show its logo, do not try again. */
    public boolean skip(String url) { return skipped.contains(url); }

    /** Records a failure of channel {@code url}; returns SKIP_CHANNEL or BLOCK_TV. {@code why} = Media3 error code name or "software decoder …". */
    public int onFail(String why, String url) {
        String w = why == null ? "" : why;
        if (w.startsWith("software decoder")) return BLOCK_TV; // the second decoder is a software one: too heavy for a TV, whatever the channel
        if (w.equals("ERROR_CODE_DECODER_INIT_FAILED") || w.equals("ERROR_CODE_DECODER_QUERY_FAILED")) {
            skipped.add(url);
            return ++inits >= INITS_TO_BLOCK ? BLOCK_TV : SKIP_CHANNEL;
        }
        skipped.add(url); // decoding failed / format unsupported / exceeds capabilities / network: this stream, not the TV
        if (!w.startsWith("ERROR_CODE_DECODING")) return SKIP_CHANNEL; // network and stream errors never count against the TV
        decodeFailed.add(url);
        return decodeFailed.size() >= CHANNELS_TO_BLOCK ? BLOCK_TV : SKIP_CHANNEL;
    }

    /** The user turned the preview back on, or a new session: start counting again. */
    public void reset() { skipped.clear(); decodeFailed.clear(); inits = 0; }
}
