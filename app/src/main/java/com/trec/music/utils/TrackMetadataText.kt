package com.trec.music.utils

import java.util.Locale

object TrackMetadataText {

    private val unknownTokens = setOf(
        "",
        "unknown",
        "unknown artist",
        "<unknown>",
        "null",
        "n/a",
        "na",
        "none",
        "-",
        "неизвестный",
        "неизвестный исполнитель"
    )

    fun normalizeValue(value: String?): String? {
        if (value == null) return null
        val cleaned = value
            .replace('\u0000', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return null
        val key = cleaned.lowercase(Locale.ROOT)
        if (unknownTokens.contains(key)) return null
        return cleaned
    }

    fun inferArtistAndTitle(rawTitle: String): Pair<String?, String> {
        val title = normalizeValue(rawTitle) ?: "Unknown Track"
        val separators = listOf(" - ", " — ", " – ", " | ")
        for (sep in separators) {
            val idx = title.indexOf(sep)
            if (idx in 2..80) {
                val left = normalizeValue(title.substring(0, idx))
                val right = normalizeValue(title.substring(idx + sep.length))
                if (left != null && right != null && right.length >= 2) {
                    return left to right
                }
            }
        }
        return null to title
    }

    fun displayArtist(
        artist: String?,
        albumArtist: String?,
        title: String?
    ): String {
        val direct = normalizeValue(artist)
        if (direct != null) return direct

        val album = normalizeValue(albumArtist)
        if (album != null) return album

        val safeTitle = normalizeValue(title)
        if (safeTitle != null) {
            val inferred = inferArtistAndTitle(safeTitle).first
            if (inferred != null) return inferred
        }

        return "Unknown Artist"
    }
}

