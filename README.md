# Moataz Dow

**Moataz Dow** is a modern Android media browser and downloader distribution based on NewPipe, with a first-class Telegram Bot integration.

> Independent project. Not affiliated with NewPipe or Telegram.

## Highlights

- New visual identity and application name: **Moataz Dow**.
- NewPipe playback, background audio, playlists, supported services, and download workflow.
- Telegram Bot connection using a bot token stored with Android Keystore encryption.
- Connection diagnostics: `getMe`, webhook status, pending updates, bot identity, and last error.
- Command synchronization and browser for `/start`, `/help`, `/download`, `/formats`, and `/status`.
- Foreground long-polling service for reliable Telegram updates while explicitly enabled by the user.
- Telegram documents, audio, and video can be downloaded into the device's Downloads collection.
- URL requests from Telegram are queued and opened in Moataz Dow's native NewPipe routing/download flow.
- Two APK outputs from GitHub Actions:
  - `arm64-v8a`: smaller build for modern 64-bit Android devices.
  - `universal`: compatible with a wider range of supported devices.

## Repository model

This repository is a maintainable customization layer. GitHub Actions checks out a pinned official NewPipe release, applies the reviewed Moataz Dow overlay, runs validation, and builds APKs. This keeps upstream updates auditable and avoids carrying unrelated historical files.

## Build

```bash
python3 scripts/prepare-source.py --source build/NewPipe --version 1.0.0
cd build/NewPipe
./gradlew assembleDebug
```

The workflow performs the upstream checkout automatically.

## Release signing

Production releases require these GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The signing key is never committed. Push a tag such as `v1.0.0` after configuring the secrets; GitHub Actions builds, signs, checksums, and publishes both APKs.

## Telegram setup

1. Create a bot using BotFather.
2. Open **Moataz Dow → Telegram Bot**.
3. Paste the token and save it.
4. Run **Test connection**, then **Sync commands**.
5. Enable the sync service.

See [Telegram integration](docs/TELEGRAM.md) and [security model](SECURITY.md).

## License

GPL-3.0-or-later. Modified source must remain available when distributed. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
