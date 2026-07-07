package com.trec.music.utils

import android.net.Uri
import com.trec.music.data.TrecTrackEnhanced
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackDuplicateDetectorTest {
    @Test
    fun findDuplicateGroups_detectsSameSongWithNoisyTitlesAndCloseDuration() {
        val tracks = listOf(
            track(
                uri = "content://music/1",
                title = "Lady Gaga - Applause",
                artist = "Lady Gaga",
                durationMs = 212_000,
                bitrate = 320,
                fileSize = 8_500_000
            ),
            track(
                uri = "content://music/2",
                title = "Applause (Official Audio) 320kbps",
                artist = "Lady Gaga",
                durationMs = 214_000,
                bitrate = 192,
                fileSize = 5_600_000
            ),
            track(
                uri = "content://music/3",
                title = "A Pearl",
                artist = "Mitski",
                durationMs = 138_000,
                bitrate = 320,
                fileSize = 5_300_000
            )
        )

        val groups = TrackDuplicateDetector.findDuplicateGroups(tracks)

        assertEquals(1, groups.size)
        assertEquals(2, groups.first().items.size)
        assertTrue(groups.first().confidence >= 0.72f)
        assertEquals("content://music/1", groups.first().recommendedKeepUri)
    }

    @Test
    fun findDuplicateGroups_ignoresDifferentSongsWithSameArtist() {
        val tracks = listOf(
            track("content://music/1", "Applause", "Lady Gaga", 212_000),
            track("content://music/2", "Bad Romance", "Lady Gaga", 294_000)
        )

        val groups = TrackDuplicateDetector.findDuplicateGroups(tracks)

        assertTrue(groups.isEmpty())
    }

    private fun track(
        uri: String,
        title: String,
        artist: String,
        durationMs: Long,
        bitrate: Int? = null,
        fileSize: Long = 0L
    ): TrecTrackEnhanced {
        return TrecTrackEnhanced(
            uri = Uri.parse(uri),
            title = title,
            artist = artist,
            durationMs = durationMs,
            bitrate = bitrate,
            fileSize = fileSize
        )
    }
}
