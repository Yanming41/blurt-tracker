package com.blurt.tracker

import android.app.Application
import com.blurt.tracker.service.ScreenReceiver

class BlurtApp : Application() {

    private val screenReceiver = ScreenReceiver()

    override fun onCreate() {
        super.onCreate()
        // 进程活着期间监听屏幕事件
        registerReceiver(screenReceiver, ScreenReceiver.newFilter())
    }
}
