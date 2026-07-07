package com.trec.music.utils

import com.trec.music.data.TrecTrackEnhanced
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class DuplicateTrackItem(
    val track: TrecTrackEnhanced,
    val qualityScore: Float,
    val qualityLabel: String
)

data class DuplicateTrackGroup(
    val id: String,
    val items: List<DuplicateTrackItem>,
    val confidence: Float,
    val reason: String,
    val recommendedKeepUri: String
) {
    val removeCount: Int get() = (items.size - 1).coerceAtLeast(0)
}

object TrackDuplicateDetector {
    private val noisyWords = setOf(
        "official", "audio", "video", "lyrics", "lyric", "clip", "hd", "hq",
        "remaster", "remastered", "original", "version", "radio", "edit",
        "extended", "mix", "single", "album", "clean", "dirty", "explicit",
        "feat", "ft", "featuring", "prod", "mp3", "m4a", "flac", "aac",
        "320", "kbps", "download"
    )

    fun findDuplicateGroups(
        tracks: List<TrecTrackEnhanced>,
        minConfidence: Float = 0.72f
    ): List<DuplicateTrackGroup> {
        val unique = tracks.distinctBy { it.uri.toString() }
        if (unique.size < 2) return emptyList()

        val signatures = unique.map(::TrackSignature)
        val parent = IntArray(unique.size) { it }
        val pairScores = mutableMapOf<Pair<Int, Int>, Pair<Float, String>>()

        fun root(index: Int): Int {
            var r = index
            while (parent[r] != r) r = parent[r]
            var node = index
            while (parent[node] != node) {
                val next = parent[node]
                parent[node] = r
                node = next
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[rb] = ra
        }

        for (i in 0 until signatures.lastIndex) {
            for (j in i + 1 until signatures.size) {
                val match = compare(signatures[i], signatures[j])
                if (match.confidence >= minConfidence) {
                    union(i, j)
                    pairScores[i to j] = match.confidence to match.reason
                }
            }
        }

        return signatures.indices
            .groupBy { root(it) }
            .values
            .filter { it.size > 1 }
            .map { component ->
                val componentPairs = pairScores.filterKeys { pair ->
                    component.contains(pair.first) && component.contains(pair.second)
                }.values
                val confidence = componentPairs.map { it.first }.average().toFloat().coerceIn(0f, 1f)
                val reason = componentPairs
                    .groupingBy { it.second }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?: "Похожие название, исполнитель и длительность"
                val items = component
                    .map { index ->
                        val track = unique[index]
                        DuplicateTrackItem(
                            track = track,
                            qualityScore = qualityScore(track),
                            qualityLabel = qualityLabel(track)
                        )
                    }
                    .sortedByDescending { it.qualityScore }

                DuplicateTrackGroup(
                    id = items.joinToString("|") { it.track.uri.toString() },
                    items = items,
                    confidence = confidence,
                    reason = reason,
                    recommendedKeepUri = items.first().track.uri.toString()
                )
            }
            .sortedWith(compareByDescending<DuplicateTrackGroup> { it.confidence }.thenByDescending { it.items.size })
    }

    private data class TrackSignature(
        val track: TrecTrackEnhanced,
        val titleText: String,
        val artistText: String,
        val albumText: String,
        val titleTokens: Set<String>,
        val artistTokens: Set<String>,
        val durationMs: Long
    ) {
        constructor(track: TrecTrackEnhanced) : this(
            track = track,
            titleText = normalizedTitle(track),
            artistText = normalizedArtist(track),
            albumText = normalizePlain(track.album.orEmpty()),
            titleTokens = tokenSet(normalizedTitle(track)),
            artistTokens = tokenSet(normalizedArtist(track)),
            durationMs = track.durationMs.coerceAtLeast(0L)
        )
    }

    private data class MatchResult(val confidence: Float, val reason: String)

    private fun compare(a: TrackSignature, b: TrackSignature): MatchResult {
        val durationDiffMs = abs(a.durationMs - b.durationMs)
        val durationClose = when {
            a.durationMs <= 0L || b.durationMs <= 0L -> 0.58f
            durationDiffMs <= 2_000L -> 1f
            durationDiffMs <= 5_000L -> 0.92f
            durationDiffMs <= 10_000L -> 0.76f
            durationDiffMs <= 18_000L -> 0.55f
            else -> 0f
        }
        if (durationClose == 0f) return MatchResult(0f, "")

        val titleSimilarity = textSimilarity(a.titleText, b.titleText, a.titleTokens, b.titleTokens)
        val artistSimilarity = if (a.artistText.isBlank() || b.artistText.isBlank()) {
            0.55f
        } else {
            textSimilarity(a.artistText, b.artistText, a.artistTokens, b.artistTokens)
        }
        val albumSimilarity = if (a.albumText.isNotBlank() && b.albumText.isNotBlank()) {
            normalizedLevenshteinSimilarity(a.albumText, b.albumText)
        } else {
            0.5f
        }

        val relaxedNameMatch = artistSimilarity >= 0.90f && titleSimilarity >= 0.52f && durationClose >= 0.76f
        val strongNameMatch = titleSimilarity >= 0.74f && artistSimilarity >= 0.74f && durationClose >= 0.55f
        val titleOnlyMatch = titleSimilarity >= 0.91f && durationClose >= 0.76f
        if (!relaxedNameMatch && !strongNameMatch && !titleOnlyMatch) return MatchResult(0f, "")

        val artistWeight = if (a.artistText.isBlank() || b.artistText.isBlank()) 0.12f else 0.25f
        val titleWeight = if (artistWeight < 0.2f) 0.58f else 0.45f
        val durationWeight = 0.25f
        val albumWeight = 1f - titleWeight - artistWeight - durationWeight
        val confidence = (
            titleSimilarity * titleWeight +
                artistSimilarity * artistWeight +
                durationClose * durationWeight +
                albumSimilarity * albumWeight
            ).coerceIn(0f, 1f)

        val reason = when {
            durationDiffMs <= 2_000L && titleSimilarity >= 0.9f -> "Почти одинаковое название и длительность"
            relaxedNameMatch -> "Один исполнитель, похожее название и близкая длительность"
            titleOnlyMatch -> "Очень похожее название и близкая длительность"
            else -> "Похожие название, исполнитель и длительность"
        }
        return MatchResult(confidence, reason)
    }

    private fun normalizedTitle(track: TrecTrackEnhanced): String {
        val title = TrackMetadataText.normalizeValue(track.title)
            ?: track.uri.lastPathSegment?.substringBeforeLast('.')
            ?: ""
        val inferred = TrackMetadataText.inferArtistAndTitle(title)
        return normalizePlain(inferred.second)
    }

    private fun normalizedArtist(track: TrecTrackEnhanced): String {
        val artist = TrackMetadataText.displayArtist(track.artist, track.albumArtist, track.title)
            .takeUnless { it.equals("Unknown Artist", ignoreCase = true) }
            ?: ""
        return normalizePlain(artist)
    }

    private fun normalizePlain(value: String): String {
        val withoutAccents = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return withoutAccents
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]|\\{[^}]*}"), " ")
            .replace(Regex("[_+.,!?:;\"'`~|/\\\\]"), " ")
            .replace(Regex("\\b\\d{2,4}\\s*(kbps|kbit|hz)\\b"), " ")
            .replace(Regex("\\b\\d{1,3}\\s*[-.]\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun tokenSet(text: String): Set<String> {
        return text.split(' ')
            .asSequence()
            .map { it.trim('-') }
            .filter { it.length >= 2 }
            .filterNot { it.matches(Regex("\\d+(kbps|kbit|hz)")) }
            .filterNot { noisyWords.contains(it) }
            .toSet()
    }

    private fun textSimilarity(
        a: String,
        b: String,
        aTokens: Set<String>,
        bTokens: Set<String>
    ): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        if (a == b) return 1f
        val tokenScore = jaccard(aTokens, bTokens)
        val editScore = normalizedLevenshteinSimilarity(a, b)
        return (tokenScore * 0.62f + editScore * 0.38f).coerceIn(0f, 1f)
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val intersection = a.intersect(b).size.toFloat()
        val union = a.union(b).size.toFloat().coerceAtLeast(1f)
        return intersection / union
    }

