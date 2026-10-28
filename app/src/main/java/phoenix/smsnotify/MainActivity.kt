package phoenix.smsnotify

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import phoenix.smsnotify.delivery.DeliveryPageView
import phoenix.smsnotify.delivery.NotifyHelper
import phoenix.smsnotify.delivery.RuleStore
import phoenix.smsnotify.delivery.Store
import phoenix.smsnotify.important.AlarmRing
import phoenix.smsnotify.important.ImportantNotify
import phoenix.smsnotify.important.ImportantPageView
import phoenix.smsnotify.important.ImportantStore
import phoenix.smsnotify.settings.SettingsPageView
import phoenix.smsnotify.ui.AppIcons
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.BottomNavBar
import phoenix.smsnotify.ui.KeepAliveTips
import phoenix.smsnotify.ui.NavItem

/**
 * 主页：底部导航 + 三屏内容。
 *
 *      ┌──────────────────────────┐
 *      │ 快递服务 / 重要短信提醒 / 设置（内容容器）│
 *      ├──────────────────────────┤
 *      │ 快递服务 │ 重要短信提醒 │ 设置 │  ← BottomNavBar
 *      └──────────────────────────┘
 *
 * 三屏都是普通的 View（[DeliveryPageView] / [ImportantPageView] / [SettingsPageView]），
 * 一起创建、叠在同一个 FrameLayout 里，切换只是改 visibility：
 *  - 没有 Activity 跳转，所以切 Tab 不闪、不过场、列表滚动位置和选择状态都保留；
 *  - 前两屏的数据监听各自挂在对应的 Store 上，收到短信时不在前台的那一屏也会静默更新。
 *
 * 本类只负责「外壳」：系统栏、权限、Tab 切换、生命周期的转发。业务都在各个页面视图里。
 * 权限和保活指引仍然挂在这里 —— 它们是全应用一次性的，不属于任何一屏。
 */
class MainActivity : BaseActivity() {

    private lateinit var deliveryPage: DeliveryPageView
    private lateinit var importantPage: ImportantPageView
    private lateinit var settingsPage: SettingsPageView
    private lateinit var nav: BottomNavBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 各模块的数据层先就绪：页面视图构建时会立刻读一次列表
        Store.init(this)
        RuleStore.init(this)
        ImportantStore.init(this)
        NotifyHelper.ensureChannel(this)
        ImportantNotify.ensureChannel(this)

        // 页面骨架：内容区吃满剩余高度，底部导航占最后一条
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 左右/上下留白由各页面自己写（pageRoot 的 16dp），系统栏由 BaseActivity 叠加
            setPadding(0, 0, 0, 0)
        }

        val container = FrameLayout(this)
        deliveryPage = DeliveryPageView(this)
        importantPage = ImportantPageView(this)
        settingsPage = SettingsPageView(this)
        listOf<View>(deliveryPage, importantPage, settingsPage).forEach {
            container.addView(it, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        root.addView(container, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        nav = BottomNavBar(
            this,
            listOf(
                NavItem("快递服务", AppIcons.parcel()),
                NavItem("重要短信提醒", AppIcons.bell()),
                NavItem("设置", AppIcons.gear())
            )
        ) { selectTab(it) }
        root.addView(nav, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        selectTab(intent?.getIntExtra(EXTRA_TAB, TAB_DELIVERY) ?: TAB_DELIVERY)

        // 权限弹窗还没结束时先不叠第二个弹窗，等结果回来再显示保活指引
        if (!requestPerms()) KeepAliveTips.showIfNeeded(this)
    }

    /** 从通知点进来时复用同一个实例：切到对应 Tab，而不是重开一页 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectTab(intent.getIntExtra(EXTRA_TAB, TAB_DELIVERY))
    }

    override fun onResume() {
        super.onResume()
        // 打开 App 就算"我看到了"：长响的规则不该在用户盯着屏幕时继续叫，
        // 通知上的「停止响铃」和划掉通知是另两条退路
        AlarmRing.stop()
        // 两屏都挂监听：另一屏不在前台时收到的短信，切过去就是最新的
        deliveryPage.attach()
        importantPage.attach()
    }

    override fun onPause() {
        super.onPause()
        deliveryPage.detach()
        importantPage.detach()
    }

    private fun selectTab(index: Int) {
        deliveryPage.visibility = if (index == TAB_DELIVERY) View.VISIBLE else View.GONE
        importantPage.visibility = if (index == TAB_IMPORTANT) View.VISIBLE else View.GONE
        settingsPage.visibility = if (index == TAB_SETTINGS) View.VISIBLE else View.GONE
        nav.setSelected(index)
        // 切到重要短信时补一次刷新：页面上有"最新通知"，不能等到下次收短信才更新
        if (index == TAB_IMPORTANT) importantPage.refresh()
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
        deliveryPage.refresh()
        // 无论用户同意还是拒绝都提示一次：没权限收不到短信，没白名单同样收不到
        KeepAliveTips.showIfNeeded(this)
    }

    companion object {
        /** 通知点进来要落在哪一屏 */
        const val EXTRA_TAB = "tab"
        const val TAB_DELIVERY = 0
        const val TAB_IMPORTANT = 1
        const val TAB_SETTINGS = 2
    }
}
