package phoenix.smsnotify.important

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import phoenix.smsnotify.common.TimeFmt
import phoenix.smsnotify.ui.actionButton
import phoenix.smsnotify.ui.AppColor
import phoenix.smsnotify.ui.AppIcons
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.SwipeGroup
import phoenix.smsnotify.ui.SwipeRow
import phoenix.smsnotify.ui.ToggleView
import phoenix.smsnotify.ui.divider
import phoenix.smsnotify.ui.emptyHint
import phoenix.smsnotify.ui.pageHint
import phoenix.smsnotify.ui.paragraph
import phoenix.smsnotify.ui.rowRipple
import phoenix.smsnotify.ui.roundedBg
import phoenix.smsnotify.ui.setCardHeader
import phoenix.smsnotify.ui.setCardTitle
import phoenix.smsnotify.ui.setDisclosure
import phoenix.smsnotify.ui.setRowMeta
import phoenix.smsnotify.ui.setRowTitle
import phoenix.smsnotify.ui.tagLabel

/**
 * 「重要短信提醒」这一屏。
 *
 *      ┌────────────────────────────┐
 *      │ 重要短信提醒            📖   │  ← 标题 + 书页图标（规则列表）
 *      │ ┌────────────────────────┐ │
 *      │ │ 最新通知            21:35 │ │  ← 单独一栏：最新命中的规则类别 + 时间
 *      │ │ 银行动账                  │ │
 *      │ └────────────────────────┘ │
 *      │ ▾ 银行动账      3 条  [开]  │  ← 规则卡片，点标题展开/收缩
 *      │    10-27 21:35  您尾号…    │
 *      │    10-26 09:12  您尾号…    │
 *      │ ▸ 验证码        8 条  [关]  │
 *      └────────────────────────────┘
 *
 * 排序规则（用户明确要求）：
 *  - 规则之间：按添加时间倒序，最新添加的在最上面；
 *  - 规则内部：命中记录按短信时间倒序，最新的在最上面。
 *
 * 这一屏只负责「看」：规则的新增/编辑/删除收在 [ImportantRulesActivity]（标题栏书页图标进入），
 * 卡片上的长按菜单保留编辑/清空记录/删除，作为就地操作的快捷方式。
 */
class ImportantPageView(private val act: BaseActivity) : LinearLayout(act) {

    /** 哪几条规则是展开的（只在本次运行内记住，重建页面不会丢，但重启后默认全部收起） */
    private val expanded = mutableSetOf<Long>()

    /**
     * 被收起的「旧条件」记录组，key = `规则id:条件指纹`。
     *
     * 只记用户主动收起来的那些：默认一律展开 —— 把旧记录藏起来，很容易让人以为
     * 改关键字的时候记录被清掉了。
     */
    private val collapsedOldGroups = mutableSetOf<String>()

    /** 命中记录的左滑删除状态：同一时刻只允许一条记录露出删除按钮 */
    private val hitSwipe = SwipeGroup()

    private lateinit var latestCard: LinearLayout
    private lateinit var latestHeader: TextView
    private lateinit var latestCategory: TextView
    private lateinit var latestTime: TextView

    /** 平时显示的最新一条摘要（一行）；响铃时让位给 [ringExcerptBox]，避免同一段话写两遍 */
    private lateinit var latestPreview: TextView

    /** 响铃时才出现：摘要区和停止键，包在 [latestRingBox] 里一起显隐 */
    private lateinit var latestRingBox: LinearLayout
    private lateinit var ringExcerptBox: LinearLayout

    private lateinit var scroll: ScrollView
    private lateinit var cardsBox: LinearLayout

    /** 每秒刷一次「已响多久」；只在响铃时跑 */
    private var ringTicker: Runnable? = null

