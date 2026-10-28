package phoenix.smsnotify.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
        addView(TextView(ctx))
        addView(TextView(ctx))
    })
    addView(ToggleView(ctx).apply {
        contentDescription = "启用该规则"
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dpi(28)
        ).apply { marginStart = ctx.dpi(12) }
    })
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
