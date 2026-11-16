package com.crrpendlebury.barrierandroid.protocol

/** Protocol versions spoken by this client (Barrier protocol v1.6). */
const val PROTOCOL_MAJOR_VERSION = 1

const val PROTOCOL_MINOR_VERSION = 6

/** Default Barrier server port and client naming defaults. */
const val DEFAULT_BARRIER_PORT = 24800
const val DEFAULT_CLIENT_NAME = "AndroidBarrierClient"

/** Server keepalive interval (ms) and missed-keepalive tolerance. */
const val KEEP_ALIVE_INTERVAL_MILLIS = 3_000L
const val KEEP_ALIVES_UNTIL_DEATH = 3.0

/** Reconnect backoff bounds (ms) for the Barrier client. */
const val INITIAL_RECONNECT_BACKOFF_MILLIS = 1_000L
const val MAX_RECONNECT_BACKOFF_MILLIS = 30_000L

/** Barrier option identifiers (4-char codes packed big-endian into a 32-bit int). */
val OPTION_CLIPBOARD_SHARING: Int = 0x434C5053  // "CLPS"
val OPTION_HEARTBEAT: Int = 0x48415254            // "HART"
val OPTION_SCREEN_SAVER_SYNC: Int = 0x53535652    // "SSVR"
val OPTION_RELATIVE_MOUSE_MOVES: Int = 0x4D444C54  // "MDLT"

/** Protocol limits. */
const val PROTOCOL_MAX_MESSAGE_LENGTH = 4 * 1024 * 1024
const val PROTOCOL_MAX_STRING_LENGTH = 1024 * 1024
const val MAX_HELLO_LENGTH = 1024
