package com.blurt.tracker.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.blurt.tracker.MainActivity
import com.blurt.tracker.data.ActivityBlock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 当 LLM 不确定（confidence < 0.7 + askUser 非空）时，弹系统通知
 * 把这些块都列在一条里，点击打开 App 让用户修正。
 */
object AskUserNotifier {
    private const val CHANNEL_ID = "blurt_ask_user"
    private const val NOTIF_ID = 3001

    fun show(ctx: Context, pendingBlocks: List<ActivityBlock>) {
        if (pendingBlocks.isEmpty()) {
            cancel(ctx)
            return
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "AI 标签待确认",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "LLM 拿不准的活动块" }
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        val text = pendingBlocks.take(4).joinToString("\n") { blk ->
            val range = "${timeFmt.format(Date(blk.startTime))}–${timeFmt.format(Date(blk.endTime))}"
            val q = blk.askUser?.takeIf { it.isNotBlank() }
                ?: "这段时间你在做什么？"
            "• $range  $q"
        } + if (pendingBlocks.size > 4) "\n…还有 ${pendingBlocks.size - 4} 段" else ""

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("🤔 我有 ${pendingBlocks.size} 个时间段拿不准")
            .setContentText("点击展开，给我个准信吧")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    fun cancel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
    }
}
