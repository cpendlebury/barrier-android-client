package com.crrpendlebury.barrierandroid.protocol

/** Lifecycle states of the Barrier client connection. */
enum class BarrierClientState {
    DISCONNECTED,
    CONNECTING,
    HANDSHAKE,
    CONNECTED,
}

/** Errors surfaced by the Barrier protocol layer. */
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

/** Input events delivered from the Barrier server to the local device. */
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

/** Consumer for Barrier client connection and input events. */
interface BarrierClientListener {
    fun onStateChanged(state: BarrierClientState)

    fun onInputEvent(event: BarrierInputEvent)

    fun onError(error: BarrierError)
}
