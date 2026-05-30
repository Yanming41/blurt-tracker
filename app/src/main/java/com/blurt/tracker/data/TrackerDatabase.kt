package com.blurt.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AppRecord::class, LocationRecord::class],
    version = 1,
    exportSchema = false,
)
abstract class TrackerDatabase : RoomDatabase() {

    abstract fun trackerDao(): TrackerDao

    companion object {
        @Volatile private var INSTANCE: TrackerDatabase? = null

        fun get(context: Context): TrackerDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                TrackerDatabase::class.java,
                "tracker.db",
            ).build().also { INSTANCE = it }
        }
    }
}
