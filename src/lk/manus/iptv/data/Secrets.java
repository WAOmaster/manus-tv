package lk.manus.iptv.data;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android Keystore AES-256-GCM storage for source credentials.
 * Never logs secrets or exception messages; returns null on any failure.
 */
public final class Secrets {
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String ALIAS = "manus.xtream";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // in bits
    private static final int IV_LENGTH = 12; // in bytes

    private Secrets() {}

    private static synchronized SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(PROVIDER);
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build();
            kg.init(spec);
            return kg.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    public static String seal(String plain) {
        if (plain == null) return null;
        try {
            SecretKey key = getOrCreateKey();
            if (key == null) return null;
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = c.getIV();
            byte[] cipherText = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String open(String sealed) {
        if (sealed == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(sealed.trim());
            if (combined.length < IV_LENGTH + 16) return null;
            SecretKey key = getOrCreateKey();
            if (key == null) return null;
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, combined, 0, IV_LENGTH);
            c.init(Cipher.DECRYPT_MODE, key, spec);
            byte[] plainBytes = c.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
