package com.app.canconnection.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages USB connectivity for the PEAK PCAN-USB adapter (VID 0x0C72 / PID 0x000C).
 *
 * Implements the PEAK proprietary binary USB bulk-transfer protocol, based on the
 * Linux kernel driver: drivers/net/can/usb/peak_usb/pcanusb.c
 *
 * Three bulk endpoints are used:
 *  - EP 0x01 (CMD OUT) — 16-byte control commands (set bitrate, bus on/off, error reporting)
 *  - EP 0x02 (MSG OUT) — 64-byte CAN TX frames
 *  - EP 0x82 (MSG IN)  — 64-byte RX packets, each containing one or more CAN frame records
 *
 * Each RX record begins with a status/flags byte (SL byte) whose bits encode:
 *  - bits 3–0: DLC (data length code, 0–8)
 *  - bit 4: RTR frame flag
 *  - bit 5: extended (29-bit) CAN ID flag
 *  - bit 6: internal (non-CAN) status record flag
 *  - bit 7: timestamp present flag
 *
 * The device always appends a 2-byte timestamp after the CAN ID regardless of the
 * TIMESTAMP flag; this is skipped unconditionally during frame parsing.
 *
 * [onLog] receives raw protocol diagnostic messages so they can be shown in the UI
 * when the "Adapter info" toggle is enabled.
 */
class PeakCanConnectionManager(private val context: Context) {

