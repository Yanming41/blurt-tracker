package com.blurt.tracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blurt.tracker.data.AppRecord
import com.blurt.tracker.data.LocationRecord
import com.blurt.tracker.data.TrackerDatabase
import com.blurt.tracker.util.Config
import com.blurt.tracker.util.PingClient
import com.blurt.tracker.util.PingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

sealed interface TimelineItem {
    val timestamp: Long
    data class App(val record: AppRecord) : TimelineItem {
        override val timestamp: Long get() = record.startTime
    }
    data class Loc(val record: LocationRecord) : TimelineItem {
        override val timestamp: Long get() = record.timestamp
    }
}

data class DashboardUiState(
    val totalScreenMillis: Long = 0,
    val itemsByHour: List<Pair<Int, List<TimelineItem>>> = emptyList(),
)

sealed interface DesktopStatus {
    data object Unknown : DesktopStatus
    data object Pinging : DesktopStatus
    data class Online(val latencyMs: Long) : DesktopStatus
    data class Offline(val reason: String) : DesktopStatus
    data object NotConfigured : DesktopStatus
}

data class DesktopUiState(
    val ip: String? = null,
    val status: DesktopStatus = DesktopStatus.Unknown,
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = TrackerDatabase.get(app).trackerDao()

    val state: StateFlow<DashboardUiState> = combine(
        dao.observeAppRecordsSince(startOfToday()),
        dao.observeLocationRecordsSince(startOfToday()),
    ) { apps, locs ->
        val total = apps.sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) }
        val items: List<TimelineItem> =
            apps.map { TimelineItem.App(it) } + locs.map { TimelineItem.Loc(it) }
        val grouped = items
            .sortedBy { it.timestamp }
            .groupBy { hourOf(it.timestamp) }
            .toSortedMap()
            .toList()
        DashboardUiState(totalScreenMillis = total, itemsByHour = grouped)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    private val _desktop = MutableStateFlow(
        DesktopUiState(
            ip = Config.getDesktopIp(app),
            status = if (Config.isConfigured(app)) DesktopStatus.Unknown else DesktopStatus.NotConfigured,
        ),
    )
    val desktop: StateFlow<DesktopUiState> = _desktop.asStateFlow()

    private var autoPingJob: Job? = null

    init {
        startAutoPing()
    }

    fun clearToday() {
        viewModelScope.launch(Dispatchers.IO) {
            val since = startOfToday()
            dao.deleteAppRecordsSince(since)
            dao.deleteLocationRecordsSince(since)
        }
    }

    /** 立即 ping 一次（按钮点击或外部触发）。 */
    fun pingNow() {
        val ip = Config.getDesktopIp(getApplication())
        if (ip == null) {
            _desktop.value = DesktopUiState(ip = null, status = DesktopStatus.NotConfigured)
            return
        }
        viewModelScope.launch {
            _desktop.value = _desktop.value.copy(ip = ip, status = DesktopStatus.Pinging)
            val result = PingClient.ping(ip)
            _desktop.value = DesktopUiState(
                ip = ip,
                status = when (result) {
                    is PingResult.Ok -> DesktopStatus.Online(result.latencyMs)
                    is PingResult.Error -> DesktopStatus.Offline(result.message)
                },
            )
        }
    }

    /** 用户从「修改 IP」弹窗保存后调用，立即刷新 ping。 */
    fun onDesktopIpChanged() {
        val ip = Config.getDesktopIp(getApplication())
        _desktop.value = DesktopUiState(
            ip = ip,
            status = if (ip == null) DesktopStatus.NotConfigured else DesktopStatus.Unknown,
        )
        startAutoPing()
    }

    private fun startAutoPing() {
        autoPingJob?.cancel()
        autoPingJob = viewModelScope.launch {
            pingNow()
            while (true) {
                delay(5 * 60_000L)
                pingNow()
            }
        }
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

    private fun hourOf(ts: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return c.get(Calendar.HOUR_OF_DAY)
    }
}
