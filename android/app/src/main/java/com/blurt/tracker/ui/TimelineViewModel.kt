package com.blurt.tracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blurt.tracker.data.LocationRecord
import com.blurt.tracker.data.MoodEntry
import com.blurt.tracker.data.ScreenEvent
import com.blurt.tracker.data.TrackerDatabase
import com.blurt.tracker.util.AppSegment
import com.blurt.tracker.util.UsageStatsHelper
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
