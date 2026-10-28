package phoenix.smsnotify.important

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import phoenix.smsnotify.MainActivity
import phoenix.smsnotify.ui.AppColor
import phoenix.smsnotify.ui.AppIcons
import phoenix.smsnotify.ui.PathIconView
import phoenix.smsnotify.ui.dpi
import phoenix.smsnotify.ui.pressScale
import phoenix.smsnotify.ui.roundedBg
import phoenix.smsnotify.ui.stopDot

// TODO(系统状态栏胶囊): 等 Android 16 普及、且主流 ROM 都实现 promoted 渲染之后，
//  这里可以整段换成系统自己画的胶囊，届时不再需要悬浮窗权限。已实测可行的做法：
//  manifest 声明 android.permission.POST_PROMOTED_NOTIFICATIONS（普通权限，装完即 granted，不审核），
//  通知 setOngoing(true) + ProgressStyle + extras 里塞
//  "android.requestPromotedOngoing" = true 与 "android.shortCriticalText"（chip 上那三五个字），
//  SystemUI 就会在状态栏正中画出胶囊，并自己把信号、电量图标让到两侧（HyperOS 3 真机验过）。
//  现在不换的原因：① chip 只认 shortCriticalText，setUsesChronometer 无效，做不出「已响 00:12」；
//  ② 没有就地停止键，只能点 chip 跳进 App 才能停；③ Android 15 及以下没有这套 API，
//  而 Android 16 的 AOSP 模拟器镜像同样不实现（同一条通知 flags 只有 ONGOING_EVENT、状态栏空白）。
//  真要换时是「启动时探测一次、一次性分派」——有就全程用系统胶囊，没有就全程用本文件的悬浮窗，
//  尤其不要在运行中来回切换形态：应用查不到 chip 到底有没有被画出来，切过去而没画出来，
//  屏幕上就真的什么都没有了，而铃声还在响。

/**
 * 响铃胶囊：屏幕未锁定时浮在顶部的一条，形如
 *
 *     ┌──────────────────────────────┐
 *     │ 🔔  银行动账  00:12 / 01:00  ◉ │
 *     └──────────────────────────────┘
 *
 * **为什么不是全屏闹钟界面**：全屏只在「手机在口袋里、屏幕黑着」时有意义 ——
 * 那种场景需要把锁屏整个盖掉，用户抬眼就能按停。屏幕亮着、用户正在别的应用里时，
 * 再弹一个全屏 Activity 会把手里的事打断，代价远大于收益；此时一条窄条足够传达
 * 「谁在响、响了多久、怎么停」三件事。
 *
 * 因此这里用**系统悬浮窗**（`TYPE_APPLICATION_OVERLAY`）而不是 Activity：
 *  - 不抢焦点（`FLAG_NOT_FOCUSABLE`），用户还能继续操作底下的应用；
 *  - 不打断当前任务，也不进任务栈，`onResume`/`onPause` 一概不触发；
 *  - 需要「显示在其他应用上层」权限，没授权时 [show] 直接返回 false，
 *    退化成通知栏那条（见 [ImportantNotify]），不会静默出现「什么都没发生」。
 *
 * 浮出后**一直待到响铃结束或被按停，不收起来、不变形态**。曾经做过「点窗口外或超时
 * 缩成 34dp 小圆点、点一下再展开」的那一套，动机是模仿系统灵动岛；但各家的岛都要
 * 场景审批，我们进不去，那个圆点就只剩「多一个状态要维护」，而且铃声还在响、屏幕上
 * 却只剩一个小圆点，比横条更让人困惑。少一个状态，也就少一类收尾回调打架的 bug。
 *
 * 点击分两处：整条点一下 = 打开 App 看这条短信，**铃声继续响**，由页面顶部那张
 * 响铃卡片上的停止键来停（和系统闹钟一致：点进时钟 App，闹钟还在响）；右侧红点 = 只停响，
 * 不离开当前应用。
 */
object RingIsland {

    private const val TAG = "RingIsland"

    /** 所有操作都在主线程：加/删窗口、跑动画、刷计时都要求有 Looper */
    private val handler = Handler(Looper.getMainLooper())

    /** 当前挂在系统里的窗口。null = 没显示 */
    private var host: WindowManager? = null
    private var pill: View? = null

    private var clock: TextView? = null
    private var ticker: Runnable? = null

    /** 有没有「显示在其他应用上层」权限。没有就只能在通知栏里提醒 */
    fun canShow(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Exception) {
        false
    }

