package com.ep133.sampletool.support

import com.ep133.sampletool.domain.firmware.FirmwareCatalog
import com.ep133.sampletool.domain.firmware.FirmwareVersion
import kotlinx.coroutines.CompletableDeferred

/**
 * Fake FirmwareCatalog — no network. Mirrors the fake in DeviceViewModelFirmwareTest:
 * returns [result] (null = catalog unavailable → Unknown) or throws when [throwOnCall].
 * Set [gate] to hold the check in the Checking state until the test completes it.
 */
class FakeFirmwareCatalog(
    private val result: FirmwareVersion? = null,
    private val throwOnCall: Boolean = false,
) : FirmwareCatalog {
    var callCount = 0
        private set

    /** When non-null, latestVersion() suspends here — complete it to release the check. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun latestVersion(): FirmwareVersion? {
        callCount++
        gate?.await()
        if (throwOnCall) throw RuntimeException("catalog unavailable (test)")
        return result
    }
}
