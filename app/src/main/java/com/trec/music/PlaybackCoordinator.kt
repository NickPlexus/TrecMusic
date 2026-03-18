package com.trec.music

object PlaybackCoordinator {
    private var pauseMusic: (() -> Unit)? = null
    private var pauseRecorder: (() -> Unit)? = null

    fun registerMusicPause(action: () -> Unit) {
        pauseMusic = action
    }

    fun registerRecorderPause(action: () -> Unit) {
        pauseRecorder = action
    }

    fun pauseMusic() {
        pauseMusic?.invoke()
    }

    fun pauseRecorder() {
        pauseRecorder?.invoke()
    }

    fun clearMusic() {
        pauseMusic = null
    }

    fun clearRecorder() {
        pauseRecorder = null
    }
}
