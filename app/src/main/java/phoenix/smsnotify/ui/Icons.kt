package phoenix.smsnotify.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

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
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val scale = side / VIEWPORT

        canvas.save()
        canvas.translate((width - side) / 2f, (height - side) / 2f)
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
}
