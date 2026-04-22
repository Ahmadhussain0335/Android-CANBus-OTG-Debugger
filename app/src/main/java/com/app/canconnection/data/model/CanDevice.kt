package com.app.canconnection.data.model

import android.hardware.usb.UsbDevice
import com.hoho.android.usbserial.driver.UsbSerialDriver

/**
 * Sealed hierarchy representing a detected CAN USB adapter.
 *
 * [SlcanDevice] wraps a driver found by the usb-serial-for-android library and
 * communicates via the SLCAN ASCII protocol over a virtual COM port at 115200 baud.
 *
 * [PeakDevice] wraps a raw [UsbDevice] for PEAK PCAN-USB hardware (VID 0x0C72 /
 * PID 0x000C) that uses a proprietary binary USB bulk-transfer protocol handled
 * by [PeakCanConnectionManager].
 */
sealed class CanDevice {
    abstract val vendorId: Int
    abstract val productId: Int
    abstract val displayName: String

    data class SlcanDevice(val driver: UsbSerialDriver) : CanDevice() {
        override val vendorId get() = driver.device.vendorId
        override val productId get() = driver.device.productId
        override val displayName get() = driver.javaClass.simpleName
    }

    data class PeakDevice(val usbDevice: UsbDevice) : CanDevice() {
        override val vendorId get() = usbDevice.vendorId
        override val productId get() = usbDevice.productId
        override val displayName = "PEAK PCAN-USB"
    }
}
