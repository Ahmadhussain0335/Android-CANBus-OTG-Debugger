package com.app.canconnection.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages USB serial connectivity for SLCAN-compatible CAN adapters
 * (e.g. CANable, Canable Pro, or any CDC/VCP adapter using the SLCAN protocol).
 *
 * Responsibilities:
 *  - Scanning for attached USB serial devices via usb-serial-for-android.
 *  - Requesting OS-level USB permission before opening a device.
 *  - Opening the serial port at 115200 8N1 and exchanging SLCAN ASCII commands.
 *  - Running a background I/O coroutine that reads incoming lines and delivers
 *    them to the caller via [startReading].
 *  - Detecting cable disconnection via a [BroadcastReceiver] on
 *    [UsbManager.ACTION_USB_DEVICE_DETACHED] and invoking [onDeviceDetached].
 *
 * Call [unregister] when the owning ViewModel is cleared to stop the I/O scope
 * and unregister the detach broadcast receiver.
 */
class UsbConnectionManager(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.app.canconnection.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null
    private var readJob: Job? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Invoked on the calling thread when the connected USB device is physically removed. */
    var onDeviceDetached: (() -> Unit)? = null

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

    /** Returns all USB serial drivers matched by the default usb-serial-for-android prober. */
    fun findDevices(): List<UsbSerialDriver> =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

    /**
     * Like [findDevices] but also returns a human-readable diagnostic string listing
     * every raw USB device, its VID/PID, and whether a serial driver matched it.
     * Useful for debugging adapters whose VID/PID is not in the default driver table.
     */
    fun findDevicesWithDiagnostics(): Pair<List<UsbSerialDriver>, String> {
        val sb = StringBuilder()
        sb.appendLine("Android SDK: ${Build.VERSION.SDK_INT}")

        val allDevices = usbManager.deviceList
        sb.appendLine("Raw USB devices: ${allDevices.size}")
        for ((name, device) in allDevices) {
            sb.appendLine("  $name")
            sb.appendLine("    VID:${"%04X".format(device.vendorId)} PID:${"%04X".format(device.productId)}")
            sb.appendLine("    Class:${device.deviceClass} Sub:${device.deviceSubclass}")
            sb.appendLine("    Has permission: ${usbManager.hasPermission(device)}")
        }

        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        sb.appendLine("Matched serial drivers: ${drivers.size}")
        for (driver in drivers) {
            sb.appendLine("  ${driver.javaClass.simpleName}")
            sb.appendLine("    VID:${"%04X".format(driver.device.vendorId)} PID:${"%04X".format(driver.device.productId)}")
            sb.appendLine("    Ports: ${driver.ports.size}")
        }

        if (allDevices.isNotEmpty() && drivers.isEmpty()) {
            sb.appendLine()
            sb.appendLine("WARNING: USB device(s) detected but no")
            sb.appendLine("serial driver matched. Your adapter VID/PID")
            sb.appendLine("may not be in the default driver table.")
        } else if (allDevices.isEmpty()) {
            sb.appendLine()
            sb.appendLine("No USB devices detected.")
            sb.appendLine("Check OTG cable and USB host support.")
        }

        return Pair(drivers, sb.toString().trimEnd())
    }

    /**
     * Requests OS USB permission for [driver]'s device. Invokes [onGranted] on the
     * main thread if the user approves; silently does nothing if denied.
     */
    fun requestPermission(driver: UsbSerialDriver, onGranted: () -> Unit) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )
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
        usbManager.requestPermission(driver.device, permissionIntent)
    }

    /** Opens the first port of [driver] at 115200 8N1. Returns false if the OS refuses the connection. */
    fun connect(driver: UsbSerialDriver): Boolean {
        return try {
            val connection = usbManager.openDevice(driver.device) ?: return false
            val serialPort = driver.ports[0]
            serialPort.open(connection)
            serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = serialPort
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Cancels the read job and closes the serial port. Safe to call multiple times. */
    fun disconnect() {
        readJob?.cancel()
        readJob = null
        try { port?.close() } catch (_: Exception) {}
        port = null
    }

    /** Writes [cmd] bytes to the serial port with a 200 ms timeout. Returns false on error. */
    fun sendCommand(cmd: String): Boolean {
        val p = port ?: return false
        return try {
            p.write(cmd.toByteArray(), 200)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Starts a background coroutine that continuously reads from the serial port,
     * accumulates bytes into lines delimited by '\r' or '\n', and calls [onData]
     * on each complete non-empty line.
     *
     * ISO-8859-1 decoding is used because SLCAN responses are 7-bit ASCII and
     * using UTF-8 would fail on raw byte values > 127 in diagnostic output.
     */
    fun startReading(onData: (String) -> Unit) {
        readJob = ioScope.launch {
            val buffer = ByteArray(256)
            val lineBuffer = StringBuilder()
            while (isActive) {
                try {
                    val len = port?.read(buffer, 100) ?: break
                    if (len > 0) {
                        val chunk = String(buffer, 0, len, Charsets.ISO_8859_1)
                        for (ch in chunk) {
                            if (ch == '\r' || ch == '\n') {
                                val line = lineBuffer.toString().trim()
                                if (line.isNotEmpty()) onData(line)
                                lineBuffer.clear()
                            } else {
                                lineBuffer.append(ch)
                            }
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    /** Unregisters the detach receiver and cancels the I/O coroutine scope. Call from onCleared(). */
    fun unregister() {
        try { context.unregisterReceiver(detachReceiver) } catch (_: Exception) {}
        ioScope.coroutineContext[Job]?.cancel()
    }
}
