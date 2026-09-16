// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Personal routing access is encrypted with a non-exportable Android Keystore key. */
final class RoutingCredentials {
    private static final String ALIAS = "moving_traveler_routing_v1";
    private static String cachedEnvelope, cachedPlain = "";
    private static final KeyProvider ANDROID_KEYS = create -> {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        SecretKey key = (SecretKey) store.getKey(ALIAS, null);
        if (key == null && create) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
            key = generator.generateKey();
        }
        return key;
    };
    private RoutingCredentials() { }

    /** Only key acquisition is substituted in fixtures; persistence and AES-GCM remain real. */
    interface KeyProvider { SecretKey load(boolean create) throws Exception; }

    static boolean valid(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{16,128}");
    }

    /** Presence is distinct from authorization and from a temporarily unavailable Keystore. */
    static boolean hasSavedPersonalKey(Context context) {
        return preferences(context).contains("sealed");
    }

    static synchronized String get(Context context) { return get(context, ANDROID_KEYS); }

    static synchronized String get(Context context, KeyProvider keys) {
        SharedPreferences preferences = preferences(context);
        // Only a genuinely absent personal value may fall back to publisher configuration.
        if (!preferences.contains("sealed"))
            return valid(BuildConfig.GEOAPIFY_API_KEY) ? BuildConfig.GEOAPIFY_API_KEY : "";
        try {
            String envelope = preferences.getString("sealed", "");
            if (envelope == null || envelope.isEmpty()) return "";
            if (envelope.equals(cachedEnvelope) && valid(cachedPlain)) return cachedPlain;
            String plain = decrypt(envelope, keys);
            cachedEnvelope = envelope; cachedPlain = plain;
            return plain;
        } catch (Exception unavailable) {
            // Keep the saved envelope so transient failures can recover. Never erase credentials,
            // fall back to plaintext, or expose provider/Keystore exception messages.
            return "";
        }
    }

    static synchronized void save(Context context, String value) throws Exception { save(context, value, ANDROID_KEYS); }

    static synchronized void save(Context context, String value, KeyProvider keys) throws Exception {
        if (!valid(value)) throw new IllegalArgumentException("Enter the API key from your Geoapify project.");
        SecretKey key = keys.load(true);
        if (key == null) throw new IllegalStateException("Secure storage is unavailable.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        String envelope = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        // Re-open the stored Keystore key instead of assuming the generated key handle is durable.
        // A failed verification leaves any previously saved access untouched.
        if (!value.equals(decrypt(envelope, keys)))
            throw new IllegalStateException("Secure storage could not be verified.");
        SharedPreferences preferences = preferences(context);
        if (!preferences.edit().putString("sealed", envelope).commit())
            throw new IllegalStateException("Routing access could not be saved.");
        if (!envelope.equals(preferences.getString("sealed", "")))
            throw new IllegalStateException("Routing access could not be verified.");
        cachedEnvelope = envelope; cachedPlain = value;
    }

    private static String decrypt(String envelope, KeyProvider keys) throws Exception {
        if (envelope == null || envelope.length() > 2048) throw new IllegalArgumentException("Invalid secure storage.");
        String[] parts = envelope.split(":", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid secure storage.");
        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
        if (iv.length != 12 || encrypted.length < 16) throw new IllegalArgumentException("Invalid secure storage.");
        SecretKey key = keys.load(false);
        if (key == null) throw new IllegalStateException("Secure storage is unavailable.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        String plain = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        if (!valid(plain)) throw new IllegalArgumentException("Invalid secure storage.");
        return plain;
    }

    static synchronized void clear(Context context) {
        preferences(context).edit().remove("sealed").apply();
        cachedEnvelope = null; cachedPlain = "";
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences("routingAccess", Context.MODE_PRIVATE);
    }
}
