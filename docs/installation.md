# Installation

This page is for building and installing the debug APK, and for running unit tests. Installing the APK does not produce a working Barrier client. Read [Status and limitations](limitations.md) first.

There is no downloadable release. Clone the repository and build it.

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

Release packaging (`:app:assembleRelease`) is not set up for distribution. Minify is off and there is no signing config in the project.

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

That runs the JVM unit tests only. There are no instrumented tests that talk to a Barrier server or an accessibility service. Expected result with the current tree: 27 tests, 0 failures.

## Names you will see

| Name | Value | Meaning |
|------|--------|---------|
| Launcher label | BarrierAndroid | `app_name` in `strings.xml` |
| Application id | `com.crrpendlebury.barrierandroid` | Install id |
| Gradle project name | FoodDiary | Leftover in `settings.gradle.kts`. Ignore it when looking for the app |
