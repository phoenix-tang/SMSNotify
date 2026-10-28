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
import phoenix.smsnotify.common.displayWidth
import phoenix.smsnotify.ui.AppColor
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
 * 多段文字用**换行**分隔：一个 EditText 里一行一段，比「加一段 / 删一段」的按钮列表
 * 更好输入（可以直接从备忘录里粘贴一大段）。
 */
class ImportantRuleEditActivity : BaseActivity() {

    private var ruleId = -1L
    private var origin: ImportantRule? = null

    /** 当前选择的响铃时长（毫秒），语义见 [RingMode] */
    private var ringMs = RingMode.DEFAULT.ms

    private lateinit var nameEdit: EditText
    private lateinit var nameCount: TextView
    private lateinit var keywordEdit: EditText
    private lateinit var keywordCount: TextView
    private lateinit var testInput: EditText
    private lateinit var testResult: TextView
    private lateinit var ringValue: TextView

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

        // 匹配文字（多段）
        root.addView(label("匹配文字（每行一段）"))
        keywordEdit = EditText(this).apply {
            hint = "余额\n到账\n消费"
            setText(rule?.keywords?.joinToString("\n") ?: "")
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(AppColor.field)
            applyInputStyle()
        }
        root.addView(keywordEdit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        keywordCount = TextView(this).apply {
            setRowMeta("")
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(keywordCount)

        root.addView(note(
            "每行一段，不区分大小写；全部出现在同一条短信里才算命中。\n" +
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
        listOf(nameEdit, keywordEdit, testInput).forEach { it.addTextChangedListener(watcher) }
        updatePreview()
    }

    /** 一行一段；顺带把「中文逗号/顿号/分号」也当分隔符，从聊天记录里粘来的短句也能直接用 */
    private fun keywordsOf(text: String): List<String> =
        text.split('\n')
            .flatMap { it.split('，', '、', '；') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun currentRule(): ImportantRule = ImportantRule(
        id = ruleId,
        name = nameEdit.text.toString().trim(),
        keywords = keywordsOf(keywordEdit.text.toString()),
        enabled = origin?.enabled ?: true,
        createdAt = origin?.createdAt ?: System.currentTimeMillis(),
        ringMs = ringMs
    )

    /**
     * 第几段（从 1 起）超过了宽度上限；没超就返回空。
     *
     * 提示和保存校验共用同一份判断，否则会出现「界面没红、保存却被拒」或反过来。
     */
    private fun overLongKeywords(keywords: List<String>): List<Int> =
        keywords.withIndex()
            .filter { displayWidth(it.value) > ImportantRule.KEYWORD_MAX_WIDTH }
            .map { it.index + 1 }

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

        val overLong = overLongKeywords(rule.keywords)
        keywordCount.setRowMeta(
            when {
                overLong.isNotEmpty() ->
                    "第 ${overLong.joinToString("、")} 段太长（每段最多 ${ImportantRule.KEYWORD_MAX_WIDTH}，1 个汉字算 2）"
                rule.keywords.isEmpty() -> "共 0 段匹配文字"
                else -> "共 ${rule.keywords.size} 段匹配文字"
            },
            color = when {
                overLong.isNotEmpty() -> AppColor.redText
                rule.keywords.isEmpty() -> AppColor.muted
                else -> AppColor.blue
            }
        )

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
        val overLong = overLongKeywords(rule.keywords)
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
