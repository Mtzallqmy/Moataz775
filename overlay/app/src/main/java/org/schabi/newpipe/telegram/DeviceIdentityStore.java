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
    private static final String PREFS = "moataz_dow_device_identity_v2";
    private static final String KEY_DEVICE_ID = "device_id";

    private final SharedPreferences preferences;
    private final SecureTokenStore secretStore;

    public DeviceIdentityStore(final Context context) {
        final Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        secretStore = new SecureTokenStore(app);
    }

    /**
     * Returns a complete identity atomically. If Android Keystore data became unreadable after an
     * upgrade, restore, or device security change, both parts are regenerated together so the app
     * never sends an old device id with a different secret.
     */
    public synchronized PairingApiClient.Identity getOrCreateIdentity() throws Exception {
        String deviceId = preferences.getString(KEY_DEVICE_ID, null);
        String deviceSecret;
        try {
            deviceSecret = secretStore.read();
        } catch (final Exception unreadableSecret) {
            clear();
            deviceId = null;
            deviceSecret = null;
        }

        if (deviceId == null || deviceId.isEmpty() || deviceSecret == null
                || deviceSecret.length() < 32) {
            deviceId = UUID.randomUUID().toString().replace("-", "");
            final byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            deviceSecret = Base64.encodeToString(random,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);

            secretStore.clear();
            secretStore.save(deviceSecret);
            final boolean saved = preferences.edit().putString(KEY_DEVICE_ID, deviceId).commit();
            if (!saved) {
                secretStore.clear();
                throw new IllegalStateException("Unable to persist the device identity");
            }
        }

        return new PairingApiClient.Identity(deviceId, deviceSecret);
    }

    public synchronized String getOrCreateDeviceId() throws Exception {
        return getOrCreateIdentity().deviceId;
    }

    public synchronized String getOrCreateDeviceSecret() throws Exception {
        return getOrCreateIdentity().deviceSecret;
    }

    public synchronized void clear() {
        preferences.edit().clear().commit();
        secretStore.clear();
    }
}
