package com.blurt.tracker.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blurt.tracker.data.LocationRecord
import com.blurt.tracker.data.TrackerDatabase
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.Locale
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
        val address = reverseGeocode(ctx, final.latitude, final.longitude)

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

    private fun reverseGeocode(ctx: Context, lat: Double, lon: Double): String {
        return try {
            val geocoder = Geocoder(ctx, Locale.getDefault())
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(lat, lon, 1)
            val a = list?.firstOrNull() ?: return "未知位置"

            // 优先用系统拼好的完整地址行（中文一般是「省+市+区+街道+门牌」）
            val line0 = a.getAddressLine(0)?.trim()
            if (!line0.isNullOrEmpty()) {
                // 去掉冗余的国家名前缀，让显示更短
                return line0
                    .removePrefix("中国")
                    .removePrefix("中華人民共和國")
                    .trim()
                    .ifEmpty { "未知位置" }
            }

            // Fallback：手动拼街道 + 街区 + 城市
            val parts = listOfNotNull(
                a.thoroughfare,         // 街道
                a.subLocality,          // 街区
                a.locality,             // 城市
            ).distinct()
            if (parts.isNotEmpty()) return parts.joinToString("·")

            // 最后兜底
            a.featureName
                ?: a.adminArea
                ?: "未知位置"
        } catch (e: Exception) {
            "位置解析失败"
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
