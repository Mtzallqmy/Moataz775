/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Owns the anonymous installation identity used by the central Moataz Dow service.
 * The random device secret is protected by Android Keystore through SecureTokenStore.
 */
public final class DeviceIdentityStore {
    private static final String PREFS = "moataz_dow_device_identity";
    private static final String KEY_DEVICE_ID = "device_id";

    private final SharedPreferences preferences;
    private final SecureTokenStore secretStore;

    public DeviceIdentityStore(final Context context) {
        final Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        secretStore = new SecureTokenStore(app);
    }

    public synchronized String getOrCreateDeviceId() {
        String value = preferences.getString(KEY_DEVICE_ID, null);
        if (value == null || value.isEmpty()) {
            value = UUID.randomUUID().toString().replace("-", "");
            preferences.edit().putString(KEY_DEVICE_ID, value).commit();
        }
        return value;
    }

    public synchronized String getOrCreateDeviceSecret() throws Exception {
        String value = secretStore.read();
        if (value == null || value.isEmpty()) {
            final byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            value = Base64.encodeToString(random,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            secretStore.save(value);
        }
        return value;
    }

    public synchronized void clear() {
        preferences.edit().clear().apply();
        secretStore.clear();
    }
}
