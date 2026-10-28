package phoenix.smsnotify.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * 界面上的「说明类」文字，按用途分成几档，规格只在这里定义。
 *
 * 之前每一处都是就地 `textSize = 13f; setTextColor(gray)`，于是同一个用途在不同页面
 * 出现 12sp / 13sp / 14sp 三种字号、muted / hint / subText 三种灰 —— 同一类信息
 * 看起来像不同层级。收进来之后，改一档就全站生效。
 *
 * 层级（都按白底算对比度）：
 *
 *     标题        BaseActivity.pageTitle      ~20sp 黑
 *     字段标签    [label]                      14sp #616161   6.2:1
 *     页面导语    [pageHint]                   13sp #757575   4.6:1
 *     正文块      [paragraph]                  13sp #424242  10.0:1
 *     小字说明    [note]                       12sp #757575   4.6:1
 *     页脚/版本   [footNote]                   12sp #8E8E93   3.0:1
 *
 * 「正文块」比「页面导语」颜色深、字号相同：前者是要读进去的段落（短信摘要、步骤），
 * 后者是一句话交代「这一页怎么用」，扫一眼就够。
 *
 * 非「说明文字」的控件文字另有归属：
 *  - 列表行 / 卡片 → Rows.kt（[setRowTitle] 等）
 *  - 输入框 → [applyInputStyle]
 *  - 按钮 → [actionButton]
 */

/** 字段标签：14sp 深灰常规。用户要靠它知道这一格填什么，不能用占位级的浅灰 */
fun Context.label(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 14f
    setTextColor(AppColor.label)
    setPadding(0, dpi(24), 0, dpi(8))
}

/**
 * 小字说明：12sp 浅灰常规字重，比 [label] 小一档、浅一档。
 *
 * 专门用来解释「代码里的固定行为」——这类规则在界面上看不见（比如取件码前缀后面
 * 允许几个字符），用户只能靠这几句话知道它存在。
 *
 * 这里**不加粗**：12sp 的中文加粗笔画会粘在一起，而且满黑的说明会比正文更抢眼，
 * 结果备注看起来像警告。需要强调时用字号/间距，或者只给关键字上 span。
 *
 * @param topDp 与上方控件的间距。默认 8dp，紧跟在输入框下面；
 *              跟在整块「设置行」后面时传 10dp，避免贴着行底。
 */
fun Context.note(text: String, topDp: Int = 8): TextView = TextView(this).apply {
    this.text = text
    textSize = 12f
    setTextColor(AppColor.subText)
    setLineSpacing(dpi(2).toFloat(), 1f)
    setPadding(0, dpi(topDp), 0, 0)
}

/**
 * 页面标题下的一句话导语：13sp、次要灰。
 *
 * 导语一律用同一档灰：它只是交代「这一页怎么用」，不是状态提示，
 * 单独给某一句上颜色会显得那句话比页面本身更重。
 *
 * @param topDp    与标题的距离，默认 8dp（标题自身已有下边距时可用 0~4）
 * @param bottomDp 与下方内容的距离，默认 8dp；紧跟列表时用 8，紧跟卡片时也用 8
 */
fun Context.pageHint(
    text: String,
    topDp: Int = 8,
    bottomDp: Int = 8
): TextView = TextView(this).apply {
    this.text = text
    textSize = 13f
    setTextColor(AppColor.subText)
    setLineSpacing(dpi(1).toFloat(), 1f)
    setPadding(0, dpi(topDp), 0, dpi(bottomDp))
}

/**
 * 空态提示：页面中央（或列表上方）那几行字，13sp 次要灰、居中、行距放宽。
 *
 * @param topDp 距顶部的距离。整页空态传 56~80 把它推到视觉中心；
 *              卡片内的小空态传 0，靠自己的 padding 定位。
 */
fun Context.emptyHint(text: String, topDp: Int = 56): TextView = TextView(this).apply {
    this.text = text
    textSize = 13f
    setTextColor(AppColor.subText)
    gravity = Gravity.CENTER
    setLineSpacing(dpi(3).toFloat(), 1f)
    setPadding(dpi(8), dpi(topDp), dpi(8), dpi(8))
}

/**
 * 多行正文块：13sp、[AppColor.body]、行距 3dp。
 *
 * 用于「关于」页的三节说明、保活指引弹窗里的段落、短信原文摘要 —— 共同点是
 * 用户真的会逐字读，所以比 [pageHint] 深一档。行数不限，需要截断的场合
 * 自己再设 `maxLines` / `ellipsize`。
 *
 * @param topDp 与上文的距离，默认 6dp
 */
fun Context.paragraph(text: CharSequence, topDp: Int = 6): TextView = TextView(this).apply {
    this.text = text
    textSize = 13f
    setTextColor(AppColor.body)
    setLineSpacing(dpi(3).toFloat(), 1f)
    setPadding(0, dpi(topDp), 0, 0)
}

/**
 * 页脚文字：版本号、应用名水印。12sp、[AppColor.hint]、水平居中。
 *
 * 这是全站最弱的一档，只用于「存在即可、不必细看」的信息。
 *
 * @param textSp 字号。设置页底部水印用默认 12；关于页版本号传 13 与页面其他行对齐
 */
