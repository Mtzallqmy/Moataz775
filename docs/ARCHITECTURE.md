# Architecture

## Overview

Moataz Dow is divided into three independently maintainable layers:

1. **Android distribution** — a reviewed overlay applied to a pinned NewPipe release.
2. **Central Telegram service** — a Supabase Edge Function handling device APIs, the Telegram webhook and administration.
3. **PostgreSQL data layer** — isolated `moataz_dow_*` tables protected by RLS and revoked public roles.

```text
Telegram user
     │ /start code, links, commands
     ▼
Telegram Bot API ──verified webhook──► Edge Function
                                          │
                                          ├── pairing and device authentication
                                          ├── task routing and acknowledgements
                                          ├── admin dashboard
                                          ▼
                                   PostgreSQL tables
                                          ▲
                                          │ HTTPS + device credentials
                                          │
                                  Android foreground sync
                                          │
                                          ▼
                                  Local request queue
                                          │
                                          ▼
                                 NewPipe routing/download UI
```

## Android modules

The overlay keeps Telegram-specific code under `org.schabi.newpipe.telegram`:

- `DeviceIdentityStore` — anonymous installation ID and Keystore-protected secret.
- `PairingApiClient` — HTTPS API client and response models.
- `TelegramIntegrationActivity` — pairing, status, synchronization and local queue UI.
- `TelegramSyncService` — foreground task synchronization with automatic retry.
- `TelegramQueueStore` — bounded private local URL queue.
- `SecureTokenStore` — reusable Android Keystore encryption primitive; it protects the device secret, not a Telegram token.

No Telegram Bot API token or Supabase service-role key exists in Android.

## Backend modules

- `_shared/config.ts` — environment configuration and service-role database client.
- `_shared/utils.ts` — hashing, constant-time comparison, signed admin sessions, HTTP responses and device authentication.
- `device.ts` — registration, pairing codes, status, task polling and acknowledgement.
- `telegram.ts` — verified webhook, commands, pairing and task creation.
- `admin.ts` — protected dashboard and administrative APIs.
- `index.ts` — route dispatcher and health endpoint.

## Data ownership

`moataz_dow_devices` is the ownership root. Pair codes and tasks reference `device_id` with cascading deletion. Telegram chats are associated with one installation at a time. The Edge Function accesses rows only after device, webhook or administrator authentication.

## Update strategy

NewPipe is pinned in GitHub Actions. Upgrading requires changing `UPSTREAM_REF`, running the overlay script and resolving failed patch anchors. This makes upstream changes explicit and prevents silent source drift.

## Release outputs

A successful tagged workflow creates:

- `Moataz-Dow-<version>-arm64-v8a.apk`
- `Moataz-Dow-<version>-universal.apk`
- `SHA256SUMS.txt`
- GitHub build provenance.
