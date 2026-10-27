package phoenix.smsnotify.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 所有页面的基类，统一处理 edge-to-edge。
 *
 * targetSdk 35+ 起系统强制全屏铺满（edge-to-edge），内容会画到状态栏/导航栏底下，
 * 而状态栏是盖在最上层的独立窗口，它占的那一条（本机 136px）触摸不会派发给 App。
 * 如果控件画进了这一条，就会出现“看得见、点不到”的假象。
 *
 * 官方推荐做法 = 背景贴边、内容避让：
 *   1. 根容器铺满全屏，可以让背景色/图片一直画到状态栏底下；
 *   2. 内容层吃一次 insets padding，永远不要手写状态栏高度。
 *
 * 本类把第 2 步固化了，页面只要继承它并调用 [pageRoot] 即可，无需再关心系统栏尺寸：
 *
 *     class FooActivity : BaseActivity() {
 *         override fun onCreate(savedInstanceState: Bundle?) {
 *             super.onCreate(savedInstanceState)
 *             val root = pageRoot()          // 已含系统栏自动避让
 *             root.addView(...)
 *             setContentView(root)
 *         }
 *     }
 *
 * 表单页（可能弹出输入法）额外设置 [avoidIme] = true，并放进可滚动容器，否则键盘会盖住底部按钮。
 *
 * 本类只负责「页面骨架 + 系统栏」。其余公共 UI 都在 ui 包里，任何功能页面都能直接用：
 *  - UiFx.kt —— 按压/水波纹、dp 换算
 *  - Widgets.kt —— 返回箭头、开关
 *  - Icons.kt —— 描边图标控件与图标路径
 *  - Theme.kt —— 全局配色
 *  - BottomNav.kt —— 底部导航
 *
 * 主页是「一个 Activity + 底部导航 + 多个页面视图」：页面视图（如 DeliveryPageView）
 * 不是 Activity 的子类，但同样需要 [dp] / [pageRoot] / [pageTitle] / [iconButton]。
 * 因此这四个成员是 internal（本模块内可见）而不是 protected —— 让页面视图直接复用，
 * 避免每个页面各写一套尺寸，视觉规格也就不会走样。
 */
open class BaseActivity : Activity() {

    /** 是否让内容避让输入法。表单页在 setContentView 之前设 true */
    protected var avoidIme = false

    // ---------------- 统一的视觉规格（主页与子页面必须一致） ----------------

    /** 页面标题字号。首页标题和各子页标题共用这一个值 */
    protected val titleSp = 22f

    /** 标题行高度；返回箭头、右侧图标按钮都撑满这个高度 */
    protected val titleRowDp = 40

    /** 标题右侧图标按钮的边长（触摸区，字形居中） */
    protected val iconButtonDp = 40

    /** dp → px */
    internal fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /**
     * 页面根容器：竖向 LinearLayout，统一留白（左右 16dp、上下 8dp）。
     * 留白只是视觉间距，状态栏/导航栏高度由 [setContentView] 自动叠加。
     */
    internal fun pageRoot(topDp: Int = 8, bottomDp: Int = 8): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(16), dp(topDp), dp(16), dp(bottomDp))
        }

    /**
     * 表单页专用：把 [pageRoot] 包进可滚动容器。
     * 结构是 FrameLayout(吃 insets padding) → ScrollView(真正被压缩) → 内容(WRAP_CONTENT)，
     * 这样键盘弹出时滚动区域会真的变矮，而不是被键盘盖住。
     */
    protected fun scrollPage(content: View): View =
        FrameLayout(this).apply {
            addView(
                android.widget.ScrollView(this@BaseActivity).apply {
                    isFillViewport = true
                    addView(content, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

    /**
     * 统一的页面标题行：可选返回箭头 + 标题。
     * 主页和所有子页面都用它，尺寸就不会各写各的（之前主页 20sp/32dp、子页 22sp/40dp，看起来一大一小）。
     *
     * 标题带 weight=1，因此外部可以继续往右侧 addView 图标按钮，图标会自动靠右。
     */
    internal fun pageTitle(title: String, withBack: Boolean = true): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBaselineAligned(false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(titleRowDp)
            )
        }
        if (withBack) {
            row.addView(BackChevronView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(titleRowDp))
                setOnClickListener { finish() }
                pressScale()
            })
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = titleSp
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        })
        return row
    }

    /** 标题行右侧的图标按钮：固定方形触摸区 + 圆形水波纹 + 按压缩放 */
    internal fun iconButton(glyph: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = titleSp
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(dp(iconButtonDp), dp(titleRowDp)).apply {
                marginStart = dp(4)
            }
            circleRipple()
            pressScale()
            setOnClickListener { onClick() }
        }

    override fun setContentView(view: View) {
        super.setContentView(view)
        applySystemBarInsets(view)
        // 必须等 DecorView 创建后再设置，否则 window.insetsController 为 null
        useDarkStatusBarIcons()
    }

    private fun applySystemBarInsets(view: View) {
        // 记录页面自己写的留白，作为基准值
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom

        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val bottom = if (avoidIme) {
                    // 键盘弹出时按键盘高度避让，收起后回到导航栏高度
                    maxOf(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
                } else bars.bottom
                v.setPadding(
                    baseLeft + bars.left,
                    baseTop + bars.top,
                    baseRight + bars.right,
                    baseBottom + bottom
                )
            } else {
                // API 30 以下没有 ime insets，键盘由 adjustResize 自己处理
                @Suppress("DEPRECATION")
                v.setPadding(
                    baseLeft + insets.systemWindowInsetLeft,
                    baseTop + insets.systemWindowInsetTop,
                    baseRight + insets.systemWindowInsetRight,
                    baseBottom + insets.systemWindowInsetBottom
                )
            }
            insets
        }
        view.requestApplyInsets()
    }

    /** 状态栏图标用深色（白底页面的正确选择） */
    protected fun useDarkStatusBarIcons() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }
}
