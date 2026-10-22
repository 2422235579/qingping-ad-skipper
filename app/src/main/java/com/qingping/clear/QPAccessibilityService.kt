package com.qingping.clear

import android.accessibilityservice.AccessibilityService
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
 * 3. **文案匹配支持倒计时**。
 *    原版用 `text in SKIP_TEXTS` 精确相等，而真实广告按钮几乎都带数字（"跳过 3s"），
 *    导致大面积漏拦。现在走 [SkipRules.isSkipText]（精确 + 正则）。
 *
 * 4. **单飞（single-flight）**：同一包名同时只保留一条重试链，避免任务堆积。
 * 5. **只在自己包名是当前活动窗口时才动手**，避免在过期的控件树上瞎点。
 * 6. **尺寸判断改成相对屏幕比例**，折叠屏/平板上不再误判。
 * 7. 协程作用域挂在服务上，`onDestroy` 统一取消（原来每次 new 一个，泄漏）。
 * 8. 复用 `Rect`、限制遍历节点数，减少主线程压力。
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

        @Volatile
        var instance: QPAccessibilityService? = null
            private set
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

        // 通道 2：文案（精确 + 倒计时正则）
        val counter = intArrayOf(0)
        if (searchByText(root, 0, counter)) {
            Log.i(TAG, "拦截 $pkg（文案）")
            recordBlock(pkg)
            return true
        }
        return false
    }

    /** 深度优先找文案匹配的节点 */
    private fun searchByText(
        node: AccessibilityNodeInfo,
        depth: Int,
        counter: IntArray
    ): Boolean {
        if (depth > SkipRules.MAX_DEPTH) return false
        if (++counter[0] > SkipRules.MAX_NODES) return false

        if (SkipRules.isSkipText(node.text) && safeClick(node)) return true

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            if (searchByText(child, depth + 1, counter)) return true
        }
        return false
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
