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

/** EP-133 / EP-133 K.O. II SKU in TE's release manifest. */
private const val EP133_SKU = "TE032AS001"

/**
 * Parses TE's firmware release manifest (see [TeFirmwareCatalog.RELEASES_URL]) and returns the
 * latest published EP-133 firmware version. The manifest is `{ "devices": [ { "sku", "version",
 * "fw_url", … }, … ] }`; we pick the EP-133 record by its [EP133_SKU] and read `version`. Returns
 * null if the EP-133 record or its version is absent or unparseable.
 *
 * Hand-rolled (rather than org.json) so it stays a pure function that unit-tests on the JVM without
 * an Android runtime. Each device object is flat (no nested braces), so matching brace-delimited
 * objects isolates them; the sku/version lookup within an object is order-independent.
 */
internal fun parseReleases(json: String): FirmwareVersion? {
    val skuPresent = Regex(""""sku"\s*:\s*"$EP133_SKU"""")
    val version = Regex(""""version"\s*:\s*"([^"]+)"""")
    for (obj in Regex("""\{[^{}]*}""").findAll(json)) {
        if (skuPresent.containsMatchIn(obj.value)) {
            val v = version.find(obj.value) ?: return null
            return FirmwareVersion.parse(v.groupValues[1])
        }
    }
    return null
}

/**
 * Resolves the latest published EP-133 firmware version from TE's own release manifest, keyed by the
 * EP-133 SKU. This is TE's authoritative source (the same JSON their update utility reads), so it
 * tracks new firmware the moment TE ships it — no app release and nothing for us to host or maintain.
 *
 * Supersedes both the earlier TE-downloads-page scrape (which pulled garbage tokens out of inline
 * SVG path data, e.g. "50.9332") and the interim Bombest-hosted manifest. Uses a plain
 * [HttpURLConnection] on [Dispatchers.IO] — no HTTP library dependency. Any failure is logged via
 * [Log.w] and resolved to [LATEST_KNOWN_FIRMWARE] by the floor.
 */
class TeFirmwareCatalog : FirmwareCatalog {

    override suspend fun latestVersion(): FirmwareVersion = withContext(Dispatchers.IO) {
        val fetched = try {
            val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val responseCode = conn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w("EP133APP", "FW releases: HTTP $responseCode")
                    null
                } else {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    parseReleases(body)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Log.w("EP133APP", "FW releases fetch failed: $e")
            null
        } catch (e: Exception) {
            Log.w("EP133APP", "FW releases fetch failed: $e")
            null
        }

        floorToBundled(fetched)
    }

    companion object {
        /** TE's authoritative firmware manifest — a `devices` array keyed by SKU. */
        const val RELEASES_URL = "https://teenage.engineering/_software/releases.json"
    }
}
