package phoenix.smsnotify.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 列表行的公共骨架：两个规则列表（快递关键字规则 / 重要短信规则）原本各写了一份
 * 一模一样的 `buildRow()`。
 *
 * [ruleRow] 的结构固定为：
 *
 *     ┌──────────────────────────────┬──────┐
 *     │ 文字列（weight=1）            │ 开关 │
 *     │   第一行：名称                │      │
 *     │   第二行：摘要（灰、小一号）  │      │
 *     └──────────────────────────────┴──────┘
 *
 * 也正因为结构固定，两个 Adapter 的 `getView` 都是按下标取子 View
 * （`row.getChildAt(0)` 取文字列、`getChildAt(1)` 取开关），
 * 不额外建 holder，复用时直接落值。
 */
fun ruleRow(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(ctx.dpi(8), ctx.dpi(12), ctx.dpi(8), ctx.dpi(12))
    rowRipple(cornerDp = 8)
    addView(LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        // 两行的字号/字重/颜色在这里就位，Adapter 里只负责填文字和停用态
        addView(TextView(ctx).apply { setRowTitle("") })
        addView(TextView(ctx).apply { setRowSub("") })
    })
    addView(ToggleView(ctx).apply {
        contentDescription = "启用该规则"
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dpi(28)
        ).apply { marginStart = ctx.dpi(12) }
    })
}

// ---------------- 列表行的三档文字 ----------------
//
// 全站的列表行都是同一个套路：一行主信息（17sp 加粗）、一行副信息（13sp 灰）、
// 偶尔再加一行时间/计数（12sp 更浅）。以前每个 Adapter 各写一遍 textSize + setTextColor，
// 字号在 16 和 17 之间飘、停用态的灰也各用各的。这里定死三档。

/**
 * 第一行：17sp 加粗。
 *
 * @param dimmed 停用/已取件时整行变灰 —— 颜色由这里决定，调用方不要再 setTextColor，
 *               否则下次复用到启用的条目会残留灰色。
 */
fun TextView.setRowTitle(text: CharSequence, dimmed: Boolean = false): TextView = apply {
    this.text = text
    textSize = 17f
    setTypeface(null, android.graphics.Typeface.BOLD)
    setTextColor(if (dimmed) AppColor.muted else AppColor.text)
}

/** 第二行：13sp 次要灰，单行放不下就省略（列表行不做两行副信息） */
fun TextView.setRowSub(text: CharSequence, dimmed: Boolean = false): TextView = apply {
    this.text = text
    textSize = 13f
    setTextColor(if (dimmed) AppColor.muted else AppColor.subText)
    maxLines = 1
    ellipsize = android.text.TextUtils.TruncateAt.END
}

/**
 * 行末或行下的极小信息：时间、条数、「还没有命中记录」这类。
 *
 * @param color 默认最浅的灰；浅蓝底上（如「最新通知」栏）传 [AppColor.blueMuted]
 */
fun TextView.setRowMeta(text: CharSequence, color: Int = AppColor.muted): TextView = apply {
    this.text = text
    textSize = 12f
    setTextColor(color)
}

// ---------------- 卡片里的文字 ----------------
//
// 主页「重要短信提醒」的卡片是列表之外的另一种容器：表头一行小标签、下面是卡片自己的
// 主标题，再往下是行式内容。它比列表行大一号，但又不能直接用页面标题 —— 单独一档。

/**
 * 卡片主标题：18sp 加粗。比列表行（17sp）略大，一眼能看出「这是这一块的标题」。
 *
 * @param dimmed 卡片整体停用时变灰
 */
fun TextView.setCardTitle(text: CharSequence, dimmed: Boolean = false): TextView = apply {
    this.text = text
    textSize = 18f
    setTypeface(null, android.graphics.Typeface.BOLD)
    setTextColor(if (dimmed) AppColor.muted else AppColor.text)
}

/**
 * 卡片表头的小标签：12sp 加粗。
 *
 * 和 [setRowMeta] 同字号，但加粗 + 品牌蓝 —— 它是这一块的「栏目名」，不是补充信息。
 */
