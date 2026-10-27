package phoenix.smsnotify.delivery

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.ToggleView
import phoenix.smsnotify.ui.pressScale
import phoenix.smsnotify.ui.rowRipple

/**
 * 规则相关的两个页面：
 *
 *  - [RulesActivity]     规则列表（编号、开关、长按删除、底部添加）
 *  - [RuleEditActivity]  单条规则的编辑（低代码拼接 → 实时预览正则 → 粘贴短信测试）
 *
 * 两者的数据来源都是 [RuleStore]，放在一个文件里方便对照列表展示和编辑表单的字段。
 */

/** 规则列表：点击编辑、右侧开关启用/停用、长按删除、底部添加 */
class RulesActivity : BaseActivity() {

    private lateinit var adapter: RuleAdapter
    private val gray = 0xFF9E9E9E.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuleStore.init(this)

        // 统一页面骨架：左右 16dp、上下 8dp 视觉留白，系统栏高度自动避让
        val root = pageRoot()

        root.addView(pageTitle("关键字提取规则"))
        root.addView(TextView(this).apply {
            text = "从上到下依次匹配，停用的规则会被跳过。柜架号只取数字，\"号柜\"这类文字填在后缀里。"
            textSize = 13f
            setTextColor(gray)
            setPadding(0, 8, 0, 8)
        })

        adapter = RuleAdapter()
        val list = ListView(this).apply { dividerHeight = 2 }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(Button(this).apply {
            text = "＋ 添加规则"
            pressScale(0.97f)
            setOnClickListener {
                startActivity(Intent(this@RulesActivity, RuleEditActivity::class.java)
                    .putExtra("id", -1L))
            }
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }

    inner class RuleAdapter : BaseAdapter() {
        override fun getCount(): Int = RuleStore.all().size
        override fun getItem(position: Int): ExtractRule = RuleStore.all()[position]
        override fun getItemId(position: Int): Long = getItem(position).id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: buildRow()
            val r = getItem(position)

            val col = row.getChildAt(0) as LinearLayout
            val line1 = col.getChildAt(0) as TextView
            val line2 = col.getChildAt(1) as TextView
            val toggle = row.getChildAt(1) as ToggleView

            // 编号 + 名称；停用的整行变灰
            line1.text = buildString {
                append(position + 1).append(". ")
                append(if (r.name.isEmpty()) "（未命名规则）" else r.name)
            }
            line1.textSize = 17f
            line1.setTextColor(
                when {
                    !r.enabled -> gray
                    r.builtin -> 0xFF1976D2.toInt()
                    else -> Color.BLACK
                }
            )
            line1.setTypeface(null, Typeface.BOLD)

            line2.text = "柜架号: ${r.cabPrefix}[柜架号]${r.cabSuffix}　取件码: ${r.codePrefix}[取件码]${r.codeSuffix}"
            line2.textSize = 13f
            line2.setTextColor(gray)

            // 开关：列表复用时直接落位，不播动画
            toggle.setChecked(r.enabled, animate = false)
            toggle.onCheckedChange = { checked ->
                RuleStore.setEnabled(r.id, checked)
                notifyDataSetChanged()   // 停用后整行要变灰
            }

            row.setOnClickListener {
                startActivity(Intent(this@RulesActivity, RuleEditActivity::class.java)
                    .putExtra("id", r.id))
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this@RulesActivity)
                    .setTitle("删除规则")
                    .setMessage("删除规则「${if (r.name.isEmpty()) "未命名规则" else r.name}」？")
                    .setPositiveButton("删除") { _, _ ->
                        RuleStore.remove(r.id)
                        notifyDataSetChanged()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            return row
        }

        /** 行结构固定为 [文字列(weight=1), 开关]，复用时直接按下标取子 View */
        private fun buildRow(): LinearLayout = LinearLayout(this@RulesActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            rowRipple(cornerDp = 8)
            addView(LinearLayout(this@RulesActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context))
                addView(TextView(context))
            })
            addView(ToggleView(this@RulesActivity).apply {
                contentDescription = "启用该规则"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)
                ).apply { marginStart = dp(12) }
            })
        }
    }
}

/** 规则编辑页：低代码拼接 → 生成正则 → 实时测试 */
class RuleEditActivity : BaseActivity() {

    private var ruleId = -1L

    /** 进页面时的原始规则，保存时用它保留 builtin / enabled 这类界面没有的字段 */
    private var origin: ExtractRule? = null

    private val gray = 0xFF9E9E9E.toInt()

    private lateinit var nameEdit: EditText
    private lateinit var cabPrefix: EditText
    private lateinit var cabSuffix: EditText
    private lateinit var codePrefix: EditText
    private lateinit var codeSuffix: EditText
    private lateinit var regexPreview: TextView
    private lateinit var testInput: EditText
    private lateinit var testResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        avoidIme = true   // 表单页：键盘弹出时让内容避让，而不是被盖住
        RuleStore.init(this)
        ruleId = intent.getLongExtra("id", -1L)
        val rule = if (ruleId >= 0) RuleStore.find(ruleId) else null
        origin = rule

        // 统一页面骨架：左右 16dp、上下 8dp 视觉留白，系统栏高度自动避让
        val root = pageRoot()

        // 标题行：返回箭头 + 标题（统一走 BaseActivity.pageTitle，和主页同规格）
        root.addView(pageTitle(
            when {
                rule == null -> "添加规则"
                rule.builtin -> "编辑内置规则"
                else -> "编辑规则"
            }
        ))

