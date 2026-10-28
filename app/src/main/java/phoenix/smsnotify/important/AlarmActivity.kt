package phoenix.smsnotify.important

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import phoenix.smsnotify.MainActivity
import phoenix.smsnotify.ui.AppIcons
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.PathIconView
import phoenix.smsnotify.ui.pressScale
import phoenix.smsnotify.ui.roundedBg

/** 全屏闹钟页的底色：比胶囊（#1C1C1E）再深一点，锁屏上一眼能看出「这是亮起来的提醒」 */
private val BG = 0xFF101013.toInt()
private val TITLE_TEXT = 0xFFFFFFFF.toInt()
private val BODY_TEXT = 0xFFD6D6DB.toInt()
private val HINT_TEXT = 0xFF8E8E93.toInt()
private val STOP_RED = 0xFFFF453A.toInt()

/**
 * 息屏/锁屏时盖住整个屏幕的提醒页。
 *
 * **为什么必须有这个页面**：普通通知在息屏时不会亮屏 —— 只会有声音。用户听到铃声、
 * 抬头看手机，屏幕还是黑的，只能再去按电源键、解锁、拉通知栏，才知道是谁在响。
 * 闹钟类提醒的标准做法是「通知 + 全屏 intent」：系统在屏幕关闭或锁屏时把这条
 * [Intent] 当成全屏提醒拉起，并把屏幕点亮。
 *
 * **权限不是配置项**：Android 14（API 34）起 `USE_FULL_SCREEN_INTENT` 默认只授予
 * 通话/闹钟类应用，普通应用需要用户在「特殊权限 → 全屏通知」里手动打开，
 * 否则系统会静默忽略全屏 intent（降级成一条普通通知，声音照响、屏幕不亮）。
 * 所以设置页有对应入口，见 [ImportantNotify.canFullScreen]。
 *
 * **和胶囊的分工**：屏幕亮着、用户正在别的应用里时，这条路不会走 —— 系统只在锁屏/息屏
 * 时才用全屏 intent，亮屏状态下依然是横幅 + 胶囊（不打断用户手上的事）。
 *
 * 页面本身只做三件事：说清是谁在响、响了多久、怎么停。不做「查看详情」以外的导航，
 * 免得用户在锁屏上被带进一个需要解锁的深页面。
 */
class AlarmActivity : BaseActivity() {

    private var nameView: TextView? = null
    private var excerptView: TextView? = null
    private var clockView: TextView? = null

    /** 每秒刷新「已响多久」；以 [AlarmRing] 里记的开始时刻为准，重建页面也不会从 00:00 重算 */
    private val ticker = object : Runnable {
        override fun run() {
            clockView?.text = elapsed()
            clockView?.postDelayed(this, 1_000)
        }
    }

    /**
     * 铃声在别处停了（通知上的按钮、划掉通知、胶囊红点）就把这一页一起收掉 ——
     * 留一个写着「响铃中」的全屏页而声音已经没了，比黑屏更让人困惑。
     */
    private val onRingChanged: () -> Unit = {
        if (AlarmRing.ringing) bindContent() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏上显示 + 点亮屏幕。manifest 里也声明了同一对属性，这里再设一次是因为
        // 部分 ROM 只在代码设置时才生效（Activity#setShowWhenLocked 是官方推荐写法）
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // 响铃期间不自动熄屏：用户抬眼就要能看到「谁在响」和停止键
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(buildUi())
        // 父类默认把状态栏图标设成深色（给白底页面用的），这一页是深色底，必须换回浅色
        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )

