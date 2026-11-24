# Using the app

This page covers setting the phone up, connecting to a Barrier server, and what each input does on the phone. For the limits of each feature see [Status and limitations](limitations.md).

## First-time setup

1. Install the APK (see [installation](installation.md)).
2. Open **Settings → Accessibility → BarrierAndroid** and turn the service on. On Android 13+ you must first allow restricted settings (see [troubleshooting](troubleshooting.md#app-was-denied-access-android-13)). The main screen shows `Accessibility service: enabled` once it is on.
3. Enable the Barrier keyboard in **Settings → Keyboard** (or **Language & input**). It is the input method that delivers keystrokes. The main screen shows the keyboard status. The app switches to the Barrier keyboard on connect and restores your previous keyboard on disconnect.
4. On the Barrier server, add a screen named `AndroidBarrierClient` (or the name you set in the app) and place it next to the computer's screen in the layout.

## Connecting

1. Enter the server's host name or IP and the port (24800 by default).
2. Optionally change the client name. It must match the screen name on the server exactly.
3. Tap **Connect**. The status line goes from `DISCONNECTED` to `CONNECTED` after the handshake. The first connection to a server shows its certificate fingerprint on the main screen; the app accepts and remembers that fingerprint.
4. Tap **Disconnect** to stop the service. Leaving the app does not stop it, so the connection survives switching to other apps. A notification shows while connected.

If the server goes away, the app reconnects with a backoff of 1 s up to 30 s. It keeps trying until you tap **Disconnect**.

## Moving the pointer and clicking

The host's pointer can move onto the phone. A white arrow cursor follows the host pointer over other apps. Movement does not send a gesture, so it does not scroll or type anything by itself.

- **Left click**: a tap at the cursor position. Press and release are a click, and holding the button drags.
- **Middle click**: a tap at the cursor position.
- **Right click** and the mouse's **back** side button: navigate back (Android Back).
- **Forward** side button: nothing. Android has no public forward action.

The cursor hides when the button is released, when the client disconnects, and when the screensaver starts. Injected input shows it again.

Known issue: in the stock Android Files app, a tapped folder does not open. Use another file manager such as Fossify File Manager (see [troubleshooting](troubleshooting.md#stock-files-app-does-not-open-folders)).

## Scrolling

Mouse wheel events scroll at the cursor position:

- Wheel forward scrolls the page up.
- Wheel back scrolls the page down.
- Horizontal wheel events (`xDelta`) scroll sideways. These are not validated on device.

Each wheel notch is a fixed 200 px swipe. Some apps ignore injected swipes and will not scroll.

## Typing

With the Barrier keyboard enabled and selected, keys typed on the host go into the focused text field on the phone: letters, numbers, punctuation, Enter, Backspace, Delete, Tab, and the arrow keys.

Without the Barrier keyboard active, ordinary keystrokes are dropped. Escape still goes Back, and the shortcuts below still work.

## Host shortcuts

These host chords run phone actions and do not need a focused field:

| Host chord | Phone action |
|------------|--------------|
| Escape | Back |
| Meta+H or Meta+Enter | Home |
| Alt+Tab or Meta+Tab | Recent apps |
| Meta+N | Notification shade |
| Meta+S | Quick settings |
| Meta+L | Lock screen (Android 9+) |
| Meta+Ctrl+S or Print Screen | Screenshot (Android 9+) |

Ctrl, Alt, and Meta combined with a letter or digit (for example Ctrl+L or Alt+Left) are sent as key events to the focused field, so an app's own shortcut can handle them. This needs a focused text field, and not every app honours synthetic key events.

Meta alone (app drawer), Meta+A (assistant), and Meta+/ (keyboard shortcuts) are not mapped.

## Clipboard

The clipboard is shared in both directions, with two steps.

- **From the server to the phone**: copy text on the server. It is pushed to the phone and stored in the device clipboard. Nothing is typed into a field.
- **Pasting on the phone**: press Ctrl+V on the host with a text field focused on the phone. The stored clipboard is committed into the field.
- **From the phone to the server**: press Ctrl+C or Ctrl+X on the host with an editable field focused on the phone. Ctrl+C copies the field's text into the device clipboard. Ctrl+X copies it and clears the field. The text is sent to the server when the server asks the phone for its clipboard, which is how Barrier fetches it.

Host Ctrl+C, Ctrl+X, and Ctrl+V use key ids `0x63` (C), `0x78` (X), and `0x76` (V) with the Control modifier. Copy and cut work only when an editable field has focus on the phone.

Clipboard sharing can be turned off by the server (`CLPS` option). In that case no clipboard data is sent or stored.

## Screen wake

When the screen is off or locked, host input wakes the display. The wake lasts 5 seconds after each input event. A locked phone still needs to be unlocked, and on some devices the wake may not happen (see [troubleshooting](troubleshooting.md#screen-does-not-wake)).

## Debug logging

The **Debug logging** checkbox writes the app's diagnostic log to `barrier_debug.log` in the app's external files directory. It is off by default. Turn it on to report a problem, and turn it off again afterwards. Unchecking it deletes the existing log.
