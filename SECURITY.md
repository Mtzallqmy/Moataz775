# Security

## Bot token

The bot token is encrypted locally. Android 6.0+ uses an AES-GCM key generated inside Android Keystore. Android 5.x uses an RSA key pair stored in Android Keystore to protect the short token value. The token is never written to logs or included in diagnostics exports.

## Network

Telegram requests use HTTPS endpoints under `api.telegram.org`. The app does not use a custom proxy by default. Webhook mode is detected and reported; long polling is not started while an external webhook is active unless the user removes it.

## Release signing

Release keys must be stored only as GitHub Actions encrypted secrets. This repository intentionally contains no keystore and no signing password.

## Permissions

The integration reuses internet and foreground data-sync permissions already required by the application. Telegram media is stored through MediaStore on Android 10+ and app-scoped external storage on older devices.

## Reporting

Do not post bot tokens, signing keys, or private chat content in public issues. Revoke a leaked bot token immediately through BotFather.
