package com.blurt.tracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * 监听屏幕亮/灭/解锁事件。
 *
 * 注意：SCREEN_ON / SCREEN_OFF 必须**动态注册**，不能写在 Manifest 里（系统限制）。
 * USER_PRESENT 也一起放在动态注册里保持一致。
 */
class ScreenReceiver(
    private val onEvent: (type: String, timestamp: Long) -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val type = when (intent.action) {
            Intent.ACTION_SCREEN_ON -> "亮屏"
            Intent.ACTION_SCREEN_OFF -> "息屏"
            Intent.ACTION_USER_PRESENT -> "解锁"
            else -> return
        }
        onEvent(type, System.currentTimeMillis())
    }

    companion object {
        fun newFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
    }
}
