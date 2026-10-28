package phoenix.smsnotify.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.TextView

/**
 * 表单里的两种常见文字：字段标签 [label] 和小字说明 [note]。
 *
 * 原来 `RuleEditActivity` 和 `ImportantRuleEditActivity` 各写了一份，字号/颜色/间距
 * 还在慢慢走样（一个 note 的顶部间距是 6px，另一个是 10dp）。收在这里之后，
 * 两个编辑页的视觉规格由这一处决定。
 *
 * 都是 [Context] 的扩展函数：
 *  - Activity（本身就是 Context）里直接 `label("规则名称")`；
 *  - 页面视图不是 Context（`DeliveryPageView` 这类是 LinearLayout），写 `act.label(...)`。
 */

/** 字段标签：14sp 灰字，上方留 24dp，和上一个字段拉开距离 */
fun Context.label(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 14f
    setTextColor(AppColor.muted)
    setPadding(0, dpi(24), 0, dpi(8))
}

/**
 * 小字说明：12sp 黑色加粗，字号比正文小一档但更醒目。
 *
 * 专门用来解释「代码里的固定行为」——这类规则在界面上看不见（比如取件码前缀后面
 * 允许几个字符），用户只能靠这几句话知道它存在。
 *
 * @param topDp 与上方控件的间距。默认 8dp，紧跟在输入框下面；
 *              跟在整块「设置行」后面时传 10dp，避免贴着行底。
 */
fun Context.note(text: String, topDp: Int = 8): TextView = TextView(this).apply {
    this.text = text
    textSize = 12f
    setTextColor(Color.BLACK)
    setTypeface(null, Typeface.BOLD)
    setLineSpacing(dpi(2).toFloat(), 1f)
    setPadding(0, dpi(topDp), 0, 0)
}
