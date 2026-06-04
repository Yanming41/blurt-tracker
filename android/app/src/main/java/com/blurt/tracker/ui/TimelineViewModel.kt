package com.blurt.tracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blurt.tracker.data.ActivityBlock
import com.blurt.tracker.data.Glance
import com.blurt.tracker.data.LocationRecord
import com.blurt.tracker.data.MoodEntry
import com.blurt.tracker.data.ScreenEvent
import com.blurt.tracker.data.TrackerDatabase
import com.blurt.tracker.util.AppSegment
import com.blurt.tracker.util.BlockBuilder
import com.blurt.tracker.util.GeocoderHelper
import com.blurt.tracker.util.UsageStatsHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

data class TodaySummary(
    val screenOnCount: Int = 0,
    val totalScreenTimeMs: Long = 0,
    val appCount: Int = 0,
    val locationCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = TrackerDatabase.get(app).trackerDao()

    // ====== 当前正在查看的"日"，0 点时间戳 ======
    private val _selectedDay = MutableStateFlow(startOfToday())
    val selectedDay: StateFlow<Long> = _selectedDay.asStateFlow()

    /** 是否在看今天 */
    val isToday: StateFlow<Boolean> = MutableStateFlow(true).also { mf ->
        viewModelScope.launch {
            _selectedDay.collect { mf.value = (it == startOfToday()) }
        }
    }

    fun goPrevDay() { _selectedDay.value = _selectedDay.value - DAY_MS }
    fun goNextDay() {
        val next = _selectedDay.value + DAY_MS
        if (next <= startOfToday()) _selectedDay.value = next
    }
    fun goToday() { _selectedDay.value = startOfToday() }

    // ====== 数据流：跟着 _selectedDay 切换 ======
    private val _appSegments = MutableStateFlow<List<AppSegment>>(emptyList())
    val appSegments: StateFlow<List<AppSegment>> = _appSegments.asStateFlow()

    val locationRecords: StateFlow<List<LocationRecord>> = _selectedDay
        .flatMapLatest { d -> dao.observeLocationRecordsBetween(d, d + DAY_MS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val screenEvents: StateFlow<List<ScreenEvent>> = _selectedDay
        .flatMapLatest { d -> dao.observeScreenEventsBetween(d, d + DAY_MS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val moodEntries: StateFlow<List<MoodEntry>> = _selectedDay
        .flatMapLatest { d -> dao.observeMoodEntriesBetween(d, d + DAY_MS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activityBlocks: StateFlow<List<ActivityBlock>> = _selectedDay
        .flatMapLatest { d -> dao.observeBlocksBetween(d, d + DAY_MS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val glances: StateFlow<List<Glance>> = _selectedDay
        .flatMapLatest { d -> dao.observeGlancesBetween(d, d + DAY_MS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _summary = MutableStateFlow(TodaySummary())
    val todaySummary: StateFlow<TodaySummary> = _summary.asStateFlow()

    // ----- 地址回填进度 -----
    sealed interface BackfillState {
        data object Idle : BackfillState
        data class Running(val done: Int, val total: Int) : BackfillState
        data class Finished(val updated: Int, val total: Int) : BackfillState
    }
    private val _backfill = MutableStateFlow<BackfillState>(BackfillState.Idle)
    val backfill: StateFlow<BackfillState> = _backfill.asStateFlow()

    // ----- 历史日数据回灌进度 -----
    sealed interface HistoryBackfillState {
        data object Idle : HistoryBackfillState
        data class Running(val currentDay: String, val done: Int, val total: Int) : HistoryBackfillState
        data class Finished(val daysProcessed: Int, val blocksCreated: Int) : HistoryBackfillState
    }
    private val _historyBackfill = MutableStateFlow<HistoryBackfillState>(HistoryBackfillState.Idle)
    val historyBackfill: StateFlow<HistoryBackfillState> = _historyBackfill.asStateFlow()

    init {
        // 切日时自动 refresh
        viewModelScope.launch {
            _selectedDay.collect { _ -> refresh() }
        }
        // 屏幕事件/位置变化时联动刷新统计
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                screenEvents, locationRecords,
            ) { s, l -> s to l }.collect { (s, l) ->
                recomputeSummary(_appSegments.value, s, l)
            }
        }
    }

    // ============== 地址回填（旧功能保留）==============

    fun backfillAddresses() {
        if (_backfill.value is BackfillState.Running) return
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val all = dao.getAllLocationRecords()
            if (all.isEmpty()) {
                _backfill.value = BackfillState.Finished(0, 0)
                return@launch
            }
            _backfill.value = BackfillState.Running(0, all.size)
            var updated = 0
            for ((i, rec) in all.withIndex()) {
                val newAddr = GeocoderHelper.reverseGeocode(ctx, rec.latitude, rec.longitude)
                if (newAddr.isNotBlank() && newAddr != rec.address &&
                    !newAddr.contains("失败") && !newAddr.contains("未知")
                ) {
                    dao.updateLocationAddress(rec.id, newAddr)
                    updated++
                }
                _backfill.value = BackfillState.Running(i + 1, all.size)
                delay(250)
            }
            _backfill.value = BackfillState.Finished(updated, all.size)
        }
    }

    fun dismissBackfill() { _backfill.value = BackfillState.Idle }

    // ============== 历史日数据回灌（新）==============

    /**
     * 回灌过去 N 天的数据：
     * - 拉 UsageEvents (App 段 + 屏幕事件)
     * - 写 Room (screen_events 去重)
     * - 跑 BlockBuilder 产出 ActivityBlock + Glance
     *
     * UsageStats 通常保留 7 天明细，再往前会失败/空。
     */
    fun backfillPastDays(days: Int = 7) {
        if (_historyBackfill.value is HistoryBackfillState.Running) return
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val today = startOfToday()
            var totalBlocks = 0
            val labelFmt = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
            for (i in 1..days) {
                val day = today - i * DAY_MS
                _historyBackfill.value = HistoryBackfillState.Running(
                    currentDay = labelFmt.format(java.util.Date(day)),
                    done = i - 1, total = days,
                )
                totalBlocks += processDay(ctx, day)
            }
            _historyBackfill.value = HistoryBackfillState.Finished(days, totalBlocks)
        }
    }

    fun dismissHistoryBackfill() { _historyBackfill.value = HistoryBackfillState.Idle }

    /**
     * 处理一天：拉 UsageStats + 切块 + 写库。
     * 返回新建的块数（粗略指标）。
     */
    private suspend fun processDay(ctx: android.content.Context, day: Long): Int {
        val dayEnd = day + DAY_MS

        // 1. App 段
        val rawEvents = UsageStatsHelper.getEventsBetween(ctx, day, dayEnd)
        val enriched = rawEvents.map {
            it.copy(
                appName = UsageStatsHelper.resolveAppName(ctx, it.packageName),
                appIcon = null, // 历史日不需要 icon
            )
        }
        val segments = UsageStatsHelper.eventsToSegments(enriched).map {
            it.copy(appName = UsageStatsHelper.resolveAppName(ctx, it.packageName))
        }

        // 2. 屏幕事件（去重）
        val fromSystem = UsageStatsHelper.getScreenEventsBetween(ctx, day, dayEnd)
        if (fromSystem.isNotEmpty()) {
            val existing = dao.getScreenEventsBetween(day, dayEnd)
            val existingKeys = existing.map { it.eventType to it.timestamp }.toHashSet()
            for (e in fromSystem) {
                if ((e.eventType to e.timestamp) !in existingKeys) dao.insertScreenEvent(e)
            }
        }

        // 3. 切块（只删自动块，保留用户改过的）
        dao.deleteAutoBlocksBetween(day, dayEnd)
        dao.deleteGlancesBetween(day, dayEnd)
        val result = BlockBuilder.build(
            segments = segments,
            screenEvents = dao.getScreenEventsBetween(day, dayEnd),
            locations = dao.getLocationsBetween(day, dayEnd),
        )
        if (result.blocks.isNotEmpty()) dao.insertActivityBlocks(result.blocks)
        if (result.glances.isNotEmpty()) dao.insertGlances(result.glances)

        android.util.Log.i("BLURT_DEBUG",
            "[backfill] day=$day segments=${segments.size} blocks=${result.blocks.size}")
        return result.blocks.size
    }

    // ============== 单日 refresh（切日 + 用户点刷新都走这里）==============

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val day = _selectedDay.value
            val dayEnd = day + DAY_MS

            val rawEvents = UsageStatsHelper.getEventsBetween(ctx, day, dayEnd)
            val enriched = rawEvents.map {
                it.copy(
                    appName = UsageStatsHelper.resolveAppName(ctx, it.packageName),
                    appIcon = UsageStatsHelper.getAppIcon(ctx, it.packageName),
                )
            }
            val segments = UsageStatsHelper.eventsToSegments(enriched).map {
                it.copy(
                    appName = UsageStatsHelper.resolveAppName(ctx, it.packageName),
                    appIcon = UsageStatsHelper.getAppIcon(ctx, it.packageName),
                )
            }
            _appSegments.value = segments

            syncScreenEventsFromUsageEvents(ctx, day, dayEnd)
            rebuildActivityBlocks(day, dayEnd, segments)
            recomputeSummary(segments, screenEvents.value, locationRecords.value)
        }
    }

    private suspend fun rebuildActivityBlocks(
        dayStart: Long, dayEnd: Long, segments: List<AppSegment>,
    ) {
        dao.deleteAutoBlocksBetween(dayStart, dayEnd)
        dao.deleteGlancesBetween(dayStart, dayEnd)
        val result = BlockBuilder.build(
            segments = segments,
            screenEvents = screenEvents.value,
            locations = locationRecords.value,
        )
        if (result.blocks.isNotEmpty()) dao.insertActivityBlocks(result.blocks)
        if (result.glances.isNotEmpty()) dao.insertGlances(result.glances)
        android.util.Log.i("BLURT_DEBUG",
            "[rebuildBlocks] day=$dayStart segments=${segments.size} blocks=${result.blocks.size}")
    }

    private suspend fun syncScreenEventsFromUsageEvents(
        ctx: android.content.Context, dayStart: Long, dayEnd: Long,
    ) {
        val fromSystem = UsageStatsHelper.getScreenEventsBetween(ctx, dayStart, dayEnd)
        if (fromSystem.isEmpty()) return
        val existing = dao.getScreenEventsBetween(dayStart, dayEnd)
        val existingKeys = existing.map { it.eventType to it.timestamp }.toHashSet()
        var inserted = 0
        for (e in fromSystem) {
            val key = e.eventType to e.timestamp
            if (key !in existingKeys) {
                dao.insertScreenEvent(e)
                inserted++
            }
        }
        android.util.Log.i("BLURT_DEBUG",
            "[syncScreenEvents] day=$dayStart system=${fromSystem.size} existing=${existing.size} inserted=$inserted")
    }

    private fun recomputeSummary(
        segs: List<AppSegment>,
        screens: List<ScreenEvent>,
        locs: List<LocationRecord>,
    ) {
        _summary.value = TodaySummary(
            screenOnCount = screens.count { it.eventType == "亮屏" },
            totalScreenTimeMs = segs.sumOf { it.durationMs },
            appCount = segs.map { it.packageName }.distinct().size,
            locationCount = locs.size,
        )
    }

    companion object {
        const val DAY_MS = 24 * 3600_000L
        const val TIMELINE_START_HOUR = 0
        const val TIMELINE_END_HOUR = 24

        fun startOfToday(): Long {
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return c.timeInMillis
        }
    }
}
