# Security

## Bot token boundary

The Telegram bot token exists only as a server-side hosting secret. It is not included in the APK, Android resources, GitHub source, database rows, diagnostics, or client responses. Users connect through a short-lived Telegram pairing code and never handle a token.

## Installation identity

Every installation creates:

- a random anonymous device ID;
- a high-entropy device secret;
- a local Android Keystore key used to encrypt that secret.

The backend stores only the SHA-256 digest of the device secret. Authenticated device requests use HTTPS and send the ID and secret in request headers. A Telegram chat can access only the task queue assigned to its paired installation.

## Pairing

Pair codes use an ambiguity-resistant alphabet, expire after ten minutes, are single-use, and are invalidated when a new code is requested. Pairing is completed only by Telegram's `/start <code>` update delivered to the verified webhook.

## Backend and database

All `moataz_dow_*` tables have RLS enabled. Direct privileges are revoked from `anon` and `authenticated`; the Edge Function is the only application boundary and uses the service role internally. Device authentication, Telegram webhook-secret validation, administrator authentication and audit logging are enforced before privileged operations.

## Administrator

The dashboard compares the submitted password with a server-side SHA-256 value and creates an HMAC-signed, eight-hour session cookie with `HttpOnly`, `Secure`, and `SameSite=Strict`. The session signing secret and password digest are hosting secrets. The dashboard is protected with CSP, frame denial, no-store caching and no-referrer headers.

## Network

Android and backend requests require HTTPS. Telegram calls originate only from the Edge Function. The app does not connect directly to `api.telegram.org` and cannot read updates belonging to another installation.

## Release signing

Production Android keys must be stored only as GitHub Actions encrypted secrets. This repository intentionally contains no keystore, signing password, bot token, administrator password, or service-role key.

## Permissions and local storage

The Telegram integration reuses the internet and foreground data-sync permissions required by the app. Received links are stored in the app's private preferences until opened or removed. Media downloads continue to use NewPipe's native user-selected storage flow.

## Reporting

Never post bot tokens, webhook secrets, administrator credentials, signing keys, device secrets, or private Telegram content in public issues. Revoke exposed Telegram credentials through BotFather and rotate affected server secrets immediately.
