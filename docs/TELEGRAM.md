# Telegram integration

## One shared bot

Moataz Dow uses one owner-managed Telegram bot. The bot token is stored only in the backend environment. Android users never create a bot and never enter a token.

## Pairing

1. The Android installation creates a local device ID and encrypted device secret.
2. It registers with the central API and requests a ten-minute code.
3. The app opens `https://t.me/<bot>?start=<code>`.
4. Telegram sends `/start <code>` to the verified webhook.
5. The backend links the Telegram chat ID to that specific installation and marks the code used.

A new code invalidates older unused codes for the same installation. `/unlink` or the Android **Disconnect** button removes the Telegram association without deleting downloaded files.

## Supported commands

- `/start <code>` — complete secure device pairing.
- `/help` — display available commands.
- `/download <url>` — send a supported media URL to the paired device.
- `/formats` — explain the native video, audio, subtitle and container workflow.
- `/status` — show whether the chat is linked and count waiting tasks.
- `/unlink` — disconnect the Telegram chat from the installation.

A plain `http://` or `https://` link is handled like `/download`.

## Delivery

The webhook creates a device-scoped task. The Android foreground service authenticates to the central API, downloads only pending tasks assigned to its installation, stores links in the local queue, and acknowledges success or failure. Opening a task routes the URL into NewPipe's native details and download flow.

The app does not consume Telegram updates directly. This avoids competing long-polling clients and prevents one device from seeing another user's messages.

## Administrator operations

The `/admin` dashboard can:

- inspect the bot and webhook status;
- synchronize BotFather commands;
- count registered and paired installations;
- inspect recent device activity;
- disconnect a device;
- review audit events through the database.

## Privacy

The database stores an anonymous installation ID, a digest of the device secret, Telegram chat ID and optional Telegram username needed for routing. It does not store the user's bot token because users do not have one. URL tasks remain only as long as required for delivery and operational history.
