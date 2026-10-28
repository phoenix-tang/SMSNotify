package phoenix.smsnotify.important

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import phoenix.smsnotify.ui.displayWidth
import phoenix.smsnotify.ui.AppColor
import phoenix.smsnotify.ui.AppIcons
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.actionButton
import phoenix.smsnotify.ui.applyInputStyle
import phoenix.smsnotify.ui.choiceRow
import phoenix.smsnotify.ui.formFeedback
import phoenix.smsnotify.ui.label
import phoenix.smsnotify.ui.note
import phoenix.smsnotify.ui.setFeedback
import phoenix.smsnotify.ui.setRowMeta

/**
 * 重要短信规则的编辑页：名称 + 多段匹配文字 + 粘贴短信试匹配。
 *
 * 表单只有两个字段，刻意保持极简 —— 这个模块的定位就是「我说几个词，你帮我盯着短信」，
 * 规则写正则或字段占位符都属于快递模块那套，不该搬到这里。
 *
 * 多段文字是「一行一个输入框」：每行右侧带自己的长度计数，行尾有删除键，底部是「＋ 添加一段」。
 * 之所以不给一个多行框逐行标长度 —— 一段文字长了会折行，计数就不知道该贴哪一行；
 * 拆成一个框一段，段和计数才一一对应。从备忘录粘贴一大段照样能用：剪贴板里的换行
 * 会被拆成多个框，而不是挤在同一段里。
 */
class ImportantRuleEditActivity : BaseActivity() {

    private var ruleId = -1L
    private var origin: ImportantRule? = null

    /** 当前选择的响铃时长（毫秒），语义见 [RingMode] */
    private var ringMs = RingMode.DEFAULT.ms

    private lateinit var nameEdit: EditText
    private lateinit var nameCount: TextView
    private lateinit var keywordBox: LinearLayout
    private lateinit var addKeywordButton: TextView
    private lateinit var testInput: EditText
    private lateinit var testResult: TextView
    private lateinit var ringValue: TextView

    /** 匹配文字的每一段，和 [keywordBox] 里的子 View 一一对应、同序 */
    private val keywordRows = mutableListOf<KeywordRow>()

    /**
     * 正在做「把粘贴进来的多行拆成多个输入框」，用来挡住拆分过程中连锁触发的 TextWatcher。
     *
     * 拆分时要对某个框 `setText`、还要新建框并 `setText`，这两步都会再进来一次；
     * 没有这个开关会无限递归（新建框又触发拆分）。
     */
    private var splitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        avoidIme = true   // 表单页：键盘弹出时让内容避让，而不是被盖住
        ImportantStore.init(this)
        ruleId = intent.getLongExtra("id", -1L)
        val rule = if (ruleId >= 0) ImportantStore.find(ruleId) else null
        origin = rule

        val root = pageRoot()
        root.addView(pageTitle(if (rule == null) "添加规则" else "编辑规则"))

