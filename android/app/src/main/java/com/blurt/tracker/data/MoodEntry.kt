package com.blurt.tracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 用户主动记录的"情绪扔进烂摊子"条目 */
@Entity(tableName = "mood_entries")
data class MoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val timestamp: Long,
)
