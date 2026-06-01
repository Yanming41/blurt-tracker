package com.blurt.tracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blurt.tracker.data.TrackerDatabase
import com.blurt.tracker.network.UploadClient
import com.blurt.tracker.util.Config
import com.blurt.tracker.util.UsageStatsHelper
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 每天 23:00 把当日数据打包上传给电脑端：
 * - 今日 App 使用段（来自系统 UsageStats）
 * - 今日位置记录（Room）
 * - 今日屏幕事件（Room）
 * - 今日情绪记录（Room）
 *
 * 后端端点：POST /mobile/daily-upload
 */
class UploadWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val baseUrl = Config.baseUrl(ctx) ?: return Result.success() // 还没配电脑 IP，跳过

        val dao = TrackerDatabase.get(ctx).trackerDao()
        val dayStart = startOfToday()
        val dayEnd = dayStart + DAY_MS

        val appUsages = if (UsageStatsHelper.hasUsageStatsPermission(ctx)) {
            UsageStatsHelper.getTodayAppUsage(ctx)
        } else emptyList()
        val locs = dao.getScreenEventsBetween(dayStart, dayEnd) // placeholder if needed
        // 直接走 UploadClient 上传屏幕事件保持原有行为
        val pendingScreen = dao.getScreenEventsAfter(Config.getLastUploadedScreenTs(ctx))
        if (pendingScreen.isNotEmpty()) {
            val watermark = UploadClient.uploadScreenEvents(baseUrl, pendingScreen)
            if (watermark != null) Config.setLastUploadedScreenTs(ctx, watermark)
            else return Result.retry()
        }

        // 打包 daily-upload
        val ok = UploadClient.uploadDailyDigest(
            baseUrl = baseUrl,
            dayStart = dayStart,
            appUsages = appUsages,
        )
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "blurt_daily_upload"
        private const val DAY_MS = 24 * 3600_000L

        /** 注册每日 23:00 周期任务（首次延迟到下一个 23:00） */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UploadWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayToNext23(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req,
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }

        private fun delayToNext23(): Long {
            val now = Calendar.getInstance()
            val target = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }

        private fun startOfToday(): Long {
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return c.timeInMillis
        }
    }
}