        // 规则名称。计数叠在输入框右端而不是另起一行 —— 名字只有一行，
        // 下方再挂一行小字会让这一块比「匹配文字」那块还高，版面上很跳
        root.addView(label("规则名称"))
        nameEdit = EditText(this).apply {
            // hint 缩短了：右侧 52dp 被计数占掉，原来那句「留空则用第一段文字代替」
            // 会被截成省略号，改成同一句话的短版
            hint = "如：银行动账（留空用第一段）"
            setText(rule?.name?.replace(LINE_BREAK, " ") ?: "")
            maxLines = 1
            // 单行输入：键盘右下角是「完成」而不是换行键。
            // maxLines=1 只挡回车，粘贴多行文本照样能把换行塞进来，所以还要 [noLineBreak]
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(noLineBreak)
            applyInputStyle()
            // 右侧给计数留出固定位置，否则文字会钻到「6 / 24」底下
            setPadding(paddingLeft, paddingTop, paddingRight + dp(COUNT_RESERVE_DP), paddingBottom)
        }
        nameCount = TextView(this).apply {
            setRowMeta("")
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(12), 0)
        }
        root.addView(FrameLayout(this).apply {
            addView(nameEdit, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(nameCount, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END
            ))
        })

        // 匹配文字：一段一个输入框，每个框右侧带自己的长度计数。
        //
        // 原来是一个多行框、每行一段，但那样没法给「每一行」标长度 —— 一段文字长了会折行，
        // 计数就不知道该贴哪一行，贴上去也会和下一段挤在一起。拆成一行一个框之后，
        // 段和计数一一对应，删除也变成「删掉这一行」而不是删掉第几行文字。
        root.addView(label("匹配文字"))
        keywordBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keywordBox)
        addKeywordButton = TextView(this).apply {
            text = "＋ 添加一段"
            textSize = 14f
            setTextColor(AppColor.blue)
            setPadding(dp(2), dp(10), 0, dp(2))
            setOnClickListener {
                focusRow(addKeywordRow(hint = if (keywordRows.isEmpty()) "如：余额" else "如：到账"))
            }
        }
        root.addView(addKeywordButton)

        // 老数据可能超过 5 段（那时还没有上限），读进来只认前 5 段 ——
        // 界面上只有 5 个框，存盘取的就是这 5 个框，不会出现「看着 5 行、存了 8 段」
        val initial = rule?.keywords?.takeIf { it.isNotEmpty() } ?: listOf("")
        initial.take(ImportantRule.KEYWORD_MAX_COUNT).forEachIndexed { i, kw ->
            addKeywordRow(kw, hint = if (i == 0) "如：余额" else "如：到账")
        }

        root.addView(note(
            "不区分大小写；全部出现在同一条短信里才算命中。\n" +
                "写具体些：「尾号1234」比「余额」更准。"
        ))

        // 响铃时长。项名放进灰色选择行里，不再单独占一行 label：
        // 一个灰底块 + 上方一行孤立的标题，视觉上是两个块，反而更散
        ringMs = rule?.ringMs ?: RingMode.DEFAULT.ms
        root.addView(
            buildRingRow(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        )
        root.addView(note(
            "由应用循环播放闹钟音，到点自停；" +
                "响铃期间点通知上的「停止响铃」或直接划掉通知，都能提前停。"
        ))

        // 测试区
        root.addView(label("测试（粘贴短信试试）"))
        testInput = EditText(this).apply {
            hint = "粘贴短信原文"
            minLines = 2
            applyInputStyle()
        }
        root.addView(testInput)
        testResult = formFeedback()
        root.addView(testResult)

        // 保存 / 删除
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        btnRow.addView(actionButton("保存") { save() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (rule != null) {
            btnRow.addView(actionButton("删除", danger = true) {
                AlertDialog.Builder(this@ImportantRuleEditActivity)
                    .setTitle("删除规则")
                    .setMessage("删除这条规则及其全部记录？")
                    .setPositiveButton("删除") { _, _ ->
                        ImportantStore.remove(ruleId); finish()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        root.addView(btnRow)

        // 表单页放进可滚动容器：键盘弹出时滚动区会变矮，内容不会被键盘盖住
        setContentView(scrollPage(root))

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updatePreview()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(nameEdit, testInput).forEach { it.addTextChangedListener(watcher) }
        updatePreview()
    }

    /** 每个框就是一段，空框直接跳过（用户点了「＋」又没填）。 */
    private fun keywordsOf(): List<String> =
        keywordRows.map { it.edit.text.toString().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun currentRule(): ImportantRule = ImportantRule(
        id = ruleId,
        name = nameEdit.text.toString().trim(),
        keywords = keywordsOf(),
        enabled = origin?.enabled ?: true,
        createdAt = origin?.createdAt ?: System.currentTimeMillis(),
        ringMs = ringMs
    )

    /**
     * 第几段（从 1 起）超过了宽度上限；没超就返回空。
     *
     * 行内计数和保存校验共用同一份判断，否则会出现「界面没红、保存却被拒」或反过来。
     */
    private fun overLongKeywordIndexes(): List<Int> =
        keywordRows.mapIndexedNotNull { i, row ->
            if (displayWidth(row.edit.text.toString().trim()) > ImportantRule.KEYWORD_MAX_WIDTH) i + 1
            else null
        }

    // ---------------- 匹配文字的那些行 ----------------

    /**
     * 加一行匹配文字。
     *
     * @param at 插到第几个位置；`-1` 表示追加到末尾（「＋ 添加一段」走这里）
     */
    private fun addKeywordRow(
        text: String = "",
        hint: String = "如：余额",
        at: Int = -1
    ): KeywordRow {
        val edit = EditText(this).apply {
            this.hint = hint
            setText(text)
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(12), dp(10), dp(12) + dp(COUNT_RESERVE_DP), dp(10))
            setBackgroundColor(AppColor.field)
            applyInputStyle()
        }
        val count = TextView(this).apply {
            setRowMeta("")
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(12), 0)
        }
        val field = FrameLayout(this).apply {
            addView(edit, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(count, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END
            ))
        }
        val del = iconButton(AppIcons.close()) {}.apply { iconColor = AppColor.muted }

        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(del)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val row = KeywordRow(view, edit, count, del)
        del.setOnClickListener { removeKeywordRow(row) }

        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (splitting) return
                val typed = s?.toString().orEmpty()
                if (typed.contains('\n')) splitKeywordRow(row, typed) else updatePreview()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        val insertAt = if (at < 0) keywordRows.size else at.coerceIn(0, keywordRows.size)
        keywordBox.addView(view, insertAt)
        keywordRows.add(insertAt, row)
        refreshKeywordRows()
        // 新行要立刻显示自己的「0 / 24」，不能等下一次输入才出现
        updateRowCount(row)
        return row
    }

    /**
     * 粘贴进来的内容里有换行：当前框留第一段，其余按顺序拆成新行插在它后面。
     *
     * 这是为了保住「从备忘录粘一大段」的用法 —— 拆成一行一个框之后，如果直接把换行
     * 吞掉或换成空格，粘进来的三段会挤在同一段里，匹配不到任何东西。
     * 已有的行不动，只在中间插入，用户原有的内容不会被覆盖。
     */
    private fun splitKeywordRow(row: KeywordRow, text: String) {
        val parts = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val index = keywordRows.indexOf(row)
        if (index < 0) return

        // 拆分同样受段数上限约束：放不下的行并进当前这一段（空格隔开），而不是悄悄丢掉。
        // 合并会让这一段的计数变红，用户能看出来还有内容被挤在这里。
        val room = (ImportantRule.KEYWORD_MAX_COUNT - keywordRows.size).coerceAtLeast(0)
        val extra = parts.drop(1)
        val inserted = extra.take(room)
        val merged = (parts.take(1) + extra.drop(room)).joinToString(" ").trim()

        splitting = true
        try {
            row.edit.setText(merged)
            inserted.forEachIndexed { offset, part ->
                addKeywordRow(part, hint = "如：到账", at = index + 1 + offset)
            }
        } finally {
            splitting = false
        }
        updatePreview()
    }

    private fun removeKeywordRow(row: KeywordRow) {
        // 只剩一行时不去掉它：那一行是唯一的输入位，清空比删掉更自然
        if (keywordRows.size <= 1) {
            row.edit.setText("")
            return
        }
        keywordRows.remove(row)
        keywordBox.removeView(row.view)
        refreshKeywordRows()
        updatePreview()
    }

    /**
     * 同步「删除键」和「＋ 添加一段」的可用状态。
     *
     * 只剩一段时删除键用 GONE（不是 INVISIBLE）：那一行没有可删的东西，留一个空位
     * 会让输入框比上面的「规则名称」窄一截。加第二行时框会收窄 34dp —— 那是「多出
     * 一个删除键」的自然结果，和「多了一行」同时发生，不会显得突然。
     */
    private fun refreshKeywordRows() {
        val showDelete = keywordRows.size > 1
        keywordRows.forEach {
            it.delete.visibility = if (showDelete) View.VISIBLE else View.GONE
        }

        val full = keywordRows.size >= ImportantRule.KEYWORD_MAX_COUNT
        addKeywordButton.text =
            if (full) "最多 ${ImportantRule.KEYWORD_MAX_COUNT} 段" else "＋ 添加一段"
        addKeywordButton.setTextColor(if (full) AppColor.muted else AppColor.blue)
        addKeywordButton.isEnabled = !full
    }

    private fun focusRow(row: KeywordRow) {
        row.edit.requestFocus()
        row.edit.setSelection(row.edit.text.length)
    }

    private fun updateRowCount(row: KeywordRow) {
        val width = displayWidth(row.edit.text.toString().trim())
        row.count.setRowMeta(
            "$width / ${ImportantRule.KEYWORD_MAX_WIDTH}",
            color = if (width > ImportantRule.KEYWORD_MAX_WIDTH) AppColor.redText else AppColor.muted
        )
    }

    /** 边输入边给反馈：长度有没有超、几段文字 + 这条测试短信会不会命中 */
    private fun updatePreview() {
        val rule = currentRule()

        // 计数按半角宽度走（1 个汉字 = 2）：直接数 `length` 会让「iiiiiiiiiiii」
        // 和「银行到账提醒」看起来一样长，但其实前者只占一半宽。
        // 超限时只把数字变红（`26 / 24` 本身就说清了），不在框里塞整句提示 —— 那行放不下
        val nameWidth = displayWidth(rule.name)
        nameCount.setRowMeta(
            "$nameWidth / ${ImportantRule.NAME_MAX_WIDTH}",
            color = if (nameWidth > ImportantRule.NAME_MAX_WIDTH) AppColor.redText else AppColor.muted
        )

        // 每一段自己带计数，这里统一刷一遍：删行、拆分、粘贴之后都要重算
        keywordRows.forEach { updateRowCount(it) }

        val sample = testInput.text.toString()
        if (sample.isBlank()) {
            testResult.setFeedback("")
            return
        }
        if (rule.keywords.isEmpty()) {
            testResult.setFeedback("✘ 还没填匹配文字", ok = false)
            return
        }
        val missing = rule.keywords.filterNot { sample.contains(it, ignoreCase = true) }
        if (missing.isEmpty()) {
            testResult.setFeedback("✔ 命中：${rule.keywords.size} 段文字全部包含", ok = true)
        } else {
            testResult.setFeedback("✘ 未命中：缺少「${missing.joinToString("」「")}」", ok = false)
        }
    }

    private fun save() {
        val rule = currentRule()

        // 长度校验放在保存时（而不是输入时硬截断）：粘贴一大段文字进来，被无声切掉
        // 比看到一行红字更难理解。这两条限制都只为「塞得进界面 / 真能命中」服务，
        // 不是安全边界，所以给用户看清楚再让他自己改。
        if (displayWidth(rule.name) > ImportantRule.NAME_MAX_WIDTH) {
            Toast.makeText(
                this,
                "规则名太长：最多 ${ImportantRule.NAME_MAX_WIDTH / 2} 个汉字（字母数字算半个）",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val overLong = overLongKeywordIndexes()
        if (overLong.isNotEmpty()) {
            Toast.makeText(
                this,
                "第 ${overLong.first()} 段匹配文字太长：每段最多 ${ImportantRule.KEYWORD_MAX_WIDTH / 2} 个汉字",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (rule.keywords.isEmpty()) {
            Toast.makeText(this, "请至少填一段匹配文字，否则这条规则匹配不到任何短信", Toast.LENGTH_LONG).show()
            return
        }

        // 改了捕获条件：已有记录是旧关键字裁出来的，保存后会归到「旧条件」组里。
        // 先把这件事说清楚 —— 否则用户回到列表发现记录"换了组"，第一反应是数据丢了
        val prev = origin
        val conditionsChanged = prev != null && !sameConditions(prev.keywords, rule.keywords)
        val oldHits = prev?.let { ImportantStore.hitCount(it.id) } ?: 0

        ImportantStore.save(rule)
        val msg = if (conditionsChanged && oldHits > 0) "已保存 · 早先的 $oldHits 条记录归入旧条件"
        else "已保存"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * 「响铃时长」设置行。
     *
     * 用「一整行可点 + 右侧显示当前值」，而不是把 6 个选项铺成一排按钮：
     * 中文选项从「10 秒」到「30 分钟」宽度差很大，等分排会挤成两行、
     * 长的那几个还会被截断。点开单选列表，一眼看全，也不用担心窄屏。
     *
     * 项名就在行内左侧（「响铃时长」），行上方不再放 label ——
     * 同一件事写两遍会让人以为下面那行是另一个设置。
     */
    private fun buildRingRow(): View {
        // 结构由 choiceRow 定死：标题 + 当前值 + 箭头，值在第 2 个子 View
        val row = choiceRow("响铃时长", RingMode.of(ringMs).label) { pickRing() }
        ringValue = row.getChildAt(1) as TextView
        return row
    }

    /**
     * 单行字段的过滤器：把换行换成空格。
     *
     * `maxLines = 1` 只挡软键盘上的回车，粘贴多行文字照样能把 `\n` 塞进来 —— 名字里带换行时，
     * 胶囊、卡片标题、通知标题（只取第一行）都会显示成被截断的样子。
     * 换成空格而不是直接删：「招商银行\n工资到账」删掉换行会连成「招商银行工资到账」，
     * 两段粘成一串；空格才符合它原本的意思。
     */
    private val noLineBreak = InputFilter { src, start, end, _, _, _ ->
        val typed = src.subSequence(start, end).toString()
        if (!typed.contains('\n') && !typed.contains('\r')) null
        else typed.replace(LINE_BREAK, " ")
    }

    private fun pickRing() {
        val modes = RingMode.entries
        AlertDialog.Builder(this)
            .setTitle("响铃时长")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), RingMode.of(ringMs).ordinal) { d, which ->
                ringMs = modes[which].ms
                ringValue.text = modes[which].label
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 一行匹配文字：输入框 + 行内计数 + 删除键 */
    private class KeywordRow(
        val view: View,
        val edit: EditText,
        val count: TextView,
        val delete: View
    )
}

/** 各种换行写法（`\r\n`、单独的 `\r`）都归一成一个空格 */
private val LINE_BREAK = Regex("[\\r\\n]+")

/**
 * 输入框右侧给「6 / 24」留的宽度（dp）。
 *
 * 计数是叠在输入框上的，必须把输入框的右内边距推开这么多，否则打字到末尾时
 * 文字会钻到计数底下。按 12sp 的 `00 / 24` 再加两侧留白估的，不算精确 ——
 * 它只影响「文字最远能到哪」，宽一点只是少几个字的可视范围。
 */
private const val COUNT_RESERVE_DP = 52
