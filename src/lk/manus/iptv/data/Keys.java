package lk.manus.iptv.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Stable item key for an M3U channel: first 16 hex chars of sha1(stream url as UTF-8) - exactly the cloud job's `h` (manus-iptv-data pipeline/common.py). */
public final class Keys {
    private Keys() {}

    public static String of(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(16);
            for (int i = 0; i < 8; i++) b.append(Character.forDigit((d[i] >> 4) & 15, 16)).append(Character.forDigit(d[i] & 15, 16));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-1 is required on every JVM / Android
        }
    }
}
