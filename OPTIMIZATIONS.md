# 优化记录

> 这份文件记录对原始代码做的修改、**为什么改**、以及**哪些没能验证**。
>
> 原始版本由豆包生成。下面每条都标注了对应用户能感知的后果，
> 不含"代码更优雅"这类没有实际影响的改动。

---

## ⚠️ 先声明：没有编译验证

这台机器上**没有 Android SDK / Gradle**（`ANDROID_HOME` 为空，`gradle` 未安装），
所以**我没有编译过这份代码，也没有在任何设备上跑过**。

改动是**代码级**的（读源码 + 按 Android API 语义判断），实际编译时如果报错，
大概率是这些地方的小问题：

- `WindowManager.currentWindowMetrics`（API 30+，已有版本判断）
- `ListAdapter` / `DiffUtil`（`androidx.recyclerview` 已在依赖里 ✅）
- `switchMap`（`lifecycle-livedata-ktx` 已在依赖里 ✅）

请用 Android Studio 打开后先 Build 一次再安装。

---

## 🔴 三个真正的功能 bug

### 1. 冷却判断写错了位置 → 耗电 + 卡顿

**原代码**：

```kotlin
val last = lastBlockMap[pkg] ?: 0L
if (now - last < DEBOUNCE_MS) return
...
if (clicked) {
    lastBlockMap[packageName] = System.currentTimeMillis()   // ← 只有成功才写
}
```

**后果**：`lastBlockMap` 只在「成功点到跳过按钮」时更新。而绝大多数事件是**找不到按钮**的。
于是冷却形同虚设 —— **每一次 `TYPE_WINDOW_CONTENT_CHANGED` 都会排一个 300ms 后的全树遍历**。
在列表页、聊天页这种界面滚动时，这个事件每秒几十次，主线程一直在遍历控件树。

**用户能感觉到的**：手机异常耗电、刷微博/知乎时有点卡。

**修法**：冷却记录改成「上次**尝试**时间」，且两种事件用不同冷却：

```kotlin
val cooldown = when (event.eventType) {
    TYPE_WINDOW_STATE_CHANGED -> 1200L     // 新窗口出现
    TYPE_WINDOW_CONTENT_CHANGED -> 900L    // 内容刷新（最频繁）
    else -> return
}
if (now - (lastAttemptAt[pkg] ?: 0L) < cooldown) return
lastAttemptAt[pkg] = now        // ← 尝试就记，不管成没成
```

---

### 2. 会点到「广告大容器」→ 把跳过变成了点广告 ⚠️

**原代码**：

```kotlin
val parent = node.parent
if (parent != null && parent.isClickable) {
    parent.performAction(ACTION_CLICK)      // ← 只看可不可点，不看多大
}
val grandParent = parent?.parent
if (grandParent != null && grandParent.isClickable) {
    grandParent.performAction(ACTION_CLICK) // ← 同上
}
```

**后果**：很多广告把「整个广告 View」设成可点击的。当"跳过"文字的父容器正好是那个
铺满屏幕的容器时，这一下点击 = **真的点开了广告**（跳应用商店/落地页）。

**这是最糟糕的一类 bug**：用户以为在拦广告，实际是程序帮他点广告。

**修法**：点击前先量尺寸，祖先容器一旦超过屏幕比例就**立刻放弃**：

```kotlin
// 按钮自身：≤ 60% 屏宽、20% 屏高
// 祖先容器：≤ 95% 屏宽、30% 屏高
if (!SkipRules.isPlausibleButton(w, h, screenW, screenH, forAncestor)) return false
```

并且尺寸判断从**写死像素**（`width < 400 && height < 200`）改成**相对屏幕比例**——
原来那套在高 DPI、平板、折叠屏上必然误判。

---

### 3. 带倒计时的跳过按钮全部漏拦

**原代码**：

```kotlin
private val SKIP_TEXTS = setOf(
    "跳过", "跳过广告", "广告跳过", "关闭广告", "立即跳过", "跳过", "跳过"
)   // 注意最后两个是重复项
...
if (nodeText in SKIP_TEXTS)      // ← 完全相等才匹配
```

**后果**：真实开屏广告的跳过按钮**几乎都带倒计时**：

```
跳过 3    跳过3s    3s 跳过    跳过广告 5    跳过 (5)    5秒跳过    跳过 >
```

