package phoenix.smsnotify.ui

import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 底部导航的一项：一个图标 + 一行文案。
 *
 * 图标用 [AppIcons] 里的 24x24 路径，导航栏按 24dp 画出来。
 */
class NavItem(val label: String, val icon: Path)

/**
 * 底部导航栏（微信 / 支付宝那种形态）：图标在上、文字在下，每项独立居中，选中变色。
 *
 * 为什么不用「顶部指示条 + 纯文字」那套：那种长条更像 Android 的 TabLayout，
 * 两个 Tab 之间没有任何分隔，整条白底连成一片，看不出是两个独立的入口。
 * 主流 App 的做法是靠 **图标** 建立每个入口的身份，靠 **颜色** 表示选中，
 * 不加整块的选中底色，也不做位移动画。
 *
 *      ┌──────────────┬──────────────┐
 *      │     📦       │      🔔      │  ← 24dp 描边图标，选中蓝
 *      │   快递服务    │  重要短信提醒  │  ← 11sp，选中蓝
 *      └──────────────┴──────────────┘
 *
 * 项目没有 AndroidX，所以不用 BottomNavigationView，全部用 LinearLayout 拼；
 * 尺寸、颜色都在本类定死，宿主只管传 [NavItem] 和回调：
 *
 *     nav = BottomNavBar(this, listOf(
 *         NavItem("快递服务", AppIcons.parcel()),
 *         NavItem("重要短信提醒", AppIcons.bell())
 *     )) { index -> selectTab(index) }
 */
class BottomNavBar(
    private val act: BaseActivity,
    private val items: List<NavItem>,
    private val onSelect: (Int) -> Unit
) : LinearLayout(act) {

    private val icons = mutableListOf<PathIconView>()
    private val labels = mutableListOf<TextView>()
    private var selected = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(AppColor.surface)

        // 顶部发丝线：1px，只为了让白色内容区和白色导航栏有个边界，不构成"色带"
        addView(View(act).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(AppColor.divider)
        })

        val row = LinearLayout(act).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(TAB_BAR_DP))
        }
        items.forEachIndexed { i, item -> row.addView(buildTab(i, item)) }
        addView(row)

        setSelected(0)
    }

    private fun buildTab(index: Int, item: NavItem): View {
        val icon = PathIconView(act).apply {
            this.icon = item.icon
            iconColor = AppColor.hint
            layoutParams = LayoutParams(act.dp(ICON_DP), act.dp(ICON_DP))
        }
        val label = TextView(act).apply {
            text = item.label
            textSize = LABEL_SP
            gravity = Gravity.CENTER
            setTextColor(AppColor.hint)
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = act.dp(LABEL_GAP_DP) }
        }
        icons.add(icon)
        labels.add(label)

        return LinearLayout(act).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // 左右留 6dp：水波纹是圆角矩形，留白后按压时不会和隔壁那一项糊在一起
            setPadding(0, act.dp(TAB_PAD_TOP_DP), 0, 0)
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = act.dp(6)
                marginEnd = act.dp(6)
            }
            rowRipple(cornerDp = 14)
            isClickable = true
            isFocusable = true
            contentDescription = item.label
            addView(icon)
            addView(label)
            setOnClickListener { if (selected != index) onSelect(index) }
        }
    }

    /**
     * 切换选中态：只改颜色。
     *
     * 刻意不做加粗 —— 中文加粗会让文字宽一点，切换时整行会轻微抖动；
     * 也不移动图标、不加动画，和微信保持一致（切换是瞬时的）。
     */
    fun setSelected(index: Int) {
        selected = index
        icons.forEachIndexed { i, v -> v.iconColor = if (i == index) AppColor.blue else AppColor.hint }
        labels.forEachIndexed { i, t ->
            t.setTextColor(if (i == index) AppColor.blue else AppColor.hint)
        }
    }

    private companion object {
        /** 导航栏内容高度（不含顶部发丝线）。56dp 是微信 / 支付宝这一档的高度 */
        const val TAB_BAR_DP = 56

        /** 图标尺寸：24dp 是主流底部导航的标准值 */
        const val ICON_DP = 24

        /** 图标与文字的间距 */
        const val LABEL_GAP_DP = 3

        /** 整块内容偏下一点更好看：图标顶到最上反而显得挤 */
        const val TAB_PAD_TOP_DP = 6

        /** 文字字号：底部导航用 11sp，比正文小、比辅助说明大 */
        const val LABEL_SP = 11f
    }
}