        if (rule?.builtin == true) {
            root.addView(TextView(this).apply {
                text = "这条规则来自内置文件，和其他规则一样可以改参数、停用或删除。"
                textSize = 13f
                setTextColor(0xFF1976D2.toInt())
                setPadding(0, 4, 0, 4)
            })
        }

        // 规则名称
        root.addView(label("规则名称"))
        nameEdit = EditText(this).apply {
            hint = "如：菜鸟"
            setText(rule?.name ?: "")
        }
        root.addView(nameEdit)

        // 第一行：【柜架号】拼接器
        root.addView(label("柜架号提取（可选；只捕获数字，\"号柜\"这类文字填在后缀）"))
        root.addView(splicerRow("前缀", "后缀",
            rule?.cabPrefix ?: "", rule?.cabSuffix ?: "",
            "【柜架号】").also { cabRow ->
            cabPrefix = cabRow.getChildAt(0) as EditText
            cabSuffix = cabRow.getChildAt(2) as EditText
        })

        // 第二行：【取件码】拼接器
        root.addView(label("取件码提取（数字或数字-连字符，如 3-2-5016）"))
        root.addView(splicerRow("前缀", "后缀",
            rule?.codePrefix ?: "", rule?.codeSuffix ?: "",
            "【取件码】").also { codeRow ->
            codePrefix = codeRow.getChildAt(0) as EditText
            codeSuffix = codeRow.getChildAt(2) as EditText
        })

        // 生成正则预览
        root.addView(label("生成的正则（自动拼接，无需手写）"))
        regexPreview = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF1976D2.toInt())
            setTypeface(Typeface.MONOSPACE)
        }
        root.addView(regexPreview)

        // 测试区
        root.addView(label("测试（粘贴一条真实短信试试）"))
        testInput = EditText(this).apply {
            hint = "粘贴短信原文"
            minLines = 2
        }
        root.addView(testInput)
        testResult = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        root.addView(testResult)

        // 保存 / 删除（内置规则同样可以删除）
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
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
                    AlertDialog.Builder(this@RuleEditActivity)
                        .setTitle("删除规则")
                        .setMessage("确定删除该规则？")
                        .setPositiveButton("删除") { _, _ ->
                            RuleStore.remove(ruleId); finish()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            })
        }
        root.addView(btnRow)

        // 表单页放进可滚动容器：键盘弹出时滚动区会变矮，内容不会被键盘盖住
        setContentView(scrollPage(root))

        // 任意输入变化 → 更新正则预览 + 测试结果
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updatePreview()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(nameEdit, cabPrefix, cabSuffix, codePrefix, codeSuffix, testInput).forEach {
            it.addTextChangedListener(watcher)
        }
        updatePreview()
    }

    private fun currentRule(): ExtractRule = ExtractRule(
        id = ruleId, name = nameEdit.text.toString().trim(),
        cabPrefix = cabPrefix.text.toString(), cabSuffix = cabSuffix.text.toString(),
        codePrefix = codePrefix.text.toString(), codeSuffix = codeSuffix.text.toString(),
        // 界面没有这两项，沿用进页面时的值，避免保存时把「已停用」悄悄改回启用
        enabled = origin?.enabled ?: true,
        builtin = origin?.builtin ?: false
    )

    private fun updatePreview() {
        val r = currentRule()

        // 两个正则各自独立，不是一个拼起来的长正则
        val cabNote = if (r.cabPrefix.isEmpty() && r.cabSuffix.isEmpty()) {
            "\n  ⚠ 柜架号前后缀都为空时，会取到短信里第一个数字，建议至少填一个"
        } else {
            "\n  （可选，匹配不到不影响取件码）"
        }
        regexPreview.text = buildString {
            append("① 柜架号: ").append(RuleStore.cabinetPattern(r)).append(cabNote)
            append("\n② 取件码: ").append(RuleStore.codePattern(r)).append("\n  （必需）")
            append("\n两条正则各自在原文上找，不是拼成一条长正则")
        }

        val sample = testInput.text.toString()
        if (sample.isEmpty()) {
            testResult.text = ""
            return
        }
        val m = RuleStore.matchOne(r, sample)
        testResult.text = if (m != null) {
            val cab = if (m.first.isEmpty()) "（未匹配到，可以没有）" else m.first
            "✔ 匹配成功　柜架号: $cab　取件码: ${m.second}"
        } else {
            "✘ 未匹配，请检查取件码前缀/后缀是否与短信原文一致"
        }
        testResult.setTextColor(if (m != null) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
    }

    private fun save() {
        val r = currentRule()
        // 四个字段全空 = 正则退化成"匹配任意数字"，会把所有短信都吃掉，必须拦住
        if (r.cabPrefix.isEmpty() && r.cabSuffix.isEmpty() &&
            r.codePrefix.isEmpty() && r.codeSuffix.isEmpty()
        ) {
            Toast.makeText(
                this, "请至少填写一个前缀或后缀，否则这条规则会匹配任意短信", Toast.LENGTH_LONG
            ).show()
            return
        }
        RuleStore.save(r)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(gray)
        setPadding(0, 24, 0, 8)
    }

    private fun splicerRow(
        leftHint: String, rightHint: String,
        leftValue: String, rightValue: String,
        centerLabel: String
    ): LinearLayout {
        fun field(hint: String, value: String): EditText = EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(field(leftHint, leftValue))
            addView(TextView(this@RuleEditActivity).apply {
                text = centerLabel
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFF1976D2.toInt())
                setPadding(12, 0, 12, 0)
            })
            addView(field(rightHint, rightValue))
        }
    }
}
