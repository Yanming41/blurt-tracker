package com.blurt.tracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_records")
data class LocationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val timestamp: Long,
)
