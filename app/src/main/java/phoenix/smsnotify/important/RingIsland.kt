package phoenix.smsnotify.important

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import phoenix.smsnotify.MainActivity
import phoenix.smsnotify.ui.AppColor
import phoenix.smsnotify.ui.AppIcons
import phoenix.smsnotify.ui.PathIconView
import phoenix.smsnotify.ui.dpi
import phoenix.smsnotify.ui.pressScale
import phoenix.smsnotify.ui.roundedBg

/**
 * 响铃胶囊：屏幕未锁定时浮在顶部的一条，形如
 *
 *     ┌──────────────────────────────┐
 *     │ 🔔  重要短信 · 银行动账  00:12  ◉ │
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
 * 两种形态（模仿系统闹钟的灵动岛）：
 *  - **展开**：上面那条横条，带规则名、已响时长和停止键；
 *  - **收起**：顶部正中一个 34dp 的小圆点（居中悬浮窗，正好落在屏幕中轴、
 *    也就是多数手机前置摄像头挖孔的位置），点一下再展开。
 *
 * 什么时候收：**点了窗口外面**（悬浮窗不抢焦点，唯一能感知到"用户去忙别的了"的信号
 * 就是 `FLAG_WATCH_OUTSIDE_TOUCH` 送来的 `ACTION_OUTSIDE`），或者展开 [AUTO_COLLAPSE_MS]
 * 没人理 —— 系统横幅也是这么自己退场的。收起后铃声照旧、通知栏那条也还在，
 * 所以任何形态下都有停下来的路。
 *
 * 点击分三处：整条点一下 = 打开 App 看这条短信（`MainActivity.onResume` 里会顺手停响）；
 * 右侧红点 = 只停响，不离开当前应用；收起后的小圆点 = 重新展开。
 */
object RingIsland {

    private const val TAG = "RingIsland"

    /** 展开后多久没人碰就缩回小圆点。和系统横幅的退场节奏接近，别一直占着屏幕 */
    private const val AUTO_COLLAPSE_MS = 6_000L

    /** 所有操作都在主线程：加/删窗口、跑动画、刷计时都要求有 Looper */
    private val handler = Handler(Looper.getMainLooper())

    /** 当前挂在系统里的窗口。null = 没显示 */
    private var host: WindowManager? = null
    private var pill: View? = null

    /** 展开态（横条）与收起态（小圆点），同一个窗口里只显示一个 */
    private var bar: View? = null
    private var dot: View? = null

    private var clock: TextView? = null
    private var ticker: Runnable? = null
    private var collapseTask: Runnable? = null
    private var startedAt = 0L

    /** 当前是不是收起状态。窗口重建后由 [show] 复位成展开 */
    private var collapsed = false

    /** 有没有「显示在其他应用上层」权限。没有就只能在通知栏里提醒 */
    fun canShow(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Exception) {
        false
    }

    /**
     * 显示胶囊；已经在显示就先换掉内容。没权限时静默返回（通知那条已经发出去了）。
     *
     * @param text 规则名，接在「重要短信 · 」后面
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
            // 不抢焦点（不挡输入法、不接管返回键）+ 点击落在胶囊外时交给下面的应用
            // + 但仍然把那次"点在外面"告诉我们 —— 收起成小圆点就靠它触发
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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
        expandNow()
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
     * 收起胶囊。
     *
     * @param now true = 立刻移除（换内容时用，避免上一条的退场动画和新的一条打架）
     */
    fun hide(now: Boolean = false) {
        cancelAutoCollapse()
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        clock = null

        val view = pill ?: return
        val wm = host
        pill = null
        host = null
        bar = null
        dot = null
        collapsed = false

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

    // ---------------- 展开 / 收起 ----------------

    /** 立刻切到展开态（新弹出来时用，不要动画） */
    private fun expandNow() {
        collapsed = false
        bar?.let {
            it.visibility = View.VISIBLE
            it.alpha = 1f
        }
        dot?.let {
            it.visibility = View.GONE
            it.alpha = 1f
            it.scaleX = 1f
            it.scaleY = 1f
        }
        armAutoCollapse()
    }

    /**
     * 展开：小圆点淡出，横条淡入。
     *
     * 窗口是 `WRAP_CONTENT`，横条一显示窗口就跟着变大 —— 这一下"长大"本身就是动画的一部分，
     * 所以内容只做透明度，不再叠缩放（缩放已经给按压缩放用了，两套抢同一个属性会打架）。
     */
    private fun expand() {
        if (!collapsed) return
        collapsed = false
        val barView = bar ?: return
        val dotView = dot ?: return

        dotView.animate().cancel()
        dotView.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f)
            .setDuration(110)
            .withEndAction { dotView.visibility = View.GONE }
            .start()

        barView.visibility = View.VISIBLE
        barView.alpha = 0f
        barView.animate().alpha(1f).setDuration(190).start()

        armAutoCollapse()
    }

