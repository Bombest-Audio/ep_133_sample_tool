package com.ep133.sampletool.midi

/** Abstraction over platform MIDI for testability. */
interface MIDIPort {
    data class Device(val id: String, val name: String)
    data class Devices(val inputs: List<Device>, val outputs: List<Device>)

    var onMidiReceived: ((String, ByteArray) -> Unit)?
    var onDevicesChanged: (() -> Unit)?

    fun getUSBDevices(): Devices
    fun sendMidi(portId: String, data: ByteArray)
    fun requestUSBPermissions()
    fun refreshDevices()
    fun startListening(portId: String)
    fun closeAllListeners()
    fun close()
}