    /**
     * 显示胶囊；已经在显示就先换掉内容。没权限时静默返回（通知那条已经发出去了）。
     *
     * @param text 规则名，用户给规则起的名字
     * @return 是否真的浮出来了。调用方（[AlarmRing.start]）拿它决定通知要不要弹横幅
     */
    fun show(ctx: Context, text: String): Boolean {
        val app = ctx.applicationContext
        if (!canShow(app)) {
            Log.i(TAG, "没有悬浮窗权限，跳过胶囊")
            return false
        }
        hide(now = true)

        val wm = app.getSystemService(WindowManager::class.java) ?: return false
        val view = build(app, text)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点（不挡输入法、不接管返回键）+ 窗口以外的点击交给下面的应用
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 坐标原点已经不含状态栏，8dp 就够；写成状态栏高度反而会在某些 ROM 上偏低
            y = app.dpi(8)
        }

        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            // 少数 ROM 会在这里抛 BadToken（权限状态与实际不符），静默降级到通知
            Log.w(TAG, "addView 失败：$e")
            return false
        }

        host = wm
        pill = view
        startClock()

        // 从中间「长出来」：缩放 + 淡入，比直接出现更像系统自己的胶囊
        view.alpha = 0f
        view.scaleX = 0.62f
        view.scaleY = 0.62f
        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
        return true
    }

    /**
     * 移除胶囊。
     *
     * @param now true = 立刻移除（换内容时用，避免上一条的退场动画和新的一条打架）
     */
    fun hide(now: Boolean = false) {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        clock = null

        val view = pill ?: return
        val wm = host
        pill = null
        host = null

        if (now || wm == null) {
            removeQuietly(wm, view)
            return
        }
        view.animate().cancel()
        view.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f)
            .setDuration(150)
            .withEndAction { removeQuietly(wm, view) }
            .start()
    }

    private fun removeQuietly(wm: WindowManager?, view: View) {
        try {
            wm?.removeView(view)
        } catch (e: Exception) {
            // 进程被杀后窗口已被系统回收，这里再删会抛 IllegalArgument，忽略即可
            Log.d(TAG, "removeView：$e")
        }
    }

    /**
     * 每秒刷一次「已过 / 总共」，让用户判断还要不要继续听。
     *
     * 起点和总时长都从 [AlarmRing.current] 现读，不在这里自己记一份：换一条短信时胶囊会被
     * 重建（[show] 先 hide 再挂），前后台切换也会重挂窗口，本地记的起点每挂一次就从 00:00 重算。
     */
    private fun startClock() {
        clock?.text = elapsed()
        val t = object : Runnable {
            override fun run() {
                clock?.text = elapsed()
                handler.postDelayed(this, 1_000)
            }
        }
        ticker = t
        handler.postDelayed(t, 1_000)
    }

    /**
     * 「00:12 / 01:00」。
     *
     * 正常情况下取不到 null —— [hide] 会先摘掉 ticker 和 clock，`current` 才被清空，
     * 这里留 `orEmpty()` 只是不让「顺序被将来改动」变成一次崩溃。
     */
    private fun elapsed(): String = AlarmRing.current?.elapsedCompact().orEmpty()

    // ---------------- 视图 ----------------

    private fun build(ctx: Context, text: String): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 右侧只留 6dp：停止键本身有 30dp，再给内边距会让它离圆角太远
            setPadding(ctx.dpi(12), ctx.dpi(9), ctx.dpi(6), ctx.dpi(9))
            background = ctx.roundedBg(AppColor.island, 22)
            contentDescription = "响铃中：$text，点一下打开查看"
            isClickable = true
            isFocusable = true
            pressScale(0.96f)
            setOnClickListener { openApp(ctx) }

            addView(PathIconView(ctx).apply {
                icon = AppIcons.bell()
                iconColor = AppColor.islandText
                layoutParams = LinearLayout.LayoutParams(ctx.dpi(16), ctx.dpi(16)).apply {
                    marginEnd = ctx.dpi(7)
                }
            })

            // 规则名可能很长（用户自己起的），限制宽度让它省略，别把胶囊拉成一整条。
            // 130dp 是给右侧时间让位：`00:12 / 30:00` 比原来的 `00:12` 宽出三倍
            addView(TextView(ctx).apply {
                this.text = text
                textSize = 13f
                setTextColor(AppColor.islandText)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = ctx.dpi(130)
            })

            clock = TextView(ctx).apply {
                textSize = 12f
                setTextColor(AppColor.islandSub)
                setPadding(ctx.dpi(8), 0, 0, 0)
                // 「00:12 / 30:00」比原来的纯秒表宽，别让它在窄屏上折成两行
                maxLines = 1
            }
            addView(clock)

            // 30dp 是胶囊的上限：整条才 44dp 高，再大就把上下内边距吃光了
            addView(ctx.stopDot(30) { stopRinging(ctx) }.apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dpi(30), ctx.dpi(30)).apply {
                    marginStart = ctx.dpi(8)
                }
            })
        }

    /**
     * 打开 App 看这条短信。
     *
     * 旗标和通知那条保持一致（`AppNotify.openApp`）：复用已有实例、走 `onNewIntent` 切 Tab，
     * 而不是再压一个 MainActivity。`FLAG_ACTIVITY_NEW_TASK` 是必须的 —— 这里手里只有
     * Application Context，从非 Activity 环境启动 Activity 必须带它。
     */
    private fun openApp(ctx: Context) {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_IMPORTANT)
        }
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            Log.w(TAG, "打开 App 失败：$e")
        }
    }
}
