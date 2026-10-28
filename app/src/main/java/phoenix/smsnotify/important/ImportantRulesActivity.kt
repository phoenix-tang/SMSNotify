package phoenix.smsnotify.important

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.ToggleView
import phoenix.smsnotify.ui.actionButton
import phoenix.smsnotify.ui.pageHint
import phoenix.smsnotify.ui.ruleRow
import phoenix.smsnotify.ui.setRowSub
import phoenix.smsnotify.ui.setRowTitle

/**
 * 重要短信的规则列表（一级子页）。从主页标题栏的书页图标进来。
 *
 * 和「重要短信提醒」主页的分工：
 *  - 主页是**结果**：哪条规则最近命中、每条规则下都记了些什么；
 *  - 这里是**配置**：有哪些规则、开着还是关着、点进去改、底部新建。
 *
 * 之所以从主页里抽出来：规则是低频操作，而主页的卡片本身就要承担展开/查看记录，
 * 底部再压一个「＋ 添加匹配规则」会一直占着一行高度，也容易和卡片长按菜单打架。
 *
 * 列表顺序沿用 [ImportantStore.rules]（按添加时间倒序，最新的在最上面），
 * 和主页卡片顺序一致 —— 两边看到的"第一条"永远是同一条规则。
 */
class ImportantRulesActivity : BaseActivity() {

    private lateinit var adapter: RuleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ImportantStore.init(this)

        val root = pageRoot()
        root.addView(pageTitle("重要短信规则"))
        root.addView(pageHint("点规则可编辑，开关控制是否生效，长按删除。"))

        adapter = RuleAdapter()
        val list = ListView(this).apply { dividerHeight = 2 }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(actionButton("＋ 添加匹配规则") {
            startActivity(Intent(this@ImportantRulesActivity, ImportantRuleEditActivity::class.java)
                .putExtra("id", -1L))
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }

    inner class RuleAdapter : BaseAdapter() {

        private val rules: List<ImportantRule> get() = ImportantStore.rules()

        override fun getCount(): Int = rules.size

        override fun getItem(position: Int): ImportantRule = rules[position]

        override fun getItemId(position: Int): Long = getItem(position).id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: buildRow()
            val r = getItem(position)

            val col = row.getChildAt(0) as LinearLayout
            val line1 = col.getChildAt(0) as TextView
            val line2 = col.getChildAt(1) as TextView
            val toggle = row.getChildAt(1) as ToggleView

            line1.setRowTitle(r.label, dimmed = !r.enabled)

            // 配置摘要：命中的文字 + 非默认的响铃时长（默认档不占位置）
            // 名称留空时 label 取的是第一段文字，摘要里就不再重复它
            val keywords = if (r.name.isBlank()) r.keywords.drop(1) else r.keywords
            val parts = mutableListOf<String>()
            if (keywords.isNotEmpty()) parts.add(keywords.joinToString("、"))
            if (r.ringMode != RingMode.DEFAULT) parts.add(r.ringMode.badge)
            line2.setRowSub(parts.joinToString("　·　"))

            toggle.setChecked(r.enabled, animate = false)
            toggle.onCheckedChange = { checked ->
                ImportantStore.setEnabled(r.id, checked)
                notifyDataSetChanged()   // 停用后整行变灰
            }

            row.setOnClickListener {
                startActivity(Intent(this@ImportantRulesActivity, ImportantRuleEditActivity::class.java)
                    .putExtra("id", r.id))
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this@ImportantRulesActivity)
                    .setTitle("删除规则")
                    .setMessage("删除「${r.label}」及其全部记录？")
                    .setPositiveButton("删除") { _, _ ->
                        ImportantStore.remove(r.id)
                        notifyDataSetChanged()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            return row
        }

        /** 行结构固定为 [文字列(weight=1), 开关] */
        private fun buildRow(): LinearLayout = ruleRow(this@ImportantRulesActivity)
    }
}