        AlarmRing.addListener(onRingChanged)
        clockView?.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        // 这一页本身就写着「正在响铃」还带停止键，胶囊再浮上来只会压住标题
        AlarmRing.onRingCardVisible(this, true)
        clockView?.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        clockView?.removeCallbacks(ticker)
        // 页面被别的东西盖住（比如用户按了 Home）时把胶囊还回去，提醒才不会断
        AlarmRing.onRingCardVisible(this, false)
    }

    override fun onDestroy() {
        clockView?.removeCallbacks(ticker)
        AlarmRing.removeListener(onRingChanged)
        super.onDestroy()
    }

    /** 返回键等同于「别响了」：这一页没有别的内容可退，退出就是不想被吵 */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        stopAndClose()
    }

    // ---------------- 布局 ----------------

    private fun buildUi(): View {
        val subject = AlarmRing.current

        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(28), dp(26), dp(20))
        }
        root.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 顶部：铃铛 + 「重要短信提醒」。这行是给「睡醒一睁眼」看的，所以小而克制，
        // 真正要看清的是下面那个规则名
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(PathIconView(this@AlarmActivity).apply {
                icon = AppIcons.bell()
                iconColor = HINT_TEXT
                layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                    marginEnd = dp(7)
                }
            })
            addView(TextView(this@AlarmActivity).apply {
                text = "重要短信提醒"
                textSize = 13f
                setTextColor(HINT_TEXT)
            })
        })

        // 中部：规则名 / 摘要 / 已响时长，整体垂直居中
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        nameView = TextView(this).apply {
            text = ruleName(subject?.title)
            textSize = 27f
            setTextColor(TITLE_TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        middle.addView(nameView, wrap())

        excerptView = TextView(this).apply {
            text = subject?.excerpt.orEmpty()
            textSize = 16f
            setTextColor(BODY_TEXT)
            gravity = Gravity.CENTER
            setLineSpacing(dp(6).toFloat(), 1f)
            layoutParams = wrap().apply { topMargin = dp(16) }
        }
        middle.addView(excerptView)

        clockView = TextView(this).apply {
            text = elapsed()
            textSize = 13f
            setTextColor(HINT_TEXT)
            gravity = Gravity.CENTER
            layoutParams = wrap().apply { topMargin = dp(14) }
        }
        middle.addView(clockView)

        column.addView(middle)

        // 底部：停止键。62dp 高、20dp 圆角 —— 半睡半醒也能一次点中
        column.addView(TextView(this).apply {
            text = "停止响铃"
            textSize = 19f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedBg(STOP_RED, 20)
            isClickable = true
            isFocusable = true
            contentDescription = "停止响铃"
            pressScale(0.97f)
            setOnClickListener { stopAndClose() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))

        // 「查看这条记录」会离开锁屏（系统要解锁才能进 App），所以只做次要入口，
        // 主操作永远是上面那个停止键
        column.addView(TextView(this).apply {
            text = "查看这条记录"
            textSize = 14f
            setTextColor(HINT_TEXT)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            pressScale(0.96f)
            setOnClickListener { openRecord() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(4)
        })

        return root
    }

    /** 只刷新文案，不重建视图（换短信时用） */
    private fun bindContent() {
        val subject = AlarmRing.current ?: return
        nameView?.text = ruleName(subject.title)
        excerptView?.text = subject.excerpt
        clockView?.text = elapsed()
    }

    private fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

    /**
     * `title` 形如「重要短信 · 银行动账」。顶部已经写了「重要短信提醒」，
     * 这里只留规则名，避免同一句话在一屏里出现两遍。
     */
    private fun ruleName(title: String?): String {
        val t = title.orEmpty()
        return t.substringAfter("· ", t).ifBlank { "重要短信" }
    }

    /**
     * 「已响 00:09 / 共 01:00」。总时长也写出来：半睡半醒时看一眼就知道这条还要响多久，
     * 自测时也不用再回去翻规则设的是哪一档。
     */
    private fun elapsed(): String = AlarmRing.current?.elapsedText().orEmpty()

    // ---------------- 动作 ----------------

    /** 停声音 + 撤通知 + 收页面，三件事一起做（漏一件就会留下「响铃中」的残影） */
    private fun stopAndClose() {
        stopRinging(this)
        finish()
    }

    private fun openRecord() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_IMPORTANT)
        })
        finish()
    }
}
