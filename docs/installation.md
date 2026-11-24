# Installation

This page covers installing the current release APK, building/installing the debug APK, and running unit tests. The app connects to a Barrier server and reaches `CONNECTED`, but input injection is limited to discrete gestures and clipboard-based text. Read [Status and limitations](limitations.md) first.

The current public release is 0.1.2, published on GitHub: [GitHub release](https://github.com/cpendlebury/barrier-android-client/releases/tag/0.1.2).

## Release APK — 0.1.2

- Release version: `0.1.2` (`versionCode` 4)
- APK: [`barrier-android-0.1.2.apk`](https://github.com/cpendlebury/barrier-android-client/releases/download/0.1.2/barrier-android-0.1.2.apk)
- SHA-256: `89ee11579d7cf977ede8a1b4f45ab72af0a39f1c814bd5ae21af0c1f5eb97cb0`

Install the release APK directly on the Android device:

1. Open the browser on your Android device and download [`barrier-android-0.1.2.apk`](https://github.com/cpendlebury/barrier-android-client/releases/download/0.1.2/barrier-android-0.1.2.apk).
2. When the download completes, open the notification shade or Downloads and tap the APK, then confirm installation.
3. If Android prompts, allow installing unknown apps from the browser; on Android 11+ you may need to grant that permission.

## Requirements

- JDK 17 or newer. The project is compiled for Java 11 (`sourceCompatibility` / `jvmTarget`) and has been built with JDK 21.
- Android SDK platform 35 (`compileSdk` 35). Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to the SDK, or put an `sdk.dir` line in `local.properties` (that file is machine-local and is not part of the project contract).
- Network access for the first build, so the Gradle wrapper (8.11.1) and dependencies can be downloaded.

Do not pass `--offline` unless you already know every dependency is in the local Gradle cache. A fresh clone will fail offline.

Android Gradle Plugin 8.9.0 and Kotlin 2.0.21 come from `gradle/libs.versions.toml`. You do not install them separately.

## Build

```bash
git clone https://github.com/cpendlebury/barrier-android-client.git
cd barrier-android-client
./gradlew :app:assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release packaging (`:app:assembleRelease`) is configured with the release signing config and produced the signed release build for 0.1.0; the official distribution APK is the GitHub release asset described above. Minify is off.

## Install on a device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The launcher label is **BarrierAndroid**. The application id is `com.crrpendlebury.barrierandroid`.

Declared `minSdk` is 22 (Android 5.1). Do not use that as a support promise. Starting the connection service calls APIs that require Android 10 (API 29), and Android 14 may reject the foreground-service type. See [limitations](limitations.md).

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

That runs the JVM unit tests only. There are no instrumented tests that talk to a Barrier server or an accessibility service. Expected result with the current tree: 48 tests, 0 failures.

## Names you will see

| Name | Value | Meaning |
|------|--------|---------|
| Launcher label | BarrierAndroid | `app_name` in `strings.xml` |
| Application id | `com.crrpendlebury.barrierandroid` | Install id |
| Gradle project name | BarrierAndroid | Set in `settings.gradle.kts`. Informational only; not the app id or label |
