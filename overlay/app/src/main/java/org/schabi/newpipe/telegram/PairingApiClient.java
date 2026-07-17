/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.telegram;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.BuildConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HTTPS client for the central Moataz Dow pairing and task service. */
public final class PairingApiClient {
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 25000;

    public static final class Identity {
        public final String deviceId;
        public final String deviceSecret;

        public Identity(final String deviceId, final String deviceSecret) {
            this.deviceId = deviceId;
            this.deviceSecret = deviceSecret;
        }
    }

    public static final class PairCode {
        public final String code;
        public final String botUrl;
        public final String botUsername;
        public final String expiresAt;

        PairCode(final JSONObject object) {
            code = object.optString("code");
            botUrl = object.optString("bot_url");
            botUsername = object.optString("bot_username");
            expiresAt = object.optString("expires_at");
        }
    }

    public static final class PairStatus {
        public final boolean paired;
        public final String telegramUsername;
        public final String pairedAt;

        PairStatus(final JSONObject object) {
            paired = object.optBoolean("paired");
            telegramUsername = object.optString("telegram_username");
            pairedAt = object.optString("paired_at");
        }
    }

    public void register(final Identity identity) throws Exception {
        final JSONObject body = new JSONObject()
                .put("device_id", identity.deviceId)
                .put("device_secret", identity.deviceSecret);
        request("POST", "/v1/device/register", null, body);
    }

    public PairCode createPairCode(final Identity identity) throws Exception {
        return new PairCode(request("POST", "/v1/pair/code", identity, new JSONObject()));
    }

    public PairStatus status(final Identity identity) throws Exception {
        return new PairStatus(request("GET", "/v1/device/status", identity, null));
    }

    public void unpair(final Identity identity) throws Exception {
        request("POST", "/v1/device/unpair", identity, new JSONObject());
    }

    public JSONArray tasks(final Identity identity) throws Exception {
        return request("GET", "/v1/device/tasks", identity, null).optJSONArray("tasks");
    }

    public void acknowledge(final Identity identity, final String taskId,
                            final boolean success, final String error) throws Exception {
        final JSONObject body = new JSONObject()
                .put("task_id", taskId)
                .put("success", success);
        if (!success && error != null) {
            body.put("error", error);
        }
        request("POST", "/v1/device/ack", identity, body);
    }

    private JSONObject request(final String method, final String path,
                               final Identity identity, final JSONObject body) throws Exception {
        final String base = BuildConfig.MOATAZ_API_BASE_URL.replaceAll("/+$", "");
        if (!base.startsWith("https://")) {
            throw new IllegalStateException("Moataz Dow API must use HTTPS");
        }
        final HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Moataz-Dow-Android/1.0");
        if (identity != null) {
            connection.setRequestProperty("X-Device-ID", identity.deviceId);
            connection.setRequestProperty("X-Device-Secret", identity.deviceSecret);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            final int code = connection.getResponseCode();
            final InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            final String text = readFully(stream);
            final JSONObject response = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            if (code < 200 || code >= 300 || response.has("error")) {
                throw new IllegalStateException(response.optString("error",
                        "Moataz Dow API request failed (HTTP " + code + ")"));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String readFully(final InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        final StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }
}
