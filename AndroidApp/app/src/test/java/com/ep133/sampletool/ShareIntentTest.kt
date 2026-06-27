package com.ep133.sampletool

import com.ep133.sampletool.ui.projects.SHARE_MIME
import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*

/**
 * Share intent construction (PROJ-04).
 *
 * Asserts the actual [SHARE_MIME] constant the share builder feeds into
 * `ShareCompat.IntentBuilder(...).setType(SHARE_MIME)` — an opaque octet-stream (T-04-09),
 * never a typed/over-broad MIME.
 *
 * A real share-intent test needs a Context and a registered FileProvider; Robolectric is not
 * in the dependency set, so the FileProvider URI + ACTION_SEND path is @Ignore'd with the
 * repo's hardware/instrumented justification string and validated on a device.
 */
class ShareIntentTest {

    @Test
    fun shareMimeType_isOctetStream() {
        // ProjectsScreen builds ShareCompat.IntentBuilder(context).setType(SHARE_MIME).setStream(uri)...
        assertEquals("application/octet-stream", SHARE_MIME)
    }

    @Ignore("ShareCompat/FileProvider intent construction requires a real Context + registered FileProvider — Robolectric not in the dep set; validated on an instrumented/physical device")
    @Test
    fun buildsFileProviderUri_andActionSend() {
        // FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        // ShareCompat.IntentBuilder(context).setType("application/octet-stream").setStream(uri)...
        // Assert intent.action == ACTION_SEND and the data URI scheme is content://
    }
}
