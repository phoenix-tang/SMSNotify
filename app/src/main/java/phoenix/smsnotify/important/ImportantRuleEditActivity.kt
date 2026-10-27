package phoenix.smsnotify.important

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import phoenix.smsnotify.ui.AppColor
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.pressScale

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

    private val gray = 0xFF9E9E9E.toInt()
    private val blue = AppColor.blue
    private val green = 0xFF2E7D32.toInt()
    private val red = 0xFFC62828.toInt()

    private lateinit var nameEdit: EditText
    private lateinit var keywordEdit: EditText
    private lateinit var keywordCount: TextView
    private lateinit var testInput: EditText
    private lateinit var testResult: TextView

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
            textSize = 15f
            maxLines = 1
        }
        root.addView(nameEdit)

        // 匹配文字（多段）
        root.addView(label("匹配文字（每行一段，出现在短信里就算命中）"))
        keywordEdit = EditText(this).apply {
            hint = "余额\n到账\n消费"
            setText(rule?.keywords?.joinToString("\n") ?: "")
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFF7F7F9.toInt())
        }
        root.addView(keywordEdit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        keywordCount = TextView(this).apply {
            textSize = 12f
            setTextColor(gray)
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(keywordCount)

        root.addView(note(
            "每行一段文字，不区分大小写；所有行都出现在同一条短信里才算命中，才记一条并响铃提醒。\n" +
                "文字要尽量短而具体，写「余额」会连广告短信一起命中，写「尾号1234」更准。"
        ))

        // 测试区
        root.addView(label("测试（粘贴一条短信试试）"))
        testInput = EditText(this).apply {
            hint = "粘贴短信原文"
            textSize = 14f
            minLines = 2
        }
        root.addView(testInput)
        testResult = TextView(this).apply {
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(testResult)

        // 保存 / 删除
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        btnRow.addView(Button(this).apply {
            text = "保存"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            pressScale(0.97f)
            setOnClickListener { save() }
        })
        if (rule != null) {
            btnRow.addView(Button(this).apply {
                text = "删除"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                pressScale(0.97f)
                setOnClickListener {
                    AlertDialog.Builder(this@ImportantRuleEditActivity)
                        .setTitle("删除规则")
                        .setMessage("删除这条规则及其全部记录？")
                        .setPositiveButton("删除") { _, _ ->
                            ImportantStore.remove(ruleId); finish()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
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
        createdAt = origin?.createdAt ?: System.currentTimeMillis()
    )

    /** 边输入边给反馈：几段文字 + 这条测试短信会不会命中 */
    private fun updatePreview() {
        val rule = currentRule()
        keywordCount.text = "共 ${rule.keywords.size} 段匹配文字"
        keywordCount.setTextColor(if (rule.keywords.isEmpty()) gray else blue)

        val sample = testInput.text.toString()
        if (sample.isBlank()) {
            testResult.text = ""
            return
        }
        if (rule.keywords.isEmpty()) {
            testResult.text = "✘ 还没填匹配文字"
            testResult.setTextColor(red)
            return
        }
        val missing = rule.keywords.filterNot { sample.contains(it, ignoreCase = true) }
        if (missing.isEmpty()) {
            testResult.text = "✔ 命中：${rule.keywords.size} 段文字全部包含"
            testResult.setTextColor(green)
        } else {
            testResult.text = "✘ 未命中：缺少「${missing.joinToString("」「")}」"
            testResult.setTextColor(red)
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

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(gray)
        setPadding(0, dp(24), 0, dp(8))
    }

    /** 小字说明：黑色加粗、字号偏小，挂在输入区下面，解释用户看不见的匹配行为 */
    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.BLACK)
        setTypeface(null, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(10), 0, 0)
    }
}
