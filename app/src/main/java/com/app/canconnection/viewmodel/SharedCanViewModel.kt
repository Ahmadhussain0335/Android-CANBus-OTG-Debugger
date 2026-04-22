package com.app.canconnection.viewmodel

import android.app.Application
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.app.canconnection.data.model.CanDevice
import com.app.canconnection.data.model.SavedCommand
import com.app.canconnection.data.repository.CommandRepository
import com.app.canconnection.hardware.CanFrameBuilder
import com.app.canconnection.hardware.PeakCanConnectionManager
import com.app.canconnection.hardware.UsbConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App-scoped ViewModel that owns all CAN bus state and orchestrates the hardware managers.
 *
 * Resolved from [CanApplication.viewModelStore] so it survives across the full activity
 * stack (DeviceList → Configure → Command → CommandLibrary). Responsibilities:
 *  - Device scanning: probes both SLCAN (via [UsbConnectionManager]) and PEAK devices.
 *  - Connection lifecycle: delegates to [UsbConnectionManager] or [PeakCanConnectionManager]
 *    depending on the selected [CanDevice] subtype.
 *  - CAN frame TX: validates and routes frames to the correct hardware manager.
 *  - Log aggregation: collects all [TX]/[RX]/[ERR]/[INIT]/[PEAK STATUS] messages into
 *    [logMessages], capped at [MAX_LOG_ENTRIES] to prevent unbounded memory growth.
 *    Verbose adapter-internal messages are gated behind [showPeakInternalLogs].
 *  - Saved-command CRUD: delegates persistence to [CommandRepository].
 */
class SharedCanViewModel(application: Application) : AndroidViewModel(application) {

    val devices          = MutableLiveData<List<CanDevice>>(emptyList())
    val selectedDevice   = MutableLiveData<CanDevice?>(null)
    val connectionStatus = MutableLiveData("disconnected")
    val logMessages      = MutableLiveData<MutableList<String>>(mutableListOf())
    val scanLog          = MutableLiveData<String>("")

    var selectedBitrate: Int = 500000
    var selectedFrameType: CanFrameBuilder.FrameType = CanFrameBuilder.FrameType.STANDARD
    var canIdHex: String = "123"

    private val commandRepo   = CommandRepository(application)
    val savedCommands         = MutableLiveData<List<SavedCommand>>(commandRepo.load())

    private val vmScope      = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler  = Handler(Looper.getMainLooper())

    companion object {
        private const val MAX_LOG_ENTRIES = 1000
    }

    private val slcanManager = UsbConnectionManager(application).also { mgr ->
        mgr.onDeviceDetached = {
            connectionStatus.postValue("disconnected")
            appendLog("[SYS] Device disconnected")
        }
    }

    private val peakManager = PeakCanConnectionManager(application).also { mgr ->
        mgr.onDeviceDetached = {
            connectionStatus.postValue("disconnected")
            appendLog("[SYS] Device disconnected")
        }
        mgr.onLog = { msg -> appendLog(msg) }
    }

    fun scanDevices() {
        val (slcanDrivers, log) = slcanManager.findDevicesWithDiagnostics()
        val slcanDevices = slcanDrivers.map { CanDevice.SlcanDevice(it) }

        val usbMgr = getApplication<Application>().getSystemService(UsbManager::class.java)
        val peakDevices = usbMgr.deviceList.values
            .filter { it.vendorId == PeakCanConnectionManager.VENDOR_ID && it.productId == PeakCanConnectionManager.PRODUCT_ID }
            .map { CanDevice.PeakDevice(it) }

        devices.value = slcanDevices + peakDevices

        val sb = StringBuilder(log)
        if (peakDevices.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("PEAK PCAN-USB: ${peakDevices.size} device(s) found")
            sb.append("  Added to device list above.")
        }
        scanLog.value = sb.toString().trimEnd()
    }

    fun selectDevice(device: CanDevice) { selectedDevice.value = device }

    fun connectDevice() {
        when (val device = selectedDevice.value ?: return) {
            is CanDevice.SlcanDevice -> connectSlcan(device)
            is CanDevice.PeakDevice  -> connectPeak(device)
        }
    }

    private fun connectSlcan(device: CanDevice.SlcanDevice) {
        connectionStatus.postValue("connecting")
        slcanManager.requestPermission(device.driver) {
            val ok = slcanManager.connect(device.driver)
            if (ok) {
                sendSlcanRaw(CanFrameBuilder.setCanSpeed(selectedBitrate))
                sendSlcanRaw(CanFrameBuilder.openChannel())
                connectionStatus.postValue("connected")
                appendLog("[SYS] Connected — ${selectedBitrate / 1000}kbps")
                slcanManager.startReading { line -> appendLog("[RX] $line") }
            } else {
                connectionStatus.postValue("disconnected")
                appendLog("[ERR] Connection failed")
            }
        }
    }

