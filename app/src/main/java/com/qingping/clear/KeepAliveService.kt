package com.qingping.clear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 前台保活服务。
 *
 * 除了原来的「挂个常驻通知」，新增一件事：
 *
 * **检测无障碍被关掉并提醒。**
 *
 * 为什么需要：无障碍服务被系统或用户关掉后，这个 App 就彻底失去作用了，
 * 但用户完全不会察觉 —— 只会觉得"怎么又开始看广告了"。
 * 安卓没有 API 能让第三方 App 静默重新绑定无障碍（这是平台安全设计，改不了），
 * 所以唯一能做的就是**主动提醒一次**，把用户带回设置页。
 *
 * 实现上每 5 分钟查一次 `ENABLED_ACCESSIBILITY_SERVICES`，开销可以忽略。
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "QPKeepAlive"

        private const val CHANNEL_KEEPALIVE = "qingping_keepalive"
        private const val CHANNEL_ALERT = "qingping_alert"

        private const val NOTIFICATION_ID_KEEPALIVE = 1001
        private const val NOTIFICATION_ID_ALERT = 1002

        /** 无障碍健康检查间隔 */
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L

        /** 启动后先等一会儿再检查，避免和系统绑定过程抢时间 */
        private const val FIRST_CHECK_DELAY_MS = 20 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val checkTask = object : Runnable {
        override fun run() {
            checkAccessibilityHealth()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIFICATION_ID_KEEPALIVE, buildKeepAliveNotification())
        handler.postDelayed(checkTask, FIRST_CHECK_DELAY_MS)
    }

    private fun buildKeepAliveNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_KEEPALIVE)
            .setContentTitle("清屏正在运行")
            .setContentText("已开启开屏广告拦截，请勿关闭此通知")
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            // HyperOS 需要高优先级才不被静默折叠 —— 这条不能降，降了服务容易被杀。
            // 但用 setSilent 让它不出声，避免变成骚扰（通道里也把声音置空了）。
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setOngoing(true)
            .build()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        // 常驻通知：高重要性（防折叠）+ 无声（不骚扰）
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_KEEPALIVE, "清屏保活服务", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "保持广告拦截服务运行"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
        )

        // 告警通知：默认优先级，因为「无障碍被关了」是需要用户处理的事
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "清屏提醒", NotificationManager.IMPORTANCE_DEFAULT)
                .apply {
                    description = "无障碍服务被关闭时提醒一次"
                    setShowBadge(true)
                }
        )
    }

    /**
     * 检查无障碍是否还开着。
     *
     * 只在「之前确认开着、现在被关了、且还没提醒过」时弹一次，
     * 避免每 5 分钟骚扰一次。
     */
    private fun checkAccessibilityHealth() {
        try {
            val prefs = QPPreferences.get(this)
            val on = isAccessibilityEnabled()

            if (on) {
                prefs.accessibilityWasOn = true
                prefs.accessibilityOffNotified = false
                return
            }

            if (!prefs.accessibilityWasOn || prefs.accessibilityOffNotified) return

            prefs.accessibilityOffNotified = true
            notifyAccessibilityOff()
        } catch (e: Exception) {
            Log.w(TAG, "无障碍健康检查失败", e)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val masterOn = try {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) == 1
        } catch (e: Exception) {
            false
        }
        if (!masterOn) return false

        val shortName = "$packageName/.QPAccessibilityService"
        val fullName = "$packageName/$packageName.QPAccessibilityService"
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val item = splitter.next()
            if (item.equals(shortName, true) || item.equals(fullName, true)) return true
        }
        return false
    }

    private fun notifyAccessibilityOff() {
        val nm = getSystemService(NotificationManager::class.java) ?: return

        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle("清屏的无障碍服务被关了")
            .setContentText("广告拦截已经停止。点这里重新开启。")
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        try {
            nm.notify(NOTIFICATION_ID_ALERT, notification)
        } catch (e: Exception) {
            Log.w(TAG, "发提醒通知失败", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
