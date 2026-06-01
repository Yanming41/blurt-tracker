package com.blurt.tracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "screen_events")
data class ScreenEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 亮屏 / 息屏 / 解锁 */
    val eventType: String,
    val timestamp: Long,
)
