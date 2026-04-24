package com.app.canconnection.ui.library

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.app.canconnection.CanApplication
import com.app.canconnection.R
import com.app.canconnection.data.model.SavedCommand
import com.app.canconnection.databinding.ActivitySavedCommandsBinding
import com.app.canconnection.viewmodel.SharedCanViewModel
import java.util.UUID

/**
 * Command Library screen for managing and sending reusable CAN frames.
 *
 * Users create named commands (name + CAN ID + hex data bytes) which are persisted
 * via [SharedCanViewModel] → [CommandRepository] and survive app restarts.
 * The list supports tap-to-select and per-item delete. The "Send Selected" button
 * transmits the selected command over the active connection immediately.
 *
 * The log section at the bottom mirrors [CommandActivity]'s live log (same
 * [SharedCanViewModel.logMessages] source) so TX/RX traffic is visible without
 * leaving this screen. The "Adapter info" toggle gates verbose PEAK protocol messages.
 */
class SavedCommandsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySavedCommandsBinding
    private lateinit var viewModel: SharedCanViewModel
    private lateinit var commandsAdapter: SavedCommandsAdapter
    private var selectedCommand: SavedCommand? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySavedCommandsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            (application as CanApplication).viewModelStore,
            ViewModelProvider.AndroidViewModelFactory(application)
        )[SharedCanViewModel::class.java]

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        commandsAdapter = SavedCommandsAdapter(
            onSelect = { cmd -> selectedCommand = cmd },
            onDelete = { id ->
                if (selectedCommand?.id == id) selectedCommand = null
                viewModel.removeSavedCommand(id)
            }
        )
        binding.rvSavedCommands.layoutManager = LinearLayoutManager(this)
        binding.rvSavedCommands.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        binding.rvSavedCommands.adapter = commandsAdapter

        viewModel.savedCommands.observe(this) { commands ->
            commandsAdapter.clearSelectionIfDeleted(commands)
            commandsAdapter.submitList(commands)
            binding.tvEmpty.visibility = if (commands.isEmpty()) View.VISIBLE else View.GONE
        }

        binding.btnAddToList.setOnClickListener {
            val name  = binding.editCommandName.text.toString().trim()
            val canId = binding.editCommandCanId.text.toString().trim()
            val data  = binding.editCommandData.text.toString().trim()
            when {
                name.isBlank()  -> Toast.makeText(this, "Enter a command name", Toast.LENGTH_SHORT).show()
                canId.isBlank() -> Toast.makeText(this, "Enter a CAN ID", Toast.LENGTH_SHORT).show()
                data.isBlank()  -> Toast.makeText(this, "Enter data bytes", Toast.LENGTH_SHORT).show()
                else -> {
                    viewModel.addSavedCommand(SavedCommand(UUID.randomUUID().toString(), name, canId, data))
                    binding.editCommandName.text?.clear()
                    binding.editCommandCanId.text?.clear()
                    binding.editCommandData.text?.clear()
                    Toast.makeText(this, "Command \"$name\" saved to library", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSend.setOnClickListener {
            val cmd = selectedCommand
            if (cmd == null) {
                Toast.makeText(this, "Tap a command to select it first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.sendCanFrame(cmd.canIdHex, cmd.dataHex)
        }

        binding.switchPeakLogs.isChecked = viewModel.showPeakInternalLogs
        binding.switchPeakLogs.setOnCheckedChangeListener { _, isChecked ->
            viewModel.showPeakInternalLogs = isChecked
        }
        binding.btnClearLog.setOnClickListener { viewModel.clearLog() }

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
}
