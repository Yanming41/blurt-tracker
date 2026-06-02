package com.blurt.tracker.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blurt.tracker.data.LocationRecord
import com.blurt.tracker.data.TrackerDatabase
import com.blurt.tracker.util.GeocoderHelper
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.concurrent.TimeUnit

/**
 * 周期 15 分钟（WorkManager 最小值）取一次位置。
 * 之前的 5 分钟靠前台服务，已经不复存在。
 *
 * 智能切换：先 BALANCED，跟上一条比距离 > 50m 再升级到 HIGH_ACCURACY。
 */
class LocationWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!hasLocationPermission(ctx)) return Result.success()

        val dao = TrackerDatabase.get(ctx).trackerDao()
        val coarse = fetchLocation(ctx, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            ?: return Result.success()

        val previous = dao.getLatestLocationRecord()
        val moved = previous == null ||
            distanceMeters(previous.latitude, previous.longitude, coarse.latitude, coarse.longitude) > MOVE_THRESHOLD_M
        val final = if (moved) fetchLocation(ctx, Priority.PRIORITY_HIGH_ACCURACY) ?: coarse else coarse
        val address = GeocoderHelper.reverseGeocode(ctx, final.latitude, final.longitude)

        dao.insertLocationRecord(
            LocationRecord(
                latitude = final.latitude,
                longitude = final.longitude,
                address = address,
                timestamp = System.currentTimeMillis(),
            ),
        )
        return Result.success()
    }

    private fun hasLocationPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private suspend fun fetchLocation(ctx: Context, priority: Int): Location? {
        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        val cts = CancellationTokenSource()
        return try {
            @Suppress("MissingPermission")
            suspendCancellableCoroutine<Location?> { cont ->
                fused.getCurrentLocation(priority, cts.token)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
                cont.invokeOnCancellation { cts.cancel() }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0]
    }

    companion object {
        private const val WORK_NAME = "blurt_location"
        private const val MOVE_THRESHOLD_M = 50f

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<LocationWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req,
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }
    }
}
