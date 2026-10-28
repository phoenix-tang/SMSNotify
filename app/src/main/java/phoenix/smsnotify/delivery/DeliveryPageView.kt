package phoenix.smsnotify.delivery

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import phoenix.smsnotify.common.TimeFmt
import phoenix.smsnotify.ui.AppColor
import phoenix.smsnotify.ui.AppIcons
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.KeepAliveTips
import phoenix.smsnotify.ui.PathIconView
import phoenix.smsnotify.ui.SwipeGroup
import phoenix.smsnotify.ui.SwipeRow
import phoenix.smsnotify.ui.actionButton
import phoenix.smsnotify.ui.emptyHint
import phoenix.smsnotify.ui.filterTab
import phoenix.smsnotify.ui.rowRipple
import phoenix.smsnotify.ui.setFilterState
import phoenix.smsnotify.ui.setRowSub
import phoenix.smsnotify.ui.setRowTitle
import phoenix.smsnotify.ui.statusDot

/**
 * 「快递服务」这一屏：取件记录列表（筛选、全选删除、左滑删除）。
 *
 * 原来这一屏就是 MainActivity 本身；加了底部导航之后它变成了主页里的一屏，
 * 所以逻辑整体搬到这里，只剩「页面骨架」由宿主 Activity 负责。
 *
 * 它不是一个 Activity，但要用到宿主的 dp / pageTitle / iconButton，
 * 因此实现上直接持有 [BaseActivity]（见 BaseActivity 里对 internal 的说明）。
 *
 * 生命周期对齐页面可见性：宿主在 onResume 调 [attach]、onPause 调 [detach]，
 * 切到另一屏时不需要额外处理 —— 数据监听挂在 Store 上，与哪一屏在前台无关。
 */
class DeliveryPageView(private val act: BaseActivity) : LinearLayout(act) {

    private var filter = 0        // 0=全部 1=未取出 2=已取出
    private var selectionMode = false
    private val selected = mutableSetOf<Long>()

    /** 左滑删除的展开状态：同一时刻只允许一行露出删除按钮 */
    private val swipe = SwipeGroup()

    private lateinit var adapter: PickupAdapter
    private lateinit var tabs: List<Button>
    private lateinit var selectBtn: PathIconView
    private lateinit var selectAllBtn: Button
    private lateinit var deleteSelBtn: Button
    private lateinit var actionRow: LinearLayout
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView

    private val tabNames = arrayOf("全部", "未取出", "已取出")

    private fun dp(v: Int): Int = act.dp(v)

    init {
        // 与 BaseActivity.pageRoot() 保持一致：左右 16dp、上下 8dp
        orientation = VERTICAL
        gravity = Gravity.TOP
        setPadding(dp(16), dp(8), dp(16), dp(8))
        Store.init(act)
        RuleStore.init(act)
        buildUi()
        adapter = PickupAdapter()
        listView.adapter = adapter
    }

    // ---------------- 生命周期 ----------------

    /** 页面可见时挂上数据监听，并刷新一次（切回来能看到新短信） */
    fun attach() {
        Store.listener = { act.runOnUiThread { adapter.notifyDataSetChanged() } }
        refresh()
    }

    fun detach() {
        Store.listener = null
    }

    // ---------------- UI 构建 ----------------

