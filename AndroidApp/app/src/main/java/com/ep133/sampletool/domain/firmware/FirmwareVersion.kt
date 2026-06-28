package com.ep133.sampletool.domain.firmware

/**
 * Immutable value type representing a firmware version as an ordered list of integer parts.
 *
 * Comparison pads the shorter operand with zeros, so 2.5 ([2,5]) is treated as [2,5,0]
 * when compared against 2.0.5 ([2,0,5]), making 2.5 > 2.0.5.
 */
data class FirmwareVersion(val parts: List<Int>) : Comparable<FirmwareVersion> {

    override fun compareTo(other: FirmwareVersion): Int {
        val maxLen = maxOf(parts.size, other.parts.size)
        for (i in 0 until maxLen) {
            val a = parts.getOrElse(i) { 0 }
            val b = other.parts.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        /**
         * Parses a raw version string like "2.5" or "2.0.5" into a FirmwareVersion.
         * Returns null on null, blank, or any non-numeric part.
         */
        fun parse(raw: String?): FirmwareVersion? {
            if (raw.isNullOrBlank()) return null
            val intParts = raw.trim().split(".").map { it.toIntOrNull() ?: return null }
            return FirmwareVersion(intParts)
        }
    }
}
