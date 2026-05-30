package com.blurt.tracker.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.blurt.tracker.MainActivity

/**
 * 当后台服务被杀（onTaskRemoved / onDestroy / Watchdog 检测到心跳超时）时发通知。
 * 单独一个高优先级通道，会弹横幅。
 */
object ServiceAliveNotifier {
    private const val CHANNEL_ID = "tracker_alive_alert"
    private const val NOTIF_ID = 2002

    fun notifyKilled(ctx: Context, reason: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "服务存活告警",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "记录服务被停止时弹通知" }
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("⚠️ 烂摊子停了")
            .setContentText(reason + "，点击恢复")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIF_ID, notif)
    }
}
