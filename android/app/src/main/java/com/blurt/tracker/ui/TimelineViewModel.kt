package com.blurt.tracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blurt.tracker.data.LocationRecord
import com.blurt.tracker.data.MoodEntry
import com.blurt.tracker.data.ScreenEvent
import com.blurt.tracker.data.TrackerDatabase
import com.blurt.tracker.util.AppSegment
import com.blurt.tracker.util.GeocoderHelper
import com.blurt.tracker.util.UsageStatsHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

data class TodaySummary(
    val screenOnCount: Int = 0,
    val totalScreenTimeMs: Long = 0,
    val appCount: Int = 0,
    val locationCount: Int = 0,
)

class TimelineViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = TrackerDatabase.get(app).trackerDao()

    private val dayStart = startOfToday()
    private val dayEnd = dayStart + DAY_MS

    private val _appSegments = MutableStateFlow<List<AppSegment>>(emptyList())
    val appSegments: StateFlow<List<AppSegment>> = _appSegments.asStateFlow()

    val locationRecords: StateFlow<List<LocationRecord>> =
        dao.observeLocationRecordsBetween(dayStart, dayEnd)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val screenEvents: StateFlow<List<ScreenEvent>> =
        dao.observeScreenEventsBetween(dayStart, dayEnd)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val moodEntries: StateFlow<List<MoodEntry>> =
        dao.observeMoodEntriesBetween(dayStart, dayEnd)
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

    init {
        refresh()
        // 屏幕事件/位置变化时联动刷新统计
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                screenEvents, locationRecords,
            ) { screens, locs -> screens to locs }.collect { (screens, locs) ->
                recomputeSummary(_appSegments.value, screens, locs)
            }
        }
    }

    /**
     * 把数据库里所有位置记录用最新 Geocoder 逻辑重新跑一遍。
     * 节流 250ms 防止 Geocoder 限流。
     */
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
                delay(250) // 节流，避免 Geocoder 限流
            }
            _backfill.value = BackfillState.Finished(updated, all.size)
        }
    }

    fun dismissBackfill() {
        _backfill.value = BackfillState.Idle
    }

    /** 重新从系统读取 UsageStats（轻量、阻塞 IO）。 */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val rawEvents = UsageStatsHelper.getTodayEvents(ctx)
            // 给每条事件补 appName + icon
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
            recomputeSummary(segments, screenEvents.value, locationRecords.value)
        }
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
        /** 时间轴可视范围（小时） */
        const val TIMELINE_START_HOUR = 6
        const val TIMELINE_END_HOUR = 23

        fun startOfToday(): Long {
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return c.timeInMillis
        }
    }
}
