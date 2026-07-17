#!/usr/bin/env python3
"""Apply the Moataz Dow Android overlay to the pinned application engine."""
from __future__ import annotations

import argparse
import shutil
from pathlib import Path

DEFAULT_API_BASE_URL = (
    "https://xfjybpzadqelzzrrdjhd.supabase.co/functions/v1/moataz-dow"
)


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Expected patch anchor not found in {path}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def copy_overlay(repository_root: Path, source: Path) -> None:
    overlay = repository_root / "overlay"
    for item in overlay.rglob("*"):
        if item.is_dir():
            continue
        relative = item.relative_to(overlay)
        destination = source / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(item, destination)


def patch_gradle(source: Path, version: str, api_base_url: str) -> None:
    if not api_base_url.startswith("https://"):
        raise ValueError("The production API base URL must use HTTPS")
    escaped_api_url = api_base_url.replace("\\", "\\\\").replace('"', '\\"')
    gradle = source / "app/build.gradle.kts"
    replace_once(gradle,
        'val gitWorkingBranch = providers.exec {',
        '''val moatazKeystorePath = System.getenv("MOATAZ_KEYSTORE_PATH")
val moatazKeystorePassword = System.getenv("MOATAZ_KEYSTORE_PASSWORD")
val moatazKeyAlias = System.getenv("MOATAZ_KEY_ALIAS")
val moatazKeyPassword = System.getenv("MOATAZ_KEY_PASSWORD")

val gitWorkingBranch = providers.exec {''')
    replace_once(gradle, 'applicationId = "org.schabi.newpipe"',
                 'applicationId = "com.moataz.dow"')
    replace_once(gradle, 'resValue("string", "app_name", "NewPipe")',
                 'resValue("string", "app_name", "Moataz Dow")\n'
                 f'        buildConfigField("String", "MOATAZ_API_BASE_URL", '
                 f'"\\"{escaped_api_url}\\"")')
    replace_once(gradle, 'versionName = "0.28.8"', f'versionName = "{version}"')
    replace_once(gradle, 'resValue("string", "app_name", "NewPipe Debug")',
                 'resValue("string", "app_name", "Moataz Dow Debug")')
    replace_once(gradle, 'resValue("string", "app_name", "NewPipe $workingBranch")',
                 'resValue("string", "app_name", "Moataz Dow $workingBranch")')
    replace_once(gradle, 'resValue("string", "app_name", "NewPipe $suffix")',
                 'resValue("string", "app_name", "Moataz Dow $suffix")')

    replace_once(gradle,
        '''    buildTypes {
        debug {''',
        '''    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("moatazRelease") {
            if (!moatazKeystorePath.isNullOrBlank()) {
                storeFile = file(moatazKeystorePath)
                storePassword = moatazKeystorePassword
                keyAlias = moatazKeyAlias
                keyPassword = moatazKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {''')

    replace_once(gradle,
        '''        release {
            System.getProperty("packageSuffix")?.let { suffix ->''',
        '''        release {
            if (!moatazKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("moatazRelease")
            }
            System.getProperty("packageSuffix")?.let { suffix ->''')


def patch_manifest(source: Path) -> None:
    manifest = source / "app/src/main/AndroidManifest.xml"
    replace_once(manifest,
        'android:icon="@mipmap/ic_launcher"',
        'android:icon="@drawable/ic_moataz_dow_app"\n        android:roundIcon="@drawable/ic_moataz_dow_app"')
    replace_once(manifest,
        '''        <activity
            android:name=".settings.SettingsActivity"''',
        '''        <activity
            android:name=".telegram.TelegramIntegrationActivity"
            android:exported="false"
            android:label="@string/telegram_integration_title" />

        <service
            android:name=".telegram.TelegramSyncService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />

        <activity
            android:name=".settings.SettingsActivity"''')


def patch_main_activity(source: Path) -> None:
    activity = source / "app/src/main/java/org/schabi/newpipe/MainActivity.java"
    replace_once(activity,
        'import org.schabi.newpipe.settings.UpdateSettingsFragment;',
        'import org.schabi.newpipe.settings.UpdateSettingsFragment;\nimport org.schabi.newpipe.telegram.TelegramIntegrationActivity;')
    replace_once(activity,
        'private static final int ITEM_ID_ABOUT = 2;',
        'private static final int ITEM_ID_ABOUT = 2;\n    private static final int ITEM_ID_TELEGRAM = 3;')
    replace_once(activity,
        '''        //Settings and About
        drawerLayoutBinding.navigation.getMenu()''',
        '''        // Telegram integration
        drawerLayoutBinding.navigation.getMenu()
                .add(R.id.menu_options_about_group, ITEM_ID_TELEGRAM, ORDER,
                        R.string.telegram_integration_title)
                .setIcon(R.drawable.ic_moataz_dow_notification);

        //Settings and About
        drawerLayoutBinding.navigation.getMenu()''')
    replace_once(activity,
        '''            case ITEM_ID_SETTINGS:
                NavigationHelper.openSettings(this);
                break;''',
        '''            case ITEM_ID_TELEGRAM:
                try {
                    startActivity(new Intent(this, TelegramIntegrationActivity.class));
                } catch (final Throwable error) {
                    ErrorUtil.showUiErrorSnackbar(this, "Opening Telegram connection", error);
                }
                break;
            case ITEM_ID_SETTINGS:
                NavigationHelper.openSettings(this);
                break;''')


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--api-base-url", default=DEFAULT_API_BASE_URL)
    args = parser.parse_args()
    source = args.source.resolve()
    repository_root = Path(__file__).resolve().parents[1]
    if not (source / "app/build.gradle.kts").exists():
        raise SystemExit(f"Not a compatible Android source checkout: {source}")
    copy_overlay(repository_root, source)
    patch_gradle(source, args.version, args.api_base_url.rstrip("/"))
    patch_manifest(source)
    patch_main_activity(source)
    print(f"Moataz Dow overlay applied to {source}")


if __name__ == "__main__":
    main()
