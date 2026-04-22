package com.app.canconnection.ui.devicelist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.app.canconnection.CanApplication
import com.app.canconnection.databinding.ActivityDeviceListBinding
import com.app.canconnection.ui.configure.ConfigureActivity
import com.app.canconnection.viewmodel.SharedCanViewModel

/**
 * Entry-point screen that lists detected CAN USB adapters and initiates scanning.
 *
 * On [btnScan] tap or automatic USB-attach detection, calls [SharedCanViewModel.scanDevices]
 * which probes both SLCAN (via usb-serial-for-android) and PEAK PCAN-USB devices.
 * Diagnostic output from the scan is shown in a collapsible log panel at the bottom.
 * Tapping a device navigates to [ConfigureActivity]. A [BroadcastReceiver] auto-triggers
 * re-scan when a USB device is physically plugged in while this screen is active.
 */
class DeviceListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceListBinding
    private lateinit var viewModel: SharedCanViewModel
    private lateinit var adapter: DeviceAdapter

    private val usbAttachedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                viewModel.scanDevices()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            (application as CanApplication).viewModelStore,
            ViewModelProvider.AndroidViewModelFactory(application)
        )[SharedCanViewModel::class.java]

        setSupportActionBar(binding.toolbar)

        adapter = DeviceAdapter { device ->
            viewModel.selectDevice(device)
            startActivity(Intent(this, ConfigureActivity::class.java))
        }

        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = adapter

        binding.btnScan.setOnClickListener { viewModel.scanDevices() }

        viewModel.devices.observe(this) { devices ->
            adapter.submitList(devices)
        }

        viewModel.scanLog.observe(this) { log ->
            if (log.isNotBlank()) {
                binding.logDivider.visibility = View.VISIBLE
                binding.tvScanLogLabel.visibility = View.VISIBLE
                binding.scrollScanLog.visibility = View.VISIBLE
                binding.tvScanLog.text = log
                binding.scrollScanLog.post {
                    binding.scrollScanLog.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbAttachedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbAttachedReceiver, filter)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            viewModel.scanDevices()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbAttachedReceiver) } catch (_: Exception) {}
    }
}