    private fun buildUi() {
        // 标题行：标题 + 帮助 / 选择 / 规则三个图标（从左到右）
        // 尺寸全部交给 BaseActivity.pageTitle / iconButton，和子页面保持一致
        val titleRow = act.pageTitle("快递取件记录", withBack = false)
        // 帮助：随时重看「后台保活」指引（首次安装时是自动弹的，之后从这里进）
        titleRow.addView(act.iconButton(AppIcons.help()) { KeepAliveTips.showNow(act) })
        selectBtn = act.iconButton(AppIcons.select()) { toggleSelectionMode() }
        titleRow.addView(selectBtn)
        // 规则列表：书页图标直达，不再藏在设置页里（设置已经升到底部导航的独立一栏）
        titleRow.addView(act.iconButton(AppIcons.book()) {
            act.startActivity(Intent(act, RulesActivity::class.java))
        })
        addView(titleRow)

        // 选择模式操作行：全选 / 删除（固定在页面底部）
        actionRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(10))
        }
        fun actBtn(text: String, weight: Float, onClick: () -> Unit) =
            act.actionButton(text) { onClick() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, weight
                )
            }
        selectAllBtn = actBtn("全选", 1f) {
            val vis = visibleRecords()
            if (vis.isNotEmpty() && selected.containsAll(vis.map { it.id })) {
                selected.clear()
            } else {
                selected.clear(); vis.forEach { selected.add(it.id) }
            }
            syncAction(); adapter.notifyDataSetChanged()
        }
        deleteSelBtn = actBtn("删除(0)", 1f) { confirmDeleteSelected() }
        actionRow.addView(selectAllBtn)
        actionRow.addView(deleteSelBtn)

        // 过滤标签行
        val tabRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
        }
        tabs = tabNames.mapIndexed { i, name ->
            act.filterTab(name).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { swipe.close(); filter = i; syncTabs(); adapter.notifyDataSetChanged() }
            }
        }
        tabs.forEach { tabRow.addView(it) }
        syncTabs()
        addView(tabRow)

        emptyView = act.emptyHint("暂无记录\n收到含取件码的短信后会自动记录", topDp = 80)
            .apply { setOnClickListener { swipe.close() } }
        addView(emptyView)

        listView = ListView(act).apply {
            dividerHeight = dp(1)
            // 点列表空白区域（条目以外）→ 收起删除按钮
            setOnTouchListener { _, _ -> swipe.close(); false }
        }
        addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // 选择模式操作行固定在页面底部
        addView(actionRow)
    }

    // ---------------- 状态与刷新 ----------------

    private fun visibleRecords(): List<PickupRecord> = when (filter) {
        1 -> Store.all().filter { !it.taken }
        2 -> Store.all().filter { it.taken }
        else -> Store.all()
    }

    private fun syncTabs() {
        tabs.forEachIndexed { i, b -> b.setFilterState(i == filter) }
    }

    private fun syncAction() {
        deleteSelBtn.text = "删除(${selected.size})"
        deleteSelBtn.isEnabled = selected.isNotEmpty()
        deleteSelBtn.setTextColor(if (selected.isNotEmpty()) AppColor.redText else AppColor.muted)
        val vis = visibleRecords()
        selectAllBtn.text = "全选 ${selected.size}/${vis.size}"
    }

    fun refresh() {
        swipe.close(animate = false)
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        NotifyHelper.update(act)
        if (::deleteSelBtn.isInitialized) syncAction()
    }

    private fun toggleSelectionMode() {
        selectionMode = !selectionMode
        if (!selectionMode) selected.clear()
        selectBtn.icon = if (selectionMode) AppIcons.close() else AppIcons.select()
        if (selectionMode) {
            // 底部操作栏滑入：先藏在下边缘外，再上浮淡入
            // 先 cancel 掉上一轮收起的动画，否则它的 endAction 会把刚显示的栏又设为 GONE
            actionRow.animate().cancel()
            actionRow.visibility = View.VISIBLE
            actionRow.alpha = 0f
            actionRow.translationY = dp(20).toFloat()
            actionRow.animate()
                .alpha(1f).translationY(0f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        } else {
            actionRow.animate()
                .alpha(0f).translationY(dp(20).toFloat())
                .setDuration(140)
                .withEndAction { actionRow.visibility = View.GONE }
                .start()
        }
        swipe.close()
        syncAction(); adapter.notifyDataSetChanged()
    }

    private fun confirmDeleteSelected() {
        if (selected.isEmpty()) return
        AlertDialog.Builder(act)
            .setTitle("删除记录")
            .setMessage("确定删除选中的 ${selected.size} 条记录？")
            .setPositiveButton("删除") { _, _ ->
                Store.removeAll(selected)
                selected.clear()
                selectionMode = false
                selectBtn.icon = AppIcons.select()
                actionRow.animate().cancel()
                actionRow.alpha = 1f
                actionRow.translationY = 0f
                actionRow.visibility = View.GONE
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 列表适配器 ----------------

    inner class PickupAdapter : BaseAdapter() {
        override fun getCount(): Int {
            val n = visibleRecords().size
            emptyView.visibility = if (n == 0) View.VISIBLE else View.GONE
            return n
        }
        override fun getItem(position: Int): PickupRecord = visibleRecords()[position]
        override fun getItemId(position: Int): Long = getItem(position).id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = convertView as? SwipeRow ?: createRow()
            val r = getItem(position)

            val front = holder.front as LinearLayout
            val check = front.getChildAt(0) as CheckBox
            val status = front.getChildAt(1) as TextView
            val textCol = front.getChildAt(2) as LinearLayout
            val line1 = textCol.getChildAt(0) as TextView
            val line2 = textCol.getChildAt(1) as TextView

            // 选择器复选框
            check.visibility = if (selectionMode) View.VISIBLE else View.GONE
            check.isChecked = r.id in selected

            // 文本：服务商 柜号 取件码（柜号可能没有，summary 会自动省略不留空格）
            line1.setRowTitle(r.summary, dimmed = r.taken)
            // 状态圆标：绿√=已取，黄i=未取
            status.text = if (r.taken) "✓" else "i"
            (status.background as android.graphics.drawable.GradientDrawable)
                .setColor(if (r.taken) AppColor.greenText else AppColor.amber)

            line2.setRowSub(TimeFmt.short(r.time))

            // 点击：选择模式下勾选/取消勾选；普通模式点击条目 = 收起删除按钮（若展开），否则切换取出状态
            front.setOnClickListener {
                if (selectionMode) {
                    if (r.id in selected) selected.remove(r.id) else selected.add(r.id)
                    check.isChecked = r.id in selected
                    syncAction()
                } else if (swipe.anyOpen) {
                    swipe.close()   // 点了其他地方 → 隐藏删除按钮
                } else {
                    Store.toggle(r.id); refresh()
                }
            }
            // 长按：单条删除确认（仅普通模式）
            front.setOnLongClickListener {
                if (selectionMode) return@setOnLongClickListener false
                AlertDialog.Builder(act)
                    .setTitle("删除记录")
                    .setMessage("删除 ${r.desc} 的记录？")
                    .setPositiveButton("删除") { _, _ -> Store.remove(r.id); refresh() }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            // 手势 + 删除按钮：覆盖式滑入、退场动画、逐条复位都在 SwipeRow 里
            holder.bind(
                swipe, r.id, swipe.isOpen(r.id), isEnabled = { !selectionMode }
            ) {
                Store.remove(r.id)
                selected.remove(r.id)
                refresh()
            }
            return holder
        }

        private fun createRow(): SwipeRow {
            val check = CheckBox(act).apply {
                isClickable = false; isFocusable = false
            }
            // 状态圆标：绿圈√=已取 / 黄圈i=未取
            val statusIcon = act.statusDot("i", AppColor.amber)
            val textCol = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(act).apply { setRowTitle("") })
                addView(TextView(act).apply { setRowSub("") })
            }
            // 前景条目：宽度固定，左侧文字始终可见，右侧越界文字被删除按钮覆盖
            val front = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(dp(8), dp(10), dp(8), dp(10))
                rowRipple()   // 按压反馈由 SwipeRow 里手动切 pressed 状态触发
                addView(check)
                addView(statusIcon)
                addView(textCol)
            }
            return SwipeRow(act, front)
        }
    }
}
