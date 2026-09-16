// SPDX-License-Identifier: GPL-3.0-or-later
package cl.coders.movingtraveler;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.SharedPreferences;
import android.content.ContextWrapper;
import android.util.Base64;
import java.security.GeneralSecurityException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Real AES-GCM + SharedPreferences fixtures with a substituted key provider; no device Keystore emulation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class RoutingCredentialsTest {
    @Before @After public void clearFixtureState() {
        preferences().edit().clear().commit();
        ReflectionHelpers.setStaticField(RoutingCredentials.class, "cachedEnvelope", null);
        ReflectionHelpers.setStaticField(RoutingCredentials.class, "cachedPlain", "");
    }

    @Test public void keyValidationRejectsMalformedAndUnsafeValues() {
        assertTrue(RoutingCredentials.valid("test_fixture_key_not_real_123456"));
        assertTrue(RoutingCredentials.valid(repeat('a', 16)));
        assertTrue(RoutingCredentials.valid(repeat('a', 128)));
        for (String value : new String[]{null, "", repeat('a', 15), repeat('a', 129),
                "test fixture with spaces", "test_fixture_key\ntrailing", "test_fixture_key&another=value",
                "test_fixture_key/../path", "test_fixture_key:secret", "test_fixture_key_東京"}) {
            assertFalse(RoutingCredentials.valid(value));
        }
    }

    @Test @Config(sdk = 23)
    public void absentPersonalKeyUsesOnlyValidPublisherConfigurationOnApi23() {
        String actual = RoutingCredentials.get(application());
        String expected = RoutingCredentials.valid(BuildConfig.GEOAPIFY_API_KEY) ? BuildConfig.GEOAPIFY_API_KEY : "";
        assertTrue("Absent personal configuration must return only the validated build setting", actual.equals(expected));
        assertFalse(preferences().contains("sealed"));
    }

    @Test public void malformedCiphertextFailsClosedInsteadOfFallingBackToAnotherKey() {
        for (String envelope : new String[]{"", "not-an-envelope", ":", "a:b:c", "AA==:AA==",
                "AAAAAAAAAAAAAAAA:invalid!base64", repeat('x', 2050) + ":AA=="}) {
            preferences().edit().putString("sealed", envelope).commit();
            assertTrue("Malformed ciphertext must not return access credentials", RoutingCredentials.get(application()).isEmpty());
            assertEquals(envelope, preferences().getString("sealed", ""));
        }
    }

    @Test public void changedCiphertextCannotReusePreviouslyCachedPlaintext() {
        ReflectionHelpers.setStaticField(RoutingCredentials.class, "cachedEnvelope", "old:envelope");
        ReflectionHelpers.setStaticField(RoutingCredentials.class, "cachedPlain", "test_fixture_key_not_real_123456");
        preferences().edit().putString("sealed", "different-corrupt-envelope").commit();
        assertTrue(RoutingCredentials.get(application()).isEmpty());
    }

    @Test public void invalidSaveDoesNotCreatePlaintextOrOverwriteExistingAccess() throws Exception {
        preferences().edit().putString("sealed", "existing:encrypted-value").commit();
        try {
            RoutingCredentials.save(application(), "invalid key with spaces");
            fail("Invalid key should be rejected before accessing Android Keystore");
        } catch (IllegalArgumentException expected) {
            assertEquals("existing:encrypted-value", preferences().getString("sealed", ""));
            assertEquals(1, preferences().getAll().size());
        }
    }

    @Test public void clearRemovesPersonalEnvelopeAndCachedAccess() {
        preferences().edit().putString("sealed", "old:envelope").commit();
        ReflectionHelpers.setStaticField(RoutingCredentials.class, "cachedEnvelope", "old:envelope");
        ReflectionHelpers.setStaticField(RoutingCredentials.class, "cachedPlain", "test_fixture_key_not_real_123456");
        RoutingCredentials.clear(application());
        assertFalse(preferences().contains("sealed"));
        assertNull(ReflectionHelpers.getStaticField(RoutingCredentials.class, "cachedEnvelope"));
        assertEquals("", ReflectionHelpers.getStaticField(RoutingCredentials.class, "cachedPlain"));
    }

    @Test public void encryptedKeyReopensAfterMemoryCacheIsLost() throws Exception {
        FixtureKeyStore keys = new FixtureKeyStore();
        String value = "test_fixture_key_not_real_persistent_1234";
        RoutingCredentials.save(application(), value, keys);
        assertTrue(RoutingCredentials.hasSavedPersonalKey(application()));
        String envelope = preferences().getString("sealed", "");
        assertFalse(envelope.contains(value));
        assertEquals("Only the authenticated encrypted envelope is persisted", 1, preferences().getAll().size());
        forgetMemory();
        assertEquals(value, RoutingCredentials.get(new ContextWrapper(application()), keys));
        assertEquals(envelope, preferences().getString("sealed", ""));
        assertEquals("The persisted key was re-opened both to verify save and after losing the cache", 2, keys.reads);
    }

    @Test public void repeatedReadsReuseThePersistedEnvelopeAndRecoverAfterCacheLoss() throws Exception {
        FixtureKeyStore keys = new FixtureKeyStore();
        String value = "test_fixture_key_not_real_endpoint_changes";
        RoutingCredentials.save(application(), value, keys);
        String envelope = preferences().getString("sealed", "");
        for (int i = 0; i < 20; i++) {
            assertEquals(value, RoutingCredentials.get(application(), keys));
            assertEquals(envelope, preferences().getString("sealed", ""));
        }
        forgetMemory();
        assertEquals(value, RoutingCredentials.get(application(), keys));
        assertEquals(2, keys.reads);
    }

    @Test public void unreadableStorageRemainsSavedAndRecoversAfterTransientKeyStoreFailure() throws Exception {
        FixtureKeyStore keys = new FixtureKeyStore();
        String value = "test_fixture_key_not_real_transient_recovery";
        RoutingCredentials.save(application(), value, keys);
        String envelope = preferences().getString("sealed", "");
        forgetMemory(); keys.unavailable = true;
        assertEquals("", RoutingCredentials.get(application(), keys));
        assertTrue("An unavailable key must not look like an intentionally deleted key", RoutingCredentials.hasSavedPersonalKey(application()));
        assertEquals(envelope, preferences().getString("sealed", ""));
        keys.unavailable = false;
        assertEquals(value, RoutingCredentials.get(application(), keys));
    }

    @Test public void failedDurabilityVerificationDoesNotReplaceExistingSavedKey() throws Exception {
        FixtureKeyStore keys = new FixtureKeyStore();
        String previous = "test_fixture_key_not_real_before_failed_save";
        RoutingCredentials.save(application(), previous, keys);
        String envelope = preferences().getString("sealed", "");
        keys.unavailable = true;
        try {
            RoutingCredentials.save(application(), "test_fixture_key_not_real_new_access", keys);
            fail("A key that cannot be re-opened must not be presented as durably saved");
        } catch (GeneralSecurityException expected) {
            assertEquals(envelope, preferences().getString("sealed", ""));
        }
        keys.unavailable = false; forgetMemory();
        assertEquals(previous, RoutingCredentials.get(application(), keys));
    }

    @Test public void replacingAccessKeepsItEncryptedAndUsesTheNewValueAfterReopen() throws Exception {
        FixtureKeyStore keys = new FixtureKeyStore();
        RoutingCredentials.save(application(), "test_fixture_key_not_real_original", keys);
        String before = preferences().getString("sealed", "");
        String replacement = "test_fixture_key_not_real_replacement";
        RoutingCredentials.save(application(), replacement, keys);
        String after = preferences().getString("sealed", "");
        assertNotEquals(before, after); assertFalse(after.contains(replacement));
        forgetMemory(); assertEquals(replacement, RoutingCredentials.get(application(), keys));
    }

    @Test public void tamperingIsRejectedWithoutSilentlyDiscardingTheSavedEnvelope() throws Exception {
        FixtureKeyStore keys = new FixtureKeyStore();
        RoutingCredentials.save(application(), "test_fixture_key_not_real_authenticated", keys);
        String[] parts = preferences().getString("sealed", "").split(":");
        byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP); ciphertext[0] ^= 1;
        String tampered = parts[0] + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
        preferences().edit().putString("sealed", tampered).commit(); forgetMemory();
        assertEquals("", RoutingCredentials.get(application(), keys));
        assertTrue(RoutingCredentials.hasSavedPersonalKey(application()));
        assertEquals(tampered, preferences().getString("sealed", ""));
    }

    @Test public void unexpectedPreferenceTypeFailsClosedAndRemainsDiagnosable() {
        preferences().edit().putInt("sealed", 42).commit();
        assertTrue(RoutingCredentials.hasSavedPersonalKey(application()));
        assertEquals("", RoutingCredentials.get(application()));
        assertEquals(42, preferences().getInt("sealed", 0));
    }

    private static void forgetMemory() {
        ReflectionHelpers.setStaticField(RoutingCredentials.class, "cachedEnvelope", null);
        ReflectionHelpers.setStaticField(RoutingCredentials.class, "cachedPlain", "");
    }

    private static final class FixtureKeyStore implements RoutingCredentials.KeyProvider {
        final SecretKey key;
        boolean unavailable;
        int reads;
        FixtureKeyStore() throws Exception {
            KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256); key = generator.generateKey();
        }
        @Override public SecretKey load(boolean create) throws Exception {
            if (!create) {
                reads++;
                if (unavailable) throw new GeneralSecurityException("Fixture temporary failure");
            }
            return key;
        }
    }

    private static Application application() { return RuntimeEnvironment.getApplication(); }
    private static SharedPreferences preferences() { return application().getSharedPreferences("routingAccess", 0); }
    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) text.append(value);
        return text.toString();
    }
}