fun Context.footNote(
    text: String,
    textSp: Float = 12f,
    topDp: Int = 6,
    bottomDp: Int = 4
): TextView = TextView(this).apply {
    this.text = text
    textSize = textSp
    setTextColor(AppColor.hint)
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(0, dpi(topDp), 0, dpi(bottomDp))
}

/** 区块小标题：14sp 加粗黑色。用于「关于」页的「做什么 / 数据放在哪」这类分节 */
fun Context.sectionTitle(text: String, topDp: Int = 22): TextView = TextView(this).apply {
    this.text = text
    textSize = 14f
    setTextColor(AppColor.text)
    setTypeface(null, android.graphics.Typeface.BOLD)
    setPadding(0, dpi(topDp), 0, 0)
}

/**
 * 表单里的校验/测试结果（「✘ 未匹配」「✔ 命中」这类）。
 *
 * 起初初始状态为空，颜色由调用方按结果覆盖（[AppColor.redText] / [AppColor.greenText]），
 * 所以这里只定字号和间距 —— 两个编辑页原本一个是 14sp、一个是 13sp，同一件事两种规格。
 */
fun Context.formFeedback(): TextView = TextView(this).apply {
    textSize = 13f
    setLineSpacing(dpi(2).toFloat(), 1f)
    setPadding(0, dpi(8), 0, dpi(8))
}

/**
 * 往 [formFeedback] 里填一条结果，颜色跟着结果走：
 * `true` 绿（命中/可用）、`false` 红（未命中/报错）、`null` 只写文字不改色（提个醒但不判定）。
 *
 * 之前每个编辑页都在三处 `setTextColor(redText/greenText)` 之间来回切，
 * 换个判定条件就要同时改文字和颜色，容易一处红一处绿。
 */
fun TextView.setFeedback(text: CharSequence, ok: Boolean? = null): TextView = apply {
    this.text = text
    when (ok) {
        true -> setTextColor(AppColor.greenText)
        false -> setTextColor(AppColor.redText)
        null -> Unit
    }
}

// ---------------- 表单输入框 ----------------

/**
 * 输入框的通用文字规格：15sp、[AppColor.text]、占位提示用 [AppColor.placeholder]。
 *
 * 全站三处表单（快递规则、重要短信规则、测试框）原本各写各的：名称 15sp、测试框 14sp、
 * 拼接器里的前后缀又单独一套 14sp 加粗，同一个页面里两种字号。
 *
 * @param bold 拼接器里的「前缀/后缀」传 true —— 它们是会被拼进正则的字面量，
 *             和旁边只读的 `[柜架号]` 标签一起构成一条语法，加粗能与占位提示区分开。
 *             普通输入框（名称、匹配文字、测试短信）用默认的常规字重。
 */
fun EditText.applyInputStyle(bold: Boolean = false): EditText = apply {
    textSize = 15f
    setTextColor(AppColor.text)
    setHintTextColor(AppColor.placeholder)
    setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
    // 输入框设了加粗后，hint 会跟着加粗 —— 空着的格子看起来像「已经填了」。
    // 这里把 hint 单独压回常规字重，并固定占位灰。
    hint?.let { raw ->
        hint = SpannableString(raw).apply {
            setSpan(
                ForegroundColorSpan(AppColor.placeholder), 0, length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(StyleSpan(Typeface.NORMAL), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

/**
 * 拼接器中间那个只读标签：`[柜架号]` / `[取件码]`。
 *
 * 它代表「这里会填进一段提取结果」，是规则语法的结构标记，所以用品牌蓝加粗；
 * 字号比两侧输入框大一号（16sp），在三个格子的横排里当分隔符用。
 */
fun Context.splicerTag(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 16f
    setTypeface(null, Typeface.BOLD)
    setTextColor(AppColor.blue)
    setPadding(dpi(12), 0, dpi(12), 0)
}

// ---------------- 按钮 ----------------

/**
 * 页面里的操作按钮（保存、删除、添加、全选）。
 *
 * 统一三个之前靠系统主题决定的东西：字号（各 ROM 的 button 样式不一样）、
 * 文字颜色（系统 accent 与本应用品牌蓝不是一个色）、全大写。
 *
 * @param danger 删除类操作用红色文字，和普通操作区分开
 */
fun Context.actionButton(
    text: String,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null
): Button = Button(this).apply {
    this.text = text
    textSize = 14f
    isAllCaps = false
    setTextColor(if (danger) AppColor.redText else AppColor.blue)
    pressScale(0.97f)
    onClick?.let { cb -> setOnClickListener { cb() } }
}

// ---------------- 关于页 ----------------

/** 关于页顶部的应用名：20sp 加粗、居中，比页面标题小一号、比列表标题大一号 */
fun Context.appTitle(text: String, topDp: Int = 12): TextView = TextView(this).apply {
    this.text = text
    textSize = 20f
    setTypeface(null, Typeface.BOLD)
    setTextColor(AppColor.text)
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(0, dpi(topDp), 0, 0)
}
