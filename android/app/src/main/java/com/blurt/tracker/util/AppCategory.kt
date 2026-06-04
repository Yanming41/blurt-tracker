package com.blurt.tracker.util

/**
 * App 类别字典 + 推断函数。
 *
 * 7 类（v1）：
 *   work / learn / social / entertainment / commute / admin / other
 *
 * 优先级：
 *   1. 包名精确匹配
 *   2. 包名/App 名关键字匹配
 *   3. fallback = other（后续 LLM 兜底）
 */
object AppCategory {

    const val WORK = "work"
    const val LEARN = "learn"
    const val SOCIAL = "social"
    const val ENTERTAINMENT = "entertainment"
    const val COMMUTE = "commute"
    const val ADMIN = "admin"
    const val OTHER = "other"

    val ALL = listOf(WORK, LEARN, SOCIAL, ENTERTAINMENT, COMMUTE, ADMIN, OTHER)

    private val EXACT: Map<String, String> = mapOf(
        // ── work ──
        "com.android.studio" to WORK,
        "com.google.android.apps.docs.editors.docs" to WORK,
        "com.google.android.apps.docs.editors.sheets" to WORK,
        "com.google.android.apps.docs.editors.slides" to WORK,
        "com.microsoft.office.outlook" to WORK,
        "com.microsoft.office.word" to WORK,
        "com.microsoft.office.excel" to WORK,
        "com.microsoft.office.powerpoint" to WORK,
        "com.microsoft.teams" to WORK,
        "com.slack" to WORK,
        "com.google.android.gm" to WORK, // Gmail
        "notion.id" to WORK,
        "com.notion.id" to WORK,
        "md.obsidian" to WORK,
        "com.todoist" to WORK,
        // ── social ──
        "com.tencent.mm" to SOCIAL,           // 微信
        "com.tencent.mobileqq" to SOCIAL,     // QQ
        "com.whatsapp" to SOCIAL,
        "com.discord" to SOCIAL,
        "org.telegram.messenger" to SOCIAL,
        "com.sina.weibo" to SOCIAL,
        "com.instagram.android" to SOCIAL,
        "com.facebook.katana" to SOCIAL,
        "com.zhiliaoapp.musically" to SOCIAL, // TikTok 国际版有时归社交也行
        // ── entertainment ──
        "com.ss.android.ugc.aweme" to ENTERTAINMENT,         // 抖音
        "tv.danmaku.bili" to ENTERTAINMENT,                  // B 站
        "com.netflix.mediaclient" to ENTERTAINMENT,
        "com.google.android.youtube" to ENTERTAINMENT,
        "com.spotify.music" to ENTERTAINMENT,
        "com.tencent.qqmusic" to ENTERTAINMENT,
        "com.netease.cloudmusic" to ENTERTAINMENT,
        "com.kuaishou.nebula" to ENTERTAINMENT,              // 快手
        // ── commute ──
        "com.baidu.BaiduMap" to COMMUTE,
        "com.autonavi.minimap" to COMMUTE,                   // 高德
        "com.google.android.apps.maps" to COMMUTE,
        "com.uber.android" to COMMUTE,
        "com.lyft.android" to COMMUTE,
        "com.sdu.didi.psnger" to COMMUTE,                    // 滴滴
        "com.MobileTicket" to COMMUTE,                       // 12306
        // ── admin ──
        "com.eg.android.AlipayGphone" to ADMIN,              // 支付宝
        "com.unionpay" to ADMIN,
        "com.icbc" to ADMIN,                                 // 工商银行
        "com.android.bankabc" to ADMIN,                      // 农行
        "com.cmbchina.ccd.pluto.cmbActivity" to ADMIN,       // 招行
        // ── learn ──
        "com.amazon.kindle" to LEARN,
        "com.google.android.apps.translate" to LEARN,
        "com.duolingo" to LEARN,
        "com.coursera.android" to LEARN,
    )

    fun categorize(pkg: String, appName: String = ""): String {
        EXACT[pkg]?.let { return it }

        val k = "$pkg ${appName.lowercase()}".lowercase()
        fun has(vararg ks: String) = ks.any { k.contains(it) }

        return when {
            // Work
            has("studio", "androidstudio", "intellij", "vscode", "vs.code", "android.studio", "idea") -> WORK
            has("office", "docs", "sheets", "outlook", "teams", "slack") -> WORK
            has("notion", "obsidian", "todoist", "jira", "trello") -> WORK
            has("github", "gitlab", "termux") -> WORK
            // Social
            has("wechat", "微信", "qq.", "tencent.mm", "weibo", "messenger", "telegram", "whatsapp") -> SOCIAL
            // Entertainment
            has("douyin", "抖音", "tiktok", "bilibili", "b站", "netflix", "youtube",
                "music", "spotify", "qqmusic", "kuaishou", "快手", "game", "游戏", "iqiyi", "腾讯视频") -> ENTERTAINMENT
            // Learn
            has("kindle", "duolingo", "coursera", "udemy", "课堂", "学习", "translate", "阅读", "read") -> LEARN
            // Commute
            has("map", "地图", "uber", "lyft", "didi", "滴滴", "高德", "百度地图", "12306", "ticket") -> COMMUTE
            // Admin
            has("bank", "银行", "pay", "支付", "alipay", "unionpay", "ICBC", "card") -> ADMIN
            else -> OTHER
        }
    }
}
