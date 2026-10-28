package phoenix.smsnotify.delivery

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.ToggleView
import phoenix.smsnotify.ui.actionButton
import phoenix.smsnotify.ui.applyInputStyle
import phoenix.smsnotify.ui.formFeedback
import phoenix.smsnotify.ui.label
import phoenix.smsnotify.ui.note
import phoenix.smsnotify.ui.pageHint
import phoenix.smsnotify.ui.ruleRow
import phoenix.smsnotify.ui.setFeedback
import phoenix.smsnotify.ui.setRowSub
import phoenix.smsnotify.ui.setRowTitle
import phoenix.smsnotify.ui.splicerTag

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuleStore.init(this)

        // 统一页面骨架：左右 16dp、上下 8dp 视觉留白，系统栏高度自动避让
        val root = pageRoot()

        root.addView(pageTitle("关键字提取规则"))
        root.addView(pageHint(
            "从上到下匹配，前面的命中后就不再往下看。带「预设」的随应用更新、只能停用，长按可删除自建规则。"
        ))

        adapter = RuleAdapter()
        val list = ListView(this).apply { dividerHeight = 2 }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(actionButton("＋ 添加规则") {
            startActivity(Intent(this@RulesActivity, RuleEditActivity::class.java)
                .putExtra("id", -1L))
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
            // 预设规则在名称后加一个小号「预设」标签：它只能停用、不能编辑或删除。
            // 标签只缩字号、不换颜色，标题始终走 setRowTitle 的同一档样式。
            val label = if (r.name.isEmpty()) "（未命名规则）" else r.name
            line1.setRowTitle(
                if (r.preset) {
                    SpannableString("$label  预设").apply {
                        setSpan(
                            RelativeSizeSpan(0.72f), label.length + 2, length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                } else {
                    label
                },
                dimmed = !r.enabled
            )

            line2.setRowSub("柜架号: ${r.cabPrefix}[柜架号]${r.cabSuffix}　取件码: ${r.codePrefix}[取件码]${r.codeSuffix}")

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
        private fun buildRow(): LinearLayout = ruleRow(this@RulesActivity)
    }
}

/** 规则编辑页：低代码拼接 → 粘贴短信实时测试。预设规则的参数只读，测试框照常可用 */
class RuleEditActivity : BaseActivity() {

    private var ruleId = -1L

    /** 进页面时的原始规则，保存时用它保留 enabled 这类界面没有的字段 */
    private var origin: ExtractRule? = null

    /** 预设规则：参数只读，不显示保存/删除，但测试框照用 */
    private var readOnly = false

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
            // 只读说明走 pageHint 的默认灰，和其他页面的导语同一档，不另上颜色
            root.addView(pageHint(
                "预设规则随应用更新，参数只读；可粘贴短信试匹配，回列表能停用。",
                topDp = 4, bottomDp = 4
            ))
        }


        // 规则名称
        root.addView(label("规则名称"))
        nameEdit = EditText(this).apply {
            hint = "如：菜鸟"
            setText(rule?.name ?: "")
            applyInputStyle()
        }
        root.addView(nameEdit)

        // 第一行：【柜架号】拼接器
        root.addView(label("柜架号提取（可选）"))
        root.addView(splicerRow("前缀", "后缀",
            rule?.cabPrefix ?: "", rule?.cabSuffix ?: "",
            "【柜架号】").also { cabRow ->
            cabPrefix = cabRow.getChildAt(0) as EditText
            cabSuffix = cabRow.getChildAt(2) as EditText
        })

        // 第二行：【取件码】拼接器
        root.addView(label("取件码提取"))
        root.addView(splicerRow("前缀", "后缀",
            rule?.codePrefix ?: "", rule?.codeSuffix ?: "",
            "【取件码】").also { codeRow ->
            codePrefix = codeRow.getChildAt(0) as EditText
            codeSuffix = codeRow.getChildAt(2) as EditText
        })
        // 「间隔」是代码里的固定行为，界面上看不见，只在取件码下面说一次
        root.addView(note(
            "取件码前缀后允许 0~4 个其他字符，如「取件码」也能匹配「取件码为111111」；柜架号必须紧贴前后缀。"
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
            btnRow.addView(actionButton("保存") { save() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (rule != null) {
                btnRow.addView(actionButton("删除", danger = true) {
                    AlertDialog.Builder(this@RuleEditActivity)
                        .setTitle("删除规则")
                        .setMessage("确定删除该规则？")
                        .setPositiveButton("删除") { _, _ ->
                            RuleStore.remove(ruleId); finish()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
            testResult.setFeedback("")
            return
        }

        val m = RuleStore.matchOne(r, sample)
        if (m == null) {
            // 具体规则上面那条 note 已经交代过，这里只说「不一致」
            testResult.setFeedback("✘ 未匹配：检查前缀/后缀是否与原文一致", ok = false)
            return
        }

        val cab = if (m.first.isEmpty()) "（未匹配到，可以没有）" else m.first
        val text = buildString {
            append("✔ 匹配成功　柜架号: ").append(cab).append("　取件码: ").append(m.second)
            // 柜架号前后缀都为空时，柜号退化成"取第一个数字"，容易抓到错误的数字，这里提醒一句
            if (r.cabPrefix.isEmpty() && r.cabSuffix.isEmpty()) {
                append("\n⚠ 柜架号前后缀都是空的，会取到短信里第一个数字，建议至少填一个")
            }
        }
        testResult.setFeedback(text, ok = true)
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

    private fun splicerRow(
        leftHint: String, rightHint: String,
        leftValue: String, rightValue: String,
        centerLabel: String
    ): LinearLayout {
        // 空着时只看得到提示语：浅灰白、不加粗，明确是「占位说明」；
        // 一旦填了内容就是黑色加粗，填没填一眼就能分辨（规格在 applyInputStyle）
        fun field(hint: String, value: String): EditText = EditText(this).apply {
            this.hint = hint
            setText(value)
            applyInputStyle(bold = true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(field(leftHint, leftValue))
            addView(splicerTag(centerLabel))
            addView(field(rightHint, rightValue))
        }
    }
}