    private fun connectPeak(device: CanDevice.PeakDevice) {
        connectionStatus.postValue("connecting")
        peakManager.requestPermission(device.usbDevice) {
            vmScope.launch(Dispatchers.IO) {
                val ok = peakManager.connect(device.usbDevice, selectedBitrate)
                if (ok) {
                    connectionStatus.postValue("connected")
                    appendLog("[SYS] Connected — ${selectedBitrate / 1000}kbps (PEAK PCAN-USB)")
                    peakManager.startReading { line -> appendLog("[RX] $line") }
                } else {
                    connectionStatus.postValue("disconnected")
                    appendLog("[ERR] PEAK connection failed: ${peakManager.lastError}")
                }
            }
        }
    }

    fun sendCanFrame(canIdHex: String, dataHex: String) {
        if (canIdHex.isBlank()) { appendLog("[ERR] CAN ID is empty"); return }
        val canId = try { canIdHex.trim().toInt(16) }
                    catch (_: NumberFormatException) { appendLog("[ERR] Invalid CAN ID hex"); return }
        if (selectedFrameType == CanFrameBuilder.FrameType.STANDARD && canId > 0x7FF) {
            appendLog("[ERR] CAN ID exceeds 0x7FF for Standard frame"); return
        }
        if (selectedFrameType == CanFrameBuilder.FrameType.EXTENDED && canId > 0x1FFFFFFF) {
            appendLog("[ERR] CAN ID out of range"); return
        }
        val stripped = dataHex.replace(" ", "")
        if (stripped.isEmpty()) { appendLog("[ERR] Data bytes are empty"); return }
        if (stripped.length % 2 != 0) { appendLog("[ERR] Invalid hex in data bytes"); return }
        val data: ByteArray = try {
            ByteArray(stripped.length / 2) { i -> stripped.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) { appendLog("[ERR] Invalid hex in data bytes"); return }
        if (data.size > 8) { appendLog("[ERR] Max 8 data bytes"); return }

        when (selectedDevice.value) {
            is CanDevice.SlcanDevice -> {
                val frame = try { CanFrameBuilder.buildFrame(selectedFrameType, canId, data) }
                            catch (e: IllegalArgumentException) { appendLog("[ERR] ${e.message}"); return }
                sendSlcanRaw(frame)
                appendLog("[TX] $frame")
            }
            is CanDevice.PeakDevice -> {
                val isExt = selectedFrameType == CanFrameBuilder.FrameType.EXTENDED
                peakManager.sendFrame(canId, data, isExt)
                val idStr = if (isExt) "%08X".format(canId) else "%03X".format(canId)
                appendLog("[TX] $idStr [${data.size}] ${data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")
            }
            null -> appendLog("[ERR] No device selected")
        }
    }

    fun addSavedCommand(cmd: SavedCommand) {
        val list = savedCommands.value.orEmpty().toMutableList()
        list.add(cmd)
        commandRepo.save(list)
        savedCommands.value = list
    }

    fun removeSavedCommand(id: String) {
        val list = savedCommands.value.orEmpty().toMutableList()
        list.removeAll { it.id == id }
        commandRepo.save(list)
        savedCommands.value = list
    }

    fun clearLog() {
        mainHandler.post { logMessages.value = mutableListOf() }
    }

    fun disconnectDevice() {
        when (selectedDevice.value) {
            is CanDevice.SlcanDevice -> {
                sendSlcanRaw(CanFrameBuilder.closeChannel())
                slcanManager.disconnect()
            }
            is CanDevice.PeakDevice -> peakManager.disconnect()
            null -> {}
        }
        connectionStatus.postValue("disconnected")
        appendLog("[SYS] Disconnected")
    }

    private fun sendSlcanRaw(cmd: String) = slcanManager.sendCommand(cmd)

    var showPeakInternalLogs: Boolean = false

    private fun appendLog(line: String) {
        val isVerbose = line.startsWith("[PEAK STATUS]") ||
                        line.startsWith("[←RX]")        ||
                        line.startsWith("[→TX]")        ||
                        line.startsWith("[→CMD]")       ||
                        line.startsWith("[INIT]")
        if (isVerbose && !showPeakInternalLogs) return
        mainHandler.post {
            val list = logMessages.value ?: mutableListOf()
            list.add(line)
            if (list.size > MAX_LOG_ENTRIES) list.removeAt(0)
            logMessages.value = list
        }
    }

    override fun onCleared() {
        super.onCleared()
        vmScope.coroutineContext[Job]?.cancel()
        slcanManager.unregister()
        peakManager.unregister()
    }
}
