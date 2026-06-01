package com.blurt.tracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.blurt.tracker.data.ScreenEvent
import com.blurt.tracker.data.TrackerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 监听屏幕亮/灭/解锁并写入 Room。
 *
 * 注意：必须**动态注册**（SCREEN_ON/OFF 无法在 Manifest 中接收）。
 * 由 BlurtApp.onCreate() 注册，App 进程活着时一直有效；
 * 进程被系统回收后失效，下次进程被任何原因（Worker、用户打开 App）拉起时再注册。
 */
class ScreenReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val type = when (intent.action) {
            Intent.ACTION_SCREEN_ON -> "亮屏"
            Intent.ACTION_SCREEN_OFF -> "息屏"
            Intent.ACTION_USER_PRESENT -> "解锁"
            else -> return
        }
        val ts = System.currentTimeMillis()
        val appCtx = context.applicationContext
        scope.launch {
            runCatching {
                TrackerDatabase.get(appCtx).trackerDao()
                    .insertScreenEvent(ScreenEvent(eventType = type, timestamp = ts))
            }
        }
    }

    companion object {
        fun newFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
    }
}
