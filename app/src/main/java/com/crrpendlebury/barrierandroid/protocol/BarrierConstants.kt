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

/** Protocol limits. */
const val PROTOCOL_MAX_MESSAGE_LENGTH = 4 * 1024 * 1024
const val PROTOCOL_MAX_STRING_LENGTH = 1024 * 1024
const val MAX_HELLO_LENGTH = 1024
