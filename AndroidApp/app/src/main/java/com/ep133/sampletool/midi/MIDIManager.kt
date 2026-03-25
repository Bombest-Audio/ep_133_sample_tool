package com.ep133.sampletool.midi

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages USB MIDI device discovery, input, and output using android.media.midi.
 *
 * Handles USB permission requests automatically — Android won't expose MIDI
 * devices until USB access is granted.
 *
 * Naming note (Android's convention is inverted from the user's perspective):
 *   - MidiInputPort = we SEND data TO the device
 *   - MidiOutputPort = we RECEIVE data FROM the device
 */
class MIDIManager(
    private val context: Context,
    private val midiManager: MidiManager
) {
    data class MIDIDevice(val id: String, val name: String)
    data class DeviceList(val inputs: List<MIDIDevice>, val outputs: List<MIDIDevice>)

    /** Called when MIDI data arrives from a device. (portId, bytes) */
    var onMidiReceived: ((String, ByteArray) -> Unit)? = null

    /** Called when MIDI device list changes (connect/disconnect/permission granted) */
    var onDevicesChanged: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val openDevices = mutableMapOf<Int, MidiDevice>()
    private val openInputPorts = mutableMapOf<String, MidiInputPort>()
    private val openOutputPorts = mutableMapOf<String, MidiOutputPort>()

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            Log.i(TAG, "MIDI device added: ${getDeviceName(device)}")
            notifyDevicesChanged()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            Log.i(TAG, "MIDI device removed: ${getDeviceName(device)}")
            closeDevice(device.id)
            notifyDevicesChanged()
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                Log.i(TAG, "USB permission ${if (granted) "granted" else "denied"} for ${device?.productName}")
                if (granted) {
                    // MIDI device should now appear — notify after a short delay
                    // to give the MIDI service time to enumerate
                    mainHandler.postDelayed({
                        notifyDevicesChanged()
                    }, 500)
                }
            }
        }
    }

    init {
        @Suppress("DEPRECATION")
        midiManager.registerDeviceCallback(deviceCallback, mainHandler)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    // MARK: - Device Enumeration

    @Suppress("DEPRECATION")
    fun getUSBDevices(): DeviceList {
        val inputs = mutableListOf<MIDIDevice>()
        val outputs = mutableListOf<MIDIDevice>()

        Log.d(TAG, "Enumerating MIDI devices...")

        for (info in midiManager.devices) {
            Log.d(TAG, "  Found MIDI device: ${getDeviceName(info)} type=${info.type} id=${info.id}")

            if (info.type != MidiDeviceInfo.TYPE_USB) {
                Log.d(TAG, "  Skipping non-USB device")
                continue
            }

            val deviceName = getDeviceName(info)
            val deviceId = info.id

            for (port in info.ports) {
                when (port.type) {
                    MidiDeviceInfo.PortInfo.TYPE_OUTPUT -> {
                        val portId = "${deviceId}_out_${port.portNumber}"
                        val portName = port.name?.takeIf { it.isNotBlank() } ?: deviceName
                        inputs.add(MIDIDevice(portId, portName))
                        Log.d(TAG, "  Input port: $portId ($portName)")
                    }
                    MidiDeviceInfo.PortInfo.TYPE_INPUT -> {
                        val portId = "${deviceId}_in_${port.portNumber}"
                        val portName = port.name?.takeIf { it.isNotBlank() } ?: deviceName
                        outputs.add(MIDIDevice(portId, portName))
                        Log.d(TAG, "  Output port: $portId ($portName)")
                    }
                }
            }
        }

        Log.d(TAG, "Total: ${inputs.size} inputs, ${outputs.size} outputs")

        // If no MIDI devices found, check if there are USB devices that need permission
        if (inputs.isEmpty() && outputs.isEmpty()) {
            requestUSBPermissions()
        }

        return DeviceList(inputs, outputs)
    }

    // MARK: - USB Permission

    /**
     * Checks for connected USB devices and requests permission if not already granted.
     * Once permission is granted, the MIDI service will enumerate the device and our
     * deviceCallback will fire.
     */
    fun requestUSBPermissions() {
        val deviceList = usbManager.deviceList
        Log.d(TAG, "Checking ${deviceList.size} USB devices for permissions...")

        for ((_, device) in deviceList) {
            Log.d(TAG, "  USB device: ${device.productName} (VID=${device.vendorId} PID=${device.productId})")

            if (!usbManager.hasPermission(device)) {
                Log.i(TAG, "  Requesting permission for ${device.productName}")
                val permissionIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_MUTABLE
                )
                usbManager.requestPermission(device, permissionIntent)
                return  // Request one at a time — Android shows a dialog
            } else {
                Log.d(TAG, "  Already have permission for ${device.productName}")
            }
        }
    }

    // MARK: - Send MIDI

    fun sendMidi(portId: String, data: ByteArray) {
        val cached = openInputPorts[portId]
        if (cached != null) {
            try {
                cached.send(data, 0, data.size)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send MIDI on cached port $portId: ${e.message}")
                openInputPorts.remove(portId)
            }
        }

        val parts = portId.split("_")
        if (parts.size < 3) {
            Log.e(TAG, "Invalid portId format: $portId")
            return
        }

        val deviceId = parts[0].toIntOrNull() ?: return
        val portNumber = parts[2].toIntOrNull() ?: return

        openOrGetDevice(deviceId) { device ->
            if (device == null) {
                Log.e(TAG, "Could not open MIDI device $deviceId")
                return@openOrGetDevice
            }

            val inputPort = device.openInputPort(portNumber)
            if (inputPort != null) {
                openInputPorts[portId] = inputPort
                try {
                    inputPort.send(data, 0, data.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send MIDI: ${e.message}")
                }
            } else {
                Log.e(TAG, "Could not open input port $portNumber on device $deviceId")
            }
        }
    }

    // MARK: - Receive MIDI

    fun startListening(portId: String) {
        if (openOutputPorts.containsKey(portId)) return

        val parts = portId.split("_")
        if (parts.size < 3) return

        val deviceId = parts[0].toIntOrNull() ?: return
        val portNumber = parts[2].toIntOrNull() ?: return

        openOrGetDevice(deviceId) { device ->
            if (device == null) return@openOrGetDevice

            val outputPort = device.openOutputPort(portNumber)
            if (outputPort != null) {
                openOutputPorts[portId] = outputPort
                outputPort.connect(object : MidiReceiver() {
                    override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        val bytes = data.copyOfRange(offset, offset + count)
                        onMidiReceived?.invoke(portId, bytes)
                    }
                })
                Log.i(TAG, "Listening on $portId")
            }
        }
    }

    // MARK: - Device Management

    fun refreshDevices() {
        requestUSBPermissions()

        val devices = getUSBDevices()
        for (input in devices.inputs) {
            startListening(input.id)
        }
        notifyDevicesChanged()
    }

    private fun notifyDevicesChanged() {
        mainHandler.post {
            onDevicesChanged?.invoke()
        }
    }

    fun close() {
        midiManager.unregisterDeviceCallback(deviceCallback)
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {}

        for ((_, port) in openInputPorts) {
            try { port.close() } catch (_: Exception) {}
        }
        for ((_, port) in openOutputPorts) {
            try { port.close() } catch (_: Exception) {}
        }
        for ((_, device) in openDevices) {
            try { device.close() } catch (_: Exception) {}
        }

        openInputPorts.clear()
        openOutputPorts.clear()
        openDevices.clear()
    }

    // MARK: - Helpers

    @Suppress("DEPRECATION")
    private fun openOrGetDevice(deviceId: Int, callback: (MidiDevice?) -> Unit) {
        val cached = openDevices[deviceId]
        if (cached != null) {
            callback(cached)
            return
        }

        val deviceInfo = midiManager.devices.firstOrNull { it.id == deviceId }
        if (deviceInfo == null) {
            callback(null)
            return
        }

        midiManager.openDevice(deviceInfo, { device ->
            if (device != null) {
                openDevices[deviceId] = device
            }
            callback(device)
        }, mainHandler)
    }

    private fun closeDevice(deviceId: Int) {
        val keysToRemove = openInputPorts.keys.filter { it.startsWith("${deviceId}_") }
        for (key in keysToRemove) {
            try { openInputPorts.remove(key)?.close() } catch (_: Exception) {}
        }
        val outKeysToRemove = openOutputPorts.keys.filter { it.startsWith("${deviceId}_") }
        for (key in outKeysToRemove) {
            try { openOutputPorts.remove(key)?.close() } catch (_: Exception) {}
        }
        try { openDevices.remove(deviceId)?.close() } catch (_: Exception) {}
    }

    private fun getDeviceName(info: MidiDeviceInfo): String {
        val props = info.properties
        return props.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: props.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "Unknown MIDI Device"
    }

    companion object {
        private const val TAG = "EP133MIDI"
        private const val ACTION_USB_PERMISSION = "com.ep133.sampletool.USB_PERMISSION"
    }
}
