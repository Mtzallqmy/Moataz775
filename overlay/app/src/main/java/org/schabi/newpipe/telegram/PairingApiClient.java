/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.telegram;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.BuildConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/** HTTPS client for the central Moataz Dow pairing and task service. */
public final class PairingApiClient {
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 20000;

    public static final class ApiException extends Exception {
        public final String code;
        public final int httpStatus;

        ApiException(final String code, final int httpStatus, final String message) {
            super(message);
            this.code = code;
            this.httpStatus = httpStatus;
        }
    }

    public static final class Identity {
        public final String deviceId;
        public final String deviceSecret;

        public Identity(final String deviceId, final String deviceSecret) {
            this.deviceId = deviceId;
            this.deviceSecret = deviceSecret;
        }
    }

    public static final class Health {
        public final boolean ok;
        public final boolean telegramConfigured;
        public final String apiUrl;

        Health(final JSONObject object) {
            ok = object.optBoolean("ok");
            telegramConfigured = object.optBoolean("telegram_configured");
            apiUrl = object.optString("api_url");
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

    public Health health() throws Exception {
        return new Health(request("GET", "/health", null, null));
    }

    public void register(final Identity identity) throws Exception {
        final JSONObject body = new JSONObject()
                .put("device_id", identity.deviceId)
                .put("device_secret", identity.deviceSecret);
        request("POST", "/v1/device/register", null, body);
    }

    public PairCode createPairCode(final Identity identity) throws Exception {
        final PairCode pairCode = new PairCode(
                request("POST", "/v1/pair/code", identity, new JSONObject()));
        if (pairCode.code.isEmpty()) {
            throw new ApiException("invalid_pair_response", 502,
                    "The pairing service returned no code");
        }
        return pairCode;
    }

    public PairStatus status(final Identity identity) throws Exception {
        return new PairStatus(request("GET", "/v1/device/status", identity, null));
    }

    public void unpair(final Identity identity) throws Exception {
        request("POST", "/v1/device/unpair", identity, new JSONObject());
    }

    public JSONArray tasks(final Identity identity) throws Exception {
        final JSONArray tasks = request("GET", "/v1/device/tasks", identity, null)
                .optJSONArray("tasks");
        return tasks == null ? new JSONArray() : tasks;
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
        final String configuredBase = BuildConfig.MOATAZ_API_BASE_URL == null
                ? "" : BuildConfig.MOATAZ_API_BASE_URL.trim();
        final String base = configuredBase.replaceAll("/+$", "");
        if (!base.startsWith("https://")) {
            throw new ApiException("invalid_api_url", 0,
                    "The Moataz Dow service URL is invalid");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Moataz-Dow-Android/1.1");
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

            final int httpStatus = connection.getResponseCode();
            final InputStream stream = httpStatus >= 200 && httpStatus < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            final String text = readFully(stream);
            final JSONObject response;
            try {
                response = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (final JSONException invalidJson) {
                throw new ApiException("invalid_server_response", httpStatus,
                        "The service returned an invalid response (HTTP " + httpStatus + ")");
            }

            if (httpStatus < 200 || httpStatus >= 300 || response.has("error")) {
                final String code = response.optString("error", "request_failed");
                throw new ApiException(code, httpStatus,
                        response.optString("message", humanMessage(code, httpStatus)));
            }
            return response;
        } catch (final ApiException error) {
            throw error;
        } catch (final UnknownHostException error) {
            throw new ApiException("network_unavailable", 0,
                    "The service address could not be reached. Check the internet connection.");
        } catch (final SocketTimeoutException error) {
            throw new ApiException("network_timeout", 0,
                    "The connection timed out. Try again.");
        } catch (final IOException error) {
            throw new ApiException("network_error", 0,
                    error.getMessage() == null ? "Unable to contact the service" : error.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String humanMessage(final String code, final int httpStatus) {
        switch (code) {
            case "telegram_not_configured":
                return "The Telegram bot has not been configured by the administrator";
            case "device_identity_conflict":
            case "invalid_device_credentials":
                return "The saved device identity is no longer valid";
            case "invalid_device_identity":
                return "The device identity is invalid";
            case "not_found":
                return "The requested service route was not found";
            default:
                return "Moataz Dow service request failed (HTTP " + httpStatus + ")";
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
