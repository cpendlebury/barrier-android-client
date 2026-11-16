# Usage

## 1. Enable the accessibility service

BarrierAndroid cannot inject input unless the accessibility service is enabled by the user.

1. Open **Settings → Accessibility** on the Android device.
2. Find **BarrierAndroid** (under "Downloaded services" or "Installed services").
3. Toggle it **On** and accept the permission dialog.

> The app shows the current status on the main screen: "Accessibility service: enabled/disabled". If disabled, an **Open Accessibility Settings** button is shown.

## 2. Connect to the Barrier server

1. Launch **BarrierAndroid**.
2. Enter the **Server Host** (IP or hostname of the Barrier server).
3. Enter the **Server Port** (default `24800`).
4. Enter a **Client Name** (default `AndroidBarrierClient`; must match the screen name configured on the server).
5. Tap **Connect**.

The status line shows the connection state:

| State | Meaning |
|-------|---------|
| `CONNECTING` | Opening TCP/TLS connection |
| `HANDSHAKE` | Hello/HelloBack + screen info exchange |
| `CONNECTED` | Ready; receiving input |
| `DISCONNECTED` | Not connected (or reconnecting with backoff) |

## 3. Configure the server

On the Barrier server:

1. Open the server configuration.
2. Add a new screen with the **exact client name** you entered in the app (case-sensitive).
3. Position it in the grid relative to the server screen.
4. Apply.

## 4. Input delivery

Once connected, input from the server is injected into the Android device:

- **Mouse motion** (absolute and relative) → pointer moves via gesture dispatch
- **Mouse buttons** (down/up) → gesture tap
- **Scroll wheel** → scroll gesture
- **Text** → injected into the focused editable field via `ACTION_SET_TEXT`
- **Back / Home / Recents** → global actions

> **Limitation**: arbitrary keycodes (letters, digits, modifiers) are not yet injectable via the public accessibility API. Text delivery works through the clipboard/text path (`ACTION_SET_TEXT`), not keystroke-by-keystroke.

## 5. Clipboard sync

- When the server grabs the client clipboard (CCLP), the app reads the device clipboard and replies (DCLP).
- When the server pushes clipboard data (DCLP), the app stores it into the device clipboard and injects it into the focused field.

## 6. Background operation

The app runs a **foreground service** while connected, so the connection and injection survive backgrounding. A persistent notification ("Barrier") is shown. Tap **Disconnect** to stop.

## 7. Reconnect

If the server drops the connection, the client reconnects automatically with exponential backoff (1s → 2s → 4s → … → 30s). User-initiated disconnects do not auto-reconnect.