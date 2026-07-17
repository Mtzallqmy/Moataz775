# Moataz Dow

**Moataz Dow** is a modern Android media browser and downloader with a secure central Telegram bot, device pairing, and an administrator dashboard.

> Independent GPL project. Not affiliated with YouTube, Supabase, or Telegram.

## Highlights

- New Android identity, icon, package ID, Arabic interface and modern Telegram screen.
- Video playback, background audio, playlists, supported services, subtitles and native format selection.
- One central Telegram bot for all users; users never paste or receive the bot token.
- One-tap pairing through a short-lived Telegram `start` code.
- Anonymous installation identity and random device secret protected by Android Keystore.
- Device-scoped task delivery: each installation can retrieve only its own Telegram requests.
- Foreground synchronization with automatic retry, local request queue and connection diagnostics.
- Administrator dashboard with signed secure sessions, bot checks, command synchronization, device overview and remote unlinking.
- Supabase Edge Function backend and isolated PostgreSQL tables with RLS and revoked client access.
- Two minified APK outputs from GitHub Actions:
  - `arm64-v8a`: smaller build for modern 64-bit Android devices.
  - `universal`: compatible with all ABIs included by the Android build.

## Repository structure

```text
.github/workflows/               Android/backend validation and release automation
backend/supabase/functions/      Central bot API, webhook and admin dashboard
backend/supabase/migrations/     Isolated database schema, indexes and RLS
contracts/                       API and security documentation
overlay/                         Maintained Android source overlay
scripts/                         Reproducible source preparation
```

The Android source is maintained as a reviewed overlay. GitHub Actions retrieves the pinned application engine, applies Moataz Dow changes, validates the backend, builds release APKs, verifies signatures and creates checksums.

## Telegram user flow

1. Open **Moataz Dow → Telegram connection**.
2. Press **Connect through Telegram**.
3. The app creates a ten-minute pairing code and opens the official bot.
4. Press **Start** in Telegram.
5. Send a link to the bot.
6. The request appears only on the paired Android installation, where the user chooses the available format and quality.

No bot token is stored in the APK or on user devices.

## Backend

The deployed API base URL is:

```text
https://xfjybpzadqelzzrrdjhd.supabase.co/functions/v1/moataz-dow
```

The health endpoint is `/health`; the administrator interface is `/admin`. Bot and administrator secrets remain unset until the owner supplies them through the hosting platform.

See [backend documentation](backend/README.md), [Telegram flow](docs/TELEGRAM.md), [architecture](docs/ARCHITECTURE.md), and [security model](SECURITY.md).

## Building Android releases

GitHub Actions performs the reproducible Android build automatically. Development builds use a temporary CI key. Tagged production releases require the private signing secrets listed below.

## Release signing

Production tags require these GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The private signing key is never committed. A tag such as `v1.0.0` triggers backend validation, Android build, signature verification, SHA-256 checksums, build provenance, and GitHub Release publication.

## Backend secrets

The central service requires:

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_BOT_USERNAME`
- `TELEGRAM_WEBHOOK_SECRET`
- `ADMIN_USERNAME`
- `ADMIN_PASSWORD_SHA256`
- `ADMIN_SESSION_SECRET`
- `PUBLIC_API_URL`

Real values must be stored only in Supabase project secrets.

## License

GPL-3.0-or-later. Modified source must remain available when distributed. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
