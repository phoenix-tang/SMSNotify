package phoenix.smsnotify.important

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import phoenix.smsnotify.common.TimeFmt
import phoenix.smsnotify.ui.AppColor
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.ToggleView
import phoenix.smsnotify.ui.pressScale
import phoenix.smsnotify.ui.rowRipple

/**
 * 「重要短信提醒」这一屏。
 *
 *      ┌────────────────────────────┐
 *      │ 重要短信提醒                  │  ← 标题
 *      │ ┌────────────────────────┐ │
 *      │ │ 最新通知            21:35 │ │  ← 单独一栏：最新命中的规则类别 + 时间
 *      │ │ 银行动账                  │ │
 *      │ └────────────────────────┘ │
 *      │ ▾ 银行动账      3 条  [开]  │  ← 规则卡片，点标题展开/收缩
 *      │    10-27 21:35  您尾号…    │
 *      │    10-26 09:12  您尾号…    │
 *      │ ▸ 验证码        8 条  [关]  │
 *      ├────────────────────────────┤
 *      │      ＋ 添加匹配规则          │  ← 固定在最下方
 *      └────────────────────────────┘
 *
 * 排序规则（用户明确要求）：
 *  - 规则之间：按添加时间倒序，最新添加的在最上面；
 *  - 规则内部：命中记录按短信时间倒序，最新的在最上面。
 *
 * 没有设置页：规则的增删改全在这一屏完成 —— 底部按钮添加、长按卡片编辑/删除。
 */
class ImportantPageView(private val act: BaseActivity) : LinearLayout(act) {

    private val blue = AppColor.blue
    private val gray = 0xFF9E9E9E.toInt()
    private val body = 0xFF424242.toInt()

    /** 哪几条规则是展开的（只在本次运行内记住，重建页面不会丢，但重启后默认全部收起） */
    private val expanded = mutableSetOf<Long>()

    private lateinit var latestCategory: TextView
    private lateinit var latestTime: TextView
    private lateinit var scroll: ScrollView
    private lateinit var cardsBox: LinearLayout

    private fun dp(v: Int): Int = act.dp(v)

    init {
        // 与 BaseActivity.pageRoot() 保持一致：左右 16dp、上下 8dp
        orientation = VERTICAL
        gravity = Gravity.TOP
        setPadding(dp(16), dp(8), dp(16), dp(8))
        ImportantStore.init(act)
        build()
        refresh()
    }

    // ---------------- 生命周期 ----------------

    /** 页面可见时挂上监听：收到短信立刻能看到「最新通知」变化 */
    fun attach() {
        ImportantStore.listener = { act.runOnUiThread { refresh() } }
        refresh()
    }

    fun detach() {
        ImportantStore.listener = null
    }

    // ---------------- UI 构建 ----------------

