/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.telegram;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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

/** User-facing pairing and diagnostics screen for the shared Moataz Dow Telegram bot. */
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
    private MaterialButton serviceButton;
    private MaterialButton openBotButton;
    private String currentBotUrl = "";

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            refreshState();
        }
    };

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        ThemeHelper.setTheme(this, ServiceHelper.getSelectedServiceId(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_telegram_integration);

        final MaterialToolbar toolbar = findViewById(R.id.telegram_toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());

        identityStore = new DeviceIdentityStore(this);
        client = new PairingApiClient();
        queueStore = new TelegramQueueStore(this);

        connectionStatus = findViewById(R.id.telegram_connection_status);
        pairCode = findViewById(R.id.telegram_pair_code);
        pairedAccount = findViewById(R.id.telegram_paired_account);
        serviceDetails = findViewById(R.id.telegram_service_details);
        queueContainer = findViewById(R.id.telegram_queue_container);
        serviceButton = findViewById(R.id.telegram_service_button);
        openBotButton = findViewById(R.id.telegram_open_bot_button);

        findViewById(R.id.telegram_pair_button).setOnClickListener(view -> createPairing());
        findViewById(R.id.telegram_refresh_button).setOnClickListener(view -> refreshPairing());
        findViewById(R.id.telegram_unpair_button).setOnClickListener(view -> unpair());
        findViewById(R.id.telegram_clear_queue_button).setOnClickListener(view -> {
            queueStore.clear();
            renderQueue();
        });
        openBotButton.setOnClickListener(view -> openBot());
        serviceButton.setOnClickListener(view -> toggleService());

        refreshState();
        refreshPairing();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, stateReceiver,
                new IntentFilter(TelegramSyncService.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        refreshState();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(stateReceiver);
        super.onStop();
    }

    private PairingApiClient.Identity identity() throws Exception {
        return new PairingApiClient.Identity(identityStore.getOrCreateDeviceId(),
                identityStore.getOrCreateDeviceSecret());
    }

    private void createPairing() {
        setBusy(getString(R.string.telegram_creating_pair_code));
        executor.execute(() -> {
            try {
                final PairingApiClient.Identity identity = identity();
                client.register(identity);
                final PairingApiClient.PairCode result = client.createPairCode(identity);
                currentBotUrl = result.botUrl;
                runOnUiThread(() -> {
                    pairCode.setText(result.code);
                    openBotButton.setEnabled(!currentBotUrl.isEmpty());
                    connectionStatus.setText(R.string.telegram_pair_code_ready);
                    if (currentBotUrl.isEmpty()) {
                        connectionStatus.setText(R.string.telegram_bot_not_configured);
                    } else {
                        openBot();
                    }
                });
            } catch (final Exception error) {
                showError(error);
            }
        });
    }

    private void refreshPairing() {
        setBusy(getString(R.string.telegram_checking_pairing));
        executor.execute(() -> {
            try {
                final PairingApiClient.Identity identity = identity();
                client.register(identity);
                final PairingApiClient.PairStatus status = client.status(identity);
                runOnUiThread(() -> {
                    if (status.paired) {
                        connectionStatus.setText(R.string.telegram_paired);
                        pairedAccount.setText(status.telegramUsername.isEmpty()
                                ? getString(R.string.telegram_account_linked)
                                : "@" + status.telegramUsername);
                    } else {
                        connectionStatus.setText(R.string.telegram_not_paired);
                        pairedAccount.setText(R.string.telegram_no_account);
                    }
                });
            } catch (final Exception error) {
                showError(error);
            }
        });
    }

    private void unpair() {
        setBusy(getString(R.string.telegram_unpairing));
        executor.execute(() -> {
            try {
                client.unpair(identity());
                stopSyncService();
                runOnUiThread(() -> {
                    currentBotUrl = "";
                    pairCode.setText("—");
                    pairedAccount.setText(R.string.telegram_no_account);
                    connectionStatus.setText(R.string.telegram_unpaired);
                    refreshState();
                });
            } catch (final Exception error) {
                showError(error);
            }
        });
    }

    private void toggleService() {
        if (TelegramSyncService.isRunning(this)) {
            stopSyncService();
        } else {
            ContextCompat.startForegroundService(this,
                    new Intent(this, TelegramSyncService.class)
                            .setAction(TelegramSyncService.ACTION_START));
        }
        refreshState();
    }

    private void stopSyncService() {
        startService(new Intent(this, TelegramSyncService.class)
                .setAction(TelegramSyncService.ACTION_STOP));
    }

    private void refreshState() {
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
        queueContainer.removeAllViews();
        final List<TelegramQueueStore.Item> items = queueStore.list();
        if (items.isEmpty()) {
            final TextView empty = new TextView(this);
            empty.setText(R.string.telegram_queue_empty);
            empty.setPadding(8, 16, 8, 16);
            queueContainer.addView(empty);
            return;
        }
        for (final TelegramQueueStore.Item item : items) {
            final MaterialCardView card = new MaterialCardView(this);
            final LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            final int padding = dp(14);
            content.setPadding(padding, padding, padding, padding);

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
            actions.addView(open, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final MaterialButton remove = new MaterialButton(this);
            remove.setText(R.string.telegram_remove);
            remove.setOnClickListener(view -> {
                queueStore.remove(item.id);
                renderQueue();
            });
            actions.addView(remove, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            content.addView(actions);
            card.addView(content);
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dp(10));
            queueContainer.addView(card, params);
        }
    }

    private void openQueueItem(final TelegramQueueStore.Item item) {
        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(item.url),
                this, RouterActivity.class);
        startActivity(intent);
    }

    private void openBot() {
        if (currentBotUrl.isEmpty()) {
            Toast.makeText(this, R.string.telegram_create_code_first, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentBotUrl)));
    }

    private void setBusy(final String text) {
        connectionStatus.setText(text);
    }

    private void showError(final Exception error) {
        final String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        runOnUiThread(() -> connectionStatus.setText(
                getString(R.string.telegram_error_format, message)));
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
