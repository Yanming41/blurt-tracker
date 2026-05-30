package com.blurt.tracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blurt.tracker.data.AppRecord
import com.blurt.tracker.data.LocationRecord
import com.blurt.tracker.data.TrackerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    fun clearToday() {
        viewModelScope.launch(Dispatchers.IO) {
            val since = startOfToday()
            dao.deleteAppRecordsSince(since)
            dao.deleteLocationRecordsSince(since)
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
