/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.telegram;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class TelegramBotClient {
    private static final String API = "https://api.telegram.org/bot";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 35000;

    public JSONObject getMe(final String token) throws Exception {
        return call(token, "getMe", null).getJSONObject("result");
    }

    public JSONObject getWebhookInfo(final String token) throws Exception {
        return call(token, "getWebhookInfo", null).getJSONObject("result");
    }

    public JSONArray getMyCommands(final String token) throws Exception {
        return call(token, "getMyCommands", null).getJSONArray("result");
    }

    public void setDefaultCommands(final String token) throws Exception {
        final JSONArray commands = new JSONArray()
                .put(new JSONObject().put("command", "start").put("description", "Show help"))
                .put(new JSONObject().put("command", "download").put("description", "Download a media URL"))
                .put(new JSONObject().put("command", "formats").put("description", "Show supported formats"))
                .put(new JSONObject().put("command", "status").put("description", "Show connection status"))
                .put(new JSONObject().put("command", "help").put("description", "Show available commands"));
        call(token, "setMyCommands", "commands=" + encode(commands.toString()));
    }

    public JSONArray getUpdates(final String token, final long offset) throws Exception {
        final String body = "timeout=25&allowed_updates="
                + encode("[\"message\",\"edited_message\"]")
                + "&offset=" + offset;
        return call(token, "getUpdates", body).getJSONArray("result");
    }

    public void sendMessage(final String token, final long chatId, final String text)
            throws Exception {
        call(token, "sendMessage", "chat_id=" + chatId
                + "&disable_web_page_preview=true&text=" + encode(text));
    }

    public JSONObject getFile(final String token, final String fileId) throws Exception {
        return call(token, "getFile", "file_id=" + encode(fileId)).getJSONObject("result");
    }

    public Uri downloadTelegramFile(final Context context, final String token,
                                    final String filePath, final String preferredName)
            throws Exception {
        final URL url = new URL("https://api.telegram.org/file/bot" + token + "/" + filePath);
        final HttpURLConnection connection = open(url);
        connection.setRequestMethod("GET");
        ensureSuccess(connection);
        final String safeName = sanitizeFileName(preferredName == null || preferredName.isEmpty()
                ? new File(filePath).getName() : preferredName);

        try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return saveWithMediaStore(context, input, safeName);
            }
            final File root = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "Moataz Dow");
            if (!root.exists() && !root.mkdirs()) {
                throw new IllegalStateException("Unable to create download directory");
            }
            final File destination = uniqueFile(root, safeName);
            try (OutputStream output = new FileOutputStream(destination)) {
                copy(input, output);
            }
            return Uri.fromFile(destination);
        } finally {
            connection.disconnect();
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private Uri saveWithMediaStore(final Context context, final InputStream input,
                                   final String safeName) throws Exception {
        final ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
        values.put(MediaStore.Downloads.MIME_TYPE, guessMime(safeName));
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Moataz Dow");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        final Uri uri = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IllegalStateException("Unable to create destination file");
        }
        try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
            if (output == null) {
                throw new IllegalStateException("Unable to open destination file");
            }
            copy(input, output);
        }
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        context.getContentResolver().update(uri, values, null, null);
        return uri;
    }

    private JSONObject call(final String token, final String method, final String body)
            throws Exception {
        validateToken(token);
        final HttpURLConnection connection = open(new URL(API + token + "/" + method));
        connection.setRequestMethod(body == null ? "GET" : "POST");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        if (body != null) {
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            final int code = connection.getResponseCode();
            final InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            final String json = readFully(stream);
            final JSONObject response = new JSONObject(json);
            if (!response.optBoolean("ok", false)) {
                throw new IllegalStateException(response.optString("description",
                        "Telegram API request failed (HTTP " + code + ")"));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(final URL url) throws Exception {
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Moataz-Dow-Android/1.0");
        return connection;
    }

    private static void ensureSuccess(final HttpURLConnection connection) throws Exception {
        final int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Telegram file download failed (HTTP " + code + ")");
        }
    }

    private static String readFully(final InputStream stream) throws Exception {
        if (stream == null) {
            return "{}";
        }
        final StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static void copy(final InputStream input, final OutputStream output) throws Exception {
        final byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private static String encode(final String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static void validateToken(final String token) {
        if (token == null || token.trim().isEmpty() || !token.contains(":")) {
            throw new IllegalArgumentException("Invalid Telegram bot token");
        }
    }

    private static String sanitizeFileName(final String name) {
        final String clean = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return clean.isEmpty() ? "telegram-file" : clean;
    }

    private static String guessMime(final String name) {
        final String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".ogg") || lower.endsWith(".opus")) return "audio/ogg";
        return "application/octet-stream";
    }

    private static File uniqueFile(final File root, final String name) {
        File file = new File(root, name);
        if (!file.exists()) return file;
        final int dot = name.lastIndexOf('.');
        final String base = dot > 0 ? name.substring(0, dot) : name;
        final String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            file = new File(root, base + " (" + i + ")" + ext);
            if (!file.exists()) return file;
        }
        return new File(root, System.currentTimeMillis() + "-" + name);
    }
}
