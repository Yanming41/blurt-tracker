package com.blurt.tracker.service

import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * 极小的嵌入式 HTTP 服务器，仅用于让电脑端探测手机在线状态。
 *
 * 端点：
 *   GET /ping    → { "status": "online", "device": "s24-ultra", "timestamp": ..., "version": "0.1.0" }
 *
 * 监听 0.0.0.0:8000，通过 Tailscale 私网即可访问。
 * 注意：手机进入 Doze / 后台被杀时端口会断 — 这是 Android 设计限制。
 */
class PingServer(port: Int = 8000) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        return when {
            uri == "/ping" -> jsonResponse(
                Response.Status.OK,
                JSONObject().apply {
                    put("status", "online")
                    put("device", Build.MODEL ?: "android")
                    put("timestamp", System.currentTimeMillis() / 1000)
                    put("version", "0.1.0")
                }.toString(),
            )
            uri == "/health" -> jsonResponse(
                Response.Status.OK,
                """{"status":"ok"}""",
            )
            else -> jsonResponse(
                Response.Status.NOT_FOUND,
                """{"detail":"not found"}""",
            )
        }
    }

    private fun jsonResponse(status: Response.IStatus, body: String): Response {
        val r = newFixedLengthResponse(status, "application/json; charset=utf-8", body)
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    fun safeStart() {
        try {
            start(SOCKET_READ_TIMEOUT, /*daemon=*/true)
            Log.i(TAG, "PingServer listening on 0.0.0.0:${listeningPort}")
        } catch (e: Exception) {
            Log.e(TAG, "PingServer start failed", e)
        }
    }

    fun safeStop() {
        try {
            stop()
            Log.i(TAG, "PingServer stopped")
        } catch (e: Exception) {
            Log.e(TAG, "PingServer stop failed", e)
        }
    }

    companion object {
        private const val TAG = "PingServer"
    }
}
