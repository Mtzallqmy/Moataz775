/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Calendar;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.security.auth.x500.X500Principal;

/**
 * Stores the anonymous device secret used by the Moataz Dow pairing service.
 *
 * <p>The preferences and Android Keystore aliases are intentionally different from the aliases
 * used by the earlier per-user bot-token prototype. This prevents an old encrypted bot token from
 * being interpreted as a device credential after an application update.</p>
 */
public final class SecureTokenStore {
    private static final String PREFS = "moataz_dow_device_secret_secure_v2";
    private static final String KEY_VALUE = "encrypted_value";
    private static final String KEY_IV = "iv";
    private static final String KEY_ALIAS_AES = "moataz_dow_device_secret_aes_v2";
    private static final String KEY_ALIAS_RSA = "moataz_dow_device_secret_rsa_v2";

    private final Context context;
    private final SharedPreferences preferences;

    public SecureTokenStore(final Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(final String value) throws Exception {
        final String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Device secret is empty");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            saveWithAes(normalized);
        } else {
            saveWithRsa(normalized);
        }
    }

    public synchronized String read() throws Exception {
        final String encoded = preferences.getString(KEY_VALUE, null);
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return readWithAes(encoded);
        }
        return readWithRsa(encoded);
    }

    public synchronized boolean hasToken() {
        return preferences.contains(KEY_VALUE);
    }

    public synchronized void clear() {
        preferences.edit().clear().commit();
        deleteKey(KEY_ALIAS_AES);
        deleteKey(KEY_ALIAS_RSA);
    }

    private static void deleteKey(final String alias) {
        try {
            final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias);
            }
        } catch (final Exception ignored) {
            // Clearing preferences is sufficient to make any remaining key unreachable.
        }
    }

    private void saveWithAes(final String value) throws Exception {
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS_AES)) {
            final KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS_AES,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        final SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS_AES, null);
        if (key == null) {
            throw new IllegalStateException("Android Keystore did not return the device key");
        }
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        final boolean saved = preferences.edit()
                .putString(KEY_VALUE, Base64.encodeToString(
                        cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .commit();
        if (!saved) {
            throw new IllegalStateException("Unable to persist the encrypted device secret");
        }
    }

    private String readWithAes(final String encoded) throws Exception {
        final String encodedIv = preferences.getString(KEY_IV, null);
        if (encodedIv == null || encodedIv.isEmpty()) {
            throw new IllegalStateException("Encrypted device secret is missing its IV");
        }
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        final SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS_AES, null);
        if (key == null) {
            throw new IllegalStateException("Encrypted device key is unavailable");
        }
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("deprecation")
    private void ensureLegacyRsaKey() throws Exception {
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS_RSA)) {
            return;
        }
        final Calendar start = Calendar.getInstance();
        final Calendar end = Calendar.getInstance();
        end.add(Calendar.YEAR, 30);
        final KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(context)
                .setAlias(KEY_ALIAS_RSA)
                .setSubject(new X500Principal("CN=Moataz Dow Device"))
                .setSerialNumber(new BigInteger(64, new SecureRandom()))
                .setStartDate(start.getTime())
                .setEndDate(end.getTime())
                .build();
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
        generator.initialize(spec);
        generator.generateKeyPair();
    }

    private void saveWithRsa(final String value) throws Exception {
        ensureLegacyRsaKey();
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        final PublicKey publicKey = keyStore.getCertificate(KEY_ALIAS_RSA).getPublicKey();
        final Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        final boolean saved = preferences.edit()
                .putString(KEY_VALUE, Base64.encodeToString(
                        cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        Base64.NO_WRAP))
                .remove(KEY_IV)
                .commit();
        if (!saved) {
            throw new IllegalStateException("Unable to persist the encrypted device secret");
        }
    }

    private String readWithRsa(final String encoded) throws Exception {
        ensureLegacyRsaKey();
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        final PrivateKey privateKey = (PrivateKey) keyStore.getKey(KEY_ALIAS_RSA, null);
        if (privateKey == null) {
            throw new IllegalStateException("Encrypted device key is unavailable");
        }
        final Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return new String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