    private fun build() {
        addView(act.pageTitle("重要短信提醒", withBack = false))

        addView(TextView(act).apply {
            text = "规则里的每段文字都出现在同一条短信里才算命中。点规则可展开看命中时间，长按可编辑或删除。"
            textSize = 12f
            setTextColor(gray)
            setPadding(0, 2, 0, 8)
        })

        addView(buildLatestCard())

        // 规则卡片区：卡片高度随展开变化，用 ScrollView + LinearLayout 而不是 ListView，
        // 这样展开/收缩只需要改一个 View 的高度，不必和复用机制打架
        cardsBox = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        scroll = ScrollView(act).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(cardsBox, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        addView(Button(act).apply {
            text = "＋ 添加匹配规则"
            pressScale(0.97f)
            setOnClickListener {
                act.startActivity(Intent(act, ImportantRuleEditActivity::class.java).putExtra("id", -1L))
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
    }

    /**
     * 顶部「最新通知」栏：左侧是最近一次命中的规则类别，右上角是那条短信的接收时间。
     * 点它直接展开并滚到对应规则 —— 收到提醒后最想看的就是"刚才那条属于哪个规则"。
     */
    private fun buildLatestCard(): View {
        val card = LinearLayout(act).apply {
            orientation = VERTICAL
            background = rounded(0xFFE8F1FC.toInt(), null, 12)
            setPadding(dp(14), dp(11), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            rowRipple(cornerDp = 12)
            setOnClickListener {
                ImportantStore.latest()?.let { focusRule(it.first.id) }
            }
        }

        latestTime = TextView(act).apply {
            textSize = 12f
            setTextColor(0xFF5C7FA8.toInt())
            gravity = Gravity.END
        }
        card.addView(LinearLayout(act).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(act).apply {
                text = "最新通知"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(blue)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(latestTime)
        })

        latestCategory = TextView(act).apply {
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(0, dp(3), 0, 0)
        }
        card.addView(latestCategory)
        return card
    }

    // ---------------- 渲染 ----------------

    fun refresh() {
        if (!::cardsBox.isInitialized) return
        renderLatest()

        cardsBox.removeAllViews()
        val rules = ImportantStore.rules()
        if (rules.isEmpty()) {
            cardsBox.addView(emptyHint())
            return
        }
        rules.forEach { cardsBox.addView(buildCard(it)) }
    }

    private fun renderLatest() {
        val latest = ImportantStore.latest()
        if (latest == null) {
            latestCategory.text = "暂无通知"
            latestCategory.setTextColor(gray)
            latestTime.text = ""
        } else {
            latestCategory.text = latest.first.label
            latestCategory.setTextColor(Color.BLACK)
            latestTime.text = TimeFmt.short(latest.second.time)
        }
    }

    private fun emptyHint(): View = TextView(act).apply {
        text = "还没有规则\n\n点最下方的「＋ 添加匹配规则」，填上你在意的文字\n" +
            "收到包含这些文字的短信就会记在这里，并响铃提醒"
        textSize = 13f
        setTextColor(gray)
        gravity = Gravity.CENTER
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(8), dp(56), dp(8), dp(8))
    }

    /** 一条规则的卡片：表头（箭头 + 名称 + 条数 + 开关）+ 展开区（命中记录） */
    private fun buildCard(rule: ImportantRule): View {
        val hitList = ImportantStore.hitsOf(rule.id)
        val isOpen = rule.id in expanded

        val card = LinearLayout(act).apply {
            orientation = VERTICAL
            background = rounded(Color.WHITE, 0xFFECECF0.toInt(), 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        // ---- 表头 ----
        val arrow = TextView(act).apply {
            text = if (isOpen) "▾" else "▸"
            textSize = 13f
            setTextColor(if (rule.enabled) 0xFF616161.toInt() else gray)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(18), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val name = TextView(act).apply {
            text = rule.label
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(when {
                !rule.enabled -> gray
                else -> Color.BLACK
            })
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(6) }
        }
        val count = TextView(act).apply {
            text = if (hitList.isEmpty()) "无记录" else "${hitList.size} 条"
            textSize = 12f
            setTextColor(gray)
        }
        val toggle = ToggleView(act).apply {
            contentDescription = "启用该规则"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)
            ).apply { marginStart = dp(10) }
            setChecked(rule.enabled, animate = false)
            onCheckedChange = { checked ->
                ImportantStore.setEnabled(rule.id, checked)
                refresh()   // 停用后整行要变灰
            }
        }

        // 非默认响铃时长给个小红标签：一眼能看出"这条会一直响"，不用点进去确认
        val ringTag = if (rule.ringMs != 0L) TextView(act).apply {
            text = "响 ${rule.ringMode.label}"
            textSize = 11f
            setTextColor(0xFFC62828.toInt())
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = rounded(0xFFFDECEA.toInt(), null, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        } else null

        val header = LinearLayout(act).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(13), dp(12), dp(13))
            isClickable = true
            isFocusable = true
            rowRipple(cornerDp = 12)
            addView(arrow)
            addView(name)
            ringTag?.let { addView(it) }
            addView(count)
            addView(toggle)
        }

        // ---- 展开区：命中记录，按时间倒序 ----
        val content = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(dp(12), 0, dp(12), dp(12))
            visibility = if (isOpen) View.VISIBLE else View.GONE
        }
        if (hitList.isEmpty()) {
            content.addView(TextView(act).apply {
                text = if (rule.enabled) "还没有命中记录" else "规则已停用，不再记录新短信"
                textSize = 12f
                setTextColor(gray)
                setPadding(dp(24), 0, 0, dp(2))
            })
        } else {
            hitList.forEachIndexed { i, hit ->
                if (i > 0) content.addView(View(act).apply {
                    setBackgroundColor(0xFFF2F2F5.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply { topMargin = dp(7) }
                })
                content.addView(buildHitRow(hit, rule))
            }
        }

        card.addView(header)
        card.addView(content)

        header.setOnClickListener {
            val open = rule.id !in expanded
            if (open) expanded.add(rule.id) else expanded.remove(rule.id)
            arrow.text = if (open) "▾" else "▸"
            animateHeight(content, open)
        }

        // 没有设置页，编辑/删除入口就在卡片上：长按（复用快递列表"长按删除"的习惯）
        header.setOnLongClickListener {
            AlertDialog.Builder(act)
                .setTitle(rule.label)
                .setItems(arrayOf("编辑规则", "清空命中记录", "删除规则")) { _, which ->
                    when (which) {
                        0 -> act.startActivity(
                            Intent(act, ImportantRuleEditActivity::class.java)
                                .putExtra("id", rule.id)
                        )
                        1 -> {
                            ImportantStore.clearHits(rule.id)
                            refresh()
                        }
                        2 -> AlertDialog.Builder(act)
                            .setTitle("删除规则")
                            .setMessage("删除「${rule.label}」及其全部记录？")
                            .setPositiveButton("删除") { _, _ ->
                                expanded.remove(rule.id)
                                ImportantStore.remove(rule.id)
                                refresh()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
                .show()
            true
        }
        return card
    }

    /**
     * 一条命中记录：第一行是接收时间，下面是「关键词 + 前后各 10 字」的摘要。
     *
     *     10-27 21:35
     *     …您尾号1234的账户余额为1,234.00元，可用余额…      ← 关键字红色加粗
     *
     * 摘要有两个关键字且彼此间隔较远时会占两行；间隔不超过 20 字则合并成一行。
     * 具体切分规则在 [smsSnippet] 里，这里只负责把每行渲染出来。
     */
    private fun buildHitRow(hit: ImportantHit, rule: ImportantRule): View = LinearLayout(act).apply {
        orientation = VERTICAL
        setPadding(0, dp(7), 0, 0)

        addView(TextView(act).apply {
            text = TimeFmt.short(hit.time)
            textSize = 12f
            setTextColor(gray)
        })

        val lines = smsSnippet(hit.body, rule.keywords)
        if (lines.isEmpty()) {
            addView(TextView(act).apply {
                text = "（无内容）"
                textSize = 13f
                setTextColor(gray)
            })
            return@apply
        }
        lines.forEach { line ->
            addView(TextView(act).apply {
                text = line          // 富文本：关键字已带红色 + 加粗 span
                textSize = 13f
                setTextColor(body)
                setPadding(0, dp(2), 0, 0)
                setLineSpacing(dp(1).toFloat(), 1f)
                // 摘要已经做过裁剪，正常一行放得下；窄屏换行时最多两行
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
    }

    // ---------------- 展开 / 收缩 ----------------

    /** 从「最新通知」跳到某条规则：展开它并滚到它所在的位置 */
    private fun focusRule(ruleId: Long) {
        expanded.add(ruleId)
        refresh()
        scroll.post {
            val index = ImportantStore.rules().indexOfFirst { it.id == ruleId }
            val card = cardsBox.getChildAt(index)
            if (card != null) scroll.smoothScrollTo(0, maxOf(0, card.top - dp(4)))
        }
    }

    /**
     * 高度动画：0 ↔ 内容高度。
     *
     * 不用 LayoutTransition / TransitionManager，是因为它们对"父容器整体高度变化"的处理
     * 在不同 ROM 上表现不一致；这里手动量一次内容高度再逐帧改 layoutParams，
     * 和快递列表的删除动画是同一套做法，行为可控。
     */
    private fun animateHeight(v: View, expand: Boolean) {
        val lp = v.layoutParams as LinearLayout.LayoutParams

        // 展开区的宽度永远是卡片宽度：内容视图若是 GONE 状态还没被布局过，
        // 自身宽为 0，就用父容器的宽度，否则第一次展开会拿不到高度、只能硬跳
        val width = if (v.width > 0) v.width else (v.parent as? View)?.width ?: 0

        // 一次都没量到宽度（页面还没上屏），没有可动画的起止值，直接落位
        if (width <= 0) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            v.visibility = if (expand) View.VISIBLE else View.GONE
            v.layoutParams = lp
            return
        }

        v.visibility = View.VISIBLE
        val from = if (expand) 0 else v.height

        // 按目标宽度量一遍，拿到展开后的高度
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        v.layoutParams = lp
        v.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val to = if (expand) v.measuredHeight else 0

        lp.height = from
        v.layoutParams = lp

        ValueAnimator.ofInt(from, to).apply {
            duration = 190L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { a ->
                lp.height = a.animatedValue as Int
                v.layoutParams = lp        // 每帧重设 → 触发重新布局，卡片高度跟着变
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    lp.height = if (expand) {
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    } else {
                        0
                    }
                    v.layoutParams = lp
                    if (!expand) v.visibility = View.GONE
                }
            })
            start()
        }
    }

    private fun rounded(fill: Int, stroke: Int?, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }
}
