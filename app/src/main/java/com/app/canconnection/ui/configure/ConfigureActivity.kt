package com.app.canconnection.ui.configure

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.app.canconnection.CanApplication
import com.app.canconnection.R
import com.app.canconnection.databinding.ActivityConfigureBinding
import com.app.canconnection.hardware.CanFrameBuilder
import com.app.canconnection.ui.command.CommandActivity
import com.app.canconnection.viewmodel.SharedCanViewModel

/**
 * Screen for selecting CAN bus parameters before establishing a connection.
 *
 * The user chooses a bitrate (10 k–1 Mbps), frame type (Standard 11-bit or Extended
 * 29-bit), and a default CAN ID that pre-fills new command cards in [CommandActivity].
 * The CAN ID is validated against the selected frame type before calling
 * [SharedCanViewModel.connectDevice]. On a successful "connected" status the activity
 * starts [CommandActivity] and finishes itself so the back stack stays clean.
 */
class ConfigureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigureBinding
    private lateinit var viewModel: SharedCanViewModel

    private val bitrateValues = intArrayOf(10000, 20000, 50000, 100000, 125000, 250000, 500000, 800000, 1000000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            (application as CanApplication).viewModelStore,
            ViewModelProvider.AndroidViewModelFactory(application)
        )[SharedCanViewModel::class.java]

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.tvStatus.text = viewModel.selectedDevice.value?.displayName ?: "No device"

        val bitrateLabels = resources.getStringArray(R.array.bitrate_labels)
        val bitrateAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bitrateLabels)
        bitrateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBitrate.adapter = bitrateAdapter
        val defaultBitratePos = bitrateValues.indexOfFirst { it == viewModel.selectedBitrate }.let { if (it < 0) 6 else it }
        binding.spinnerBitrate.setSelection(defaultBitratePos)
        binding.spinnerBitrate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                viewModel.selectedBitrate = bitrateValues[pos]
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        val frameLabels = resources.getStringArray(R.array.frame_type_labels)
        val frameAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, frameLabels)
        frameAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFrameType.adapter = frameAdapter
        val defaultFramePos = if (viewModel.selectedFrameType == CanFrameBuilder.FrameType.STANDARD) 0 else 1
        binding.spinnerFrameType.setSelection(defaultFramePos)
        binding.spinnerFrameType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                viewModel.selectedFrameType =
                    if (pos == 0) CanFrameBuilder.FrameType.STANDARD else CanFrameBuilder.FrameType.EXTENDED
                updateCanIdHint()
                clearCanIdError()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        updateCanIdHint()
        binding.editCanId.setText(viewModel.canIdHex)

        binding.editCanId.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = clearCanIdError()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnConnect.setOnClickListener {
            val canIdText = binding.editCanId.text.toString().trim()
            val error = validateCanId(canIdText, viewModel.selectedFrameType)
            if (error != null) { showCanIdError(error); return@setOnClickListener }
            clearCanIdError()
            viewModel.canIdHex = canIdText
            viewModel.connectDevice()
        }

        viewModel.connectionStatus.observe(this) { status ->
            when (status) {
                "connecting" -> {
                    binding.btnConnect.isEnabled = false
                    binding.btnConnect.text = "Connecting..."
                    binding.dotStatus.setBackgroundResource(R.drawable.dot_grey)
                    binding.tvStatus.text = "Connecting..."
                }
                "connected" -> {
                    startActivity(Intent(this, CommandActivity::class.java))
                    finish()
                }
                "disconnected" -> {
                    binding.btnConnect.isEnabled = true
                    binding.btnConnect.text = "Connect"
                    binding.dotStatus.setBackgroundResource(R.drawable.dot_grey)
                    binding.tvStatus.text = viewModel.selectedDevice.value?.displayName ?: "No device"
                }
            }
        }
    }

    private fun validateCanId(text: String, frameType: CanFrameBuilder.FrameType): String? {
        if (text.isBlank()) return "CAN ID is required"
        val value = try { text.toLong(16) }
                    catch (_: NumberFormatException) { return "Invalid hex value — use digits 0-9 and letters A-F" }
        if (value < 0) return "CAN ID must be a positive value"
        return when (frameType) {
            CanFrameBuilder.FrameType.STANDARD ->
                if (value > 0x7FF) "Standard frame ID must be ≤ 7FF (11-bit max)" else null
            CanFrameBuilder.FrameType.EXTENDED ->
                if (value > 0x1FFFFFFF) "Extended frame ID must be ≤ 1FFFFFFF (29-bit max)" else null
        }
    }

    private fun showCanIdError(message: String) {
        binding.tvCanIdError.text = message
        binding.tvCanIdError.visibility = View.VISIBLE
        binding.editCanId.requestFocus()
    }

    private fun clearCanIdError() { binding.tvCanIdError.visibility = View.GONE }

    private fun updateCanIdHint() {
        binding.editCanId.hint =
            if (viewModel.selectedFrameType == CanFrameBuilder.FrameType.STANDARD)
                "e.g. 123  (max 7FF)"
            else
                "e.g. 1FFFFFFF  (max 1FFFFFFF)"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
