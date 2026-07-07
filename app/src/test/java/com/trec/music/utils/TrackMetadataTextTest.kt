package com.trec.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMetadataTextTest {
    @Test
    fun normalizeValue_removesTechnicalUnknownValues() {
        assertNull(TrackMetadataText.normalizeValue(null))
        assertNull(TrackMetadataText.normalizeValue(" <unknown> "))
        assertNull(TrackMetadataText.normalizeValue("null"))
        assertEquals("Daft Punk", TrackMetadataText.normalizeValue("  Daft   Punk  "))
    }

    @Test
    fun inferArtistAndTitle_splitsCommonSeparator() {
        val (artist, title) = TrackMetadataText.inferArtistAndTitle("Massive Attack - Teardrop")

        assertEquals("Massive Attack", artist)
        assertEquals("Teardrop", title)
    }

    @Test
    fun displayArtist_prefersDirectArtistThenAlbumArtistThenTitleInference() {
        assertEquals(
            "Radiohead",
            TrackMetadataText.displayArtist("Radiohead", "Various Artists", "No Surprises")
        )
        assertEquals(
            "Massive Attack",
            TrackMetadataText.displayArtist(null, null, "Massive Attack - Teardrop")
        )
    }
}
