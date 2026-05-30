package com.blurt.tracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_records")
data class AppRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appName: String,
    val packageName: String,
    val startTime: Long,
    val endTime: Long,
)
