package phoenix.smsnotify.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * 项目里的自绘控件：本文件放「有交互状态」的控件，纯图形图标在 Icons.kt。
 *
 *  - [BackChevronView]：页面标题行的返回箭头
 *  - [ToggleView]：规则列表右侧的绿/白开关
 *
 * 它们都不是「一个控件一个文件」的独立组件库，而是为这个 App 的视觉规格定制的小控件，
 * 放在一起方便对照着调尺寸和颜色。
 */

/**
 * 返回箭头（chevron）。
 *
 * 为什么不直接用 "<" 字符：小于号的夹角由字体决定（约 60°，且粗细随字号变），
 * 而主流 App（Material / iOS）的返回箭头是 90° 开口、2dp 描边、可调的独立图形。
 * 这里用 Canvas 画两段线，角度、臂长、线宽都能调：
 *
 *     BackChevronView(this).apply {
 *         openAngleDeg = 90f    // 开口角度
 *         armLengthDp = 10f     // 两条臂的长度
 *         strokeWidthDp = 2f
 *     }
 */
class BackChevronView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 开口角度：两条臂之间的夹角，主流 App 约 90° */
    var openAngleDeg: Float = 90f
        set(value) { field = value; invalidate() }

    /** 单条臂的长度 */
    var armLengthDp: Float = 10f
        set(value) { field = value; invalidate() }

    var strokeWidthDp: Float = 2f
        set(value) { field = value; invalidate() }

    var arrowColor: Int = 0xFF212121.toInt()
        set(value) { field = value; paint.color = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = arrowColor
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val path = Path()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "返回"
        // 无边界水波纹，点击有反馈
        val out = TypedValue()
        if (context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, out, true
            )
        ) {
            context.getDrawable(out.resourceId)?.let { background = it }
        }
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.strokeWidth = dp(strokeWidthDp)

        val half = Math.toRadians(openAngleDeg / 2.0)
        val arm = dp(armLengthDp)
        val dx = (arm * cos(half)).toFloat()
        val dy = (arm * sin(half)).toFloat()

        // 图形在触摸区内居中（触摸区比图形大，给人手指留余量）
        val tipX = (width - dx) / 2f
        val cy = height / 2f

        path.reset()
        path.moveTo(tipX + dx, cy - dy)
        path.lineTo(tipX, cy)
        path.lineTo(tipX + dx, cy + dy)
        canvas.drawPath(path, paint)
    }
}

/**
 * 自绘开关（iOS 风格胶囊）。
 *
 * 不用系统 `Switch` 的原因：它的轨道尺寸、圆角、滑块内边距都由主题样式决定，
 * 不同 ROM 长得不一样，很难精确对上设计稿（绿 #00C375 轨道 + 纯白滑块）。
 * 这里全部自己画，宽高比固定 2:1，颜色可调。
 *
 * - 开启：绿色轨道 + 白色滑块
 * - 关闭：白色轨道（带 1dp 浅灰描边，否则白底页面上看不见）+ 白色滑块
 */
class ToggleView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    /** 开启时的轨道颜色 */
    var onColor = 0xFF00C375.toInt()
    /** 关闭时的轨道颜色 */
    var offColor = Color.WHITE
    /** 关闭时的轨道描边颜色 */
    var offBorderColor = 0xFFD8D8DE.toInt()
    /** 滑块颜色 */
    var thumbColor = Color.WHITE

    /** 用户点按后回调；程序调用 [setChecked] 不会触发 */
    var onCheckedChange: ((Boolean) -> Unit)? = null

    private var checked = false
    /** 0f = 关闭，1f = 开启，动画过程中是小数 */
    private var fraction = 0f
    private var animator: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            setChecked(!checked, animate = true)
            onCheckedChange?.invoke(checked)
        }
    }

    val isChecked: Boolean get() = checked

    /**
     * 设置开关状态。
     * @param animate 列表复用条目时传 false，避免滚动时看到一堆滑块乱动
     */
    fun setChecked(value: Boolean, animate: Boolean = true) {
        val target = if (value) 1f else 0f
        if (value == checked && fraction == target) return
        checked = value
        animator?.cancel()
        if (!animate) {
            fraction = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 高度优先（布局里一般给 28dp），宽度按 2:1 算出来，避免胶囊被拉成椭圆
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            dp(28f).toInt()
        }
        setMeasuredDimension(h * 2, h)
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val border = dp(1f)
        rect.set(border, border, width - border, h - border)
        val radius = rect.height() / 2f

        // 轨道：白 → 绿 按 fraction 插值
        trackPaint.color = blend(offColor, onColor, fraction)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        // 关闭（或正在回到关闭）时叠一圈浅灰描边，白轨道在白底上才看得见
        if (fraction < 0.999f) {
            borderPaint.color = offBorderColor
            borderPaint.strokeWidth = border
            borderPaint.alpha = ((1f - fraction) * 255).toInt()
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
        }

        // 滑块：左右各留 2dp 行程边距
        val pad = dp(2f)
        val thumbR = (rect.height() - dp(4f)) / 2f
        val minX = rect.left + pad + thumbR
        val maxX = rect.right - pad - thumbR
        val cx = minX + (maxX - minX) * fraction
        thumbPaint.color = thumbColor
        canvas.drawCircle(cx, h / 2f, thumbR, thumbPaint)

        // 关闭态轨道也是白的，滑块会和轨道糊在一起，补一圈浅灰描边把轮廓勾出来
        if (fraction < 0.999f) {
            thumbBorderPaint.color = offBorderColor
            thumbBorderPaint.strokeWidth = dp(1f)
            thumbBorderPaint.alpha = ((1f - fraction) * 255).toInt()
            canvas.drawCircle(cx, h / 2f, thumbR - dp(0.5f), thumbBorderPaint)
        }
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // 让 TalkBack / uiautomator 能读到"已开启/已关闭"
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = checked
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        if (t <= 0f) return from
        if (t >= 1f) return to
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
