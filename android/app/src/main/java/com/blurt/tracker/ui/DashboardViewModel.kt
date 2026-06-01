package com.blurt.tracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blurt.tracker.data.MoodEntry
import com.blurt.tracker.data.TrackerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = TrackerDatabase.get(app).trackerDao()

    val todayMoods: StateFlow<List<MoodEntry>> = dao
        .observeMoodEntriesSince(startOfToday())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addMood(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertMoodEntry(
                MoodEntry(content = content, timestamp = System.currentTimeMillis()),
            )
        }
    }

    fun clearToday() {
        viewModelScope.launch(Dispatchers.IO) {
            val since = startOfToday()
            dao.deleteAppRecordsSince(since)
            dao.deleteLocationRecordsSince(since)
            dao.deleteScreenEventsSince(since)
            dao.deleteMoodEntriesSince(since)
        }
    }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }
}
