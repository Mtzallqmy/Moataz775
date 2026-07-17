# Moataz Dow central Telegram service

This backend powers one shared Telegram bot for all Moataz Dow installations. The bot token never ships inside the APK.

## Flow

1. The Android app creates an anonymous installation ID and a random secret protected by Android Keystore.
2. The app registers the installation and requests a ten-minute pairing code.
3. Telegram opens `https://t.me/<bot>?start=<code>`.
4. The webhook links the Telegram chat to that installation.
5. Links sent to the bot are stored as device-scoped tasks.
6. The Android foreground service authenticates with its installation secret and retrieves only its own tasks.

## Components

- `supabase/migrations`: isolated `moataz_dow_*` tables, indexes, RLS and grants.
- `supabase/functions/moataz-dow`: Edge Function for health checks, device APIs, Telegram webhook and the administration dashboard.

## Required secrets

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_BOT_USERNAME`
- `TELEGRAM_WEBHOOK_SECRET`
- `ADMIN_USERNAME`
- `ADMIN_PASSWORD_SHA256` — lowercase SHA-256 of the administrator password.
- `ADMIN_SESSION_SECRET` — at least 32 random bytes.
- `PUBLIC_API_URL`

Supabase provides `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` to the Edge Function. Never expose the service-role key to Android or JavaScript clients.

## Telegram webhook

Configure Telegram to send updates to:

```text
https://PROJECT_REF.supabase.co/functions/v1/moataz-dow/telegram/webhook
```

Use the same random value for Telegram's `secret_token` and `TELEGRAM_WEBHOOK_SECRET`.

## Administrator

The dashboard is available at:

```text
https://PROJECT_REF.supabase.co/functions/v1/moataz-dow/admin
```

Authentication uses an HttpOnly, Secure, SameSite=Strict signed session cookie. The dashboard can inspect the bot, synchronize commands, review devices and disconnect a device.

## Security boundaries

The public `anon` and `authenticated` database roles have no direct table access. RLS is enabled on every Moataz Dow table, and the Edge Function applies device authentication, webhook-secret verification and administrator authentication.
