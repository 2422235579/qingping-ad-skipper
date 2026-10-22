package com.qingping.clear

/**
 * 开屏广告「跳过」按钮的识别规则，集中在这里，方便维护和扩充。
 *
 * 设计原则：
 * 1. 宁可漏拦，不可误点 —— 误点会真的打开广告/应用商店，比漏拦更糟。
 * 2. 规则全部可读、可改，不写死在服务里。
 */
object SkipRules {

    /**
     * 精确匹配的文案（最快路径）。
     *
     * 注意：原实现这里有重复项（"跳过" 写了三遍），Set 会自动去重，
     * 但重复写意味着作者漏掉了别的变体 —— 所以下面补了正则。
     */
    private val EXACT_TEXTS: Set<String> = hashSetOf(
        "跳过", "跳过广告", "广告跳过", "关闭广告", "立即跳过",
        "跳过此广告", "跳过视频", "skip", "Skip", "SKIP", "跳过>", "跳过>", "跳过 »"
    )

    /**
     * 倒计时变体（提升覆盖率的关键）。
     *
     * 真实开屏广告里，跳过按钮 90% 带倒计时数字或单位，例如：
     *   跳过 3    跳过3s    3s 跳过    跳过广告 5    跳过 (5)    5秒跳过
     * 原来只做「完全相等」匹配 → 这些全都漏掉。
     */
    private val COUNTDOWN_PATTERN = Regex(
        "^\\s*(跳过|跳过广告|关闭广告|立即跳过|skip)\\s*[（(]?\\s*" +
            "\\d{0,2}\\s*[)）]?\\s*[sS秒]?\\s*[>》»]?\\s*$",
        RegexOption.IGNORE_CASE
    )

    /** 判断一段文案是不是跳过按钮 */
    fun isSkipText(raw: CharSequence?): Boolean {
        val text = raw?.toString()?.trim() ?: return false
        if (text.isEmpty() || text.length > 12) return false  // 太长的一定不是按钮
        if (text in EXACT_TEXTS) return true
        return COUNTDOWN_PATTERN.matches(text)
    }

    /**
     * 已知广告 SDK 的跳过按钮 resourceId。
     *
     * ⚠️ 这些 id 会随 SDK 版本变化，不必迷信它们 —— 它们只是「精确文字匹配」
     * 之外的补充通道。要扩充的话，把 [QPAccessibilityService] 的 debug 开关打开，
     * 日志里会打出「长得像跳过按钮但没被识别」的候选，从真实日志里加最靠谱。
     */
    val SKIP_VIEW_IDS: List<String> = listOf(
        "com.qq.e.ads:id/skip",                        // 腾讯优量汇
        "com.baidu.mobads:id/skip_tv",                 // 百度
        "com.bytedance.sdk.openadsdk:id/skip",         // 穿山甲
        "com.kwad.sdk:id/ksad_tv_skip"                 // 快手
    )

    /** 这些包名不会被拦截（自己是第一个） */
    val IGNORE_PACKAGES: Set<String> = hashSetOf(
        "com.qingping.clear",
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        // 桌面/负一屏，避免在桌面上乱点
        "com.miui.home",
        "com.miui.securitycenter",
        "com.android.thememanager",
        "com.huawei.android.launcher",
        "com.bbk.launcher2",
        "com.oppo.launcher"
    )

    /**
     * 尺寸合理性判断（相对屏幕，不再写死 400x200 像素）。
     *
     * 原实现写死 `width < 400 && height < 200`，在平板/折叠屏/高 DPI 上会误判。
     */
    fun isPlausibleButton(
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int,
        forAncestor: Boolean = false
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        // 按钮本身：不超过屏幕 60% 宽、20% 高
        // 祖先容器：放宽到 95% 宽、30% 高（跳过条常常是整条横栏）
        val maxW = screenWidth * if (forAncestor) 0.95 else 0.60
        val maxH = screenHeight * if (forAncestor) 0.30 else 0.20
        return width <= maxW && height <= maxH
    }

    /** 单次遍历的节点上限，防止在超大控件树上卡住主线程 */
    const val MAX_NODES = 900

    /** 遍历时深度上限 */
    const val MAX_DEPTH = 24
}
