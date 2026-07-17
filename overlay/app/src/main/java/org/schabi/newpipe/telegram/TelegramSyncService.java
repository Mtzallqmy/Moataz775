/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.telegram;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.R;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TelegramSyncService extends Service {
    public static final String ACTION_START = "org.moatazdow.telegram.START";
    public static final String ACTION_STOP = "org.moatazdow.telegram.STOP";
    public static final String ACTION_STATE_CHANGED = "org.moatazdow.telegram.STATE_CHANGED";

    private static final String CHANNEL_ID = "moataz_dow_telegram_sync";
    private static final int NOTIFICATION_ID = 77549;
    private static final String PREFS = "moataz_dow_telegram_state";
    private static final String KEY_OFFSET = "offset";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_BOT_NAME = "bot_name";
    private static final String KEY_LAST_SYNC = "last_sync";
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean shouldRun;
    private TelegramBotClient client;
    private TelegramQueueStore queueStore;
    private SecureTokenStore tokenStore;
    private SharedPreferences state;

    @Override
    public void onCreate() {
        super.onCreate();
        client = new TelegramBotClient();
        queueStore = new TelegramQueueStore(this);
        tokenStore = new SecureTokenStore(this);
        state = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSync();
            return START_NOT_STICKY;
        }
        if (!shouldRun) {
            shouldRun = true;
            state.edit().putBoolean(KEY_RUNNING, true).remove(KEY_LAST_ERROR).apply();
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.telegram_connecting)));
            broadcastState();
            executor.execute(this::pollLoop);
        }
        return START_STICKY;
    }

    private void pollLoop() {
        try {
            final String token = tokenStore.read();
            if (token == null) {
                throw new IllegalStateException(getString(R.string.telegram_token_required));
            }
            final JSONObject bot = client.getMe(token);
            final String botName = bot.optString("username", bot.optString("first_name", "Telegram bot"));
            state.edit().putString(KEY_BOT_NAME, botName).apply();

            final JSONObject webhook = client.getWebhookInfo(token);
            if (!webhook.optString("url", "").isEmpty()) {
                throw new IllegalStateException(getString(R.string.telegram_webhook_conflict));
            }

            updateNotification("@" + botName + " • " + getString(R.string.telegram_connected));
            long offset = state.getLong(KEY_OFFSET, 0L);
            while (shouldRun) {
                final JSONArray updates = client.getUpdates(token, offset);
                for (int i = 0; i < updates.length(); i++) {
                    final JSONObject update = updates.getJSONObject(i);
                    offset = Math.max(offset, update.optLong("update_id", 0L) + 1L);
                    processUpdate(token, update);
                }
                state.edit().putLong(KEY_OFFSET, offset)
                        .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                        .remove(KEY_LAST_ERROR)
                        .apply();
                updateNotification("@" + botName + " • "
                        + getString(R.string.telegram_queue_count, queueStore.list().size()));
                broadcastState();
            }
        } catch (final Exception error) {
            state.edit().putString(KEY_LAST_ERROR, safeMessage(error)).apply();
            updateNotification(getString(R.string.telegram_sync_error));
            broadcastState();
        } finally {
            shouldRun = false;
            state.edit().putBoolean(KEY_RUNNING, false).apply();
            broadcastState();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(false);
            }
            stopSelf();
        }
    }

    private void processUpdate(final String token, final JSONObject update) throws Exception {
        JSONObject message = update.optJSONObject("message");
        if (message == null) {
            message = update.optJSONObject("edited_message");
        }
        if (message == null) {
            return;
        }
        final JSONObject chat = message.optJSONObject("chat");
        if (chat == null) {
            return;
        }
        final long chatId = chat.optLong("id", 0L);

        final JSONObject document = message.optJSONObject("document");
        final JSONObject audio = message.optJSONObject("audio");
        final JSONObject video = message.optJSONObject("video");
        final JSONObject media = document != null ? document : (audio != null ? audio : video);
        if (media != null) {
            final String fileId = media.optString("file_id", "");
            final String fileName = media.optString("file_name",
                    audio != null ? "telegram-audio.mp3" : "telegram-video.mp4");
            final JSONObject file = client.getFile(token, fileId);
            final Uri saved = client.downloadTelegramFile(this, token,
                    file.getString("file_path"), fileName);
            client.sendMessage(token, chatId,
                    getString(R.string.telegram_file_saved, fileName, saved.toString()));
            return;
        }

        final String rawText = message.optString("text", message.optString("caption", "")).trim();
        if (rawText.isEmpty()) {
            return;
        }
        final String commandText = rawText.replaceFirst("^/([a-zA-Z]+)@[^\\s]+", "/$1");
        final String lower = commandText.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/start") || lower.startsWith("/help")) {
            client.sendMessage(token, chatId, helpText());
            return;
        }
        if (lower.startsWith("/formats")) {
            client.sendMessage(token, chatId, getString(R.string.telegram_formats_reply));
            return;
        }
        if (lower.startsWith("/status")) {
            client.sendMessage(token, chatId,
                    getString(R.string.telegram_status_reply, queueStore.list().size()));
            return;
        }

        final Matcher matcher = URL_PATTERN.matcher(commandText);
        if (lower.startsWith("/download") || matcher.find()) {
            matcher.reset();
            if (!matcher.find()) {
                client.sendMessage(token, chatId, getString(R.string.telegram_download_usage));
                return;
            }
            final String url = matcher.group().replaceAll("[),.;]+$", "");
            queueStore.add(url, chatId);
            client.sendMessage(token, chatId, getString(R.string.telegram_added_to_queue, url));
            broadcastState();
            return;
        }
        client.sendMessage(token, chatId, getString(R.string.telegram_unknown_command));
    }

    private String helpText() {
        return getString(R.string.telegram_help_reply);
    }

    private void stopSync() {
        shouldRun = false;
        state.edit().putBoolean(KEY_RUNNING, false).apply();
        broadcastState();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private Notification buildNotification(final String content) {
        final Intent openIntent = new Intent(this, TelegramIntegrationActivity.class);
        final PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_moataz_dow_notification)
                .setContentTitle(getString(R.string.telegram_service_title))
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(final String content) {
        final NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(content));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.telegram_service_title), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.telegram_service_description));
            final NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private void broadcastState() {
        sendBroadcast(new Intent(ACTION_STATE_CHANGED).setPackage(getPackageName()));
    }

    private static String safeMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    public static boolean isRunning(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_RUNNING, false);
    }

    public static String getLastError(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_ERROR, "");
    }

    public static String getBotName(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_BOT_NAME, "");
    }

    public static long getLastSync(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SYNC, 0L);
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        shouldRun = false;
        executor.shutdownNow();
        state.edit().putBoolean(KEY_RUNNING, false).apply();
        super.onDestroy();
    }
}
