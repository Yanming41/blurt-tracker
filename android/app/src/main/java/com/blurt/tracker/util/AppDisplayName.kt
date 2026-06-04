package com.blurt.tracker.util

import android.content.Context
import android.content.pm.PackageManager

/**
 * 显示用 App 名查找。
 *
 * 优先级：
 *  1. 内置字典（常用国产/国际 App 的中文名）
 *  2. PackageManager.getApplicationLabel
 *  3. 包名末尾段（"com.tencent.mm" -> "mm"）
 *  4. 原包名兜底
 */
object AppDisplayName {

    private val DICT: Map<String, String> = mapOf(
        // ----- 社交 -----
        "com.tencent.mm" to "微信",
        "com.tencent.mobileqq" to "QQ",
        "com.tencent.mobileqqi" to "QQ 国际",
        "com.sina.weibo" to "微博",
        "org.telegram.messenger" to "Telegram",
        "com.whatsapp" to "WhatsApp",
        "com.discord" to "Discord",
        "com.instagram.android" to "Instagram",
        "com.facebook.katana" to "Facebook",
        "com.snapchat.android" to "Snapchat",
        // ----- 娱乐 -----
        "tv.danmaku.bili" to "哔哩哔哩",
        "tv.danmaku.bilibilihd" to "哔哩哔哩 HD",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.kuaishou.nebula" to "快手",
        "com.smile.gifmaker" to "快手",
        "com.google.android.youtube" to "YouTube",
        "com.netflix.mediaclient" to "Netflix",
        "com.spotify.music" to "Spotify",
        "com.tencent.qqmusic" to "QQ 音乐",
        "com.netease.cloudmusic" to "网易云音乐",
        "com.tencent.qqlive" to "腾讯视频",
        "com.qiyi.video" to "爱奇艺",
        "com.youku.phone" to "优酷",
        "com.hunantv.imgo.activity" to "芒果 TV",
        // ----- 工作 -----
        "com.android.studio" to "Android Studio",
        "com.android.chrome" to "Chrome",
        "com.microsoft.emmx" to "Edge",
        "com.microsoft.office.outlook" to "Outlook",
        "com.microsoft.teams" to "Teams",
        "com.microsoft.office.word" to "Word",
        "com.microsoft.office.excel" to "Excel",
        "com.microsoft.office.powerpoint" to "PowerPoint",
        "com.slack" to "Slack",
        "com.google.android.gm" to "Gmail",
        "com.google.android.apps.docs" to "Drive",
        "com.google.android.apps.docs.editors.docs" to "Google Docs",
        "com.google.android.apps.docs.editors.sheets" to "Google Sheets",
        "notion.id" to "Notion",
        "md.obsidian" to "Obsidian",
        "com.todoist" to "Todoist",
        // ----- 学习/阅读 -----
        "com.amazon.kindle" to "Kindle",
        "com.duolingo" to "多邻国",
        "com.coursera.android" to "Coursera",
        "com.google.android.apps.translate" to "Google 翻译",
        // ----- 通勤 -----
        "com.baidu.BaiduMap" to "百度地图",
        "com.autonavi.minimap" to "高德地图",
        "com.google.android.apps.maps" to "Google 地图",
        "com.uber.android" to "Uber",
        "com.lyft.android" to "Lyft",
        "com.sdu.didi.psnger" to "滴滴",
        "com.MobileTicket" to "12306",
        // ----- 事务/支付 -----
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.unionpay" to "云闪付",
        "com.icbc" to "工商银行",
        "com.android.bankabc" to "农业银行",
        "com.cmbchina.ccd.pluto.cmbActivity" to "招商银行",
        // ----- 三星全家桶常见 -----
        "com.samsung.android.calendar" to "三星日历",
        "com.samsung.android.app.notes" to "三星笔记",
        "com.samsung.android.messaging" to "三星信息",
        "com.sec.android.gallery3d" to "相册",
        "com.sec.android.app.myfiles" to "我的文件",
        // ----- 浏览器/通用 -----
        "com.UCMobile" to "UC 浏览器",
        "com.tencent.mtt" to "QQ 浏览器",
        "sogou.mobile.explorer" to "搜狗浏览器",
        "org.mozilla.firefox" to "Firefox",
    )

    fun resolve(context: Context, pkg: String): String {
        if (pkg.isBlank()) return "未知"
        // 1. 字典
        DICT[pkg]?.let { return it }
        // 2. PackageManager
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(info).toString().trim()
            // 系统有时会返回包名本身 —— 那就走兜底
            if (label.isNotBlank() && label != pkg && !label.matches(Regex("[a-z]+(\\.[a-z0-9_]+)+"))) {
                return label
            }
        } catch (_: PackageManager.NameNotFoundException) {
            // 继续兜底
        }
        // 3. 包名末段
        val tail = pkg.substringAfterLast('.', "").ifBlank { pkg }
        return tail.replaceFirstChar { it.uppercase() }
    }
}
