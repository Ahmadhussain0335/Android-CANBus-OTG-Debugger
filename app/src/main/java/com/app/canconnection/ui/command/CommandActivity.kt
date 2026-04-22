package com.app.canconnection.ui.command

import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.app.canconnection.CanApplication
import com.app.canconnection.R
import com.app.canconnection.databinding.ActivityCommandBinding
import com.app.canconnection.hardware.CanFrameBuilder
import com.app.canconnection.ui.devicelist.DeviceListActivity
import com.app.canconnection.ui.library.SavedCommandsActivity
import com.app.canconnection.viewmodel.SharedCanViewModel

/**
 * Main operational screen displayed after a successful CAN bus connection.
 *
 * Hosts a horizontal [RecyclerView] of [CommandAdapter] command cards. Each card is
 * independently configurable for CAN ID, data bytes, and optional periodic interval.
 * The "More Details" link opens [SavedCommandsActivity]. The Disconnect button calls
 * [SharedCanViewModel.disconnectDevice], which posts "disconnected" status; this
 * activity observes that status and navigates back to [DeviceListActivity].
 *
 * Back-press is intentionally suppressed: users must disconnect explicitly to prevent
 * abandoning an active CAN bus session without closing the channel.
 */
class CommandActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCommandBinding
    private lateinit var viewModel: SharedCanViewModel
    private lateinit var commandAdapter: CommandAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommandBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            (application as CanApplication).viewModelStore,
            ViewModelProvider.AndroidViewModelFactory(application)
        )[SharedCanViewModel::class.java]

        setSupportActionBar(binding.toolbar)

        val deviceName = viewModel.selectedDevice.value?.displayName ?: "Unknown"
        val bitrate    = viewModel.selectedBitrate / 1000
        val frameType  = if (viewModel.selectedFrameType == CanFrameBuilder.FrameType.STANDARD) "STD" else "EXT"
        binding.tvConnectionInfo.text = "$deviceName · ${bitrate}kbps · $frameType"

        commandAdapter = CommandAdapter(
            frameType         = viewModel.selectedFrameType,
            onSend            = { canId, data -> viewModel.sendCanFrame(canId, data) },
            onValidationError = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        )
        binding.rvCommands.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCommands.adapter = commandAdapter
        commandAdapter.addItem(CommandItem(canIdHex = viewModel.canIdHex))

        binding.btnAddCommand.setOnClickListener {
            commandAdapter.addItem(CommandItem())
            binding.rvCommands.post {
                binding.rvCommands.smoothScrollToPosition(commandAdapter.itemCount - 1)
            }
        }

        binding.tvMoreDetails.setOnClickListener {
            startActivity(Intent(this, SavedCommandsActivity::class.java))
        }

        binding.switchPeakLogs.isChecked = viewModel.showPeakInternalLogs
        binding.switchPeakLogs.setOnCheckedChangeListener { _, isChecked ->
            viewModel.showPeakInternalLogs = isChecked
        }

        binding.btnClearLog.setOnClickListener { viewModel.clearLog() }
        binding.btnDisconnect.setOnClickListener { viewModel.disconnectDevice() }

        viewModel.connectionStatus.observe(this) { status ->
            if (status == "disconnected") {
                commandAdapter.stopAll()
                val intent = Intent(this, DeviceListActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()
            }
        }

        viewModel.logMessages.observe(this) { messages ->
            try {
                val snapshot    = messages.toList()
                val sb          = SpannableStringBuilder()
                val colorTx     = getColor(R.color.color_info)
                val colorRx     = getColor(R.color.color_success)
                val colorErr    = getColor(R.color.color_danger)
                val colorInit   = getColor(R.color.color_info)
                val colorStatus = getColor(R.color.color_secondary)
                for (msg in snapshot) {
                    val start = sb.length
                    sb.append(msg).append("\n")
                    val end = sb.length
                    val color = when {
                        msg.startsWith("[TX]")   || msg.startsWith("[→TX]")  -> colorTx
                        msg.startsWith("[RX]")   || msg.startsWith("[←RX]") -> colorRx
                        msg.startsWith("[ERR]")                               -> colorErr
                        msg.startsWith("[→CMD]") || msg.startsWith("[INIT]") -> colorInit
                        msg.startsWith("[PEAK STATUS]")                       -> colorStatus
                        else                                                  -> colorStatus
                    }
                    sb.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                binding.tvLog.text = sb
            } catch (e: Exception) {
                binding.tvLog.text = "[render error] ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Intentional no-op — user must tap Disconnect
    }
}
