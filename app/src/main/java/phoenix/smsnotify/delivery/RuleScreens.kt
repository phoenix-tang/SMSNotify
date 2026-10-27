package phoenix.smsnotify.delivery

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
import phoenix.smsnotify.ui.AppColor
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.ToggleView
import phoenix.smsnotify.ui.pressScale
import phoenix.smsnotify.ui.rowRipple

/**
 * 规则相关的两个页面：
 *
 *  - [RulesActivity]     规则列表（开关、长按删除、底部添加）
 *  - [RuleEditActivity]  单条规则的编辑（低代码拼接 → 粘贴短信测试）
 *
 * 两者的数据来源都是 [RuleStore]，放在一个文件里方便对照列表展示和编辑表单的字段。
 *
 * 预设规则（[ExtractRule.preset]）与自建规则的权限不同：可停用，但不能进编辑页、不能删除，
 * 内容随应用更新同步；自建规则完全归用户自己。
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
            text = "从上到下依次匹配，前面的规则命中后就不再往下看。" +
                "预设规则排在前面并随应用更新，只能停用；自己添加的规则排在后面，不会被更新改动。"
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

            // 名称；停用的整行变灰。列表里不显示序号——两种规则视觉一致，
            // 顺序的含义（先匹配的优先）由页面顶部的说明交代。
            // 预设规则在名称后加一个小号「预设」标签：它只能停用、不能编辑或删除
            val label = if (r.name.isEmpty()) "（未命名规则）" else r.name
            line1.text = if (r.preset) {
                val s = SpannableString("$label  预设")
                s.setSpan(
                    RelativeSizeSpan(0.72f), label.length + 2, s.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                s
            } else {
                label
            }
            line1.textSize = 17f
            line1.setTextColor(
                when {
                    !r.enabled -> gray
                    r.preset -> AppColor.blue
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
                // 预设规则也能进来，只是页里参数只读、只能用测试框试匹配
                startActivity(Intent(this@RulesActivity, RuleEditActivity::class.java)
                    .putExtra("id", r.id))
            }
            row.setOnLongClickListener {
                if (r.preset) {
                    Toast.makeText(
                        this@RulesActivity,
                        "预设规则随应用更新，不能删除，可以用右侧开关停用",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    AlertDialog.Builder(this@RulesActivity)
                        .setTitle("删除规则")
                        .setMessage("删除规则「${if (r.name.isEmpty()) "未命名规则" else r.name}」？")
                        .setPositiveButton("删除") { _, _ ->
                            RuleStore.remove(r.id)
                            notifyDataSetChanged()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
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

/** 规则编辑页：低代码拼接 → 粘贴短信实时测试。预设规则的参数只读，测试框照常可用 */
class RuleEditActivity : BaseActivity() {

    private var ruleId = -1L

    /** 进页面时的原始规则，保存时用它保留 enabled 这类界面没有的字段 */
    private var origin: ExtractRule? = null

    /** 预设规则：参数只读，不显示保存/删除，但测试框照用 */
    private var readOnly = false

    private val gray = 0xFF9E9E9E.toInt()

    /** 前后缀输入框里「前缀」「后缀」占位提示的字色：比标签灰再浅一档 */
    private val hintGray = 0xFFBDBDBD.toInt()

    private lateinit var nameEdit: EditText
    private lateinit var cabPrefix: EditText
    private lateinit var cabSuffix: EditText
    private lateinit var codePrefix: EditText
    private lateinit var codeSuffix: EditText
    private lateinit var testInput: EditText
    private lateinit var testResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        avoidIme = true   // 表单页：键盘弹出时让内容避让，而不是被盖住
        RuleStore.init(this)
        ruleId = intent.getLongExtra("id", -1L)
        val rule = if (ruleId >= 0) RuleStore.find(ruleId) else null
        origin = rule
        readOnly = rule?.preset == true

        // 统一页面骨架：左右 16dp、上下 8dp 视觉留白，系统栏高度自动避让
        val root = pageRoot()

        // 标题行：返回箭头 + 标题（统一走 BaseActivity.pageTitle，和主页同规格）
        root.addView(pageTitle(
            when {
                rule == null -> "添加规则"
                readOnly -> "查看预设规则"
                else -> "编辑规则"
            }
        ))

