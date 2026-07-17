# Architecture

## Layers

- **Upstream application:** pinned NewPipe source release.
- **Customization script:** deterministic source patching and branding.
- **Telegram UI:** connection, diagnostics, commands, service control, and queued requests.
- **Telegram domain:** command processing and queue persistence.
- **Telegram data:** HTTPS Bot API client and encrypted token storage.
- **Background execution:** explicit foreground sync service using long polling.
- **Delivery:** GitHub Actions validation, ABI split builds, signing, checksums, and releases.

## Update strategy

Change `UPSTREAM_REF` in the workflow, run the workflow without a release tag, inspect the build artifact and tests, then publish a new version tag. Patch failures are intentional: they indicate an upstream source change that needs review rather than silently producing a broken APK.
