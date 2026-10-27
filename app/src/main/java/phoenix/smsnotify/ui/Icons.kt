package phoenix.smsnotify.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 描边图标控件 —— 底部导航、标题栏、空状态都用它。
 *
 * 项目没有 AndroidX，也就没有 `MaterialButton` / vector drawable 那一套；
 * 用 emoji 或字符当图标又会随系统字体变形（同一行里粗细、基线都不一致）。
 * 所以统一在这里用 Canvas 画：图形定义在 **24x24 视口**里（跟 Material 图标同规格），
 * 尺寸随便给，`onDraw` 里按比例缩放，线宽保持物理 1.7dp 不变粗不变细。
 *
 *     val icon = PathIconView(this).apply {
 *         icon = AppIcons.parcel()
 *         iconColor = AppColor.blue
 *     }
 *     icon.layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
 *
 * 只支持描边（stroke），够用且风格统一；要填充色块图标的话另开一个 `fill = true` 开关再加。
 */
class PathIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 24x24 坐标系下的图形 */
    var icon: Path? = null
        set(value) {
            field = value
            invalidate()
        }

    var iconColor: Int = AppColor.hint
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    /** 线宽（dp）。图标只有 24dp，1.7dp 是「看着不糊、又不显胖」的经验值 */
    var strokeWidthDp: Float = 1.7f
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = iconColor
    }

    override fun onDraw(canvas: Canvas) {
        val path = icon ?: return
        // 扣掉内边距后的可用区域：这样 40dp 的触摸区里可以用 padding 画出 22dp 的图标，
        // 而不用为了图标尺寸再套一层 FrameLayout。
        val availW = width - paddingLeft - paddingRight
        val availH = height - paddingTop - paddingBottom
        val side = min(availW, availH).toFloat()
        if (side <= 0f) return
        val scale = side / VIEWPORT

        canvas.save()
        canvas.translate(
            paddingLeft + (availW - side) / 2f,
            paddingTop + (availH - side) / 2f
        )
        canvas.scale(scale, scale)
        // 线宽在缩放后会被一起放大，这里反除回去，保证任何尺寸下都是 strokeWidthDp 那么粗
        paint.strokeWidth = strokeWidthDp * resources.displayMetrics.density / scale
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private companion object {
        const val VIEWPORT = 24f
    }
}

/**
 * 图标图形库：每个方法返回一条 24x24 视口的 [Path]。
 *
 * 新增图标就加一个方法，复用 [PathIconView]，不要在每个页面里各画一套。
 */
object AppIcons {

    /**
     * 快递包裹：箱体外框 + 盖子接缝 + 一段封箱胶带。
     *
     *      ┌────┬──────┐
     *      │    │      │   ← 盖子：竖的是胶带
     *      ├────┴──────┤   ← 接缝
     *      │           │
     *      └───────────┘
     *
     * 两种画法都试过，最后用这个：
     *  - 立方体斜视：24dp 下斜角糊成一团，像块宝石；
     *  - 盖子单独画一个矩形：盖子只有 4 个单位高，上下两条描边在小尺寸下会粘成实心块。
     */
    fun parcel(): Path = Path().apply {
        // 箱体（圆角矩形，圆角跟描边的圆头接得上）
        addRoundRect(
            RectF(3.4f, 4.0f, 20.6f, 20.2f), 2.4f, 2.4f, Path.Direction.CW
        )

        // 盖子的接缝
        moveTo(3.4f, 9.0f)
        lineTo(20.6f, 9.0f)

        // 封箱胶带：从顶边拉下来，止于接缝
        moveTo(12f, 4.0f)
        lineTo(12f, 9.0f)
    }

    /**
     * 提醒铃铛：铃身 + 钟口弧线 + 下方的铃舌。
     */
    fun bell(): Path = Path().apply {
        moveTo(12f, 2.9f)
        cubicTo(8.9f, 2.9f, 6.4f, 5.4f, 6.4f, 8.5f)
        lineTo(6.4f, 12.3f)
        lineTo(4.7f, 16.1f)
        cubicTo(4.4f, 16.7f, 4.8f, 17.3f, 5.5f, 17.3f)
        lineTo(18.5f, 17.3f)
        cubicTo(19.2f, 17.3f, 19.6f, 16.7f, 19.3f, 16.1f)
        lineTo(17.6f, 12.3f)
        lineTo(17.6f, 8.5f)
        cubicTo(17.6f, 5.4f, 15.1f, 2.9f, 12f, 2.9f)
        close()

        // 铃舌
        moveTo(9.9f, 20.2f)
        cubicTo(10.3f, 21.0f, 11.1f, 21.5f, 12f, 21.5f)
        cubicTo(12.9f, 21.5f, 13.7f, 21.0f, 14.1f, 20.2f)
    }

