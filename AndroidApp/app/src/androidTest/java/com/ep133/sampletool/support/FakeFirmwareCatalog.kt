package com.ep133.sampletool.support

import com.ep133.sampletool.domain.firmware.FirmwareCatalog
import com.ep133.sampletool.domain.firmware.FirmwareVersion

/**
 * Fake FirmwareCatalog — no network. Mirrors the fake in DeviceViewModelFirmwareTest:
 * returns [result] (null = catalog unavailable → Unknown) or throws when [throwOnCall].
 */
class FakeFirmwareCatalog(
    private val result: FirmwareVersion? = null,
    private val throwOnCall: Boolean = false,
) : FirmwareCatalog {
    var callCount = 0
        private set

    override suspend fun latestVersion(): FirmwareVersion? {
        callCount++
        if (throwOnCall) throw RuntimeException("catalog unavailable (test)")
        return result
    }
}
