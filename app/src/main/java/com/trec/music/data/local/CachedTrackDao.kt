package com.trec.music.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CachedTrackDao {
    @Query("SELECT * FROM track_cache ORDER BY sortIndex ASC")
    suspend fun getTracks(): List<CachedTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<CachedTrackEntity>)

    @Query("DELETE FROM track_cache")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(tracks: List<CachedTrackEntity>) {
        clear()
        if (tracks.isNotEmpty()) insertAll(tracks)
    }
}
