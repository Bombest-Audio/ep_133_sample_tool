package com.ep133.sampletool.ui.offline

import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Local one-shot playback seam for offline audition (ROADMAP 999.11 / issue #55).
 *
 * The ViewModel talks to this interface so unit tests can pin audition behavior with a fake;
 * the app wires [MediaPlayerSamplePlayer], which mirrors the Kit Builder's MediaPlayer audition
 * path but plays manifest WAV [File]s instead of SAF URIs.
 */
interface SamplePlayer {
    /**
     * Start playing [file] from the top, replacing any current playback.
     *
     * @param onComplete invoked when playback finishes or errors out (not when [stop] cuts it).
     * @return true if playback started, false if the file could not be played.
     */
    fun play(file: File, onComplete: () -> Unit): Boolean

    /** Stop and release any current playback. Safe to call when idle. */
    fun stop()
}

/** MediaPlayer-backed [SamplePlayer] — one player at a time, released on stop/complete/error. */
class MediaPlayerSamplePlayer : SamplePlayer {

    private var player: MediaPlayer? = null

    override fun play(file: File, onComplete: () -> Unit): Boolean {
        stop()
        return try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop(); onComplete() }
                setOnErrorListener { _, _, _ -> stop(); onComplete(); true }
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Offline audition failed for ${file.name}", e)
            stop()
            false
        }
    }

    override fun stop() {
        player?.release()
        player = null
    }

    private companion object {
        const val TAG = "EP133APP"
    }
}
