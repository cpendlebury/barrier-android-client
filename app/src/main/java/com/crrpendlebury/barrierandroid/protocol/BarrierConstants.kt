package com.crrpendlebury.barrierandroid.protocol

/**
 * Compile-time protocol numbers for this Barrier client.
 *
 * Direction of control: the computer runs the Barrier server and owns the
 * keyboard and mouse. This process is the client. These constants are what
 * the client advertises and what it uses for timers. They are not user
 * preferences, and the Android UI does not expose them.
 *
 * Version check in [BarrierClient]: a server hello is accepted only when
 * major equals [PROTOCOL_MAJOR_VERSION] and minor is at least
 * [PROTOCOL_MINOR_VERSION]. That is "1.6 or newer minor of major 1", not
 * "any 1.x".
 */
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

/**
 * Barrier option ids. Each is the 4-character code from Barrier's
 * `OPTION_CODE` macro, packed big-endian into a 32-bit int
 * (`'C'<<24 | 'L'<<16 | 'P'<<8 | 'S'` for clipboard sharing).
 *
 * [BarrierClient.applyOptions] reads only the first three. [OPTION_RELATIVE_MOUSE_MOVES]
 * is named so a later editor does not invent a second numeric id, but nothing
 * reads it yet. Storing an option does not mean the client honours it: clipboard
 * handlers ignore [OPTION_CLIPBOARD_SHARING], and the keepalive timer ignores
 * [OPTION_HEARTBEAT].
 */
val OPTION_CLIPBOARD_SHARING: Int = 0x434C5053  // "CLPS"
val OPTION_HEARTBEAT: Int = 0x48415254            // "HART"
val OPTION_SCREEN_SAVER_SYNC: Int = 0x53535652    // "SSVR"
val OPTION_RELATIVE_MOUSE_MOVES: Int = 0x4D444C54  // "MDLT"

/** Protocol limits. */
const val PROTOCOL_MAX_MESSAGE_LENGTH = 4 * 1024 * 1024
const val PROTOCOL_MAX_STRING_LENGTH = 1024 * 1024
const val MAX_HELLO_LENGTH = 1024