    /**
     * 设置：6 齿齿轮 + 中心圆孔。
     *
     * 齿形用三角函数参数化（齿顶圆弧 + 齿根圆弧），不手写坐标 —— 手写 6 组点容易
     * 齿距不均，改齿数还得重算一遍。
     *
     * 只做 6 个齿：图标实际显示 22dp，齿越多齿间缝隙越窄，1.7dp 的线宽会把缝涂死，
     * 远看就是一坨黑饼。齿深也刻意留够（外 10.2 / 内 6.8）。
     */
    fun gear(): Path = Path().apply {
        val teeth = 6
        val cx = 12f
        val cy = 12f
        // 齿轮直径刻意比「选择」的方框略大一点点就行：圆形在视觉上比同宽度的方形小，
        // 差太多（比如 20 对 17）摆在一起会明显一大一小。
        val outerR = 9.2f
        val innerR = 6.2f
        val pitch = (2 * PI / teeth).toFloat()
        val halfTop = pitch * 0.18f    // 齿顶半宽（角度）
        val flank = pitch * 0.32f      // 齿顶到齿根的角位移

        fun x(r: Float, a: Float) = cx + r * cos(a)
        fun y(r: Float, a: Float) = cy + r * sin(a)

        for (i in 0 until teeth) {
            // 从正上方开始，图形天然左右对称
            val a = -PI.toFloat() / 2 + i * pitch
            if (i == 0) moveTo(x(outerR, a - halfTop), y(outerR, a - halfTop))
            else lineTo(x(outerR, a - halfTop), y(outerR, a - halfTop))
            lineTo(x(outerR, a + halfTop), y(outerR, a + halfTop))      // 齿顶
            lineTo(x(innerR, a + flank), y(innerR, a + flank))          // 齿侧
            lineTo(x(innerR, a + pitch - flank), y(innerR, a + pitch - flank))  // 齿根
        }
        close()

        // 中心孔：轴孔，也让齿轮中间不至于糊成实心
        addCircle(cx, cy, 2.9f, Path.Direction.CW)
    }

    /**
     * 帮助：圆环 + 问号。
     *
     * 问号用两段三次贝塞尔勾出弯钩，下方的点画成「零长度线段」—— 圆头笔帽会把 0 长度
     * 线段渲染成直径正好等于线宽的实心点，不用另外算小圆半径，也不会「点比线粗」。
     *
     * 试过去掉圆环、让问号吃满视口的画法：问号确实更清楚，但独一个弯钩摆在齿轮、选择框
     * 旁边显得偏轻、不成一组，所以还是保留圆环 —— 环本身就是"这是帮助入口"的语义，
     * 里面那个问号小一点也够用。
     */
    fun help(): Path = Path().apply {
        addCircle(12f, 12f, 8.6f, Path.Direction.CW)

        // 弯钩：左中起笔 → 绕过顶部 → 收回到中间，再向下弯进竖笔
        moveTo(9.5f, 9.6f)
        cubicTo(9.5f, 7.7f, 10.7f, 6.6f, 12.1f, 6.6f)
        cubicTo(13.6f, 6.6f, 14.7f, 7.7f, 14.7f, 9.0f)
        cubicTo(14.7f, 10.4f, 13.8f, 11.1f, 12.6f, 11.9f)
        cubicTo(12.0f, 12.4f, 11.9f, 13.0f, 11.9f, 13.7f)

        // 点
        moveTo(11.9f, 16.8f)
        lineTo(11.9f, 16.9f)
    }

    /**
     * 多选：圆角方框 + 一个勾。
     *
     * 之前标题栏用的是字符「☑」，它跟旁边的齿轮一样，字形由系统字体决定：
     * 大小、粗细、基线都不受控，和自绘图标摆在一起明显不是一套。
     */
    fun select(): Path = Path().apply {
        addRoundRect(RectF(3.6f, 3.6f, 20.4f, 20.4f), 4.6f, 4.6f, Path.Direction.CW)
        moveTo(8.2f, 12.2f)
        lineTo(11.0f, 15.0f)
        lineTo(16.0f, 9.3f)
    }

    /** 关闭（退出多选）：两条对角线 */
    fun close(): Path = Path().apply {
        moveTo(7.2f, 7.2f)
        lineTo(16.8f, 16.8f)
        moveTo(16.8f, 7.2f)
        lineTo(7.2f, 16.8f)
    }
}