    /**
     * 收起：横条淡出，然后换成小圆点带一点回弹地出现。
     *
     * 两段是分开的而不是交叉的：窗口在横条隐藏的那一刻就会缩到 34dp，
     * 若让两者同时动，会看到横条被"截断"一下；横条先消失，用户看到的就只是
     * "它缩回顶部去了"。
     */
    private fun collapse() {
        if (collapsed) return
        val barView = bar ?: return
        val dotView = dot ?: return
        collapsed = true
        cancelAutoCollapse()
        Log.i(TAG, "collapse")

        // 万一 110ms 内刚展开过，先把小圆点那半截动画掐掉再直接藏起来：
        // 留着会压在横条上，留着它的收尾回调则会在收起后把圆点设成 GONE（屏幕变空）
        dotView.animate().cancel()
        dotView.visibility = View.GONE
        dotView.alpha = 1f
        dotView.scaleX = 1f
        dotView.scaleY = 1f
        barView.animate().cancel()
        barView.animate().alpha(0f)
            .setDuration(130)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                barView.visibility = View.GONE
                barView.alpha = 1f
                // 淡出期间又被展开（点了外面之后立刻点回来）就不要再把它换掉了
                if (!collapsed) return@withEndAction
                dotView.visibility = View.VISIBLE
                dotView.alpha = 0f
                dotView.scaleX = 0.4f
                dotView.scaleY = 0.4f
                dotView.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            }
            .start()
    }

    private fun armAutoCollapse() {
        cancelAutoCollapse()
        val task = Runnable { collapse() }
        collapseTask = task
        handler.postDelayed(task, AUTO_COLLAPSE_MS)
    }

    private fun cancelAutoCollapse() {
        collapseTask?.let { handler.removeCallbacks(it) }
        collapseTask = null
    }

    /** 每秒刷一次「已响多久」，让用户判断还要不要继续听 */
    private fun startClock() {
        startedAt = SystemClock.elapsedRealtime()
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

    /** 只按分:秒计，超过一小时就继续累加分钟（响这么久大概率是忘了停） */
    private fun elapsed(): String {
        val s = (SystemClock.elapsedRealtime() - startedAt) / 1_000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    // ---------------- 视图 ----------------

    private fun build(ctx: Context, text: String): View {
        val root = IslandRoot(ctx)
        val barView = buildBar(ctx, text)
        val dotView = buildDot(ctx)

        bar = barView
        dot = dotView
        root.addView(barView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        // 小圆点定死 34dp：窗口缩到它本身的大小，位置才会正对屏幕中轴
        root.addView(dotView, FrameLayout.LayoutParams(
            ctx.dpi(34), ctx.dpi(34), Gravity.CENTER
        ))

        // 「点了别处」= 用户去忙别的了，把横条收成小圆点，别继续压在屏幕上
        root.onOutside = { collapse() }
        return root
    }

    private fun buildBar(ctx: Context, text: String): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 右侧只留 6dp：停止键本身有 30dp，再给内边距会让它离圆角太远
            setPadding(ctx.dpi(12), ctx.dpi(9), ctx.dpi(6), ctx.dpi(9))
            background = ctx.roundedBg(AppColor.island, 22)
            contentDescription = "重要短信响铃中：$text"
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

            // 规则名可能很长（用户自己起的），限制宽度让它省略，别把胶囊拉成一整条
            addView(TextView(ctx).apply {
                this.text = text
                textSize = 13f
                setTextColor(AppColor.islandText)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = ctx.dpi(170)
            })

            clock = TextView(ctx).apply {
                textSize = 12f
                setTextColor(AppColor.islandSub)
                setPadding(ctx.dpi(8), 0, 0, 0)
            }
            addView(clock)

            addView(stopButton(ctx))
        }

    /**
     * 收起态的小圆点：34dp 的深色圆 + 铃铛 + 右上角一个红点。
     *
     * 尺寸按"比前置摄像头挖孔略大一点"定的 —— 手机上它正好套在屏幕中轴的挖孔上，
     * 看起来就是系统自己的灵动岛；没有挖孔的设备上则是一个悬浮的圆形小标。
     * 红点的作用是「收起来以后也看得出还在响」，不然会像个普通图标。
     */
    private fun buildDot(ctx: Context): View =
        FrameLayout(ctx).apply {
            background = ctx.roundedBg(AppColor.island, 17)
            contentDescription = "重要短信响铃中，点一下展开"
            isClickable = true
            isFocusable = true
            pressScale(0.88f)
            setOnClickListener { expand() }
            visibility = View.GONE

            addView(PathIconView(ctx).apply {
                icon = AppIcons.bell()
                iconColor = AppColor.islandText
                layoutParams = FrameLayout.LayoutParams(ctx.dpi(16), ctx.dpi(16), Gravity.CENTER)
            })
            addView(View(ctx).apply {
                background = ctx.roundedBg(AppColor.islandStop, 3)
                layoutParams = FrameLayout.LayoutParams(ctx.dpi(7), ctx.dpi(7)).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = ctx.dpi(4)
                    marginEnd = ctx.dpi(4)
                }
            })
        }

    /** 右侧停止键：红色圆底 + 白色圆角方块（比字符「■」可控，不受系统字体影响） */
    private fun stopButton(ctx: Context): View = FrameLayout(ctx).apply {
        background = ctx.roundedBg(AppColor.islandStop, 15)
        contentDescription = "停止响铃"
        isClickable = true
        isFocusable = true
        pressScale(0.88f)
        layoutParams = LinearLayout.LayoutParams(ctx.dpi(30), ctx.dpi(30)).apply {
            marginStart = ctx.dpi(8)
        }
        addView(View(ctx).apply {
            background = ctx.roundedBg(AppColor.islandText, 1)
            layoutParams = FrameLayout.LayoutParams(ctx.dpi(10), ctx.dpi(10), Gravity.CENTER)
        })
        setOnClickListener { stopRinging(ctx) }
    }

    /**
     * 窗口根视图，只为一件事存在：接住「点了窗口外面」。
     *
     * `FLAG_WATCH_OUTSIDE_TOUCH` 只会在窗口外层被按下时送一个 `ACTION_OUTSIDE`，
     * 它不会把触摸截走（底下那个应用照样收到），所以这里处理完直接返回 true 即可。
     */
    private class IslandRoot(ctx: Context) : FrameLayout(ctx) {

        var onOutside: (() -> Unit)? = null

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                onOutside?.invoke()
                return true
            }
            return super.dispatchTouchEvent(ev)
        }
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
