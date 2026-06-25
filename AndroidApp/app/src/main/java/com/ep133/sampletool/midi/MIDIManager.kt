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
import com.ep133.sampletool.domain.model.PermissionState

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
    private val midiManager: MidiManager,
) : MIDIPort {

    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null

    /** Current USB permission lifecycle state. Read by MIDIRepository to populate DeviceState. */
    var currentPermissionState: PermissionState = PermissionState.UNKNOWN
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    // All three are ConcurrentHashMap: openDevices is read from sendMidi()/openOrGetDevice()
    // on the sequencer's background thread while device-callbacks mutate it on the main thread.
    private val openDevices = java.util.concurrent.ConcurrentHashMap<Int, MidiDevice>()
    private val openInputPorts = java.util.concurrent.ConcurrentHashMap<String, MidiInputPort>()
    private val openOutputPorts = java.util.concurrent.ConcurrentHashMap<String, MidiOutputPort>()
    // Ports whose openOutputPort() is in flight. startListening()'s openOutputPorts guard is
    // insufficient because openOrGetDevice() is async: two rapid enumerations both pass the
    // guard before the first callback sets openOutputPorts, connecting TWO receivers → every
    // incoming SysEx delivered twice (corrupts response correlation). This set dedups synchronously.
    private val startingOutputPorts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
                    // The authoritative trigger for re-enumeration after permission grant
                    // is deviceCallback.onDeviceAdded() — no manual delay needed (D-09).
                    currentPermissionState = PermissionState.GRANTED
                    notifyDevicesChanged()
                } else {
                    currentPermissionState = PermissionState.DENIED
                    notifyDevicesChanged()
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
    override fun getUSBDevices(): MIDIPort.Devices {
        val inputs = mutableListOf<MIDIPort.Device>()
        val outputs = mutableListOf<MIDIPort.Device>()

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
                        inputs.add(MIDIPort.Device(portId, portName))
                        Log.d(TAG, "  Input port: $portId ($portName)")
                    }
                    MidiDeviceInfo.PortInfo.TYPE_INPUT -> {
                        val portId = "${deviceId}_in_${port.portNumber}"
                        val portName = port.name?.takeIf { it.isNotBlank() } ?: deviceName
                        outputs.add(MIDIPort.Device(portId, portName))
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

        return MIDIPort.Devices(inputs, outputs)
    }

    // MARK: - USB Permission

    /**
     * Checks for connected USB devices and requests permission if not already granted.
     * Once permission is granted, the MIDI service will enumerate the device and our
     * deviceCallback will fire.
     */
    override fun requestUSBPermissions() {
        val deviceList = usbManager.deviceList
        Log.d(TAG, "Checking ${deviceList.size} USB devices for permissions...")

        for ((_, device) in deviceList) {
            Log.d(TAG, "  USB device: ${device.productName} (VID=${device.vendorId} PID=${device.productId})")

            if (!usbManager.hasPermission(device)) {
                Log.i(TAG, "  Requesting permission for ${device.productName}")
                // Set AWAITING BEFORE calling requestPermission — the system dialog appears
                // asynchronously, so state must reflect "awaiting" while dialog is showing (D-19).
                currentPermissionState = PermissionState.AWAITING
                notifyDevicesChanged()
                // Explicit Intent (setPackage) keeps the PendingIntent from being an
                // implicit-mutable one — clears Lint MutableImplicitPendingIntent. FLAG_MUTABLE
                // is still required so UsbManager can inject the granted-device extra.
                val permissionIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) },
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

    override fun prewarmSendPort(portId: String) {
        if (openInputPorts.containsKey(portId)) return
        val parts = portId.split("_")
        if (parts.size < 3) return
        val deviceId = parts[0].toIntOrNull() ?: return
        val portNumber = parts[2].toIntOrNull() ?: return
        Log.d(TAG, "Pre-warming send port $portId")
        openOrGetDevice(deviceId) { device ->
            if (device == null) return@openOrGetDevice
            val inputPort = device.openInputPort(portNumber)
            if (inputPort != null) {
                openInputPorts[portId] = inputPort
                Log.i(TAG, "Send port $portId ready")
            }
        }
    }

    override fun sendMidi(portId: String, data: ByteArray) {
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

    /** Close all stale listeners (e.g. after device reconnect with new ID). */
    override fun closeAllListeners() {
        for ((id, port) in openOutputPorts) {
            try { port.close() } catch (_: Exception) {}
            Log.d(TAG, "Closed stale listener on $id")
        }
        openOutputPorts.clear()
    }

    override fun startListening(portId: String) {
        if (openOutputPorts.containsKey(portId)) return

        val parts = portId.split("_")
        if (parts.size < 3) return

        val deviceId = parts[0].toIntOrNull() ?: return
        val portNumber = parts[2].toIntOrNull() ?: return

        // Synchronous dedup: if an openOutputPort() for this port is already in flight, bail —
        // otherwise a second enumeration connects a duplicate receiver (every message twice).
        if (!startingOutputPorts.add(portId)) return

        Log.d(TAG, "startListening: opening device $deviceId for port $portId")
        openOrGetDevice(deviceId) { device ->
            try {
                if (device == null) {
                    Log.e(TAG, "startListening: device $deviceId open failed")
                    return@openOrGetDevice
                }
                // Re-check after the async hop in case another path already connected.
                if (openOutputPorts.containsKey(portId)) return@openOrGetDevice

                val outputPort = device.openOutputPort(portNumber)
                if (outputPort != null) {
                    openOutputPorts[portId] = outputPort
                    outputPort.connect(object : MidiReceiver() {
                        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                            val bytes = data.copyOfRange(offset, offset + count)
                            mainHandler.post { onMidiReceived?.invoke(portId, bytes) }
                        }
                    })
                    Log.i(TAG, "Listening on $portId — receiving MIDI input")
                } else {
                    Log.e(TAG, "startListening: openOutputPort($portNumber) returned null")
                }
            } finally {
                startingOutputPorts.remove(portId)
            }
        }
    }

    // MARK: - Device Management

    override fun refreshDevices() {
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

    override fun close() {
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
