package com.crrpendlebury.barrierandroid.protocol

/**
 * Wire-level command identifiers for the Barrier protocol.
 *
 * Every packet payload starts with a command string. Most commands are exactly
 * four ASCII characters; the greeting handshake command ("Barrier") is longer
 * and is carried in its entirety, hence it is not encoded on four bytes.
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