    companion object {
        const val VENDOR_ID  = 0x0C72
        const val PRODUCT_ID = 0x000C

        private const val ACTION_USB_PERMISSION = "com.app.canconnection.PEAK_USB_PERMISSION"

        private const val EP_CMD_OUT = 0x01
        private const val EP_MSG_OUT = 0x02
        private const val EP_MSG_IN  = 0x82

        private const val CMD_LEN        = 16
        private const val RX_BUFFER_SIZE = 64
        private const val TX_BUFFER_SIZE = 64
        private const val USB_TIMEOUT_MS = 1000

        private const val CMD_BITRATE = 0x01
        private const val CMD_SET_BUS = 0x03
        private const val CMD_EXT_VCC = 0x0A
        private const val CMD_ERR_FR  = 0x0B

        private const val CMD_SET   = 0x02
        private const val BUS_XCVER = 0x02

        private const val SL_DLC_MASK  = 0x0F
        private const val SL_RTR       = 0x10
        private const val SL_EXT_ID    = 0x20
        private const val SL_INTERNAL  = 0x40
        private const val SL_TIMESTAMP = 0x80

        private const val MSG_TX_CAN = 0x02

        private val BTR_MAP = mapOf(
            10000   to byteArrayOf(0x3A.toByte(), 0x31.toByte()),
            20000   to byteArrayOf(0x3A.toByte(), 0x18.toByte()),
            50000   to byteArrayOf(0x3A.toByte(), 0x09.toByte()),
            100000  to byteArrayOf(0x3A.toByte(), 0x04.toByte()),
            125000  to byteArrayOf(0x3A.toByte(), 0x03.toByte()),
            250000  to byteArrayOf(0x3A.toByte(), 0x01.toByte()),
            500000  to byteArrayOf(0x3A.toByte(), 0x00.toByte()),
            800000  to byteArrayOf(0x16.toByte(), 0x00.toByte()),
            1000000 to byteArrayOf(0x14.toByte(), 0x00.toByte())
        )
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epCmdOut: UsbEndpoint? = null
    private var epMsgOut: UsbEndpoint? = null
    private var epMsgIn: UsbEndpoint? = null
    private var txCounter = 0
    private var readJob: Job? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onDeviceDetached: (() -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var lastError: String = ""
        private set

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                disconnect()
                onDeviceDetached?.invoke()
            }
        }
    }

    init {
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(detachReceiver, filter)
        }
    }

    fun requestPermission(device: UsbDevice, onGranted: () -> Unit) {
        if (usbManager.hasPermission(device)) { onGranted(); return }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val permIntent = PendingIntent.getBroadcast(context, 1, Intent(ACTION_USB_PERMISSION), flags)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    ctx.unregisterReceiver(this)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        onGranted()
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
        }
        usbManager.requestPermission(device, permIntent)
    }

    fun connect(device: UsbDevice, bitrate: Int): Boolean {
        lastError = ""
        return try {
            val btr = BTR_MAP[bitrate] ?: BTR_MAP[500000]!!

            onLog?.invoke("[INIT] Opening device VID:%04X PID:%04X".format(device.vendorId, device.productId))
            val conn = usbManager.openDevice(device) ?: run {
                lastError = "openDevice returned null (permission missing?)"
                onLog?.invoke("[ERR] $lastError")
                return false
            }

            val iface = device.getInterface(0)
            onLog?.invoke("[INIT] Claiming interface 0 (endpoints: ${iface.endpointCount})")
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                lastError = "claimInterface(0) failed"
                onLog?.invoke("[ERR] $lastError")
                return false
            }

            epCmdOut = findEndpoint(iface, EP_CMD_OUT)
            epMsgOut = findEndpoint(iface, EP_MSG_OUT)
            epMsgIn  = findEndpoint(iface, EP_MSG_IN)

            onLog?.invoke("[INIT] EP_CMD_OUT(0x01)=${epCmdOut != null}  EP_MSG_OUT(0x02)=${epMsgOut != null}  EP_MSG_IN(0x82)=${epMsgIn != null}")

            if (epCmdOut == null || epMsgOut == null || epMsgIn == null) {
                conn.releaseInterface(iface); conn.close()
                lastError = "Required endpoints not found"
                onLog?.invoke("[ERR] $lastError")
                return false
            }

            connection = conn
            usbInterface = iface

            onLog?.invoke("[INIT] CMD_BITRATE: ${bitrate / 1000}kbps  BTR1=0x%02X BTR0=0x%02X".format(btr[0].toInt() and 0xFF, btr[1].toInt() and 0xFF))
            sendCmd(CMD_BITRATE, CMD_SET, btr)
            Thread.sleep(10)

            onLog?.invoke("[INIT] CMD_EXT_VCC: external VCC off")
            sendCmd(CMD_EXT_VCC, CMD_SET, byteArrayOf(0x00))

            onLog?.invoke("[INIT] CMD_ERR_FR: enable bus-error reporting (mask=0x06)")
            sendCmd(CMD_ERR_FR, CMD_SET, byteArrayOf(0x06))

            onLog?.invoke("[INIT] CMD_SET_BUS: transceiver ON (bus open)")
            sendCmd(CMD_SET_BUS, BUS_XCVER, byteArrayOf(0x01))
            Thread.sleep(10)

            onLog?.invoke("[INIT] Init complete — listening on EP 0x82")
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            onLog?.invoke("[ERR] connect() exception: $lastError")
            disconnect()
            false
        }
    }

    fun disconnect() {
        readJob?.cancel()
        readJob = null
        try {
            onLog?.invoke("[INIT] CMD_SET_BUS: transceiver OFF (bus close)")
            sendCmd(CMD_SET_BUS, BUS_XCVER, byteArrayOf(0x00))
        } catch (_: Exception) {}
        try { connection?.releaseInterface(usbInterface) } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        connection = null; usbInterface = null
        epCmdOut = null; epMsgOut = null; epMsgIn = null
        txCounter = 0
    }

    fun sendFrame(canId: Int, data: ByteArray, isExtended: Boolean): Boolean {
        val conn = connection ?: return false
        val ep   = epMsgOut  ?: return false
        val buf  = ByteArray(TX_BUFFER_SIZE)

        buf[0] = MSG_TX_CAN.toByte()
        buf[1] = 1

        val dlc = data.size.coerceIn(0, 8)
        var sl  = dlc
        var ptr = 3

        if (isExtended) {
            sl = sl or SL_EXT_ID
            buf[2] = sl.toByte()
            val rawId = (canId.toLong() and 0x1FFF_FFFFL) shl 3
            buf[ptr++] = (rawId          and 0xFF).toByte()
            buf[ptr++] = ((rawId shr  8) and 0xFF).toByte()
            buf[ptr++] = ((rawId shr 16) and 0xFF).toByte()
            buf[ptr++] = ((rawId shr 24) and 0xFF).toByte()
        } else {
            buf[2] = sl.toByte()
            val rawId = (canId and 0x7FF) shl 5
            buf[ptr++] = (rawId        and 0xFF).toByte()
            buf[ptr++] = ((rawId shr 8) and 0xFF).toByte()
        }

        data.copyInto(buf, ptr, 0, dlc)
        val dataEnd = ptr + dlc
        buf[TX_BUFFER_SIZE - 1] = (txCounter++ and 0xFF).toByte()

        onLog?.invoke("[→TX]  ${buf.toHex(dataEnd)} ... ctr=%02X".format(buf[TX_BUFFER_SIZE - 1].toInt() and 0xFF))

        val result = conn.bulkTransfer(ep, buf, TX_BUFFER_SIZE, USB_TIMEOUT_MS)
        if (result <= 0) onLog?.invoke("[ERR] TX bulkTransfer failed (result=$result)")
        return result > 0
    }

    fun startReading(onData: (String) -> Unit) {
        readJob = ioScope.launch {
            val buf = ByteArray(RX_BUFFER_SIZE)
            onLog?.invoke("[INIT] RX loop started on EP 0x82")
            while (isActive) {
                try {
                    val conn = connection ?: break
                    val ep   = epMsgIn    ?: break
                    val len  = conn.bulkTransfer(ep, buf, RX_BUFFER_SIZE, 100)
                    when {
                        len > 2 -> {
                            val (frames, statusLogs) = parseRxFrames(buf, len)
                            onLog?.invoke("[←RX]  ${buf.toHex(len)}")
                            statusLogs.forEach { onLog?.invoke(it) }
                            frames.forEach { frame -> onData(formatFrame(frame)) }
                        }
                        len in 1..2 -> onLog?.invoke("[←RX]  ${buf.toHex(len)}")
                        len < 0     -> { /* timeout — normal poll interval */ }
                    }
                } catch (e: Exception) {
                    onLog?.invoke("[ERR] RX exception: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            onLog?.invoke("[INIT] RX loop ended")
        }
    }

    private data class RxFrame(
        val canId: Int, val dlc: Int, val data: ByteArray,
        val isRtr: Boolean, val isExtended: Boolean
    )

    private fun parseRxFrames(buf: ByteArray, len: Int): Pair<List<RxFrame>, List<String>> {
        val frames     = mutableListOf<RxFrame>()
        val statusLogs = mutableListOf<String>()
        if (len <= 2) return Pair(frames, statusLogs)

        val recCount = buf[1].toInt() and 0xFF
        var ptr      = 2

        for (recIdx in 0 until recCount) {
            if (ptr >= len) break
            val sl           = buf[ptr++].toInt() and 0xFF
            val dlc          = sl and SL_DLC_MASK
            val isRtr        = (sl and SL_RTR)       != 0
            val isExtId      = (sl and SL_EXT_ID)    != 0
            val isInternal   = (sl and SL_INTERNAL)  != 0
            val hasTimestamp = (sl and SL_TIMESTAMP) != 0
            val tsBytes      = if (recIdx == 0) 2 else 1

            if (isInternal) {
                if (ptr + 2 > len) break
                val func = buf[ptr].toInt() and 0xFF
                val num  = buf[ptr + 1].toInt() and 0xFF
                ptr += 2
                if (hasTimestamp) { if (ptr + tsBytes > len) break; ptr += tsBytes }
                val dataLen = dlc.coerceAtMost(len - ptr).coerceAtLeast(0)
                val data    = ByteArray(dataLen)
                if (dataLen > 0) System.arraycopy(buf, ptr, data, 0, dataLen)
                ptr += dlc
                statusLogs.add(decodeInternalRecord(dlc, func, num, data))
                continue
            }

            val canId: Int
            if (isExtId) {
                if (ptr + 4 > len) break
                val raw = (buf[ptr].toInt() and 0xFF)          or
                          ((buf[ptr + 1].toInt() and 0xFF) shl  8) or
                          ((buf[ptr + 2].toInt() and 0xFF) shl 16) or
                          ((buf[ptr + 3].toInt() and 0xFF) shl 24)
                canId = raw ushr 3; ptr += 4
            } else {
                if (ptr + 2 > len) break
                val raw = (buf[ptr].toInt() and 0xFF) or ((buf[ptr + 1].toInt() and 0xFF) shl 8)
                canId = raw ushr 5; ptr += 2
            }

            // PCAN-USB always includes a timestamp between ID and data regardless of TIMESTAMP flag.
            if (ptr + tsBytes > len) break
            ptr += tsBytes

            val data = ByteArray(dlc)
            if (!isRtr && dlc > 0) {
                if (ptr + dlc > len) break
                System.arraycopy(buf, ptr, data, 0, dlc)
            }
            ptr += dlc
            frames.add(RxFrame(canId, dlc, data, isRtr, isExtId))
        }
        return Pair(frames, statusLogs)
    }

    private fun decodeInternalRecord(recType: Int, func: Int, num: Int, data: ByteArray): String =
        when (recType) {
            1 -> "[PEAK STATUS] Bus error — TEC=%02X REC=%02X".format(func, num)
            2 -> "[PEAK STATUS] Device analog (voltage/temp) — data=${data.toHex()}"
            3 -> "[PEAK STATUS] Bus load — %d%%".format(func)
            4 -> "[PEAK STATUS] Timestamp sync — %02X%02X".format(func, num)
            5 -> "[PEAK STATUS] Bus event — ${if (num == 1) "ON" else "OFF"}"
            else -> "[PEAK STATUS] Internal record type=$recType func=%02X num=%02X".format(func, num)
        }

    private fun formatFrame(frame: RxFrame): String {
        val idStr   = if (frame.isExtended) "%08X".format(frame.canId) else "%03X".format(frame.canId)
        val dataStr = if (frame.isRtr) "RTR" else frame.data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        return "$idStr [${frame.dlc}] $dataStr"
    }

    private fun sendCmd(func: Int, num: Int, args: ByteArray? = null): Boolean {
        val conn = connection ?: return false
        val ep   = epCmdOut  ?: return false
        val buf  = ByteArray(CMD_LEN)
        buf[0] = func.toByte(); buf[1] = num.toByte()
        args?.copyInto(buf, 2, 0, minOf(args.size, CMD_LEN - 2))
        val result = conn.bulkTransfer(ep, buf, CMD_LEN, USB_TIMEOUT_MS)
        onLog?.invoke("[→CMD] ${buf.toHex()} (result=$result)")
        if (result <= 0) onLog?.invoke("[ERR] CMD bulkTransfer failed (result=$result)")
        return result > 0
    }

    private fun findEndpoint(iface: UsbInterface, address: Int): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.address == address) return ep
        }
        return null
    }

    private fun ByteArray.toHex(len: Int = size): String =
        take(len).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun unregister() {
        try { context.unregisterReceiver(detachReceiver) } catch (_: Exception) {}
        ioScope.coroutineContext[Job]?.cancel()
    }
}
