package com.trec.music.data.local

import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.trec.music.data.TrecTrackEnhanced

@Entity(tableName = "track_cache")
data class CachedTrackEntity(
    @PrimaryKey val uri: String,
    val sortIndex: Int,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val albumArtist: String?,
    val genre: String?,
    val year: Int?,
    val trackNumber: Int?,
    val composer: String?,
    val bitrate: Int?,
    val sampleRate: Int?,
    val mimeType: String?,
    val fileSize: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val isLocal: Boolean,
    val path: String?
) {
    fun toTrack(): TrecTrackEnhanced = TrecTrackEnhanced(
        uri = Uri.parse(uri),
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        albumArtist = albumArtist,
        genre = genre,
        year = year,
        trackNumber = trackNumber,
        composer = composer,
        bitrate = bitrate,
        sampleRate = sampleRate,
        mimeType = mimeType,
        fileSize = fileSize,
        dateAdded = dateAdded,
        dateModified = dateModified,
        isLocal = isLocal,
        path = path
    )

    companion object {
        fun fromTrack(track: TrecTrackEnhanced, sortIndex: Int): CachedTrackEntity =
            CachedTrackEntity(
                uri = track.uri.toString(),
                sortIndex = sortIndex,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                albumArtist = track.albumArtist,
                genre = track.genre,
                year = track.year,
                trackNumber = track.trackNumber,
                composer = track.composer,
                bitrate = track.bitrate,
                sampleRate = track.sampleRate,
                mimeType = track.mimeType,
                fileSize = track.fileSize,
                dateAdded = track.dateAdded,
                dateModified = track.dateModified,
                isLocal = track.isLocal,
                path = track.path
            )
    }
}