        if (readOnly) {
            root.addView(TextView(this).apply {
                text = "预设规则随应用更新：参数只读，不能修改或删除，只能回列表用开关停用（自己添加的规则不受影响）。" +
                    "下面可以粘贴短信试匹配。"
                textSize = 13f
                setTextColor(AppColor.blue)
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
        // 「间隔」是代码里的固定行为，界面上看不见，所以在这里说明它到底放宽了什么
        root.addView(note(
            "注意：前缀和取件码之间允许 0~4 个其他字符，所以前缀填「取件码」就能匹配「取件码111111」「取件码为111111」「取件码： 111111」。\n" +
            "柜架号那一侧没有这个间隔，必须紧贴前后缀。"
        ))

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

        // 预设规则：参数框去掉输入能力（长按仍可复制），测试框不动，物尽其用
        if (readOnly) {
            listOf(nameEdit, cabPrefix, cabSuffix, codePrefix, codeSuffix).forEach { et ->
                et.keyListener = null          // 不接收任何键盘输入
                et.isCursorVisible = false
                et.showSoftInputOnFocus = false
            }
        }

        // 保存 / 删除：预设规则没有可保存的字段，也不给删除入口
        if (!readOnly) {
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
        }

        // 表单页放进可滚动容器：键盘弹出时滚动区会变矮，内容不会被键盘盖住
        setContentView(scrollPage(root))

        // 任意输入变化 → 重跑测试
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateTest()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(nameEdit, cabPrefix, cabSuffix, codePrefix, codeSuffix, testInput).forEach {
            it.addTextChangedListener(watcher)
        }
        updateTest()
    }

    private fun currentRule(): ExtractRule = ExtractRule(
        id = ruleId, name = nameEdit.text.toString().trim(),
        cabPrefix = cabPrefix.text.toString(), cabSuffix = cabSuffix.text.toString(),
        codePrefix = codePrefix.text.toString(), codeSuffix = codeSuffix.text.toString(),
        // 界面没有 enabled / preset，沿用进页面时的值，避免保存时把「已停用」悄悄改回启用
        enabled = origin?.enabled ?: true,
        preset = origin?.preset ?: false
    )

    private fun updateTest() {
        val r = currentRule()
        val sample = testInput.text.toString()
        if (sample.isEmpty()) {
            testResult.text = ""
            return
        }

        val m = RuleStore.matchOne(r, sample)
        if (m == null) {
            testResult.text = "✘ 未匹配，请检查取件码前缀/后缀是否与短信原文一致" +
                "（前缀后允许 0~4 个其他字符，再往后要是 3 位以上数字）"
            testResult.setTextColor(0xFFC62828.toInt())
            return
        }

        val cab = if (m.first.isEmpty()) "（未匹配到，可以没有）" else m.first
        testResult.text = buildString {
            append("✔ 匹配成功　柜架号: ").append(cab).append("　取件码: ").append(m.second)
            // 柜架号前后缀都为空时，柜号退化成"取第一个数字"，容易抓到错误的数字，这里提醒一句
            if (r.cabPrefix.isEmpty() && r.cabSuffix.isEmpty()) {
                append("\n⚠ 柜架号前后缀都是空的，会取到短信里第一个数字，建议至少填一个")
            }
        }
        testResult.setTextColor(0xFF2E7D32.toInt())
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

    /** 小字说明：黑色加粗、字号偏小，挂在某个输入区下面，解释用户看不见的固定规则 */
    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.BLACK)
        setTypeface(null, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, 6, 0, 0)
    }

    private fun splicerRow(
        leftHint: String, rightHint: String,
        leftValue: String, rightValue: String,
        centerLabel: String
    ): LinearLayout {
        fun field(hint: String, value: String): EditText = EditText(this).apply {
            // 空着时只看得到提示语：浅灰白、不加粗，明确是「占位说明」；
            // 一旦填了内容就是黑色加粗，填没填一眼就能分辨
            setHint(SpannableString(hint).apply {
                setSpan(
                    ForegroundColorSpan(hintGray), 0, length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(StyleSpan(Typeface.NORMAL), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            })
            setHintTextColor(hintGray)
            setText(value)
            textSize = 14f
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
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
                setTextColor(AppColor.blue)
                setPadding(12, 0, 12, 0)
            })
            addView(field(rightHint, rightValue))
        }
    }
}
