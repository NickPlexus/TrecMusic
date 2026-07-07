package com.trec.music.utils

import android.os.SystemClock
import kotlin.math.max

data class AudioAnalysisFrame(
    val volume: Float,
    val peak: Float,
    val bass: Float,
    val mid: Float,
    val treble: Float,
    val beat: Float,
    val timestampMs: Long
) {
    companion object {
        val Silent = AudioAnalysisFrame(
            volume = 0f,
            peak = 0f,
            bass = 0f,
            mid = 0f,
            treble = 0f,
            beat = 0f,
            timestampMs = 0L
        )
    }
}

object AudioAnalysisBus {
    @Volatile
    private var latestFrame: AudioAnalysisFrame = AudioAnalysisFrame.Silent

    fun publish(volume: Float, peak: Float, bass: Float, mid: Float, treble: Float, beat: Float) {
        latestFrame = AudioAnalysisFrame(
            volume = volume.coerceIn(0f, 1f),
            peak = peak.coerceIn(0f, 1f),
            bass = bass.coerceIn(0f, 1f),
            mid = mid.coerceIn(0f, 1f),
            treble = treble.coerceIn(0f, 1f),
            beat = beat.coerceIn(0f, 1f),
            timestampMs = SystemClock.elapsedRealtime()
        )
    }

    fun latest(maxAgeMs: Long = 450L): AudioAnalysisFrame {
        val frame = latestFrame
        val age = SystemClock.elapsedRealtime() - frame.timestampMs
        if (frame.timestampMs == 0L || age <= maxAgeMs) return frame

        val fade = (1f - (age - maxAgeMs).toFloat() / 900f).coerceIn(0f, 1f)
        if (fade <= 0f) return AudioAnalysisFrame.Silent
        return frame.copy(
            volume = frame.volume * fade,
            peak = frame.peak * fade,
            bass = frame.bass * fade,
            mid = frame.mid * fade,
            treble = frame.treble * fade,
            beat = max(frame.beat * fade, 0f)
        )
    }
}