    private fun normalizedLevenshteinSimilarity(a: String, b: String): Float {
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1f
        val distance = levenshtein(a, b)
        return (1f - distance.toFloat() / maxLen.toFloat()).coerceIn(0f, 1f)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = min(
                    min(current[j] + 1, previous[j + 1] + 1),
                    previous[j] + cost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun qualityScore(track: TrecTrackEnhanced): Float {
        val estimatedBitrate = if (track.bitrate != null && track.bitrate > 0) {
            track.bitrate.toFloat()
        } else if (track.fileSize > 0L && track.durationMs > 0L) {
            (track.fileSize * 8f / track.durationMs.toFloat()).coerceAtLeast(0f)
        } else {
            0f
        }

        val bitrateScore = (estimatedBitrate / 320f).coerceIn(0f, 1.15f)
        val sampleScore = ((track.sampleRate ?: 44_100).toFloat() / 48_000f).coerceIn(0f, 1f)
        val metadataScore = listOf(
            track.title.isNotBlank(),
            !track.artist.isNullOrBlank(),
            !track.album.isNullOrBlank(),
            !track.genre.isNullOrBlank()
        ).count { it } / 4f
        val sizeScore = if (track.fileSize > 0L) {
            (track.fileSize.toFloat() / (12L * 1024L * 1024L).toFloat()).coerceIn(0f, 1f)
        } else {
            0.45f
        }

        return (bitrateScore * 0.46f + sampleScore * 0.16f + metadataScore * 0.22f + sizeScore * 0.16f)
            .coerceIn(0f, 1.2f)
    }

    private fun qualityLabel(track: TrecTrackEnhanced): String {
        val bitrate = track.bitrate?.takeIf { it > 0 }
        val estimatedBitrate = if (bitrate == null && track.fileSize > 0L && track.durationMs > 0L) {
            (track.fileSize * 8f / track.durationMs.toFloat()).roundToInt().coerceAtLeast(1)
        } else {
            null
        }
        val bitrateText = when {
            bitrate != null -> "$bitrate kbps"
            estimatedBitrate != null -> "~$estimatedBitrate kbps"
            else -> "качество неизвестно"
        }
        return "$bitrateText · ${track.getFormattedFileSize()}"
    }
}