fun TextView.setCardHeader(text: CharSequence, color: Int = AppColor.blue): TextView = apply {
    this.text = text
    textSize = 12f
    setTypeface(null, android.graphics.Typeface.BOLD)
    setTextColor(color)
}

/**
 * 可展开卡片的箭头：▾ 已展开 / ▸ 已收起。
 *
 * 字号 13sp 对齐 [setRowSub]（箭头是行内的次要元素，不该跟标题同大），
 * 颜色跟着整张卡片是否停用走。
 */
fun TextView.setDisclosure(open: Boolean, dimmed: Boolean = false): TextView = apply {
    text = if (open) "▾" else "▸"
    textSize = 13f
    setTextColor(if (dimmed) AppColor.muted else AppColor.label)
}

/**
 * 过滤标签（「全部 / 待取 / 已取」）的选中态：选中蓝 + 加粗，未选中灰 + 常规。
 *
 * 刻意只改颜色和字重、不改字号：中文加粗会宽一点，换字号会让整行标签左右跳。
 */
fun TextView.setFilterState(selected: Boolean): TextView = apply {
    setTextColor(if (selected) AppColor.blue else AppColor.subText)
    setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
}

/**
 * 过滤标签本身：透明底的按钮，只借它的点击和按压反馈，视觉全靠 [setFilterState]。
 *
 * 不用 TextView + setOnClickListener 是因为按钮自带 stateListAnimator 的点击反馈，
 * 又不用再套一层 ripple。
 */
fun Context.filterTab(text: String): Button = Button(this).apply {
    this.text = text
    textSize = 14f
    isAllCaps = false
    setBackgroundColor(Color.TRANSPARENT)
    setPadding(dpi(8), dpi(6), dpi(8), dpi(6))
    rowRipple(cornerDp = 10)   // 透明底 + 前景水波纹，不破坏选中态文字颜色
    pressScale(0.96f)
    setFilterState(false)
}

/**
 * 发丝线：设置项之间、命中记录之间。
 *
 * @param topMarginDp 上方的间距。设置项本身已有 14dp 的上下内边距，所以传 0；
 *                    卡片内部的记录行之间传 7dp，让线和上一行隔开一点。
 */
fun Context.divider(topMarginDp: Int = 0): View = View(this).apply {
    setBackgroundColor(AppColor.divider)
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dpi(1)
    ).apply { topMargin = dpi(topMarginDp) }
}

/**
 * 表单里的「选一项」行：左侧项名 + 右侧当前值 + 箭头，整行可点。
 *
 * 结构固定为三个子 View，和 [ruleRow] 一样按位置取：
 *
 *     ┌────────────────────────────────┐
 *     │ 响铃时长              30 分钟  › │
 *     └────────────────────────────────┘
 *      getChildAt(0) 标题   (1) 当前值   (2) 箭头
 *
 * 值要随选择结果变化，所以调用方用 `row.getChildAt(1) as TextView` 拿到它再改文字。
 *
 * 项名写在这里、而不是在行上方再放一个 label：两者内容天然重复，
 * 一个灰底块配一行孤立的标题反而比「标题 + 值」的单行更散。
 *
 * @param title 左侧项名，也是这一项的说明
 * @param value 初始显示的值
 * @param onClick 点击整行的回调（通常是弹一个单选列表）
 */
fun Context.choiceRow(title: String, value: String, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(12), dpi(14), dpi(12), dpi(14))
        setBackgroundColor(AppColor.field)
        isClickable = true
        isFocusable = true

        addView(TextView(this@choiceRow).apply {
            text = title
            textSize = 15f
            setTextColor(AppColor.text)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        addView(TextView(this@choiceRow).apply {
            this.text = value
            textSize = 15f
            setTextColor(AppColor.blue)
        })
        addView(TextView(this@choiceRow).apply {
            text = "  ›"
            textSize = 15f
            setTextColor(AppColor.muted)
        })
        setOnClickListener { onClick() }
    }
