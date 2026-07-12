package com.ep133.sampletool.midi

/**
 * A parsed USB-MIDI port identifier.
 *
 * The wire form is `"<deviceId>_<direction>_<portNumber>"` (direction is [DIR_OUT] for a device
 * output port we listen on, [DIR_IN] for a device input port we send to) — the opaque string that
 * crosses the [MIDIPort] boundary and is stored in `DeviceState`. [parse] recovers the fields from
 * that string; [wire] rebuilds it. Keeping the split/format in one place avoids the fragile,
 * repeated `portId.split("_")` parsing at every call site.
 */
internal data class PortId(val deviceId: Int, val direction: String, val portNumber: Int) {
    val wire: String get() = "${deviceId}_${direction}_$portNumber"

    companion object {
        const val DIR_OUT = "out"
        const val DIR_IN = "in"

        /**
         * Parse a wire-form port id, or null if it isn't exactly the `id_dir_num` shape (three
         * underscore-separated segments, a numeric id/portNumber, and [DIR_OUT]/[DIR_IN]).
         */
        fun parse(wire: String): PortId? {
            val parts = wire.split("_")
            if (parts.size != 3) return null
            val deviceId = parts[0].toIntOrNull() ?: return null
            val direction = parts[1].takeIf { it == DIR_OUT || it == DIR_IN } ?: return null
            val portNumber = parts[2].toIntOrNull() ?: return null
            return PortId(deviceId, direction, portNumber)
        }
    }
}
