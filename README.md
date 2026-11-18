# world live tv — Android

A native Android wrapper and **Media3/ExoPlayer** player for the public live-TV country/channel directory.

**Credit: KAYRA HAN AYDOĞAN**

## Build a debug APK with GitHub

1. Create a GitHub repository (public **or** private).
2. Upload **the contents of this folder to the repository root**. Keep `.github/workflows/android-apk.yml` and `app/` as directories; do not upload the ZIP as a single file.
3. On GitHub, open **Actions** → **Build APK** → **Run workflow** (or push to `main`).
4. After a successful build, open the run → **Artifacts** → `world-live-tv-debug-apk`.
5. Download the artifact ZIP, extract it, and install `app-debug.apk` on Android. You may need to allow installs from that file manager/browser.

The pipeline uses `actions/setup-java@v4` (JDK 17), `gradle/actions/setup-gradle@v4` (Gradle 8.13), and the preinstalled Ubuntu runner Android SDK `cmdline-tools/latest/bin/sdkmanager` to install **platforms;android-36** and **build-tools;35.0.0 / 36.0.0**. There is no obsolete `tools` SDK package, no `android-actions/setup-android@v3`, and no Gradle-wrapper JAR dependency. This repository intentionally runs `gradle :app:assembleDebug`, not `./gradlew`.

## What it includes

- Android-native player using ExoPlayer + HLS + DASH + progressive formats, with automatic source and format retries and timeouts.
- Worldwide public catalog, country flags, searching, favorite channels, local history, alternate stream sources, playlist import.
- M3U and JSON file import/export through Android's system file picker.
- Native full-screen video controls, picture-in-picture on supported devices, and open externally.
- The HTML/CSS/JS interface is bundled inside the APK. **No hosted website is required to load the interface**; live TV/catalog requests still require internet.

## Requirements / limitations

- **Android 8.0+ (API 26)**, an internet connection, current Android System WebView.
- Streams are community maintained, may fail or be geo-blocked, DRM protected, subscriber-only, or removed. No bypass of DRM, authorization, or geo restrictions is implemented.
- HTTPS and HTTP streams are both supported in native playback (some broadcasters still only offer HTTP). This is why `usesCleartextTraffic` is set to `true`; streams are untrusted third-party destinations.
- The local UI is loaded via `WebViewAssetLoader` at `https://appassets.androidplatform.net/assets/index.html`. The bridge is exposed only to local bundled content; remote pages are opened in an external browser.
- The `debug` APK is automatically signed with a **debug** key for testing. A production Play Store release requires your own keystore and release-signing configuration.
- The code was checked for structure and JavaScript syntax, but **a complete Gradle/Android SDK build was not executed in this environment**. Use GitHub Actions to validate the real Android build.

## Local Android Studio build

Open the repository folder in Android Studio, let Gradle sync, select `app`, and run **Build > Build APK(s)**. Alternatively, install Gradle 8.13 and run `gradle :app:assembleDebug` with SDK 36 available.

## Source ownership

This app lists externally maintained public channel indexes. It does not distribute hosted TV streams. You are responsible for the rights and permissions associated with streams you add.
