package phoenix.smsnotify.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * 通用的「点按反馈」：按压缩放 + 水波纹，和任何具体页面无关，所以放在公共的 ui 模块。
 *
 * 都是 [View] 的扩展函数，直接 `button.pressScale()` / `row.rowRipple()` 即可；
 * 在 `apply { }` 里写 `pressScale()` 也行（隐式接收者是 View 本身）。
 *
 * 为什么用 StateListAnimator 而不是 OnTouchListener 做按压缩放：
 * 列表条目自己写了 OnTouchListener 处理左滑，用 touch 监听会把滑动手势吃掉。
 */

/** 水波纹颜色（品牌蓝，透明度约 18%） */
const val RIPPLE_COLOR = 0x2E1976D2

/**
 * 按压缩放反馈：按下缩到 [pressedScale]，松开带一点回弹。
 * 用 StateListAnimator，因此不会和既有的 OnTouchListener / 点击事件抢手势。
 */
fun View.pressScale(pressedScale: Float = 0.94f): View {
    val down = ObjectAnimator.ofPropertyValuesHolder(
        this,
        PropertyValuesHolder.ofFloat(View.SCALE_X, pressedScale),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, pressedScale)
    ).apply {
        duration = 90
        interpolator = DecelerateInterpolator()
    }
    val up = ObjectAnimator.ofPropertyValuesHolder(
        this,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f)
    ).apply {
        duration = 170
        interpolator = OvershootInterpolator(1.6f)
    }
    stateListAnimator = StateListAnimator().apply {
        addState(intArrayOf(android.R.attr.state_pressed), down)
        addState(intArrayOf(), up)
    }
    return this
}

/** 圆形水波纹，用于方形图标按钮。会替换 background（这类按钮本来也没有背景） */
fun View.circleRipple(color: Int = RIPPLE_COLOR): View {
    val mask = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
    }
    background = RippleDrawable(ColorStateList.valueOf(color), null, mask)
    return this
}

/**
 * 矩形水波纹，用于整行可点条目。
 * 画在 foreground 上而不是替换 background，这样条目自身的白底/线条不会被冲掉。
 */
fun View.rowRipple(color: Int = RIPPLE_COLOR, cornerDp: Int = 0): View {
    val radius = cornerDp * resources.displayMetrics.density
    val mask = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Color.WHITE)
    }
    foreground = RippleDrawable(ColorStateList.valueOf(color), null, mask)
    return this
}
