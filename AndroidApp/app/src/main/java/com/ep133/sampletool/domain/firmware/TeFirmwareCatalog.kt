package com.ep133.sampletool.domain.firmware

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Abstraction over "what is the latest published firmware version?"
 * Implemented by [TeFirmwareCatalog]; tests inject a fake.
 *
 * The return is nullable on purpose: it is the seam the DeviceViewModel check treats
 * defensively (a null latest → Unknown, no banner), and it lets a fake return null to
 * exercise that path. The production [TeFirmwareCatalog] floors to [LATEST_KNOWN_FIRMWARE]
 * and so never returns null — its override narrows the type accordingly.
 */
interface FirmwareCatalog {
    suspend fun latestVersion(): FirmwareVersion?
}

/** Bundled floor: the most recent firmware version known at ship time. Bump on each app release. */
val LATEST_KNOWN_FIRMWARE: FirmwareVersion = FirmwareVersion.parse("2.5")!!

/**
 * Applies the bundled floor so a missing or stale manifest never reports a version older than what
 * the app already knows about. This is the FW-02 offline-detection guarantee: with no network — or
 * before the manifest is ever published — the user still gets a meaningful comparison against 2.5.
 */
internal fun floorToBundled(fetched: FirmwareVersion?): FirmwareVersion =
    if (fetched != null && fetched > LATEST_KNOWN_FIRMWARE) fetched else LATEST_KNOWN_FIRMWARE

/**
 * Parses the firmware manifest body — a tiny JSON document like `{"latest":"2.5"}` — into a
 * [FirmwareVersion]. Returns null if the `latest` field is absent, blank, or not a version.
 *
 * A hand-rolled extractor (rather than org.json) keeps this a pure function that unit-tests without
 * an Android runtime, and the manifest is trivial enough not to warrant a JSON dependency.
 */
internal fun parseManifest(json: String): FirmwareVersion? {
    val match = Regex(""""latest"\s*:\s*"([^"]*)"""").find(json) ?: return null
    return FirmwareVersion.parse(match.groupValues[1])
}

/**
 * Resolves the latest published EP-133 firmware version from a small JSON manifest hosted on the
 * Bombest releases bucket. The manifest is updated when TE ships new firmware — no app release
 * required — and falls back to [LATEST_KNOWN_FIRMWARE] whenever it can't be fetched or parsed.
 *
 * Replaces the earlier approach of scraping the TE downloads page, which pulled garbage tokens out
 * of inline SVG path data (e.g. "50.9332"). Uses a plain [HttpURLConnection] on [Dispatchers.IO] —
 * no HTTP library dependency. Any failure is logged via [Log.w] and resolved by the floor.
 */
class TeFirmwareCatalog : FirmwareCatalog {

    override suspend fun latestVersion(): FirmwareVersion = withContext(Dispatchers.IO) {
        val fetched = try {
            val conn = URL(MANIFEST_URL).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val responseCode = conn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w("EP133APP", "FW manifest: HTTP $responseCode")
                    null
                } else {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    parseManifest(body)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Log.w("EP133APP", "FW manifest fetch failed: $e")
            null
        } catch (e: Exception) {
            Log.w("EP133APP", "FW manifest fetch failed: $e")
            null
        }

        floorToBundled(fetched)
    }

    companion object {
        /** Bombest releases bucket; update this object when TE ships firmware. */
        const val MANIFEST_URL = "https://bombest-releases.s3.amazonaws.com/ep133/firmware.json"
    }
}
