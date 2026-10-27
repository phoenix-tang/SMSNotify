package phoenix.smsnotify.delivery

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.KeepAliveTips
import phoenix.smsnotify.ui.pressScale
import phoenix.smsnotify.ui.rowRipple

class MainActivity : BaseActivity() {

    private var filter = 0        // 0=全部 1=未取出 2=已取出
    private var selectionMode = false
    private val selected = mutableSetOf<Long>()
    private var openItemId: Long? = null   // 当前左滑展开的条目
    private var openDelBtn: Button? = null
    private val delWidthDp = 56            // 删除按钮宽度（覆盖式，条目本身不动）
    private val deletingIds = mutableSetOf<Long>()   // 正在播删除动画的条目，防连点

    private lateinit var adapter: PickupAdapter
    private lateinit var tabs: List<Button>
    private lateinit var selectBtn: TextView
    private lateinit var selectAllBtn: Button
    private lateinit var deleteSelBtn: Button
    private lateinit var actionRow: LinearLayout
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView

    private val tabNames = arrayOf("全部", "未取出", "已取出")
    private val blue = 0xFF1976D2.toInt()
    private val gray = 0xFF9E9E9E.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        RuleStore.init(this)
        NotifyHelper.ensureChannel(this)
        setContentView(buildUi())
        adapter = PickupAdapter()
        listView.adapter = adapter
        // 权限弹窗还没结束时先不叠第二个弹窗，等结果回来再显示保活指引
        if (!requestPerms()) KeepAliveTips.showIfNeeded(this)
    }

    override fun onResume() {
        super.onResume()
        Store.listener = { runOnUiThread { adapter.notifyDataSetChanged() } }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Store.listener = null
    }

    // ---------------- UI 构建 ----------------

    private fun buildUi(): View {
        // 统一页面骨架：左右 16dp、上下 8dp 视觉留白，系统栏高度自动避让
        val root = pageRoot()

        // 标题行：标题 + 选择器入口 + 设置齿轮
        // 尺寸全部交给 BaseActivity.pageTitle / iconButton，和子页面保持一致
        val titleRow = pageTitle("快递取件记录", withBack = false)
        selectBtn = iconButton("☑") { toggleSelectionMode() }
        titleRow.addView(selectBtn)
        titleRow.addView(iconButton("⚙") {
            startActivity(android.content.Intent(this@MainActivity, SettingsActivity::class.java))
        })
        root.addView(titleRow)

        // 选择模式操作行：全选 / 删除（固定在页面底部）
        actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(10))
        }
        fun actBtn(text: String, weight: Float, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            pressScale(0.96f)
            setOnClickListener { onClick() }
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
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
        }
        tabs = tabNames.mapIndexed { i, name ->
            Button(this).apply {
                text = name
                isAllCaps = false
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                rowRipple(cornerDp = 10)   // 透明底 + 前景水波纹，不破坏选中态文字颜色
                pressScale(0.96f)
                setOnClickListener { closeSwipe(); filter = i; syncTabs(); adapter.notifyDataSetChanged() }
            }
        }
        tabs.forEach { tabRow.addView(it) }
        syncTabs()
        root.addView(tabRow)

        emptyView = TextView(this).apply {
            text = "暂无记录\n收到含“号柜+取件码”的短信后会自动记录"
            gravity = Gravity.CENTER
            setPadding(0, dp(80), 0, 0)
            setTextColor(gray)
            setOnClickListener { closeSwipe() }
        }
        root.addView(emptyView)

        listView = ListView(this).apply {
            dividerHeight = dp(1)
            // 点列表空白区域（条目以外）→ 收起删除按钮
            setOnTouchListener { _, _ -> closeSwipe(); false }
        }
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // 选择模式操作行固定在页面底部
        root.addView(actionRow)
        return root
    }

    // ---------------- 状态与刷新 ----------------

    private fun visibleRecords(): List<PickupRecord> = when (filter) {
        1 -> Store.all().filter { !it.taken }
        2 -> Store.all().filter { it.taken }
        else -> Store.all()
    }

    private fun syncTabs() {
        tabs.forEachIndexed { i, b ->
            if (i == filter) {
                b.setTextColor(blue); b.setTypeface(null, Typeface.BOLD)
            } else {
                b.setTextColor(gray); b.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    private fun syncAction() {
        deleteSelBtn.text = "删除(${selected.size})"
        deleteSelBtn.isEnabled = selected.isNotEmpty()
        deleteSelBtn.setTextColor(if (selected.isNotEmpty()) 0xFFC62828.toInt() else gray)
        val vis = visibleRecords()
        selectAllBtn.text = "全选 ${selected.size}/${vis.size}"
    }

    private fun refresh() {
        closeSwipe()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        NotifyHelper.update(this)
        if (::deleteSelBtn.isInitialized) syncAction()
    }

    /** 收起左滑删除按钮（点击条目以外区域时调用） */
    private fun closeSwipe() {
        if (openItemId == null) return
        openDelBtn?.animate()
            ?.translationX(dp(delWidthDp).toFloat())
            ?.setDuration(150)
            ?.setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            ?.start()
        openItemId = null; openDelBtn = null
    }

    /**
     * 滑动删除的退场动画，分两段：
     *   1) 红色删除层从右往左铺满整行（130ms）
     *   2) 行高收到 0 + 淡出（190ms），下面的条目跟着上移
     * 数据等动画结束才真正删，否则列表会在动画中途重排、动画白做。
     */
    private fun animateDeleteRow(row: View, delBtn: Button, r: PickupRecord) {
        if (!deletingIds.add(r.id)) return          // 防连点重复删除
        // 只清状态，不要动 translationX —— 删除层要保持在可见位置
        if (openItemId == r.id) { openItemId = null; openDelBtn = null }

        val rowH = row.height
        val delLp = delBtn.layoutParams as FrameLayout.LayoutParams
        val fullW = maxOf(row.width, delLp.width)   // 行还没测量完时退回原宽度
        delBtn.isClickable = false

        // 第一段：铺满整行
        val expand = android.animation.ValueAnimator.ofInt(delLp.width, fullW)
        expand.duration = 130
        expand.interpolator = android.view.animation.DecelerateInterpolator(1.5f)
        expand.addUpdateListener {
            delLp.width = it.animatedValue as Int
            delBtn.layoutParams = delLp
            delBtn.text = if (it.animatedFraction > 0.6f) "" else "删除"
        }
        expand.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                delBtn.text = ""
                if (rowH <= 0) { finishDeleteRow(row, r); return }
                collapseRow(row, r, rowH)
            }
        })
        expand.start()
    }

    /** 第二段：行高塌陷，制造"这条被抽走、下面往上补"的观感 */
    private fun collapseRow(row: View, r: PickupRecord, fromHeight: Int) {
        val lp = row.layoutParams ?: FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, fromHeight
        )
        lp.height = fromHeight
        row.layoutParams = lp

        val collapse = android.animation.ValueAnimator.ofInt(fromHeight, 0)
        collapse.duration = 190
        collapse.interpolator = android.view.animation.AccelerateInterpolator(1.4f)
        collapse.addUpdateListener { a ->
            lp.height = a.animatedValue as Int
            row.layoutParams = lp          // 每帧重设 → 触发列表重新布局，下方条目逐帧上移
            row.alpha = 1f - a.animatedFraction * 0.85f
        }
        collapse.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                finishDeleteRow(row, r)
            }
        })
        collapse.start()
    }

    /** 动画收尾：复位被动画改过的属性（列表会复用这个 View），再真正删数据 */
    private fun finishDeleteRow(row: View, r: PickupRecord) {
        deletingIds.remove(r.id)
        row.alpha = 1f
        row.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT
        val delBtn = (row as? FrameLayout)?.getChildAt(1) as? Button
        if (delBtn != null) {
            (delBtn.layoutParams as? FrameLayout.LayoutParams)?.width = dp(delWidthDp)
            delBtn.translationX = dp(delWidthDp).toFloat()
            delBtn.text = "删除"
        }
        Store.remove(r.id)
        selected.remove(r.id)
        refresh()
    }

    private fun toggleSelectionMode() {
        selectionMode = !selectionMode
        if (!selectionMode) selected.clear()
        selectBtn.text = if (selectionMode) "✕" else "☑"
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
        closeSwipe()
        syncAction(); adapter.notifyDataSetChanged()
    }

    private fun confirmDeleteSelected() {
        if (selected.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("删除记录")
            .setMessage("确定删除选中的 ${selected.size} 条记录？")
            .setPositiveButton("删除") { _, _ ->
                Store.removeAll(selected)
                selected.clear()
                selectionMode = false
                selectBtn.text = "☑"       // 之前误写成"选择"，和图标按钮不一致
                actionRow.animate().cancel()
                actionRow.alpha = 1f
                actionRow.translationY = 0f
                actionRow.visibility = View.GONE
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 返回是否弹出了权限申请（弹出时保活指引要等申请结果回来再显示，避免两个弹窗叠一起） */
    private fun requestPerms(): Boolean {
        val perms = mutableListOf(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return false
        requestPermissions(missing.toTypedArray(), 1)
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        // 无论用户同意还是拒绝都提示一次：没权限收不到短信，没白名单同样收不到
        KeepAliveTips.showIfNeeded(this)
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
            val holder = convertView as? FrameLayout ?: createRow()
            val r = getItem(position)

            val delBtn = holder.getChildAt(1) as Button
            val front = holder.getChildAt(0) as LinearLayout
            val check = front.getChildAt(0) as CheckBox
            val status = front.getChildAt(1) as TextView
            val textCol = front.getChildAt(2) as LinearLayout
            val line1 = textCol.getChildAt(0) as TextView
            val line2 = textCol.getChildAt(1) as TextView

            // 覆盖式：条目不动，删除按钮从右缘滑入（收起时平移出屏幕外）
            // 复用条目时要把删除动画改过的宽度/文字复位，否则会残留"整行红条"
            val delLp = delBtn.layoutParams as FrameLayout.LayoutParams
            if (delLp.width != dp(delWidthDp)) {
                delLp.width = dp(delWidthDp)
                delBtn.layoutParams = delLp
            }
            delBtn.text = "删除"
            delBtn.isClickable = true
            delBtn.translationX = if (openItemId == r.id) 0f else dp(delWidthDp).toFloat()

            // 选择器复选框
            check.visibility = if (selectionMode) View.VISIBLE else View.GONE
            check.isChecked = r.id in selected

            // 文本：服务商 柜号 取件码（柜号可能没有，summary 会自动省略不留空格）
            line1.text = r.summary
            line1.textSize = 17f
            line1.setTextColor(if (r.taken) gray else Color.BLACK)
            line1.setTypeface(null, Typeface.BOLD)
            // 状态圆标：绿√=已取，黄i=未取
            status.text = if (r.taken) "✓" else "i"
            (status.background as android.graphics.drawable.GradientDrawable)
                .setColor(if (r.taken) 0xFF2E7D32.toInt() else 0xFFF9A825.toInt())

            line2.text = Store.fmtTime(r.time)
            line2.textSize = 13f
            line2.setTextColor(gray)

            // 点击：选择模式下勾选/取消勾选；普通模式点击条目 = 收起删除按钮（若展开），否则切换取出状态
            front.setOnClickListener {
                if (selectionMode) {
                    if (r.id in selected) selected.remove(r.id) else selected.add(r.id)
                    check.isChecked = r.id in selected
                    syncAction()
                } else if (openItemId != null) {
                    closeSwipe()   // 点了其他地方 → 隐藏删除按钮
                } else {
                    Store.toggle(r.id); refresh()
                }
            }
            // 长按：单条删除确认（仅普通模式）
            front.setOnLongClickListener {
                if (selectionMode) return@setOnLongClickListener false
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("删除记录")
                    .setMessage("删除 ${r.desc} 的记录？")
                    .setPositiveButton("删除") { _, _ -> Store.remove(r.id); refresh() }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            bindSwipe(front, r, delBtn)

            // 覆盖在条目右侧的删除按钮：点它 → 走退场动画，动画结束才真正删数据
            delBtn.setOnClickListener { animateDeleteRow(holder, delBtn, r) }
            return holder
        }

        private fun createRow(): FrameLayout {
            val check = CheckBox(this@MainActivity).apply {
                isClickable = false; isFocusable = false
            }
            // 状态圆标：绿圈√=已取 / 黄圈i=未取
            val statusIcon = TextView(this@MainActivity).apply {
                gravity = Gravity.CENTER
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xFFF9A825.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                    marginEnd = dp(10)
                }
            }
            val textCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity))
                addView(TextView(this@MainActivity))
            }
            // 前景条目：宽度固定，左侧文字始终可见，右侧越界文字被删除按钮覆盖
            val front = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(dp(8), dp(10), dp(8), dp(10))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                rowRipple()   // 按压反馈由 bindSwipe 里手动切 pressed 状态触发
                addView(check)
                addView(statusIcon)
                addView(textCol)
            }
            // 删除按钮：铺满行高，平时平移出屏幕右缘（收起），左滑时滑入覆盖右侧
            val delBtn = Button(this@MainActivity).apply {
                text = "删除"
                setBackgroundColor(0xFFE53935.toInt())
                setTextColor(Color.WHITE)
                translationX = dp(delWidthDp).toFloat()
                layoutParams = FrameLayout.LayoutParams(
                    dp(delWidthDp), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END
                )
            }
            return FrameLayout(this@MainActivity).apply {
                addView(front)
                addView(delBtn)   // 后添加 → 画在条目上层
            }
        }
    }

    // ---------------- 左滑手势 ----------------

    private fun bindSwipe(front: View, r: PickupRecord, delBtn: Button) {
        val delW = dp(delWidthDp).toFloat()
        var downX = 0f; var downY = 0f; var startDelTx = 0f; var downAt = 0L
        var dragging = false

        front.setOnTouchListener { v, ev ->
            if (selectionMode) return@setOnTouchListener false   // 选择模式下禁用滑动
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    startDelTx = if (openItemId == r.id) 0f else delW
                    downAt = SystemClock.elapsedRealtime()
                    dragging = false
                    delBtn.animate().cancel()   // 手指接管，停掉上一次的回弹动画
                    v.isPressed = true          // 手动切 pressed，触发前景水波纹
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!dragging) {
                        if (Math.abs(dx) > dp(8) && Math.abs(dx) > Math.abs(dy)) {
                            dragging = true
                            v.isPressed = false
                            (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                            // 同时只允许一条展开（另一条用短动画收回）
                            if (openDelBtn != null && openItemId != r.id) {
                                openDelBtn?.animate()?.translationX(delW)?.setDuration(150)?.start()
                                openDelBtn = null; openItemId = null
                            }
                        } else if (Math.abs(dy) > dp(8)) {
                            v.isPressed = false
                            return@setOnTouchListener false   // 纵向意图 → 交给列表滚动
                        }
                    }
                    if (dragging) {
                        // 删除按钮跟随手指滑入/滑出，条目本身不动
                        delBtn.translationX = (startDelTx + dx).coerceIn(0f, delW)
                        // 露出过程同步淡入，滑入一点就有"红色浮出来"的层次感
                        delBtn.alpha = 0.4f + 0.6f * (1f - delBtn.translationX / delW)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (dragging) {
                        val open = delBtn.translationX < delW / 2
                        // 松手后带缓动收尾，而不是瞬间跳到终点
                        delBtn.animate()
                            .translationX(if (open) 0f else delW)
                            .alpha(1f)
                            .setDuration(if (open) 220L else 170L)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                            .start()
                        if (open) { openItemId = r.id; openDelBtn = delBtn }
                        else if (openItemId == r.id) { openItemId = null; openDelBtn = null }
                        true
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        // 未产生滑动：按按压时长分派点击 / 长按
                        if (SystemClock.elapsedRealtime() - downAt > 500) v.performLongClick()
                        else v.performClick()
                        true
                    } else false
                }
                else -> false
            }
        }
    }
}
