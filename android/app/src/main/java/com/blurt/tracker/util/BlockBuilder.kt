package com.blurt.tracker.util

import com.blurt.tracker.data.ActivityBlock
import com.blurt.tracker.data.Glance
import com.blurt.tracker.data.LocationRecord
import com.blurt.tracker.data.ScreenEvent

/**
 * Phase 1：从原始 AppSegment 切出 ActivityBlock + Glance。
 * 纯规则，可单测，不依赖 LLM / 网络。
 *
 * 规则（v1）：
 *  - 段长 < 5 分钟  -> Glance（瞥一眼），不成块
 *  - 段长 >= 5 分钟 -> 候选成块
 *  - 相邻同类别 + 之间间隔 <= 30min       -> 合并
 *  - 类别变化 / 间隔 > 30min               -> 切
 *  - 跨日 (0 点)                           -> 切（调用方按天切分调用即可）
 *  - 块的 interruptionCount = 块内 Glance 数 + 块内"跨类别短切"次数
 */
object BlockBuilder {

    const val MIN_BLOCK_MS = 5 * 60_000L
    const val HARD_GAP_MS = 30 * 60_000L

    data class Result(
        val blocks: List<ActivityBlock>,
        val glances: List<Glance>,
    )

    fun build(
        segments: List<AppSegment>,
        screenEvents: List<ScreenEvent> = emptyList(),
        locations: List<LocationRecord> = emptyList(),
    ): Result {
        if (segments.isEmpty()) return Result(emptyList(), emptyList())

        val sorted = segments.sortedBy { it.startTime }

        // Step 1: 分流 —— 长段（待成块）与短段（瞥一眼）
        val longSegs = mutableListOf<AppSegment>()
        val glanceSegs = mutableListOf<AppSegment>()
        for (s in sorted) {
            if (s.durationMs >= MIN_BLOCK_MS) longSegs += s else glanceSegs += s
        }

        // Step 2: 按 (类别 + 间隔) 把长段串成 group
        data class Group(val cat: String, val segs: MutableList<AppSegment>)
        val groups = mutableListOf<Group>()
        var current: Group? = null
        for (s in longSegs) {
            val cat = AppCategory.categorize(s.packageName, s.appName)
            val gap = if (current != null) s.startTime - current.segs.last().endTime else 0L
            if (current != null && current.cat == cat && gap <= HARD_GAP_MS) {
                current.segs += s
            } else {
                current = Group(cat, mutableListOf(s))
                groups += current
            }
        }

        // Step 3: group -> ActivityBlock 雏形
        val blocks = groups.map { g ->
            val start = g.segs.first().startTime
            val end = g.segs.last().endTime
            val totalAppMs = g.segs.sumOf { it.durationMs }
            val dominantBy = g.segs.groupBy { it.packageName }
                .maxByOrNull { (_, v) -> v.sumOf { it.durationMs } }!!
            val domSeg = dominantBy.value.first()

            ActivityBlock(
                startTime = start,
                endTime = end,
                category = g.cat,
                dominantAppPackage = domSeg.packageName,
                dominantAppName = domSeg.appName,
                totalAppTimeMs = totalAppMs,
                durationMs = end - start,
                interruptionCount = 0, // 下一步算
                locationAddress = pickPrimaryLocation(start, end, locations),
            )
        }.toMutableList()

        // Step 4: 把 Glance 归属到块（落在块时间范围内）
        val glancesOut = glanceSegs.map { g ->
            // 我们不知道块 ID（未入库），后面 ViewModel 持久化后再回填 absorbedIntoBlockId
            Glance(
                timestamp = g.startTime,
                durationMs = g.durationMs,
                packageName = g.packageName,
                appName = g.appName,
                absorbedIntoBlockId = null,
            )
        }

        // Step 5: 计算每个块的 interruptionCount
        val finalBlocks = blocks.map { blk ->
            val n = glancesOut.count {
                it.timestamp in blk.startTime..blk.endTime
            }
            blk.copy(interruptionCount = n)
        }

        return Result(finalBlocks, glancesOut)
    }

    /** 块期间最频繁出现的位置地址（按时间内距离最近原则） */
    private fun pickPrimaryLocation(
        start: Long,
        end: Long,
        locations: List<LocationRecord>,
    ): String? {
        if (locations.isEmpty()) return null
        val inRange = locations.filter { it.timestamp in start..end }
        if (inRange.isNotEmpty()) {
            // 频率最高
            return inRange.groupingBy { it.address }.eachCount()
                .maxByOrNull { it.value }?.key
        }
        // 块期间没采样点 -> 取最接近开始时间的那一条
        return locations.minByOrNull { kotlin.math.abs(it.timestamp - start) }?.address
    }
}
