package com.crrpendlebury.barrierandroid.protocol

/**
 * Wire-level command identifiers for the Barrier control protocol, as this
 * client spells them.
 *
 * Every payload starts with a command string. Most commands are exactly four
 * ASCII characters. The greeting command is the seven-byte string "Barrier"
 * and must not be sliced to four bytes: the first four characters are "Barr",
 * which is not a command.
 *
 * [wireLength] is `value.length`, so greeting writes and reads use 7 and every
 * other command uses 4. Decode in [BarrierPacket] special-cases the greeting
 * before it takes the four-byte prefix.
 *
 * This enum does not record direction. Both [HELLO] (server hello and the
 * client's opening hello) and a longer HelloBack share the same command
 * string; length distinguishes them. See [BarrierPacket.decode].
 */
enum class BarrierCommand(val value: String) {
    HELLO("Barrier"),
    QINF("QINF"),
    DINF("DINF"),
    CIAK("CIAK"),
    CINN("CINN"),
    COUT("COUT"),
    CNOP("CNOP"),
    CBYE("CBYE"),
    CCLP("CCLP"),
    CSEC("CSEC"),
    CROP("CROP"),
    CALV("CALV"),
    DKDN("DKDN"),
    DKRP("DKRP"),
    DKUP("DKUP"),
    DMDN("DMDN"),
    DMUP("DMUP"),
    DMMV("DMMV"),
    DMRM("DMRM"),
    DMWM("DMWM"),
    DCLP("DCLP"),
    DSOP("DSOP"),
    DFTR("DFTR"),
    DDRG("DDRG"),
    EICV("EICV"),
    EBSY("EBSY"),
    EUNK("EUNK"),
    EBAD("EBAD");

    /** Length of the command prefix carried in a packet payload. */
    val wireLength: Int get() = value.length
}
