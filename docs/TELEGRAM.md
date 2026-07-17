# Telegram integration

## Supported commands

- `/start` and `/help` — show the available commands.
- `/download <url>` — add a supported media URL to the Moataz Dow queue.
- `/formats` — show available video/audio workflow and Telegram media handling.
- `/status` — return bot connectivity and queue status.

A plain `http://` or `https://` link is treated like `/download`.

## Media messages

Documents, audio, and video sent to the bot are downloaded through Telegram's `getFile` flow. Android 10+ stores them in `Downloads/Moataz Dow`. Older Android versions use the app's external files directory to avoid unsafe broad storage access.

## Polling and webhooks

The app uses Bot API long polling while the foreground service is enabled. The diagnostics screen displays webhook status. A bot should not use a webhook and long polling at the same time.

## Privacy

Only updates visible to the configured bot are processed. The queue is local. No analytics service or external backend is included.
