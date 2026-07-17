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

public final class SecureTokenStore {
    private static final String PREFS = "moataz_dow_telegram_secure";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_IV = "iv";
    private static final String KEY_ALIAS_AES = "moataz_dow_telegram_aes";
    private static final String KEY_ALIAS_RSA = "moataz_dow_telegram_rsa";

    private final Context context;
    private final SharedPreferences preferences;

    public SecureTokenStore(final Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(final String token) throws Exception {
        final String normalized = token == null ? "" : token.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Bot token is empty");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            saveWithAes(normalized);
        } else {
            saveWithRsa(normalized);
        }
    }

    public synchronized String read() throws Exception {
        final String encoded = preferences.getString(KEY_TOKEN, null);
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return readWithAes(encoded);
        }
        return readWithRsa(encoded);
    }

    public synchronized boolean hasToken() {
        return preferences.contains(KEY_TOKEN);
    }

    public synchronized void clear() {
        preferences.edit().remove(KEY_TOKEN).remove(KEY_IV).apply();
    }

    private void saveWithAes(final String token) throws Exception {
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
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        preferences.edit()
                .putString(KEY_TOKEN, Base64.encodeToString(
                        cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    private String readWithAes(final String encoded) throws Exception {
        final String encodedIv = preferences.getString(KEY_IV, null);
        if (encodedIv == null) {
            return null;
        }
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        final SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS_AES, null);
        if (key == null) {
            return null;
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
                .setSubject(new X500Principal("CN=Moataz Dow Telegram"))
                .setSerialNumber(new BigInteger(64, new SecureRandom()))
                .setStartDate(start.getTime())
                .setEndDate(end.getTime())
                .build();
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
        generator.initialize(spec);
        generator.generateKeyPair();
    }

    private void saveWithRsa(final String token) throws Exception {
        ensureLegacyRsaKey();
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        final PublicKey publicKey = keyStore.getCertificate(KEY_ALIAS_RSA).getPublicKey();
        final Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        preferences.edit()
                .putString(KEY_TOKEN, Base64.encodeToString(
                        cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        Base64.NO_WRAP))
                .remove(KEY_IV)
                .apply();
    }

    private String readWithRsa(final String encoded) throws Exception {
        ensureLegacyRsaKey();
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        final PrivateKey privateKey = (PrivateKey) keyStore.getKey(KEY_ALIAS_RSA, null);
        final Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return new String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
