# Installation

## Prerequisites

- **JDK 21** (or 17+)
- **Android SDK** with platform `android-35` and build-tools
- **Gradle** (wrapper included — `./gradlew`)
- A Barrier server reachable on the network (v2.4.0)

Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to your SDK path, e.g.:

```bash
export ANDROID_HOME=/home/chris/Android/Sdk
```

## Build from source

```bash
git clone https://github.com/cpendlebury/barrier-android-client.git
cd barrier-android-client
```

### Debug APK

```bash
./gradlew :app:assembleDebug --offline
```

The APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Release APK

```bash
./gradlew :app:assembleRelease --offline
```

> Note: release builds are unsigned by default. Sign with your keystore for distribution.

## Install on device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the device and install manually (enable "Install from unknown sources" if prompted).

## Run tests

```bash
./gradlew :app:testDebugUnitTest --offline
```

Expected: 27 tests passing (PacketCodecTest 16, BarrierClientTest 11).

## Environment notes

- **Offline builds**: all dependencies are expected to be cached. Use `--offline`.
- **`androidx.appcompat`** is not cached in this environment; the app uses Compose + core-ktx only, so this causes no issues.
- **TLS**: `SSLSocketFactory.createSSLSocket` is missing in `android-35`, so TLS socket creation uses reflection. This is handled internally.
- **JDK**: target/source compatibility is Java 11; the build runs on JDK 21.