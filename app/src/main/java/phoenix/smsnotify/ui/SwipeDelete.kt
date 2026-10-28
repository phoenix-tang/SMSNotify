package phoenix.smsnotify.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 「左滑露出删除按钮」的行容器。
 *
 * 两个列表用的是同一种交互，之前这些逻辑只存在于快递列表（ListView + BaseAdapter 复用）里：
 *  - 快递取件记录：ListView，View 会被复用
 *  - 重要短信命中记录：ScrollView 里动态 addView，不复用
 *
 * 差别只在外层怎么刷新，所以手势、覆盖式按钮、退场动画全部收在这里，两边都只负责：
 *  1) 造好「前景」内容（文字、图标、点击/长按）；
 *  2) 在 [bind] 的 `onDelete` 里删掉数据并刷新列表。
 *
 * 视觉约定：条目本身不动，红色删除按钮从右缘滑入**覆盖**在右侧。
 * 整行左移那种做法会让长文本在滑动过程中不断重排、抖动。
 *
 * 用法：
 * ```
 * val row = SwipeRow(ctx, front).apply {
 *     bind(group, key = record.id, open = group.isOpen(record.id)) {
 *         Store.remove(record.id); refresh()
 *     }
 * }
 * ```
 */
class SwipeRow(
    context: Context,
    /** 条目本身：内容、背景、点击与长按都由调用方决定 */
    val front: View,
    /** 删除按钮宽度 */
    private val widthDp: Int = 56,
    private val label: String = "删除"
) : FrameLayout(context) {

    val deleteButton: Button = context.swipeDeleteButton(label) {}.apply {
        translationX = px(widthDp).toFloat()
        layoutParams = FrameLayout.LayoutParams(
            px(widthDp), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END
        )
    }

    private var group: SwipeGroup? = null
    private var key: Any? = null

    /** 正在播退场动画：期间的重复点击直接忽略，否则会删两次 */
    private var deleting = false

    // 手势状态
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var downAt = 0L
    private var dragging = false

    /** 收起时按钮完全推出右缘 —— 靠父容器的裁剪藏起来，不用改可见性 */
    private val hiddenX: Float get() = px(widthDp).toFloat()

    /** 拖动超过这个距离才判定为「横向滑动」，否则算点击 */
    private val touchSlop: Int get() = px(8)

    /** dp → px。类里同时有 Context 和 View 两套扩展，写明走 Context 那套 */
    private fun px(v: Int): Int = context.dpi(v)

    init {
        addView(front, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        addView(deleteButton)   // 后添加 → 画在条目上层
    }

    /**
     * 绑上手势与删除动作；同时把被上一轮动画改过的属性复位。
     *
     * ListView 每次 [android.widget.BaseAdapter.getView] 都会调用，所以这里必须能重复执行。
     *
     * @param group    同一列表共用的展开状态（保证同时只有一行展开）
     * @param key      这条记录的标识，用来判断「当前展开的是不是它」
     * @param open     这条记录此刻是否处于展开态（复用时按数据恢复）
     * @param isEnabled 返回 false 时手势不生效，例如快递列表的多选模式
     * @param onDelete 点删除按钮、退场动画播完之后调用：删数据 + 刷新
     */
    fun bind(
        group: SwipeGroup,
        key: Any,
        open: Boolean,
        isEnabled: () -> Boolean = { true },
        onDelete: () -> Unit
    ) {
        this.group = group
        this.key = key

        deleteButton.text = label
        deleteButton.isClickable = true
        deleteButton.alpha = 1f
        val lp = deleteButton.layoutParams as? FrameLayout.LayoutParams
        if (lp != null && lp.width != px(widthDp)) {
            lp.width = px(widthDp)
            deleteButton.layoutParams = lp
        }
        deleteButton.translationX = if (open) 0f else hiddenX
        deleteButton.setOnClickListener { animateDelete(onDelete) }

        front.setOnTouchListener { _, ev -> onTouch(ev, isEnabled) }
    }

    // ---------------- 手势 ----------------

    private fun onTouch(ev: MotionEvent, isEnabled: () -> Boolean): Boolean {
        if (!isEnabled()) return false
        val g = group ?: return false
        val k = key ?: return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                startX = if (g.isOpen(k)) 0f else hiddenX
                downAt = SystemClock.elapsedRealtime()
                dragging = false
                deleteButton.animate().cancel()   // 手指接管，停掉上一次的回弹动画
                front.isPressed = true            // 手动切 pressed，触发前景水波纹
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging) {
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        dragging = true
                        front.isPressed = false
                        // 横向意图：别让外层 ScrollView / ListView 抢走这段位移
                        (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                        g.closeExcept(this)   // 同时只允许一条展开
                    } else if (abs(dy) > touchSlop) {
                        front.isPressed = false
                        return false          // 纵向意图 → 交给外层滚动
                    }
                }
                if (!dragging) return false

                deleteButton.translationX = (startX + dx).coerceIn(0f, hiddenX)
                // 露出过程同步淡入，滑入一点就有「红色浮出来」的层次感
                deleteButton.alpha = 0.4f + 0.6f * (1f - deleteButton.translationX / hiddenX)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                front.isPressed = false
                if (dragging) {
                    val open = deleteButton.translationX < hiddenX / 2
                    // 松手后带缓动收尾，而不是瞬间跳到终点
                    deleteButton.animate()
                        .translationX(if (open) 0f else hiddenX)
                        .alpha(1f)
                        .setDuration(if (open) 220L else 170L)
                        .setInterpolator(DecelerateInterpolator(1.8f))
                        .start()
                    if (open) g.opened(this, k) else g.closed(this)
                    return true
                }
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    // 没产生滑动：按按压时长分派点击 / 长按
                    if (SystemClock.elapsedRealtime() - downAt > 500) front.performLongClick()
                    else front.performClick()
                    return true
                }
                return false
            }
        }
        return false
    }

    /** 被别的行顶掉时用短动画收回 */
    internal fun closeAnimated(duration: Long = 150) {
        deleteButton.animate()
            .translationX(hiddenX)
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /** 立即收回（刷新列表时用，不要动画） */
    internal fun jumpToClosed() {
        deleteButton.animate().cancel()
        deleteButton.translationX = hiddenX
        deleteButton.alpha = 1f
    }

    // ---------------- 退场动画 ----------------

    /**
     * 分两段：
     *   1) 红色删除层从右往左铺满整行（130ms）
     *   2) 行高收到 0 + 淡出（190ms），下面的条目跟着上移
     *
     * 数据等动画结束才真正删，否则列表会在动画中途重排、动画白做。
     */
    private fun animateDelete(onFinished: () -> Unit) {
        if (deleting) return
        deleting = true
        // 只清展开状态，不动 translationX —— 删除层要保持在可见位置
        group?.closed(this)

        val rowH = height
        val lp = deleteButton.layoutParams as FrameLayout.LayoutParams
        val fullW = maxOf(width, lp.width)   // 行还没测量完时退回原宽度
        deleteButton.isClickable = false

        val expand = ValueAnimator.ofInt(lp.width, fullW)
        expand.duration = 130
        expand.interpolator = DecelerateInterpolator(1.5f)
        expand.addUpdateListener {
            lp.width = it.animatedValue as Int
            deleteButton.layoutParams = lp
            deleteButton.text = if (it.animatedFraction > 0.6f) "" else label
        }
        expand.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                deleteButton.text = ""
                if (rowH <= 0) {
                    finish(onFinished)
                    return
                }
                collapse(rowH, onFinished)
            }
        })
        expand.start()
    }

    /** 第二段：行高塌陷，制造「这条被抽走、下面往上补」的观感 */
    private fun collapse(fromHeight: Int, onFinished: () -> Unit) {
        val lp = layoutParams ?: FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, fromHeight
        )
        lp.height = fromHeight
        layoutParams = lp

        val anim = ValueAnimator.ofInt(fromHeight, 0)
        anim.duration = 190
        anim.interpolator = AccelerateInterpolator(1.4f)
        anim.addUpdateListener { a ->
            lp.height = a.animatedValue as Int
            layoutParams = lp          // 每帧重设 → 触发重新布局，下方条目逐帧上移
            alpha = 1f - a.animatedFraction * 0.85f
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                finish(onFinished)
            }
        })
        anim.start()
    }

    /** 复位被动画改过的属性，再交给调用方删数据（列表可能复用这个 View） */
    private fun finish(onFinished: () -> Unit) {
        deleting = false
        alpha = 1f
        layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT
        (deleteButton.layoutParams as? FrameLayout.LayoutParams)?.width = px(widthDp)
        deleteButton.translationX = hiddenX
        deleteButton.text = label
        deleteButton.isClickable = true
        onFinished()
    }
}

/**
 * 一组 [SwipeRow] 共享的展开状态：**同时只允许一行**露出删除按钮。
 *
 * 不放在行自己身上的原因：行会被复用（ListView），而「哪一条展开着」属于列表级状态，
 * 换绑数据时按 key 恢复，而不是按 View 恢复。
 */
class SwipeGroup {

    private var openRow: SwipeRow? = null
    private var openKey: Any? = null

    /** 这条记录是否正展开着 */
    fun isOpen(key: Any?): Boolean = key != null && key == openKey

    /** 是否有任意一行展开着 —— 点空白处收起时用 */
    val anyOpen: Boolean get() = openRow != null

    /** 收起当前展开的那一行 */
    fun close(animate: Boolean = true) {
        val row = openRow ?: return
        openRow = null
        openKey = null
        if (animate) row.closeAnimated() else row.jumpToClosed()
    }

    /** 展开另一行时，把当前这行收回 */
    internal fun closeExcept(row: SwipeRow) {
        if (openRow != null && openRow !== row) close(animate = true)
    }

    internal fun opened(row: SwipeRow, key: Any) {
        openRow = row
        openKey = key
    }

    internal fun closed(row: SwipeRow) {
        if (openRow !== row) return
        openRow = null
        openKey = null
    }
}
