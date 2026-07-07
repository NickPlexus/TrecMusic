package com.trec.music.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CachedTrackEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TrecDatabase : RoomDatabase() {
    abstract fun cachedTrackDao(): CachedTrackDao

    companion object {
        @Volatile
        private var instance: TrecDatabase? = null

        fun get(context: Context): TrecDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrecDatabase::class.java,
                    "trec_music.db"
                ).build().also { instance = it }
            }
    }
}
