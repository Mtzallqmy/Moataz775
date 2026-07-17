/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.telegram;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.RouterActivity;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.ThemeHelper;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Crash-safe pairing and diagnostics screen for the shared Moataz Dow Telegram bot. */
public final class TelegramIntegrationActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private DeviceIdentityStore identityStore;
    private PairingApiClient client;
    private TelegramQueueStore queueStore;
    private TextView connectionStatus;
    private TextView pairCode;
    private TextView pairedAccount;
    private TextView serviceDetails;
    private LinearLayout queueContainer;
    private MaterialButton pairButton;
    private MaterialButton refreshButton;
    private MaterialButton unpairButton;
    private MaterialButton serviceButton;
    private MaterialButton openBotButton;
    private String currentBotUrl = "";
    private boolean receiverRegistered;
    private boolean initialized;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            refreshState();
        }
    };

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        try {
            ThemeHelper.setTheme(this, ServiceHelper.getSelectedServiceId(this));
        } catch (final Throwable ignored) {
            // A bad saved theme must not prevent diagnostics from opening.
        }
        super.onCreate(savedInstanceState);

        try {
            initializeUi();
            initialized = true;
            refreshState();
            refreshPairing();
        } catch (final Throwable startupError) {
            initialized = false;
            renderStartupFailure(startupError);
        }
    }

    private void initializeUi() {
        setContentView(R.layout.activity_telegram_integration);
        final MaterialToolbar toolbar = requireView(R.id.telegram_toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());

        identityStore = new DeviceIdentityStore(this);
        client = new PairingApiClient();
        queueStore = new TelegramQueueStore(this);
        connectionStatus = requireView(R.id.telegram_connection_status);
        pairCode = requireView(R.id.telegram_pair_code);
        pairedAccount = requireView(R.id.telegram_paired_account);
        serviceDetails = requireView(R.id.telegram_service_details);
        queueContainer = requireView(R.id.telegram_queue_container);
        pairButton = requireView(R.id.telegram_pair_button);
        refreshButton = requireView(R.id.telegram_refresh_button);
        unpairButton = requireView(R.id.telegram_unpair_button);
        serviceButton = requireView(R.id.telegram_service_button);
        openBotButton = requireView(R.id.telegram_open_bot_button);

        pairButton.setOnClickListener(view -> createPairing());
        refreshButton.setOnClickListener(view -> refreshPairing());
        unpairButton.setOnClickListener(view -> unpair());
        serviceButton.setOnClickListener(view -> toggleService());
        openBotButton.setOnClickListener(view -> openBot());
        requireView(R.id.telegram_clear_queue_button).setOnClickListener(view -> {
            queueStore.clear();
            renderQueue();
        });
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T requireView(@IdRes final int id) {
        final View view = findViewById(id);
        if (view == null) {
            throw new IllegalStateException("Telegram screen is missing view " + id);
        }
        return (T) view;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!initialized || receiverRegistered) {
            return;
        }
        try {
            ContextCompat.registerReceiver(this, stateReceiver,
                    new IntentFilter(TelegramSyncService.ACTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
            refreshState();
        } catch (final Throwable error) {
            showError(error);
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (final IllegalArgumentException ignored) {
                // Receiver may already be gone after process recovery.
            }
            receiverRegistered = false;
        }
        super.onStop();
    }

    private PairingApiClient.Identity registerWithRecovery() throws Exception {
        PairingApiClient.Identity identity = identityStore.getOrCreateIdentity();
        try {
            client.register(identity);
            return identity;
        } catch (final PairingApiClient.ApiException error) {
            if (!"device_identity_conflict".equals(error.code)
                    && !"invalid_device_credentials".equals(error.code)) {
                throw error;
            }
            identityStore.clear();
            identity = identityStore.getOrCreateIdentity();
            client.register(identity);
            return identity;
        }
    }

    private void createPairing() {
        setBusy(R.string.telegram_creating_pair_code);
        setControlsEnabled(false);
        executor.execute(() -> {
            try {
                final PairingApiClient.Health health = client.health();
                if (!health.ok) {
                    throw new IllegalStateException(getString(R.string.telegram_service_unavailable));
                }
                if (!health.telegramConfigured) {
                    throw new PairingApiClient.ApiException("telegram_not_configured", 503,
                            getString(R.string.telegram_bot_not_configured));
                }

                final PairingApiClient.PairCode result =
                        client.createPairCode(registerWithRecovery());
                currentBotUrl = result.botUrl;
                runOnUiThreadSafe(() -> {
                    pairCode.setText(result.code);
                    connectionStatus.setText(R.string.telegram_pair_code_ready);
                    setControlsEnabled(true);
                    if (currentBotUrl.isEmpty()) {
                        connectionStatus.setText(R.string.telegram_bot_not_configured);
                    } else {
                        openBot();
                    }
                });
            } catch (final Throwable error) {
                showError(error);
                runOnUiThreadSafe(() -> setControlsEnabled(true));
            }
        });
    }

    private void refreshPairing() {
        setBusy(R.string.telegram_checking_pairing);
        setControlsEnabled(false);
        executor.execute(() -> {
            try {
                final PairingApiClient.Health health = client.health();
                final PairingApiClient.PairStatus status = client.status(registerWithRecovery());
                runOnUiThreadSafe(() -> {
                    if (status.paired) {
                        connectionStatus.setText(R.string.telegram_paired);
                        pairedAccount.setText(status.telegramUsername.isEmpty()
                                ? getString(R.string.telegram_account_linked)
                                : "@" + status.telegramUsername);
                    } else if (!health.telegramConfigured) {
                        connectionStatus.setText(R.string.telegram_bot_not_configured);
                        pairedAccount.setText(R.string.telegram_no_account);
                    } else {
                        connectionStatus.setText(R.string.telegram_not_paired);
                        pairedAccount.setText(R.string.telegram_no_account);
                    }
                    setControlsEnabled(true);
                });
            } catch (final Throwable error) {
                showError(error);
                runOnUiThreadSafe(() -> setControlsEnabled(true));
            }
        });
    }

    private void unpair() {
        setBusy(R.string.telegram_unpairing);
        setControlsEnabled(false);
        executor.execute(() -> {
            try {
                client.unpair(registerWithRecovery());
                stopSyncService();
                runOnUiThreadSafe(() -> {
                    currentBotUrl = "";
                    pairCode.setText("—");
                    pairedAccount.setText(R.string.telegram_no_account);
                    connectionStatus.setText(R.string.telegram_unpaired);
                    setControlsEnabled(true);
                    refreshState();
                });
            } catch (final Throwable error) {
                showError(error);
                runOnUiThreadSafe(() -> setControlsEnabled(true));
            }
        });
    }

    private void toggleService() {
        if (TelegramSyncService.isRunning(this)) {
            stopSyncService();
            refreshState();
            return;
        }

        setBusy(R.string.telegram_checking_pairing);
        serviceButton.setEnabled(false);
        executor.execute(() -> {
            try {
                final PairingApiClient.PairStatus status = client.status(registerWithRecovery());
                if (!status.paired) {
                    throw new PairingApiClient.ApiException("not_paired", 409,
                            getString(R.string.telegram_pair_before_sync));
                }
                runOnUiThreadSafe(() -> {
                    try {
                        ContextCompat.startForegroundService(this,
                                new Intent(this, TelegramSyncService.class)
                                        .setAction(TelegramSyncService.ACTION_START));
                        connectionStatus.setText(R.string.telegram_sync_started);
                    } catch (final Throwable error) {
                        showError(error);
                    } finally {
                        serviceButton.setEnabled(true);
                        refreshState();
                    }
                });
            } catch (final Throwable error) {
                showError(error);
                runOnUiThreadSafe(() -> serviceButton.setEnabled(true));
            }
        });
    }

    private void stopSyncService() {
        try {
            stopService(new Intent(this, TelegramSyncService.class));
        } catch (final Throwable error) {
            showError(error);
        }
    }

    private void refreshState() {
        if (!initialized || serviceButton == null) {
            return;
        }
        final boolean running = TelegramSyncService.isRunning(this);
        serviceButton.setText(running
                ? R.string.telegram_stop_service : R.string.telegram_start_service);
        final String error = TelegramSyncService.getLastError(this);
        final long lastSync = TelegramSyncService.getLastSync(this);
        final String lastSyncText = lastSync <= 0 ? getString(R.string.telegram_never)
                : DateFormat.getDateTimeInstance().format(new Date(lastSync));
        serviceDetails.setText(getString(R.string.telegram_service_state_format,
                running ? getString(R.string.telegram_running) : getString(R.string.telegram_stopped),
                lastSyncText, error.isEmpty() ? getString(R.string.telegram_no_errors) : error));
        renderQueue();
    }

    private void renderQueue() {
        if (queueContainer == null || queueStore == null) {
            return;
        }
        queueContainer.removeAllViews();
        final List<TelegramQueueStore.Item> items = queueStore.list();
        if (items.isEmpty()) {
            final TextView empty = new TextView(this);
            empty.setText(R.string.telegram_queue_empty);
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            queueContainer.addView(empty);
            return;
        }

        for (final TelegramQueueStore.Item item : items) {
            final MaterialCardView card = new MaterialCardView(this);
            final LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(14), dp(14), dp(14), dp(14));

            final TextView url = new TextView(this);
            url.setText(item.url);
            url.setTextIsSelectable(true);
            content.addView(url);

            final TextView date = new TextView(this);
            date.setText(DateFormat.getDateTimeInstance().format(new Date(item.createdAt)));
            content.addView(date);

            final LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            final MaterialButton open = new MaterialButton(this);
            open.setText(R.string.telegram_open_in_app);
            open.setOnClickListener(view -> openQueueItem(item));
            actions.addView(open, weightedButtonParams());
            final MaterialButton remove = new MaterialButton(this);
            remove.setText(R.string.telegram_remove);
            remove.setOnClickListener(view -> {
                queueStore.remove(item.id);
                renderQueue();
            });
            actions.addView(remove, weightedButtonParams());
            content.addView(actions);
            card.addView(content);

            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dp(10));
            queueContainer.addView(card, params);
        }
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void openQueueItem(final TelegramQueueStore.Item item) {
        try {
            final Uri uri = Uri.parse(item.url);
            final String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException(getString(R.string.telegram_invalid_link));
            }
            startActivity(new Intent(Intent.ACTION_VIEW, uri, this, RouterActivity.class));
        } catch (final Throwable error) {
            showError(error);
        }
    }

    private void openBot() {
        if (currentBotUrl.isEmpty()) {
            Toast.makeText(this, R.string.telegram_create_code_first, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            final Uri uri = Uri.parse(currentBotUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"t.me".equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException(getString(R.string.telegram_invalid_bot_link));
            }
            final Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            if (intent.resolveActivity(getPackageManager()) == null) {
                throw new ActivityNotFoundException(getString(R.string.telegram_no_handler));
            }
            startActivity(intent);
        } catch (final Throwable error) {
            showError(error);
        }
    }

    private void setControlsEnabled(final boolean enabled) {
        if (pairButton == null) {
            return;
        }
        pairButton.setEnabled(enabled);
        refreshButton.setEnabled(enabled);
        unpairButton.setEnabled(enabled);
        serviceButton.setEnabled(enabled);
        openBotButton.setEnabled(enabled && !currentBotUrl.isEmpty());
    }

    private void setBusy(@IdRes final int stringId) {
        if (connectionStatus != null) {
            connectionStatus.setText(stringId);
        }
    }

    private void showError(final Throwable error) {
        final String message = errorMessage(error);
        runOnUiThreadSafe(() -> {
            if (connectionStatus != null) {
                connectionStatus.setText(getString(R.string.telegram_error_format, message));
            } else {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private String errorMessage(final Throwable error) {
        if (error instanceof PairingApiClient.ApiException) {
            final String code = ((PairingApiClient.ApiException) error).code;
            switch (code) {
                case "telegram_not_configured":
                    return getString(R.string.telegram_bot_not_configured);
                case "network_unavailable":
                case "network_error":
                    return getString(R.string.telegram_network_error);
                case "network_timeout":
                    return getString(R.string.telegram_network_timeout);
                case "not_paired":
                    return getString(R.string.telegram_pair_before_sync);
                default:
                    break;
            }
        }
        return error.getMessage() == null || error.getMessage().trim().isEmpty()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void runOnUiThreadSafe(final Runnable action) {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                && isDestroyed())) {
            return;
        }
        runOnUiThread(action);
    }

    private void renderStartupFailure(final Throwable error) {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        final TextView title = new TextView(this);
        title.setText(R.string.telegram_startup_error_title);
        title.setTextSize(22f);
        root.addView(title);

        final TextView details = new TextView(this);
        details.setText(getString(R.string.telegram_startup_error_message,
                errorMessage(error)));
        details.setPadding(0, dp(16), 0, dp(16));
        details.setTextIsSelectable(true);
        root.addView(details);

        final MaterialButton retry = new MaterialButton(this);
        retry.setText(R.string.telegram_retry);
        retry.setOnClickListener(view -> recreate());
        root.addView(retry);

        final MaterialButton close = new MaterialButton(this);
        close.setText(android.R.string.cancel);
        close.setOnClickListener(view -> finish());
        root.addView(close);
        setContentView(root);
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
