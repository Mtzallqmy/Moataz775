/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.telegram;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.R;
import org.schabi.newpipe.RouterActivity;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.ThemeHelper;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TelegramIntegrationActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SecureTokenStore tokenStore;
    private TelegramBotClient client;
    private TelegramQueueStore queueStore;
    private TextInputEditText tokenInput;
    private TextView connectionStatus;
    private TextView botDetails;
    private TextView webhookDetails;
    private TextView commandsDetails;
    private TextView serviceDetails;
    private LinearLayout queueContainer;
    private MaterialButton serviceButton;
    private String botUsername;

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

        tokenStore = new SecureTokenStore(this);
        client = new TelegramBotClient();
        queueStore = new TelegramQueueStore(this);

        tokenInput = findViewById(R.id.telegram_token_input);
        connectionStatus = findViewById(R.id.telegram_connection_status);
        botDetails = findViewById(R.id.telegram_bot_details);
        webhookDetails = findViewById(R.id.telegram_webhook_details);
        commandsDetails = findViewById(R.id.telegram_commands_details);
        serviceDetails = findViewById(R.id.telegram_service_details);
        queueContainer = findViewById(R.id.telegram_queue_container);
        serviceButton = findViewById(R.id.telegram_service_button);

        final MaterialButton visibilityButton = findViewById(R.id.telegram_token_visibility_button);
        visibilityButton.setOnClickListener(view -> toggleTokenVisibility(visibilityButton));
        findViewById(R.id.telegram_save_button).setOnClickListener(view -> saveToken());
        findViewById(R.id.telegram_test_button).setOnClickListener(view -> testConnection());
        findViewById(R.id.telegram_commands_button).setOnClickListener(view -> syncCommands());
        findViewById(R.id.telegram_open_bot_button).setOnClickListener(view -> openBot());
        findViewById(R.id.telegram_clear_button).setOnClickListener(view -> clearConfiguration());
        findViewById(R.id.telegram_clear_queue_button).setOnClickListener(view -> {
            queueStore.clear();
            renderQueue();
        });
        serviceButton.setOnClickListener(view -> toggleService());

        if (tokenStore.hasToken()) {
            tokenInput.setHint(R.string.telegram_token_saved_hint);
        }
        refreshState();
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

    private void saveToken() {
        final String entered = textOf(tokenInput);
        if (entered.isEmpty()) {
            Toast.makeText(this, tokenStore.hasToken()
                    ? R.string.telegram_token_already_saved : R.string.telegram_token_required,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        executor.execute(() -> {
            try {
                tokenStore.save(entered);
                runOnUiThread(() -> {
                    tokenInput.setText("");
                    tokenInput.setHint(R.string.telegram_token_saved_hint);
                    connectionStatus.setText(R.string.telegram_token_saved_securely);
                });
            } catch (final Exception error) {
                showError(error);
            }
        });
    }

    private void testConnection() {
        setBusy(getString(R.string.telegram_testing_connection));
        executor.execute(() -> {
            try {
                final String token = resolveToken();
                final JSONObject bot = client.getMe(token);
                final JSONObject webhook = client.getWebhookInfo(token);
                final JSONArray commands = client.getMyCommands(token);
                botUsername = bot.optString("username", "");
                final String botText = getString(R.string.telegram_bot_details_format,
                        bot.optLong("id"), bot.optString("first_name", ""),
                        botUsername.isEmpty() ? "—" : "@" + botUsername,
                        bot.optBoolean("can_join_groups"), bot.optBoolean("supports_inline_queries"));
                final String webhookUrl = webhook.optString("url", "");
                final String webhookText = webhookUrl.isEmpty()
                        ? getString(R.string.telegram_webhook_disabled)
                        : getString(R.string.telegram_webhook_enabled, webhookUrl,
                                webhook.optInt("pending_update_count", 0));
                final String commandText = formatCommands(commands);
                runOnUiThread(() -> {
                    connectionStatus.setText(R.string.telegram_connected);
                    botDetails.setText(botText);
                    webhookDetails.setText(webhookText);
                    commandsDetails.setText(commandText);
                    refreshState();
                });
            } catch (final Exception error) {
                showError(error);
            }
        });
    }

    private void syncCommands() {
        setBusy(getString(R.string.telegram_syncing_commands));
        executor.execute(() -> {
            try {
                final String token = resolveToken();
                client.setDefaultCommands(token);
                final JSONArray commands = client.getMyCommands(token);
                runOnUiThread(() -> {
                    connectionStatus.setText(R.string.telegram_commands_synced);
                    commandsDetails.setText(formatCommands(commands));
                });
            } catch (final Exception error) {
                showError(error);
            }
        });
    }

    private String resolveToken() throws Exception {
        final String entered = textOf(tokenInput);
        if (!entered.isEmpty()) {
            tokenStore.save(entered);
            runOnUiThread(() -> {
                tokenInput.setText("");
                tokenInput.setHint(R.string.telegram_token_saved_hint);
            });
            return entered;
        }
        final String saved = tokenStore.read();
        if (saved == null) {
            throw new IllegalStateException(getString(R.string.telegram_token_required));
        }
        return saved;
    }

    private void toggleService() {
        if (TelegramSyncService.isRunning(this)) {
            startService(new Intent(this, TelegramSyncService.class)
                    .setAction(TelegramSyncService.ACTION_STOP));
        } else {
            if (!tokenStore.hasToken() && textOf(tokenInput).isEmpty()) {
                Toast.makeText(this, R.string.telegram_token_required, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!textOf(tokenInput).isEmpty()) {
                saveToken();
            }
            ContextCompat.startForegroundService(this,
                    new Intent(this, TelegramSyncService.class)
                            .setAction(TelegramSyncService.ACTION_START));
        }
        refreshState();
    }

    private void clearConfiguration() {
        startService(new Intent(this, TelegramSyncService.class)
                .setAction(TelegramSyncService.ACTION_STOP));
        tokenStore.clear();
        queueStore.clear();
        tokenInput.setText("");
        tokenInput.setHint(R.string.telegram_bot_token_hint);
        botUsername = null;
        connectionStatus.setText(R.string.telegram_not_configured);
        botDetails.setText("—");
        webhookDetails.setText("—");
        commandsDetails.setText("—");
        refreshState();
    }

    private void refreshState() {
        final boolean running = TelegramSyncService.isRunning(this);
        serviceButton.setText(running
                ? R.string.telegram_stop_service : R.string.telegram_start_service);
        final String name = TelegramSyncService.getBotName(this);
        final String error = TelegramSyncService.getLastError(this);
        final long lastSync = TelegramSyncService.getLastSync(this);
        if (!name.isEmpty()) {
            botUsername = name;
        }
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
        final String username = botUsername == null ? "" : botUsername.replace("@", "");
        if (username.isEmpty()) {
            Toast.makeText(this, R.string.telegram_test_first, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/" + username)));
    }

    private void toggleTokenVisibility(final MaterialButton button) {
        final boolean visible = tokenInput.getInputType() == InputType.TYPE_CLASS_TEXT;
        tokenInput.setInputType(visible
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        tokenInput.setSelection(tokenInput.length());
        button.setText(visible ? R.string.telegram_show_token : R.string.telegram_hide_token);
    }

    private String formatCommands(final JSONArray commands) {
        if (commands.length() == 0) {
            return getString(R.string.telegram_no_commands);
        }
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < commands.length(); i++) {
            final JSONObject command = commands.optJSONObject(i);
            if (command != null) {
                builder.append('/').append(command.optString("command"))
                        .append(" — ").append(command.optString("description")).append('\n');
            }
        }
        return builder.toString().trim();
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

    private static String textOf(final TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
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
