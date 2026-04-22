package com.app.canconnection.hardware

/**
 * Builds SLCAN (Serial Line CAN) ASCII protocol strings for standard serial CAN adapters
 * such as CANable, Canable Pro, or any adapter implementing the SLCAN specification.
 *
 * All commands are single-line ASCII strings terminated by carriage return '\r':
 *  - S<n>\r              — set bitrate (S0 = 10 kbps … S8 = 1 Mbps)
 *  - O\r                 — open CAN channel (start receiving)
 *  - C\r                 — close CAN channel
 *  - t<id3><dlc><data>\r — transmit standard 11-bit frame (lowercase 't')
 *  - T<id8><dlc><data>\r — transmit extended 29-bit frame (uppercase 'T')
 *
 * This object is stateless; all functions are pure and throw [IllegalArgumentException]
 * when arguments violate CAN protocol limits.
 */
object CanFrameBuilder {

    enum class FrameType { STANDARD, EXTENDED }

    /** Returns the SLCAN speed-select command string for the given [bitrate] in bps. */
    fun setCanSpeed(bitrate: Int): String = when (bitrate) {
        10000   -> "S0\r"
        20000   -> "S1\r"
        50000   -> "S2\r"
        100000  -> "S3\r"
        125000  -> "S4\r"
        250000  -> "S5\r"
        500000  -> "S6\r"
        800000  -> "S7\r"
        1000000 -> "S8\r"
        else    -> throw IllegalArgumentException("Unsupported bitrate: $bitrate")
    }

    /** Returns the SLCAN open-channel command ("O\r"). */
    fun openChannel(): String = "O\r"

    /** Returns the SLCAN close-channel command ("C\r"). */
    fun closeChannel(): String = "C\r"

    /**
     * Builds a SLCAN transmit command string for the given [canId] and [data].
     *
     * @param frameType [FrameType.STANDARD] produces a 3-hex-digit ID (t…); [FrameType.EXTENDED] produces 8-digit (T…).
     * @param canId     Arbitration ID. Must be ≤ 0x7FF for Standard, ≤ 0x1FFFFFFF for Extended.
     * @param data      Data payload; maximum 8 bytes (CAN 2.0 limit).
     */
    fun buildFrame(frameType: FrameType, canId: Int, data: ByteArray): String {
        if (data.size > 8) throw IllegalArgumentException("Max 8 data bytes")
        val dataHex = data.joinToString("") { "%02X".format(it) }
        return when (frameType) {
            FrameType.STANDARD -> {
                if (canId > 0x7FF)
                    throw IllegalArgumentException("CAN ID exceeds 0x7FF for Standard frame")
                "t${canId.toString(16).padStart(3, '0').uppercase()}${data.size}$dataHex\r"
            }
            FrameType.EXTENDED -> {
                if (canId > 0x1FFFFFFF)
                    throw IllegalArgumentException("CAN ID out of range for Extended frame")
                "T${canId.toString(16).padStart(8, '0').uppercase()}${data.size}$dataHex\r"
            }
        }
    }
}
