package phoenix.smsnotify.settings

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import phoenix.smsnotify.important.RingIsland
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.divider
import phoenix.smsnotify.ui.footNote
import phoenix.smsnotify.ui.rowRipple
import phoenix.smsnotify.ui.setRowSub
import phoenix.smsnotify.ui.setRowTitle

/**
 * 「设置」这一屏（底部导航第三栏）。
 *
 * 导航升成三栏之后，设置不再挂在某个功能的标题栏上 —— 它管的是整个应用：
 * 现在只有「关于」和「检测更新」两条，后续的应用级开关（通知、数据清理等）也放这里，
 * 不再往快递页 / 重要短信页的标题栏塞图标。
 *
 * 功能自己的配置不放这里：快递的规则、重要短信的规则各自有书页图标，
 * 就近放在对应功能的标题栏 —— 设置页保持"只有全局项"。
 */
class SettingsPageView(private val act: BaseActivity) : LinearLayout(act) {

    /** 「响铃胶囊」这一行。副标题要跟着权限状态变，所以留个引用按位置取第二个子 View */
    private lateinit var islandRow: LinearLayout

    init {
        orientation = VERTICAL
        gravity = Gravity.TOP
        // 与 BaseActivity.pageRoot() 保持一致：左右 16dp、上下 8dp
        setPadding(act.dp(16), act.dp(8), act.dp(16), act.dp(8))

        addView(act.pageTitle("设置", withBack = false))

        islandRow = entry("响铃胶囊", islandSubtitle()) { openOverlaySettings() }
        addView(islandRow)
        addView(act.divider())

        addView(entry("关于", "版本信息与应用说明") {
            act.startActivity(Intent(act, AboutActivity::class.java))
        })
        addView(act.divider())
        addView(entry("检测更新", "当前版本 ${AppInfo.versionName(act)}") { checkUpdate() })
        addView(act.divider())

        // 底部版本水印：占满剩余高度，让它在最下面
        addView(View(act), LinearLayout.LayoutParams(0, 0, 1f))
        addView(act.footNote("短信提醒助手  v${AppInfo.versionName(act)}", topDp = 8))
    }

    /**
     * 从系统设置的权限页返回时刷新副标题。
     *
     * 权限状态没法监听（`SYSTEM_ALERT_WINDOW` 没有回调），只能每次回到前台重读一次。
     */
    fun refresh() {
        if (!::islandRow.isInitialized) return
        (islandRow.getChildAt(1) as? TextView)?.text = islandSubtitle()
    }

    private fun islandSubtitle(): String = if (RingIsland.canShow(act)) {
        "已允许 · 非锁屏时浮在顶部，点右侧红点即停"
    } else {
        // 副标题只有一行，长一点就会被省略号吃掉；这里刚好把系统设置里的原话放进去
        "未允许 · 点这里开启「显示在其他应用上层」"
    }

    /**
     * 打开本应用的「显示在其他应用上层」设置页。
     *
     * 个别 ROM 没有这个页面（抛 [ActivityNotFoundException]），退到应用详情页 ——
     * 那里至少能找到同一个入口，不至于点了没反应。
     */
    private fun openOverlaySettings() {
        val pkg = Uri.parse("package:${act.packageName}")
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
        try {
            act.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
    }

    /** 一条设置项：粗标题 + 灰色副标题，整行可点、带水波纹 */
    private fun entry(title: String, subtitle: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(act.dp(4), act.dp(14), act.dp(4), act.dp(14))
            isClickable = true
            isFocusable = true
            rowRipple(cornerDp = 8)
            setOnClickListener { onClick() }

            addView(TextView(act).apply { setRowTitle(title) })
            addView(TextView(act).apply {
                setRowSub(subtitle)
                setPadding(0, act.dp(2), 0, 0)
            })
        }

    /**
     * 检测更新：暂时没有更新服务（也还没有下载地址），
     * 先如实说明当前版本，别做成一个点了没反应的假按钮。
     */
    private fun checkUpdate() {
        AlertDialog.Builder(act)
            .setTitle("检查更新")
            .setMessage(
                "当前版本：${AppInfo.versionName(act)}\n\n" +
                    "更新服务还没接入，暂时需要手动安装新版本。接入后这里会显示有没有新版本。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }
}

/**
 * 读取本应用自己的版本信息。
 *
 * 放在 settings 包里而不是 `common`：目前只有关于页和设置页用得到，
 * 抽成公共层反而让 common 承担了它不该知道的"关于本应用"这种业务语义。
 */
internal object AppInfo {

    fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "未知"
    } catch (_: Exception) { "未知" }

    fun versionCode(ctx: Context): Long = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        // longVersionCode 是 API 28 才有的，minSdk 26 需要兜底
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    } catch (_: Exception) { 0L }
}