这些**一个都匹配不上**。另外 Set 里 "跳过" 写了三遍，说明作者本来还想加变体但没写对。

**修法**：新增 `SkipRules.isSkipText()`，精确集合 + 倒计时正则：

```kotlin
Regex("^\\s*(跳过|跳过广告|关闭广告|立即跳过|skip)\\s*[（(]?\\s*" +
      "\\d{0,2}\\s*[)）]?\\s*[sS秒]?\\s*[>》»]?\\s*$", IGNORE_CASE)
```

同时加了长度上限（> 12 字符直接判否），避免把一段正文里的"跳过"当按钮。

---

## 🟡 四个次要改进

### 4. 无障碍配置缺 `flagIncludeNotImportantViews`

原配置没有这个标志。**很多广告 SDK 把跳过按钮放在「对无障碍不重要」的容器里**，
不加这个标志，控件树里**根本看不到它** → 直接漏拦。

已加到 `res/xml/accessibility_config.xml`。这是提升覆盖率成本最低的一步。

### 5. 任务堆积 + 协程泄漏

- 原代码每个事件都 `handler.postDelayed`，没有去重 → 待执行任务会堆一堆。
  改成**每个包名单飞**（`pendingTask` 映射，同一包同时只跑一条重试链）。
- 原代码 `recordBlock()` 里每次 `CoroutineScope(Dispatchers.IO).launch { }` —— **每次新建一个作用域**，
  没有任何地方取消它。改成服务持有 `SupervisorJob` 作用域，`onDestroy` 里 `cancel()`。

### 6. 主界面在主线程查应用名 → 列表卡

**原代码**：

```kotlin
override fun onBindViewHolder(holder: VH, position: Int) {
    val appName = pm.getApplicationLabel(pm.getApplicationInfo(item.packageName, 0))
    ...
}
```

`PackageManager` 查询是**主线程 I/O**，每个 item 每次重绘都查一次。
配合 `notifyDataSetChanged()`（全量重绘），列表越长越卡。

**修法**：
- 用 `ListAdapter` + `DiffUtil`，只重绘变化的行
- 应用名预取进 `HashMap` 缓存（在 IO 线程批量取，取完再交给 UI）
- `getApplicationInfo(pkg, 0)` 在 API 33+ 已废弃 → 用 `ApplicationInfoFlags.of(0)`

### 7. 「今日」统计跨零点会错

原代码在 `onCreate` 里算一次 `todayStart` 就固定了。应用挂着跨过 0 点后，
"今日拦截"显示的其实是昨天的数字。

**修法**：`dayStart` 改成 `MutableLiveData` + `switchMap`，`onResume` 时重算。

---

## 🟢 安全/隐私清洗

| 项 | 原来 | 现在 | 为什么 |
| --- | --- | --- | --- |
| `allowBackup` | `true` | `false` | 拦截记录包含「什么时候用过哪些 App」，不该进云备份/被 `adb backup` 导出 |
| `BootReceiver` 的 `exported` | `true` | `false` | 它只接收系统广播，暴露出去等于任何 App 都能给它发广播 |
| `SYSTEM_ALERT_WINDOW` | 声明了但没实现 | **已删除** | 一个能读全屏+模拟点击的无障碍应用，再要悬浮窗权限对用户是风险，也容易被市场卡 |

---

## 还可以继续做的（未实现）

按性价比排序，供后续参考：

1. **坐标点击兜底**（`canPerformGestures` 已经开了）
   有些广告的跳过按钮被画在自定义 View 里、没有 `text`、也没有 viewId，
   控件树完全看不到。只能靠「屏幕右上角固定区域外点」这类几何规则，属于下策，会误点。
2. **规则在线更新**
   把 `SkipRules` 的文案/ID 列表做成可从远端拉取的配置，不用每次改代码发版。
   代价是引入网络权限和隐私争议。
3. **误拦/漏拦的本地统计**
   记录"候选按钮出现了但没点"的次数，帮用户判断规则该不该调。
4. **开机后自动检测无障碍是否被关**
   系统不提供静音重开无障碍的 API（这是平台安全设计），但可以在
   `KeepAliveService` 里检测到「开关被关」时发一条提醒通知。
5. **`specialUse` 前台服务在国内 ROM 的存活率**
   `specialUse` 在部分国产 ROM 上会被降级，可以评估改回 `dataSync` + 引导白名单。