    /** 响铃起停时刷新卡片。抽成属性是为了 detach 时能摘掉同一个引用 */
    private val ringListener: () -> Unit = { act.runOnUiThread { renderLatest() } }

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
        AlarmRing.addListener(ringListener)
        refresh()
    }

    fun detach() {
        ImportantStore.listener = null
        AlarmRing.removeListener(ringListener)
        stopRingTicker()
    }

    // ---------------- UI 构建 ----------------

    private fun build() {
        // 标题栏右侧的书页图标 = 规则列表（增删改都在那里，这一屏只负责"看结果"）
        val titleRow = act.pageTitle("重要短信提醒", withBack = false)
        titleRow.addView(act.iconButton(AppIcons.book()) {
            act.startActivity(Intent(act, ImportantRulesActivity::class.java))
        })
        addView(titleRow)

        addView(act.pageHint(
            "点规则展开记录；记录左滑可删除，长按规则可编辑。", topDp = 2, bottomDp = 8
        ))

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
    }

    /**
     * 顶部「最新通知」栏：左侧是最近一次命中的规则类别，右上角是那条短信的接收时间。
     * 点它直接展开并滚到对应规则 —— 收到提醒后最想看的就是"刚才那条属于哪个规则"。
     *
     * **响铃时它就地变身**：表头换成红色的「正在响铃」、右上角换成已响时长，下面长出一段
     * 摘要和一个「停止响铃」按钮；铃声一停就缩回去。复用这一块而不是另起一张卡片，是因为
     * 两者说的是同一件事（刚才捕获到什么），响铃时用户最该看到的就是这条短信本身，
     * 两张内容重复的卡片叠在一起只会让人多扫一眼。
     */
    private fun buildLatestCard(): View {
        latestCard = LinearLayout(act).apply {
            orientation = VERTICAL
            background = act.roundedBg(AppColor.blueBg, 12)
            setPadding(dp(14), dp(11), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            rowRipple(cornerDp = 12)
            setOnClickListener {
                ImportantStore.latest()?.let { focusRule(it.first.id) }
            }
        }

        latestTime = TextView(act).apply {
            setRowMeta("", color = AppColor.blueMuted)
            gravity = Gravity.END
        }
        latestHeader = TextView(act).apply {
            setCardHeader("最新通知")
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        latestCard.addView(LinearLayout(act).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(latestHeader)
            addView(latestTime)
        })

        latestCategory = TextView(act).apply {
            setCardTitle("")
            setPadding(0, dp(3), 0, 0)
        }
        latestCard.addView(latestCategory)

        // 平时也露一行摘要：刚收到一条短信时最想知道它说了什么，
        // 以前只有响铃的那一刻看得到，铃声一停就只剩规则名了
        latestPreview = act.paragraph("", topDp = 3).apply {
            visibility = GONE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        latestCard.addView(latestPreview)

        // 摘要 + 停止键：平时整块 GONE，只在响铃时出现
        latestRingBox = LinearLayout(act).apply {
            orientation = VERTICAL
            visibility = GONE
        }
        ringExcerptBox = LinearLayout(act).apply { orientation = VERTICAL }
        latestRingBox.addView(ringExcerptBox)

        latestRingBox.addView(
            act.actionButton("停止响铃", danger = true) { stopRinging(act) }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
        )
        latestCard.addView(latestRingBox)

        return latestCard
    }

    // ---------------- 渲染 ----------------

    fun refresh() {
        if (!::cardsBox.isInitialized) return
        hitSwipe.close(animate = false)   // 卡片要整体重建，展开态跟着旧 View 一起丢弃
        renderLatest()

        cardsBox.removeAllViews()
        val rules = ImportantStore.rules()
        if (rules.isEmpty()) {
            cardsBox.addView(buildEmptyHint())
            return
        }
        rules.forEach { cardsBox.addView(buildCard(it)) }
    }

    /**
     * 「最新通知」卡片的两种状态：平时是近期命中，响铃时变红并长出摘要和停止键。
     *
     * 响铃态用 [AlarmRing.current] 而不是 `ImportantStore.latest()`：状态跟着铃声走，
     * 铃声一停就缩回去（划掉通知、超时自停、按停止键都会通知到这里）。
     */
    private fun renderLatest() {
        val subject = AlarmRing.current
        if (subject != null) {
            renderRinging(subject)
            return
        }

        latestCard.background = act.roundedBg(AppColor.blueBg, 12)
        latestHeader.setCardHeader("最新通知")
        latestTime.setRowMeta("", color = AppColor.blueMuted)
        latestRingBox.visibility = GONE
        latestPreview.visibility = GONE
        stopRingTicker()

        val latest = ImportantStore.latest()
        if (latest == null) {
            latestCategory.setCardTitle("暂无通知", dimmed = true)
        } else {
            latestCategory.setCardTitle(latest.first.label)
            latestTime.text = TimeFmt.short(latest.second.time)
            // 摘要只取第一行（多段时第二段是小字补充，最新栏里放不下也不必放）。
            // 上色同样用记录自己的关键字快照，规则后来改过条件也不会标错
            val line = latest.second.excerpt.split('\n').firstOrNull { it.isNotEmpty() }
            if (line != null) {
                latestPreview.text = highlightKeywords(
                    line, latest.second.keywords.ifEmpty { latest.first.keywords }
                )
                latestPreview.visibility = VISIBLE
            }
        }
    }

    private fun renderRinging(subject: RingSubject) {
        latestCard.background = act.roundedBg(AppColor.redBg, 12)
        latestHeader.setCardHeader("正在响铃", color = AppColor.redText)
        // 这一屏本来就叫「重要短信提醒」，前缀是给胶囊和通知用的，卡片里省掉
        latestCategory.setCardTitle(subject.title.removePrefix("重要短信 · "))
        latestTime.setRowMeta(ringElapsed(subject.since), color = AppColor.redText)

        latestRingBox.visibility = VISIBLE
        latestPreview.visibility = GONE   // 摘要由 ringExcerptBox 完整显示，别写两遍
        ringExcerptBox.removeAllViews()
        val lines = subject.excerpt.split('\n').filter { it.isNotEmpty() }.take(2)
        if (lines.isEmpty()) {
            ringExcerptBox.addView(act.paragraph("（无内容）", topDp = 2))
        } else {
            lines.forEach { line ->
                ringExcerptBox.addView(
                    act.paragraph(highlightKeywords(line, subject.keywords), topDp = 2).apply {
                        // 和记录行同一档：摘要已裁过，正常一行放得下，窄屏最多两行
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                )
            }
        }
        startRingTicker()
    }

    private fun ringElapsed(since: Long): String {
        val s = (SystemClock.elapsedRealtime() - since) / 1_000
        return "已响 %02d:%02d".format(s / 60, s % 60)
    }

    private fun startRingTicker() {
        if (ringTicker != null) return
        val t = object : Runnable {
            override fun run() {
                val subject = AlarmRing.current
                if (subject == null) {
                    // 兜底：万一哪条停止路径没通知到，也不能把红色卡片和停止键留在屏幕上
                    renderLatest()
                    return
                }
                latestTime.text = ringElapsed(subject.since)
                postDelayed(this, 1_000)
            }
        }
        ringTicker = t
        postDelayed(t, 1_000)
    }

    private fun stopRingTicker() {
        ringTicker?.let { removeCallbacks(it) }
        ringTicker = null
    }

    private fun buildEmptyHint(): View = act.emptyHint(
        "还没有规则\n\n点右上角的书页图标进入规则列表，添加你在意的文字\n" +
            "收到包含这些文字的短信就会记在这里，并响铃提醒",
        topDp = 56
    )

    /** 一条规则的卡片：表头（箭头 + 名称 + 条数 + 开关）+ 展开区（命中记录） */
    private fun buildCard(rule: ImportantRule): View {
        val hitList = ImportantStore.hitsOf(rule.id)
        val isOpen = rule.id in expanded

        val card = LinearLayout(act).apply {
            orientation = VERTICAL
            background = act.roundedBg(Color.WHITE, 12, AppColor.cardBorder)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        // ---- 表头 ----
        val arrow = TextView(act).apply {
            setDisclosure(isOpen, dimmed = !rule.enabled)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(18), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val name = TextView(act).apply {
            setRowTitle(rule.label, dimmed = !rule.enabled)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(6) }
        }
        val count = TextView(act).apply {
            setRowMeta(if (hitList.isEmpty()) "无记录" else "${hitList.size} 条")
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
        val ringTag = if (rule.ringMode != RingMode.DEFAULT) act.tagLabel(
            rule.ringMode.badge, AppColor.redText, AppColor.redBg
        ).apply {
            // 左右都留 8dp：左边和规则名分开，右边和「N 条」分开，
            // 否则「无限响铃 2 条」会读成一句
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
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
                setRowMeta(if (rule.enabled) "还没有命中记录" else "规则已停用，不再记录新短信")
                setPadding(dp(24), 0, 0, dp(2))
            })
        } else {
            addHitGroups(content, hitList, rule)
        }

        card.addView(header)
        card.addView(content)

        header.setOnClickListener {
            val open = rule.id !in expanded
            if (open) expanded.add(rule.id) else expanded.remove(rule.id)
            arrow.setDisclosure(open, dimmed = !rule.enabled)
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
     * 命中记录按「捕获条件」分组渲染。
     *
     * 记录只存摘要、不存原文，所以改了规则关键字也不会重算旧摘要 —— 那些旧摘要里的词
     * 已经不在当前条件里了，和新的混在一起看，会让人以为它们是按今天的规则记下来的。
     *
     * 所以当前条件捕获的排最上（没有组头，和以前完全一样），旧条件捕获的收在
     * 「旧条件：余额、到账 · 2 条」下面，点组头可以收起。改过两次就是两组旧条件、
     * 各一个组头，不需要"版本号"这种东西；当前条件还没抓到新短信时，顶部会明说一句。
     */
    private fun addHitGroups(content: LinearLayout, hits: List<ImportantHit>, rule: ImportantRule) {
        val currentKey = conditionKey(rule.keywords)
        // hits 已按时间倒序，所以 LinkedHashMap 里的每组也天然是倒序
        val groups = LinkedHashMap<String, MutableList<ImportantHit>>()
        hits.forEach { h ->
            // 快照为空只会在规则已被删掉这种理论状态下出现，按当前条件兜底
            val key = if (h.keywords.isEmpty()) currentKey else conditionKey(h.keywords)
            groups.getOrPut(key) { mutableListOf() } += h
        }

        // 刚改过条件、还没抓到新的：明说一句，否则用户只看到一堆"旧条件"，会以为新条件没生效
        if (currentKey !in groups) {
            content.addView(TextView(act).apply {
                setRowMeta("当前条件还没有新记录")
                setPadding(0, dp(6), 0, 0)
            })
        }

        // 当前组排最前；其余组按各自最新一条的时间倒序
        val ordered = groups.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<ImportantHit>>> { it.key == currentKey }
                .thenByDescending { it.value.first().time }
        )

        ordered.forEachIndexed { index, entry ->
            if (index > 0) content.addView(act.divider(topMarginDp = 7))

            val body = LinearLayout(act).apply { orientation = VERTICAL }
            entry.value.forEachIndexed { i, hit ->
                if (i > 0) body.addView(act.divider(topMarginDp = 7))
                body.addView(buildHitRow(hit, rule))
            }

            // 当前条件那组不加组头：它就是这张卡片的主视图
            if (entry.key == currentKey) {
                content.addView(body)
                return@forEachIndexed
            }

            val collapseKey = "${rule.id}:${entry.key}"
            val collapsed = collapseKey in collapsedOldGroups
            body.visibility = if (collapsed) View.GONE else View.VISIBLE

            val arrow = TextView(act).apply {
                setDisclosure(!collapsed, dimmed = true)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(18), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val words = entry.value.first().keywords
            val label = TextView(act).apply {
                setRowMeta("旧条件：${words.take(3).joinToString("、")}" +
                    (if (words.size > 3) "…" else "") + " · ${entry.value.size} 条")
                // 关键字是用户自己写的一句话，可能很长：给它剩余宽度并省略，
                // 否则这一行会顶出卡片右边界
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            content.addView(LinearLayout(act).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(1))
                isClickable = true
                addView(arrow)
                addView(label)
                setOnClickListener {
                    // 就地切换，不整页重建：重建立刻丢掉滚动位置，长列表里很难受
                    val open = body.visibility != View.VISIBLE
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    arrow.setDisclosure(open, dimmed = true)
                    if (open) collapsedOldGroups.remove(collapseKey)
                    else collapsedOldGroups.add(collapseKey)
                }
            })
            content.addView(body)
        }
    }

    /**
     * 一条命中记录：第一行是接收时间，下面是「关键词 + 前后各 10 字」的摘要。
     *
     *     10-27 21:35
     *     …您尾号1234的账户余额为1,234.00元，可用余额…      ← 关键字红色加粗
     *
     * 摘要有两个关键字且彼此间隔较远时会占两行；间隔不超过 20 字则合并成一行。
     * 切分发生在**入库时**（见 [smsExcerpt]），这里只负责把存下来的文字按关键字重新上色 ——
     * 上色用的是这条记录自己的关键字快照（[ImportantHit.keywords]），不是规则今天的条件，
     * 否则规则改过关键字后，旧摘要会漏标当年的词、又误标新加的词。
     *
     * 整行套一层 [SwipeRow]：左滑露出删除按钮，和快递列表是同一套交互与动画。
     */
    private fun buildHitRow(hit: ImportantHit, rule: ImportantRule): View {
        val marks = hit.keywords.ifEmpty { rule.keywords }
        val content = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(0, dp(7), 0, 0)

            addView(TextView(act).apply {
                setRowMeta(TimeFmt.short(hit.time))
            })

            val lines = hit.excerpt.split('\n').filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                addView(TextView(act).apply {
                    setRowMeta("（无内容）")
                })
            } else {
                lines.forEach { line ->
                    addView(act.paragraph(highlightKeywords(line, marks), topDp = 2).apply {
                        // 摘要已经做过裁剪，正常一行放得下；窄屏换行时最多两行
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
            }
        }

        return SwipeRow(act, content).apply {
            bind(hitSwipe, hit.id, hitSwipe.isOpen(hit.id)) {
                ImportantStore.removeHit(hit.id)
                refresh()
            }
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
}
