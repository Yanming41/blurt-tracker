package com.blurt.tracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blurt.tracker.data.AppRecord
import com.blurt.tracker.data.LocationRecord
import com.blurt.tracker.data.ScreenEvent
import com.blurt.tracker.data.TrackerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

sealed interface TLItem {
    val time: Long
    data class App(val record: AppRecord) : TLItem {
        override val time: Long get() = record.startTime
    }
    data class Loc(val record: LocationRecord) : TLItem {
        override val time: Long get() = record.timestamp
    }
    data class Screen(val event: ScreenEvent) : TLItem {
        override val time: Long get() = event.timestamp
    }
    /** 灰色"休息中"区块：从 start 到 end 一直息屏 */
    data class Idle(val start: Long, val end: Long) : TLItem {
        override val time: Long get() = start
        val durationMs: Long get() = end - start
    }
}

data class TimelineStats(
    val screenOnCount: Int = 0,
    val screenTimeMs: Long = 0,
    val appCount: Int = 0,
    val locationChanges: Int = 0,
)

data class TimelineUiState(
    val dayStart: Long = 0L,
    val items: List<TLItem> = emptyList(),
    val stats: TimelineStats = TimelineStats(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = TrackerDatabase.get(app).trackerDao()

    /** "目标日期"的 0 点。改这个就切换日。 */
    private val _dayStart = MutableStateFlow(startOfToday())
    val dayStart: StateFlow<Long> = _dayStart.asStateFlow()

    val state: StateFlow<TimelineUiState> = _dayStart.flatMapLatest { ds ->
        val de = ds + DAY_MS
        combine(
            dao.observeAppRecordsBetween(ds, de),
            dao.observeLocationRecordsBetween(ds, de),
            dao.observeScreenEventsBetween(ds, de),
        ) { apps, locs, screens ->
            build(ds, apps, locs, screens)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineUiState())

    fun goPrevDay() { _dayStart.value = _dayStart.value - DAY_MS }
    fun goNextDay() {
        val next = _dayStart.value + DAY_MS
        if (next <= startOfToday()) _dayStart.value = next
    }
    fun goToday() { _dayStart.value = startOfToday() }

    private fun build(
        dayStart: Long,
        apps: List<AppRecord>,
        locs: List<LocationRecord>,
        screens: List<ScreenEvent>,
    ): TimelineUiState {
        // ---- 计算 Idle 区块（息屏 → 下一次亮屏/解锁之间） ----
        val idles = mutableListOf<TLItem.Idle>()
        var lastOff: Long? = null
        for (e in screens) {
            when (e.eventType) {
                "息屏" -> lastOff = e.timestamp
                "亮屏", "解锁" -> {
                    val off = lastOff
                    if (off != null && e.timestamp - off >= IDLE_MIN_MS) {
                        idles += TLItem.Idle(off, e.timestamp)
                    }
                    lastOff = null
                }
            }
        }
        // 当天最后一段没合上：如果还没亮，截到当前
        lastOff?.let { off ->
            val now = System.currentTimeMillis().coerceAtMost(dayStart + DAY_MS)
            if (now - off >= IDLE_MIN_MS) idles += TLItem.Idle(off, now)
        }

        // ---- 合并所有事件 ----
        val items: List<TLItem> = buildList {
            addAll(apps.map { TLItem.App(it) })
            addAll(locs.map { TLItem.Loc(it) })
            // 解锁单独显示；亮/息屏的事件本身不再插入 timeline（用 Idle 代替了息屏段）
            addAll(screens.filter { it.eventType == "解锁" }.map { TLItem.Screen(it) })
            addAll(idles)
        }.sortedBy { it.time }

        // ---- 统计 ----
        val screenOnCount = screens.count { it.eventType == "亮屏" }
        val screenTimeMs = apps.sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) }
        val appCount = apps.map { it.packageName }.distinct().size
        val locationChanges = locs.size

        return TimelineUiState(
            dayStart = dayStart,
            items = items,
            stats = TimelineStats(
                screenOnCount = screenOnCount,
                screenTimeMs = screenTimeMs,
                appCount = appCount,
                locationChanges = locationChanges,
            ),
        )
    }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    companion object {
        private const val DAY_MS = 24 * 3600_000L
        /** 息屏低于 5 分钟不显示为 Idle 区块 */
        private const val IDLE_MIN_MS = 5 * 60_000L
        /** 超过 30 分钟显示 💤 强调图标 */
        const val IDLE_DEEP_MS = 30 * 60_000L
    }
}
