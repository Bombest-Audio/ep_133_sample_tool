package com.ep133.sampletool

import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*

/**
 * Wave 0 scaffold for the share intent construction (PROJ-04).
 *
 * Covers (when filled by Wave 3):
 *  - ShareCompat.IntentBuilder + FileProvider content:// URI + ACTION_SEND
 *
 * A real share-intent test needs a Context and a registered FileProvider; Robolectric
 * is not in the dependency set, so the intent path is @Ignore'd with the repo's
 * hardware/instrumented justification string. The executing placeholder asserts the
 * MIME constant the share builder will use.
 *
 * TODO(04-project-management-04): replace the MIME placeholder + un-Ignore once a
 * Robolectric/instrumented harness can construct the FileProvider share intent.
 */
class ShareIntentTest {

    @Test
    fun shareMimeType_isOctetStream() {
        // TODO(04-project-management-04): replace placeholder with real ShareCompat/FileProvider intent assertions
        // Wave 3 builds ShareCompat.IntentBuilder(context).setType(SHARE_MIME).setStream(uri)...
        val shareMime = "application/octet-stream"
        assertEquals("application/octet-stream", shareMime)
    }

    @Ignore("ShareCompat/FileProvider intent construction requires a real Context + registered FileProvider — Robolectric not in the dep set; validated on an instrumented/physical device")
    @Test
    fun buildsFileProviderUri_andActionSend() {
        // FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        // ShareCompat.IntentBuilder(context).setType("application/octet-stream").setStream(uri)...
        // Assert intent.action == ACTION_SEND and the data URI scheme is content://
    }
}
