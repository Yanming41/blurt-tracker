package com.blurt.tracker.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blurt.tracker.MainActivity
import com.blurt.tracker.util.Heartbeat
import java.util.concurrent.TimeUnit

/**
 * 后台守护：每 15 分钟（系统允许的最小周期）跑一次。
 * - 心跳超过 5 分钟没更新 -> 发"被杀"通知 + 尝试拉起服务
 * - 心跳正常 -> 什么都不做
 */
class WatchdogWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Heartbeat.isExpected(ctx)) return Result.success()

        val ageMs = Heartbeat.ageMs(ctx)
        if (Heartbeat.isStale(ctx)) {
            notifyServiceDead(ctx, ageMs)
            // 尝试拉起一次
            runCatching { TrackerService.start(ctx) }
        }
        return Result.success()
    }

    private fun notifyServiceDead(ctx: Context, ageMs: Long) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "服务监控",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "记录服务被系统杀死时通知" }
            nm.createNotificationChannel(ch)
        }

        val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val minutes = (ageMs / 60_000L).coerceAtLeast(1)
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("⚠️ 烂摊子服务停了")
            .setContentText("已 ${minutes} 分钟没采样，点击恢复")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    companion object {
        private const val CHANNEL_ID = "tracker_watchdog"
        private const val NOTIF_ID = 2001
        private const val WORK_NAME = "tracker_watchdog"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }
    }
}
