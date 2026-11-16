# barrier-android-client

Experimental Android **client** for a [Barrier](https://github.com/debauchee/barrier) server.

The intended setup is the normal Barrier one, with the phone as a secondary screen:

- The computer runs the Barrier **server** and owns the real keyboard and mouse.
- The phone runs this **client**.
- The server sends input to the phone. The phone is supposed to act on that input.
- The phone does not display the computer's screen. Video is out of scope.
- Clipboard sharing is in scope.

This repository does **not** turn the phone into a keyboard or mouse for the computer. Control is meant to flow the other way: computer keyboard and mouse, into the phone.

It is also **not a working client yet**. The protocol code is unit-tested. The Android app does not open a Barrier session, and it cannot inject input from a live connection. See [Status and limitations](docs/limitations.md) before trying to use it.

Launcher name: **BarrierAndroid**. Application id: `com.crrpendlebury.barrierandroid`. There are no published releases and no license file in this repository.

## What is actually implemented

| Area | State |
|------|--------|
| Packet codec and client state machine | Implemented and covered by unit tests against an in-memory transport |
| TCP session from the app | Not started. Connect stores a request the service never reads |
| Barrier TLS, or a plaintext fallback | Not implemented. The socket helper does not open a plain `Socket`, and it does not speak Barrier's TLS handshake |
| Pointer, key, and text injection | Methods exist on the accessibility service. Nothing in a live session calls them, and the service is not registered so Android will show it in Accessibility settings |
| Clipboard, screensaver, options, reconnect | Handled inside `BarrierClient` and unit-tested. Not reachable from the app, and several of those values are stored without changing behaviour |

## Documentation

| Document | What it covers |
|----------|----------------|
| [Status and limitations](docs/limitations.md) | What works, what does not, and why |
| [Installation](docs/installation.md) | Build the debug APK and run the unit tests |
| [Using the app](docs/usage.md) | What the current screen does when you tap Connect |
| [Configuration](docs/configuration.md) | Defaults, and which server options the code actually applies |
| [Troubleshooting](docs/troubleshooting.md) | Why a session does not come up |
| [Architecture](docs/architecture.md) | How the pieces fit together, including the broken wiring |
| [Protocol notes](docs/protocol-reference.md) | What this client's codec implements. Not a Barrier specification |

## Building

Requirements and commands are in [Installation](docs/installation.md). Short version, from a checkout with network access:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Installing it will not give you a working Barrier screen.
