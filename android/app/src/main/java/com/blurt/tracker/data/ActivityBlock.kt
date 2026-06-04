package com.blurt.tracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 时间块：用户在 [startTime, endTime] 内做了一件"同类别"的事。
 *
 * Phase 1 由 BlockBuilder 纯规则生成，label/confidence/reasoning 为空。
 * Phase 2 LLM 跑批后回填 label 字段。
 * Phase 3 用户手动修正会置 manuallyCorrected=true，不再被覆盖。
 */
@Entity(tableName = "activity_blocks")
data class ActivityBlock(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    /** AppCategory 常量之一 */
    val category: String,
    val dominantAppPackage: String,
    val dominantAppName: String,
    /** 块内 App 前台时间总和（去掉 idle 部分） */
    val totalAppTimeMs: Long,
    /** 块时长 = endTime - startTime */
    val durationMs: Long,
    /** 块内"瞥一眼"事件数（含其他 App 短切） */
    val interruptionCount: Int,
    // ----- LLM 后期填写 -----
    val activityLabel: String? = null,
    val subLabel: String? = null,
    val confidence: Float? = null,
    val reasoning: String? = null,
    val askUser: String? = null,
    val manuallyCorrected: Boolean = false,
    // ----- 位置（取块期间主导位置的地址） -----
    val locationAddress: String? = null,
)
