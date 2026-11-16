# Troubleshooting

## Connect does nothing useful

Expected with this tree. **Connect** starts a foreground service and does not open a socket. The status line stays `Barrier state: DISCONNECTED`. The notification, if the process gets that far, stays on "Waiting for Barrier server".

Cause: `MainActivity` sets `BarrierForegroundService.pendingConnection` on the companion object. `onStartCommand` reads an instance field of the same name, which is always null. The activity's listener is never assigned to `connectionListener` either, so input would still be dropped after that read is fixed.

This is not a firewall, hostname, or server-config problem.

## The accessibility service is not in Settings

Expected. The manifest service has the bind permission and the intent action, and no `android.accessibilityservice` meta-data resource. Android does not list a service that lacks that XML. The button in the app only opens the system screen.

`Accessibility service: disabled` is the correct reading of secure settings for this build.

## The app crashes when Connect is tapped

On Android 5.1–9, `BarrierForegroundService` calls `NotificationChannel` and the three-argument `startForeground` with no `SDK_INT` check. Those require API 26 and API 29. `minSdk` 22 does not mean those versions work.

On Android 14 and later, a `specialUse` foreground service without `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` can be rejected. There is no such property in the manifest.

## "incompatible server version" / "server is busy" / "unknown client"

Those strings exist for `EICV`, `EBSY`, `EUNK`, and `EBAD` if a client is running and the listener is attached. The current app never gets there. If you see them, you are not running this wiring.

A TCP failure, TLS failure, or timeout will not show those messages. The transport does not map socket errors onto `BarrierError`.

## Text injection failed / no focused field

Those strings are only set from `handleClipboardData`, which only runs from `onClipboardData`. Connect does not register that callback on a live client, so this error means some other caller invoked the handler. It does not mean typing from the server works.

## Build fails offline

`--offline` is for a machine that already cached the dependencies. A fresh clone needs network. Remove `--offline` and retry.

## The Gradle project name

`settings.gradle.kts` sets `rootProject.name = "BarrierAndroid"`. The Gradle name is informational only; it is not the application id (`com.crrpendlebury.barrierandroid`) or the launcher label (`BarrierAndroid`).

## Tests pass but the phone does nothing

That is consistent. The 27 unit tests drive `BarrierClient` through `FakeBarrierTransport`. They never start `BarrierForegroundService`, never open a socket, and never enable an accessibility service.
