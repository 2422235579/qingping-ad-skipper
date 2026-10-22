package com.qingping.clear

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量设置存储。
 *
 * 目前管三件事：
 * 1. **自定义跳过文案** —— 用户遇到的、内置规则没覆盖的按钮文案。
 * 2. **诊断模式** —— 打开后会把「长得像跳过按钮但没被识别」的候选记下来，
 *    方便扩充规则（这是 [SkipRules] 里说的"从真实日志里加最靠谱"的落地）。
 * 3. **坐标兜底开关** —— 默认关闭。见 [QPAccessibilityService] 里的说明。
 *
 * 用 SharedPreferences 而不是 DataStore：这里只有几个短小的值，
 * 引入 DataStore 的协程开销不值得。
 */
class QPPreferences private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("qingping_prefs", Context.MODE_PRIVATE)

    // ---------- 坐标兜底 ----------

    /** 默认关闭：这条规则会"盲点"，只有确认存在广告 SDK 时才启用一次 */
    var coordinateFallbackEnabled: Boolean
        get() = sp.getBoolean(KEY_COORD_FALLBACK, false)
        set(value) = sp.edit().putBoolean(KEY_COORD_FALLBACK, value).apply()

    // ---------- 诊断 ----------

    var diagnoseEnabled: Boolean
        get() = sp.getBoolean(KEY_DIAGNOSE, false)
        set(value) = sp.edit().putBoolean(KEY_DIAGNOSE, value).apply()

    /** 诊断收集到的候选（去重、限量，越新越靠前） */
    fun candidates(): List<String> {
        val raw = sp.getString(KEY_CANDIDATES, null) ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    /** 记一个候选；返回 true 表示是新的（用于避免日志刷屏） */
    fun rememberCandidate(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 24) return false
        val list = candidates().toMutableList()
        if (trimmed in list) return false
        list.add(0, trimmed)
        while (list.size > MAX_CANDIDATES) list.removeAt(list.size - 1)
        sp.edit().putString(KEY_CANDIDATES, list.joinToString("\n")).apply()
        return true
    }

    fun clearCandidates() = sp.edit().remove(KEY_CANDIDATES).apply()

    // ---------- 自定义文案 ----------

    /** 用户自己加的跳过文案（精确匹配） */
    var customSkipTexts: Set<String>
        get() = sp.getStringSet(KEY_CUSTOM_TEXTS, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_CUSTOM_TEXTS, value).apply()

    fun addCustomText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        customSkipTexts = customSkipTexts + t
    }

    // ---------- 无障碍开关状态（用于被关掉时提醒） ----------

    /** 上一次确认「无障碍是开着的」——启动时用来判断是不是被系统/用户关掉了 */
    var accessibilityWasOn: Boolean
        get() = sp.getBoolean(KEY_ACC_WAS_ON, false)
        set(value) = sp.edit().putBoolean(KEY_ACC_WAS_ON, value).apply()

    /** 已经提醒过就别每 5 分钟弹一次 */
    var accessibilityOffNotified: Boolean
        get() = sp.getBoolean(KEY_ACC_OFF_NOTIFIED, false)
        set(value) = sp.edit().putBoolean(KEY_ACC_OFF_NOTIFIED, value).apply()

    companion object {
        private const val KEY_COORD_FALLBACK = "coordinate_fallback"
        private const val KEY_DIAGNOSE = "diagnose"
        private const val KEY_CANDIDATES = "diagnose_candidates"
        private const val KEY_CUSTOM_TEXTS = "custom_skip_texts"
        private const val KEY_ACC_WAS_ON = "accessibility_was_on"
        private const val KEY_ACC_OFF_NOTIFIED = "accessibility_off_notified"

        private const val MAX_CANDIDATES = 50 // 只留 50 条，别把 prefs 撑爆

        @Volatile
        private var instance: QPPreferences? = null

        fun get(context: Context): QPPreferences =
            instance ?: synchronized(this) {
                instance ?: QPPreferences(context.applicationContext).also { instance = it }
            }
    }
}
