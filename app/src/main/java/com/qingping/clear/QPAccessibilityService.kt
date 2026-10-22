package com.qingping.clear

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.qingping.clear.data.AppDatabase
import com.qingping.clear.data.InterceptionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 核心：开屏广告跳过。
 *
 * 相对原版的改动（按重要性）：
 *
 * 1. **冷却判断改到「尝试」这一层**。
 *    原版把 `lastBlockMap[pkg]` 写在「成功拦截」分支里，所以**没找到按钮时冷却永远不生效**
 *    —— 每一次 WINDOW_CONTENT_CHANGED 都会排一个 300ms 后的全树遍历。
 *    列表滚动时每秒几十次事件 → 耗电、掉帧。现在改成记录「上次尝试时间」。
 *
 * 2. **点击祖先前先看它多大**。
 *    原版对 `parent` / `grandParent` 只判断 `isClickable` 就点。如果父容器是铺满屏幕的
 *    广告 View，这一下**点开的正是广告**，把「跳过」变成了「点击广告」。
 *
 * 3. **文案匹配支持倒计时**（见 [SkipRules.isSkipText]）。
 *
 * 4. **三条通道合并成一次遍历**：文案匹配 + 广告 SDK 探测 + 诊断候选收集，
 *    不再为了不同目的反复爬同一棵树。
 *
 * 5. **坐标兜底（默认关闭）**：只在「确认这一屏存在广告 SDK 渲染」时才盲点右上角一次。
 *    没有这个前提就盲点，等于拿用户的手指乱戳，比漏拦更糟。
 *
 * 6. **单飞（single-flight）**：同一包名同时只保留一条重试链。
 *
 * 7. **只在自己包名是当前活动窗口时才动手**，避免在过期的控件树上瞎点。
 *
 * 8. 尺寸判断改成相对屏幕比例；复用 `Rect`；限制遍历节点数。
 * 9. 协程作用域挂在服务上，`onDestroy` 统一取消（原来每次 new 一个，泄漏）。
 */
class QPAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "QPService"

        /** 首扫延迟：广告弹出时往往已经渲染好，先快扫一次 */
        private const val FIRST_SCAN_DELAY_MS = 120L

        /** 重试间隔：覆盖广告 SDK 延迟渲染 */
        private const val RETRY_DELAY_MS = 200L

        /** 最多重试次数（原版 2 次太短，很多广告 1 秒后才渲染出按钮） */
        private const val MAX_RETRY = 5

        /** 同一包名：窗口切换后的冷却 */
        private const val COOLDOWN_STATE_MS = 1200L

        /** 同一包名：内容变化后的冷却（这种事件最频繁） */
        private const val COOLDOWN_CONTENT_MS = 900L

        /** 坐标兜底点击的位置（屏幕比例）：右上角，跳过按钮的常见位置 */
        private const val FALLBACK_X_RATIO = 0.90f
        private const val FALLBACK_Y_RATIO = 0.08f

        @Volatile
        var instance: QPAccessibilityService? = null
            private set
    }

    /** 一次遍历的收集结果 */
    private class ScanContext(
        val extraTexts: Set<String>,
        val diagnose: Boolean
    ) {
        var adSdkDetected = false
        var clicked = false
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 上次【尝试】时间，用于冷却判断。原版记的是「上次成功」，是主要 bug 之一。 */
    private val lastAttemptAt = HashMap<String, Long>()

    /** 每个包名当前待执行的那一条扫描任务（单飞） */
    private val pendingTask = HashMap<String, Runnable>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 复用同一个 Rect，避免每次遍历都 new 出成百上千个 */
    private val rect = Rect()

    private var screenW = 0
    private var screenH = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // 记下「无障碍确实是开着的」，给保活服务做「被关掉」的判断依据
        QPPreferences.get(this).apply {
            accessibilityWasOn = true
            accessibilityOffNotified = false
        }

        Log.i(TAG, "无障碍服务已连接")
        KeepAliveService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in SkipRules.IGNORE_PACKAGES) return

        val cooldown = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> COOLDOWN_STATE_MS
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> COOLDOWN_CONTENT_MS
            else -> return
        }

        val now = SystemClock.uptimeMillis()
        val last = lastAttemptAt[pkg] ?: 0L
        if (now - last < cooldown) return

        // 已经有一条重试链在跑，让它自己跑完，别叠任务
        if (pendingTask.containsKey(pkg)) return

        lastAttemptAt[pkg] = now
        scheduleScan(pkg, 0, FIRST_SCAN_DELAY_MS)
    }

    /** 排一次扫描；retry 表示第几次重试 */
    private fun scheduleScan(pkg: String, retry: Int, delayMs: Long) {
        val task = Runnable {
            if (attemptSkip(pkg)) {
                lastAttemptAt[pkg] = SystemClock.uptimeMillis()
                pendingTask.remove(pkg)
                return@Runnable
            }
            if (retry < MAX_RETRY) {
                scheduleScan(pkg, retry + 1, RETRY_DELAY_MS)
            } else {
                pendingTask.remove(pkg)
            }
        }
        pendingTask[pkg] = task
        handler.postDelayed(task, delayMs)
    }

    /** 尝试跳过；返回 true 表示真的点到了 */
    private fun attemptSkip(pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false

        // 只在「当前活动窗口确实属于这个包」时才点，避免拿过期的树乱点
        val rootPkg = root.packageName?.toString()
        if (rootPkg != null && rootPkg != pkg) return false

        ensureScreenSize()
        val prefs = QPPreferences.get(this)

        // 通道 1：已知广告 SDK 的 resourceId
        for (id in SkipRules.SKIP_VIEW_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            for (node in nodes) {
                if (safeClick(node)) {
                    Log.i(TAG, "拦截 $pkg（viewId: $id）")
                    recordBlock(pkg)
                    return true
                }
            }
        }

        // 通道 2：一次遍历同时做三件事
        val counter = intArrayOf(0)
        val ctx = ScanContext(prefs.customSkipTexts, prefs.diagnoseEnabled)
        scanTree(root, 0, counter, ctx)

        if (ctx.clicked) {
            Log.i(TAG, "拦截 $pkg（文案）")
            recordBlock(pkg)
            return true
        }

        // 通道 3：坐标兜底 —— 必须两个条件同时成立才允许
        //   a) 用户显式开启（默认关）
        //   b) 这一屏确实存在广告 SDK 的 view
        if (prefs.coordinateFallbackEnabled && ctx.adSdkDetected) {
            if (tapFallbackPoint()) {
                Log.i(TAG, "拦截 $pkg（坐标兜底，已检测到广告 SDK）")
                recordBlock(pkg)
                return true
            }
        }

        return false
    }

    /** 单次深度优先遍历：文案匹配 + 广告 SDK 探测 + 诊断候选 */
    private fun scanTree(
        node: AccessibilityNodeInfo,
        depth: Int,
        counter: IntArray,
        ctx: ScanContext
    ) {
        if (ctx.clicked) return
        if (depth > SkipRules.MAX_DEPTH) return
        if (++counter[0] > SkipRules.MAX_NODES) return

        // 广告 SDK 探测（viewIdResourceName 依赖 flagReportViewIds）
        if (!ctx.adSdkDetected && SkipRules.isAdSdkView(node.viewIdResourceName)) {
            ctx.adSdkDetected = true
        }

        val text = node.text
        if (text != null) {
            if (SkipRules.isSkipText(text, ctx.extraTexts)) {
                if (safeClick(node)) {
                    ctx.clicked = true
                    return
                }
            } else if (ctx.diagnose && SkipRules.looksLikeCandidate(text)) {
                // 只记没命中但像按钮的文案，供后续扩充规则
                if (QPPreferences.get(this).rememberCandidate(text.toString())) {
                    Log.i(TAG, "诊断候选: '$text'")
                }
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            scanTree(child, depth + 1, counter, ctx)
            if (ctx.clicked) return
        }
    }

    /**
     * 安全点击：
     * 1. 先试节点自己（尺寸要合理）
     * 2. 再最多往上找 2 层父节点，但**祖先一旦过大就立刻放弃**
     *    —— 点大容器 = 点开广告，等于帮倒忙
     */
    private fun safeClick(node: AccessibilityNodeInfo): Boolean {
        if (tryClickNode(node, forAncestor = false)) return true

        var depth = 0
        var cur: AccessibilityNodeInfo? = node.parent
        while (cur != null && depth < 2) {
            if (cur.isClickable) {
                // 可点击但尺寸不合理 → 这就是「广告大容器」，点了反而中招
                return tryClickNode(cur, forAncestor = true)
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun tryClickNode(node: AccessibilityNodeInfo, forAncestor: Boolean): Boolean {
        if (!node.isClickable) return false
        node.getBoundsInScreen(rect)
        if (!SkipRules.isPlausibleButton(rect.width(), rect.height(), screenW, screenH, forAncestor)) {
            return false
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * 坐标兜底：在右上角轻点一下。
     *
     * ⚠️ 这是**下策**：控件树里看不到按钮时唯一还能做的动作，但它是"盲点"。
     * 所以调用点有两道闸门（用户开关 + 广告 SDK 存在），并且一次扫描只试一次。
     */
    private fun tapFallbackPoint(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (screenW <= 0 || screenH <= 0) return false
        return try {
            val x = screenW * FALLBACK_X_RATIO
            val y = screenH * FALLBACK_Y_RATIO
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 40L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "坐标兜底手势失败", e)
            false
        }
    }

    private fun ensureScreenSize() {
        if (screenW > 0 && screenH > 0) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
                screenW = bounds.width()
                screenH = bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val dm = resources.displayMetrics
                screenW = dm.widthPixels
                screenH = dm.heightPixels
            }
        } catch (e: Exception) {
            // 兜底：拿不到真实分辨率时用常见值，至少让比例判断能跑
            if (screenW <= 0) screenW = 1080
            if (screenH <= 0) screenH = 2400
        }
    }

    private fun recordBlock(packageName: String) {
        scope.launch {
            try {
                val dao = AppDatabase.getInstance(applicationContext).interceptionDao()
                dao.insert(InterceptionRecord(packageName = packageName, type = 1))
            } catch (e: Exception) {
                Log.e(TAG, "写入统计失败", e)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        pendingTask.clear()
        lastAttemptAt.clear()
        scope.cancel()
    }
}
