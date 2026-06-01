package com.blurt.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AppRecord::class, LocationRecord::class, ScreenEvent::class],
    version = 2,
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
            )
                // MVP 阶段：表结构变化时直接重建本地库（测试期间数据不重要）
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}
