package com.crrpendlebury.barrierandroid.protocol

/**
 * States and events the protocol layer shows to the Android UI.
 *
 * Nothing in this file talks to Android. The activity maps these types onto
 * text. Direction of control remains: the computer's Barrier server owns the
 * keyboard and mouse and pushes events here; this process does not send key
 * or pointer events upstream.
 */

/**
 * Lifecycle of one [BarrierClient] session.
 *
 * [CONNECTING] and [HANDSHAKE] are written by the client but are not passed to
 * [BarrierClientListener.onStateChanged]. The listener hears [CONNECTED] and
 * [DISCONNECTED] only. Do not write UI that waits for a handshake callback.
 */
enum class BarrierClientState {
    DISCONNECTED,
    CONNECTING,
    HANDSHAKE,
    CONNECTED,
}

/**
 * Failures the client chooses to surface.
 *
 * Socket connect failures, read errors, and unknown packets are not values of
 * this type. [SocketBarrierTransport] swallows read errors, and a thrown
 * [BarrierTransport.connect] is caught by the foreground service, which wraps
 * the connect call in `runCatching` and surfaces the failure through the client
 * listener. Only an incompatible hello and the server rejection packets (`EBSY`,
 * `EUNK`, `EBAD`) become a [BarrierError].
 */
sealed class BarrierError {
    data class IncompatibleVersion(
        val serverMajorVersion: Int,
        val serverMinorVersion: Int,
    ) : BarrierError() {

        fun describe(): String = "incompatible server version ${serverMajorVersion}.${serverMinorVersion}"
    }

    data class ConnectionFailed(
        val message: String,
    ) : BarrierError() {

        fun describe(): String = message
    }
}

/**
 * Input the server asked this client to apply locally.
 *
 * Field names match the packet fields, not Android [android.view.KeyEvent]
 * or [android.view.MotionEvent] constants. In particular [KeyDown.keyId] is a
 * Barrier key id. Comparing it to `KeyEvent.KEYCODE_*` will not match real
 * server traffic. [KeyDown.button] is a separate protocol field; this client
 * does not interpret it.
 *
 * The activity currently ignores [ScreenEntered] and [ScreenLeft]. Pointer
 * and key subclasses are forwarded to the accessibility service only if that
 * service's [com.crrpendlebury.barrierandroid.accessibility.BarrierAccessibilityService.instance]
 * is non-null, which requires the user to have enabled a service this manifest
 * does not fully register.
 */
sealed class BarrierInputEvent {
    data class KeyDown(
        val keyId: Int,
        val modifierMask: Int,
        val button: Int,
    ) : BarrierInputEvent()

    data class KeyRepeat(
        val keyId: Int,
        val modifierMask: Int,
        val repeat: Int,
        val button: Int,
    ) : BarrierInputEvent()

    data class KeyUp(
        val keyId: Int,
        val modifierMask: Int,
        val button: Int,
    ) : BarrierInputEvent()

    data class MouseMove(
        val x: Int,
        val y: Int,
    ) : BarrierInputEvent()

    data class MouseRelativeMove(
        val dx: Int,
        val dy: Int,
    ) : BarrierInputEvent()

    data class MouseWheel(
        val xDelta: Int,
        val yDelta: Int,
    ) : BarrierInputEvent()

    data class MouseButtonDown(
        val button: Int,
    ) : BarrierInputEvent()

    data class MouseButtonUp(
        val button: Int,
    ) : BarrierInputEvent()

    data class ScreenEntered(
        val x: Int,
        val y: Int,
        val seq: Int,
        val modifierMask: Int,
    ) : BarrierInputEvent()

    data class ScreenLeft(
        val seq: Int,
    ) : BarrierInputEvent()
}

/**
 * Callbacks from [BarrierClient] into the host.
 *
 * Invoked on the client's [kotlinx.coroutines.CoroutineScope], which in the
 * app is `Dispatchers.IO`. Implementations must hop to the main thread before
 * touching views. [onStateChanged] is not called for [BarrierClientState.CONNECTING]
 * or [BarrierClientState.HANDSHAKE].
 *
 * The foreground service wraps the activity listener and, on
 * [BarrierClientState.DISCONNECTED], forwards the state without stopping the
 * service; the client publishes that state before scheduling a reconnect, so
 * stopping here would cancel the scope the reconnect loop needs. A listener
 * that treats every disconnect as fatal will defeat [BarrierClient]'s backoff
 * loop.
 */
interface BarrierClientListener {
    fun onStateChanged(state: BarrierClientState)

    fun onInputEvent(event: BarrierInputEvent)

    fun onError(error: BarrierError)

    /**
     * Invoked when the TLS handshake completes and the server fingerprint is
     * observed, or with a null fingerprint when the transport did not run TLS
     * ([SocketBarrierTransport] clears the observed value for plaintext). The
     * value is the SHA-256 fingerprint of the server leaf certificate, lowercase
     * hex with no separators; render it for display with
     * [BarrierServerTrustManager.format]. Invoked on the client's scope, not
     * the main thread; implementations must hop to the main thread before
     * touching views.
     */
    fun onServerFingerprintObserved(fingerprint: String?)
}
