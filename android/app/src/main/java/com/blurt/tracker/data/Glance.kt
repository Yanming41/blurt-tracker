package com.blurt.tracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 「瞥一眼」事件：短于 5 分钟的 App 前台片段，不成块。
 * 在时间轴上用小图标表示，所在 Block 的 interruptionCount +1。
 */
@Entity(tableName = "glances")
data class Glance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val durationMs: Long,
    val packageName: String,
    val appName: String,
    /** 若被某个块包含，存块 ID；否则 null（孤立瞥一眼，落在 idle 区） */
    val absorbedIntoBlockId: Long? = null,
)
