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
 */
interface FirmwareCatalog {
    suspend fun latestVersion(): FirmwareVersion?
}

/** Bundled floor: the most recent firmware version known at ship time. */
val LATEST_KNOWN_FIRMWARE: FirmwareVersion = FirmwareVersion.parse("2.5")!!

/**
 * Applies the bundled floor so a failed or stale scrape never reports a version
 * older than what the app already knows about. This is the FW-02 offline-detection
 * guarantee: even with no network the user still gets a meaningful comparison.
 */
internal fun floorToBundled(scraped: FirmwareVersion?): FirmwareVersion =
    if (scraped != null && scraped > LATEST_KNOWN_FIRMWARE) scraped else LATEST_KNOWN_FIRMWARE

/**
 * Extracts the highest valid firmware version token from a raw HTML string.
 *
 * 1. Finds all tokens matching `\d+\.\d+(?:\.\d+)?`
 * 2. Parses each with [FirmwareVersion.parse]
 * 3. Discards any whose major component > 50 — this excludes ISO-style release dates
 *    like `2026.06.24` which appear on the TE downloads page and would otherwise be
 *    misidentified as a version number.
 * 4. Returns the maximum remaining token, or null if none survive.
 */
internal fun parseVersions(html: String): FirmwareVersion? {
    val regex = Regex("""\d+\.\d+(?:\.\d+)?""")
    return regex.findAll(html)
        .mapNotNull { FirmwareVersion.parse(it.value) }
        .filter { it.parts[0] <= 50 }
        .maxOrNull()
}

/**
 * Scrapes the Teenage Engineering EP-133 downloads page to determine the latest
 * published firmware version.
 *
 * Uses a plain [HttpURLConnection] on [Dispatchers.IO] — no HTTP library dependency.
 * Short timeouts (5 s each) ensure the Device screen is never blocked.
 * Any failure is logged via [Log.w] and resolved to [LATEST_KNOWN_FIRMWARE] by the floor.
 */
class TeFirmwareCatalog : FirmwareCatalog {

    override suspend fun latestVersion(): FirmwareVersion? = withContext(Dispatchers.IO) {
        val scraped = try {
            val conn = URL("https://teenage.engineering/downloads/ep-133")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w("EP133APP", "FW check: HTTP $responseCode")
                conn.disconnect()
                null
            } else {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                conn.disconnect()
                parseVersions(body)
            }
        } catch (e: IOException) {
            Log.w("EP133APP", "FW check failed: $e")
            null
        } catch (e: Exception) {
            Log.w("EP133APP", "FW check failed: $e")
            null
        }

        floorToBundled(scraped)
    }

    companion object {
        /** Expose [parseVersions] for unit testing without a network call. */
        fun parseVersions(html: String): FirmwareVersion? =
            com.ep133.sampletool.domain.firmware.parseVersions(html)
    }
}
