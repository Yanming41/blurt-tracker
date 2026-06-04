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

    @Query("SELECT * FROM location_records ORDER BY timestamp ASC")
    suspend fun getAllLocationRecords(): List<LocationRecord>

    @Query("UPDATE location_records SET address = :address WHERE id = :id")
    suspend fun updateLocationAddress(id: Long, address: String)

    @Query("SELECT * FROM location_records WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp ASC")
    suspend fun getLocationsBetween(start: Long, end: Long): List<LocationRecord>

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

    // ---------- Mood ----------
    @Insert
    suspend fun insertMoodEntry(entry: MoodEntry): Long

    @Query("SELECT * FROM mood_entries WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp ASC")
    fun observeMoodEntriesBetween(start: Long, end: Long): Flow<List<MoodEntry>>

    @Query("SELECT * FROM mood_entries WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun observeMoodEntriesSince(since: Long): Flow<List<MoodEntry>>

    @Query("DELETE FROM mood_entries WHERE timestamp >= :since")
    suspend fun deleteMoodEntriesSince(since: Long)

    // ---------- ActivityBlock ----------
    @Insert
    suspend fun insertActivityBlock(block: ActivityBlock): Long

    @Insert
    suspend fun insertActivityBlocks(blocks: List<ActivityBlock>): List<Long>

    @Query("SELECT * FROM activity_blocks WHERE startTime BETWEEN :start AND :end ORDER BY startTime ASC")
    fun observeBlocksBetween(start: Long, end: Long): Flow<List<ActivityBlock>>

    @Query("SELECT * FROM activity_blocks WHERE startTime BETWEEN :start AND :end ORDER BY startTime ASC")
    suspend fun getBlocksBetween(start: Long, end: Long): List<ActivityBlock>

    /** 只删未被用户修正过的块（保留用户改过的标签） */
    @Query("DELETE FROM activity_blocks WHERE startTime BETWEEN :start AND :end AND manuallyCorrected = 0")
    suspend fun deleteAutoBlocksBetween(start: Long, end: Long)

    @Query("DELETE FROM activity_blocks WHERE startTime >= :since")
    suspend fun deleteAllBlocksSince(since: Long)

    @Query("""
        UPDATE activity_blocks SET
            activityLabel = :label,
            confidence = :conf,
            reasoning = :reasoning,
            askUser = :askUser,
            category = :category
        WHERE id = :id AND manuallyCorrected = 0
    """)
    suspend fun updateBlockLabel(
        id: Long,
        label: String?,
        category: String,
        conf: Float?,
        reasoning: String?,
        askUser: String?,
    )

    @Query("SELECT * FROM activity_blocks WHERE startTime = :startTime LIMIT 1")
    suspend fun getBlockByStart(startTime: Long): ActivityBlock?

    // ---------- Glance ----------
    @Insert
    suspend fun insertGlance(g: Glance): Long

    @Insert
    suspend fun insertGlances(gs: List<Glance>): List<Long>

    @Query("SELECT * FROM glances WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp ASC")
    fun observeGlancesBetween(start: Long, end: Long): Flow<List<Glance>>

    @Query("DELETE FROM glances WHERE timestamp BETWEEN :start AND :end")
    suspend fun deleteGlancesBetween(start: Long, end: Long)
}
