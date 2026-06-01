package com.blurt.tracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackerDao {

    // ---------- App ----------
    @Insert
    suspend fun insertAppRecord(record: AppRecord): Long

    @Update
    suspend fun updateAppRecord(record: AppRecord)

    /** 最近一条记录，用于判断是否需要合并 */
    @Query("SELECT * FROM app_records ORDER BY endTime DESC LIMIT 1")
    suspend fun getLatestAppRecord(): AppRecord?

    @Query("SELECT * FROM app_records WHERE startTime >= :since ORDER BY startTime ASC")
    fun observeAppRecordsSince(since: Long): Flow<List<AppRecord>>

    @Query("DELETE FROM app_records WHERE startTime >= :since")
    suspend fun deleteAppRecordsSince(since: Long)

    // ---------- Location ----------
    @Insert
    suspend fun insertLocationRecord(record: LocationRecord): Long

    @Query("SELECT * FROM location_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestLocationRecord(): LocationRecord?

    @Query("SELECT * FROM location_records WHERE timestamp >= :since ORDER BY timestamp ASC")
    fun observeLocationRecordsSince(since: Long): Flow<List<LocationRecord>>

    @Query("DELETE FROM location_records WHERE timestamp >= :since")
    suspend fun deleteLocationRecordsSince(since: Long)

    // ---------- Screen events ----------
    @Insert
    suspend fun insertScreenEvent(event: ScreenEvent): Long

    @Query("SELECT * FROM screen_events WHERE timestamp >= :since ORDER BY timestamp ASC")
    fun observeScreenEventsSince(since: Long): Flow<List<ScreenEvent>>

    @Query("SELECT * FROM screen_events WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp ASC")
    suspend fun getScreenEventsBetween(start: Long, end: Long): List<ScreenEvent>

    @Query("SELECT * FROM screen_events WHERE timestamp > :sinceTs ORDER BY timestamp ASC")
    suspend fun getScreenEventsAfter(sinceTs: Long): List<ScreenEvent>

    @Query("DELETE FROM screen_events WHERE timestamp >= :since")
    suspend fun deleteScreenEventsSince(since: Long)

    // ---------- Cross-cut queries for Timeline ----------
    @Query("SELECT * FROM app_records WHERE startTime BETWEEN :start AND :end ORDER BY startTime ASC")
    fun observeAppRecordsBetween(start: Long, end: Long): Flow<List<AppRecord>>

    @Query("SELECT * FROM location_records WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp ASC")
    fun observeLocationRecordsBetween(start: Long, end: Long): Flow<List<LocationRecord>>

    @Query("SELECT * FROM screen_events WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp ASC")
    fun observeScreenEventsBetween(start: Long, end: Long): Flow<List<ScreenEvent>>
}
