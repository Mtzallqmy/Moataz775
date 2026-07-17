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
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pulls only this installation's tasks from the central Moataz Dow service. */
public final class TelegramSyncService extends Service {
    public static final String ACTION_START = "org.moatazdow.telegram.START";
    public static final String ACTION_STOP = "org.moatazdow.telegram.STOP";
    public static final String ACTION_STATE_CHANGED = "org.moatazdow.telegram.STATE_CHANGED";

    private static final String CHANNEL_ID = "moataz_dow_telegram_sync";
    private static final int NOTIFICATION_ID = 77549;
    private static final String PREFS = "moataz_dow_telegram_state";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_LAST_SYNC = "last_sync";
    private static final long POLL_INTERVAL_MS = 15000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean shouldRun;
    private PairingApiClient client;
    private TelegramQueueStore queueStore;
    private DeviceIdentityStore identityStore;
    private SharedPreferences state;

    @Override
    public void onCreate() {
        super.onCreate();
        client = new PairingApiClient();
        queueStore = new TelegramQueueStore(this);
        identityStore = new DeviceIdentityStore(this);
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
            startForeground(NOTIFICATION_ID,
                    buildNotification(getString(R.string.telegram_connecting)));
            broadcastState();
            executor.execute(this::pollLoop);
        }
        return START_STICKY;
    }

    private PairingApiClient.Identity identity() throws Exception {
        return new PairingApiClient.Identity(identityStore.getOrCreateDeviceId(),
                identityStore.getOrCreateDeviceSecret());
    }

    private void pollLoop() {
        try {
            final PairingApiClient.Identity identity = identity();
            client.register(identity);
            while (shouldRun) {
                try {
                    final PairingApiClient.PairStatus pairStatus = client.status(identity);
                    if (!pairStatus.paired) {
                        throw new IllegalStateException(getString(R.string.telegram_not_paired));
                    }
                    final JSONArray tasks = client.tasks(identity);
                    int received = 0;
                    if (tasks != null) {
                        for (int i = 0; i < tasks.length(); i++) {
                            final JSONObject task = tasks.getJSONObject(i);
                            final String taskId = task.optString("id");
                            try {
                                final String type = task.optString("task_type");
                                final JSONObject payload = task.optJSONObject("payload");
                                final String url = payload == null ? "" : payload.optString("url");
                                if (!"url".equals(type) || url.isEmpty()) {
                                    throw new IllegalArgumentException("Unsupported Telegram task");
                                }
                                queueStore.add(url, 0L);
                                client.acknowledge(identity, taskId, true, null);
                                received++;
                            } catch (final Exception taskError) {
                                client.acknowledge(identity, taskId, false, safeMessage(taskError));
                            }
                        }
                    }
                    state.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                            .remove(KEY_LAST_ERROR).apply();
                    updateNotification(getString(R.string.telegram_queue_count,
                            queueStore.list().size()));
                    if (received > 0) {
                        broadcastState();
                    }
                } catch (final Exception error) {
                    state.edit().putString(KEY_LAST_ERROR, safeMessage(error)).apply();
                    updateNotification(getString(R.string.telegram_sync_error));
                    broadcastState();
                }
                if (!sleepUntilNextPoll()) {
                    break;
                }
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

    private boolean sleepUntilNextPoll() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
            return shouldRun;
        } catch (final InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
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
