package com.qingping.clear

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.qingping.clear.data.AppDatabase
import com.qingping.clear.data.AppStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 主界面：状态 + 统计。
 *
 * 相对原版的改动：
 * 1. **应用名缓存**：原来 `onBindViewHolder` 里每次都调 `pm.getApplicationLabel()`
 *    （主线程 + PackageManager 查询），列表一滚就卡。现在预取一次进 Map。
 * 2. **DiffUtil 代替 notifyDataSetChanged**：只重绘变化的那几行。
 * 3. **今日统计跨零点会错**：原来 `todayStart` 只在 onCreate 算一次，
 *    应用挂着跨过 0 点后「今日」数字就错了。现在用 switchMap + onResume 刷新。
 * 4. 异常不再静默吞掉（原来只弹 Toast，日志里什么都没有）。
 * 5. 补上协程作用域，`onDestroy` 里取消。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "QPMain"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvToday: TextView
    private lateinit var rvStats: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvGuide: TextView

    private lateinit var adapter: StatsAdapter
    private lateinit var db: AppDatabase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 包名 → 应用名，避免每次绑定都查 PackageManager */
    private val labelCache = HashMap<String, String>()

    /** 今日起点，变化时自动重查 */
    private val dayStart = MutableLiveData<Long>()

    /** 状态栏复查的任务，避免 onResume 里重复堆积 */
    private val statusRefresh = Runnable { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvTotal = findViewById(R.id.tvTotal)
        tvToday = findViewById(R.id.tvToday)
        rvStats = findViewById(R.id.rvStats)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvGuide = findViewById(R.id.tvGuide)

        val btnAccessibility = findViewById<TextView>(R.id.btnAccessibility)
        val btnUsage = findViewById<TextView>(R.id.btnUsage)
        val btnBattery = findViewById<TextView>(R.id.btnBattery)
        val btnNotif = findViewById<TextView>(R.id.btnNotif)
        val btnAutostart = findViewById<TextView>(R.id.btnAutostart)

        db = AppDatabase.getInstance(this)

        adapter = StatsAdapter { pkg -> labelCache[pkg] ?: pkg }
        rvStats.layoutManager = LinearLayoutManager(this)
        rvStats.adapter = adapter

        btnAccessibility.setOnClickListener { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        btnUsage.setOnClickListener { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        btnBattery.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!tryStart(intent)) openAppDetail()
        }
        btnNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                Toast.makeText(this, "系统版本低，无需通知权限", Toast.LENGTH_SHORT).show()
            }
        }
        btnAutostart.setOnClickListener {
            openAppDetail()
            Toast.makeText(this, "请在应用信息页找到「自启动」并开启", Toast.LENGTH_LONG).show()
        }

        showManufacturerGuide()
        setupDiagnosticsEntry()
        observeStats()
    }

    // ---------- 高级设置（长按状态栏） ----------
    // 故意不新增界面元素：改 layout 会带来无法本地验证的风险，
    // 长按入口对普通用户不可见，也不会误触。

    private fun setupDiagnosticsEntry() {
        tvStatus.setOnLongClickListener {
            try {
                showDiagnosticsDialog()
            } catch (e: Exception) {
                Log.w(TAG, "打开高级设置失败", e)
            }
            true
        }
    }

    private fun showDiagnosticsDialog() {
        val prefs = QPPreferences.get(this)

        val options = arrayOf(
            "诊断模式（记录没被识别的按钮文案）",
            "坐标兜底（有风险，默认关）"
        )
        val checked = booleanArrayOf(prefs.diagnoseEnabled, prefs.coordinateFallbackEnabled)

        AlertDialog.Builder(this)
            .setTitle("高级设置")
            .setMultiChoiceItems(options, checked) { _, which, isChecked ->
                when (which) {
                    0 -> prefs.diagnoseEnabled = isChecked
                    1 -> prefs.coordinateFallbackEnabled = isChecked
                }
            }
            .setPositiveButton("看候选") { _, _ -> showCandidates(prefs) }
            .setNeutralButton("关闭", null)
            .show()
    }

    private fun showCandidates(prefs: QPPreferences) {
        val list = prefs.candidates()
        if (list.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("诊断候选")
                .setMessage("还没有候选。\n\n打开诊断模式后，去刷几个带开屏广告的 App，\n没被识别的按钮文案会出现在这里。")
                .setPositiveButton("好", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("诊断候选（${list.size}）")
            .setItems(list.toTypedArray()) { _, which ->
                // 点一条就直接加进自定义规则
                prefs.addCustomText(list[which])
                Toast.makeText(this, "已加入规则：${list[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("清空") { _, _ -> prefs.clearCandidates() }
            .setPositiveButton("关闭", null)
            .show()
    }

    // ---------- 统计 ----------

    private fun observeStats() {
        db.interceptionDao().getTotalCount().observe(this) { count ->
            tvTotal.text = "${count ?: 0}"
        }

        // 今日计数：dayStart 一变就自动重查（解决跨零点）
        dayStart.switchMap { start -> db.interceptionDao().getTodayCount(start) }
            .observe(this) { count -> tvToday.text = "${count ?: 0}" }

        db.interceptionDao().getStatsByApp().observe(this) { list ->
            if (list.isNullOrEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvStats.visibility = View.GONE
                adapter.submitList(emptyList())
            } else {
                tvEmpty.visibility = View.GONE
                rvStats.visibility = View.VISIBLE
                // 先在 IO 线程把应用名查好，再交给 UI
                scope.launch {
                    val missing = list.map { it.packageName }.filter { !labelCache.containsKey(it) }
                    val fetched = withContext(Dispatchers.IO) {
                        missing.associateWith { appLabel(it) }
                    }
                    runOnUiThread {
                        labelCache.putAll(fetched)
                        adapter.submitList(list)
                    }
                }
            }
        }

        dayStart.value = todayStartMillis()
    }

    private fun todayStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun appLabel(pkg: String): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(pkg, 0)
        }
        packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        pkg
    }

    // ---------- 状态 ----------

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 跨零点的兜底：回到前台就重算今日起点
        dayStart.value = todayStartMillis()
        // HyperOS 开启无障碍后服务绑定有延迟，2 秒后再查一次
        tvStatus.removeCallbacks(statusRefresh)
        tvStatus.postDelayed(statusRefresh, 2000)
    }

    override fun onPause() {
        tvStatus.removeCallbacks(statusRefresh)
        super.onPause()
    }

    private fun refreshStatus() {
        val serviceAlive = QPAccessibilityService.instance != null
        val systemOn = isAccessibilityServiceOn()
        if (serviceAlive || systemOn) {
            tvStatus.text = "● 拦截服务运行中"
            tvStatus.setTextColor(0xFF4CAF50.toInt())
            KeepAliveService.start(this)
        } else {
            tvStatus.text = "● 无障碍未开启（点下方按钮去开启）"
            tvStatus.setTextColor(0xFFF44336.toInt())
        }
    }

    private fun isAccessibilityServiceOn(): Boolean {
        val shortName = "$packageName/.QPAccessibilityService"
        val fullName = "$packageName/$packageName.QPAccessibilityService"

        val enabled = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (e: Exception) {
            Log.w(TAG, "读无障碍列表失败", e)
            null
        } ?: return false

        val masterOn = try {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) == 1
        } catch (e: Exception) {
            false
        }
        if (!masterOn) return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val item = splitter.next()
            if (item.equals(shortName, true) || item.equals(fullName, true)) return true
        }
        return false
    }

    // ---------- 跳转 ----------

    private fun openSettings(action: String) {
        tryStart(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "跳转失败: ${intent.action}", e)
        false
    }

    private fun openAppDetail() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!tryStart(intent)) {
            Toast.makeText(this, "无法打开应用详情", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showManufacturerGuide() {
        val m = Build.MANUFACTURER.lowercase()
        if (m == "xiaomi" || m == "redmi" || m == "poco") {
            tvGuide.visibility = View.VISIBLE
            tvGuide.text = """
                ⚠ 检测到小米/HyperOS，请额外完成：
                1. 开启无障碍服务
                2. 点「应用详情」，把「自启动」打开
                3. 最近任务栏里下拉本 APP 卡片加锁
                4. 电池设置里选「无限制」

                注意：HyperOS 重启后无障碍可能被系统关闭，需重新开启。
            """.trimIndent()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 列表 ----------

    private class StatsAdapter(
        private val labelProvider: (String) -> String
    ) : ListAdapter<AppStats, StatsAdapter.VH>(DIFF) {

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<AppStats>() {
                override fun areItemsTheSame(a: AppStats, b: AppStats) = a.packageName == b.packageName
                override fun areContentsTheSame(a: AppStats, b: AppStats) = a == b
            }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvAppName: TextView = v.findViewById(R.id.tvAppName)
            val tvCount: TextView = v.findViewById(R.id.tvCount)
            val tvDetail: TextView = v.findViewById(R.id.tvDetail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app_stats, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.tvAppName.text = labelProvider(item.packageName)
            holder.tvCount.text = "${item.totalCount} 次"
            holder.tvDetail.text = "开屏 ${item.splashCount} · 跳转 ${item.jumpCount}"
        }
    }
}
