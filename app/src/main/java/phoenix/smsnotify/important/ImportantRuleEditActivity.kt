package phoenix.smsnotify.important

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private var ringMs = 0L

    private lateinit var nameEdit: EditText
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

        // 规则名称
        root.addView(label("规则名称"))
        nameEdit = EditText(this).apply {
            hint = "如：银行动账（留空则用第一段文字代替）"
            setText(rule?.name ?: "")
            maxLines = 1
            applyInputStyle()
        }
        root.addView(nameEdit)

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
        ringMs = rule?.ringMs ?: 0L
        root.addView(
            buildRingRow(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        )
        root.addView(note(
            "10 秒以上由应用循环播放闹钟音，到点自停；" +
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

    /** 边输入边给反馈：几段文字 + 这条测试短信会不会命中 */
    private fun updatePreview() {
        val rule = currentRule()
        keywordCount.setRowMeta(
            "共 ${rule.keywords.size} 段匹配文字",
            color = if (rule.keywords.isEmpty()) AppColor.muted else AppColor.blue
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
        if (rule.keywords.isEmpty()) {
            Toast.makeText(this, "请至少填一段匹配文字，否则这条规则匹配不到任何短信", Toast.LENGTH_LONG).show()
            return
        }
        ImportantStore.save(rule)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * 「响铃时长」设置行。
     *
     * 用「一整行可点 + 右侧显示当前值」，而不是把 6 个选项铺成一排按钮：
     * 中文选项从「单次提示」到「无限」宽度差很大，等分排会挤成两行、
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
