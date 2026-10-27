package phoenix.smsnotify.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 首次打开时的「后台保活」指引弹窗。
 *
 * 为什么需要它：国产 ROM 会把后台应用的进程冻结，短信广播到时唤不醒应用，
 * 表现是「短信收到了、但本应用什么都没记录」，而且用户完全无从察觉。
 * 纯代码无法自救（`cmd deviceidle whitelist` 对厂商自己的冻结机制也无效），
 * 只能引导用户去系统设置里放行这一次。
 *
 * 触发时机：**每次安装/更新后的第一次打开**，不做任何「是否已开启」的检测
 * （各厂商设置项的读法不统一，检测既不可靠也会误弹）。
 * 用一个「安装/更新时间戳」当标记：装新包时间戳必变，于是新装、覆盖升级都会再弹一次，
 * 同一个包反复打开则只弹第一次。
 *
 * 不依赖用户当时点「去设置」：看过一眼知道去哪设，就已经达到目的。
 */
object KeepAliveTips {

    private const val PREFS = "app_prefs"
    private const val KEY_STAMP = "keepalive_tips_stamp"

    /** 一家厂商的入口说明；[keys] 用于和 Build.MANUFACTURER 比对，命中后高亮 */
    private class Vendor(val name: String, val keys: List<String>, val steps: List<String>)

    /**
     * 四家主流厂商的路径。各家 ROM 版本之间菜单名会改（尤其 ColorOS / OriginOS），
     * 所以写的是「大类 → 子项 → 目标开关」，并按常见的两种入口各给一条。
     * `%s` 会被替换成应用名。
     */
    private val vendors = listOf(
        Vendor(
            "小米 / Redmi（HyperOS、MIUI）", listOf("xiaomi", "redmi"),
            listOf(
                "设置 → 应用设置 → 应用管理 → %s",
                "省电策略 → 选「无限制」",
                "同一页往下 → 自启动 → 打开"
            )
        ),
        Vendor(
            "OPPO / 一加 / realme（ColorOS）", listOf("oppo", "oneplus", "realme"),
            listOf(
                "设置 → 电池 → 应用耗电管理 → %s → 允许「后台运行」「关联启动」",
                "设置 → 应用 → 自启动管理 → 打开 %s（部分机型在「手机管家 → 权限隐私」）"
            )
        ),
        Vendor(
            "vivo / iQOO（OriginOS）", listOf("vivo", "iqoo"),
            listOf(
                "设置 → 电池 → 后台耗电管理 → %s → 允许后台高耗电",
                "设置 → 应用与权限 → 权限管理 → 自启动 → 打开 %s（或在「i管家 → 应用管理」里）"
            )
        ),
        Vendor(
            "荣耀（MagicOS）", listOf("honor"),
            listOf(
                "设置 → 电池 → 应用耗电管理 → %s",
                "关掉「自动管理」，手动打开「自启动」「关联启动」「后台活动」"
            )
        )
    )

    /** 新装或升级后的第一次打开才弹；已在本次安装中弹过就直接返回 */
    fun showIfNeeded(activity: Activity) {
        if (activity.isFinishing) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = installStamp(activity)
        if (prefs.getLong(KEY_STAMP, -1L) == stamp) return
        prefs.edit().putLong(KEY_STAMP, stamp).apply()   // 先记账：弹窗中途被杀也不会反复弹
        show(activity)
    }

    private fun show(activity: Activity) {
        val current = currentVendor()
        AlertDialog.Builder(activity)
            .setTitle("让短信能被收到")
            .setView(buildContent(activity, current))
            .setPositiveButton("去应用设置") { _, _ -> openAppSettings(activity) }
            .setNegativeButton("知道了", null)
            .show()
    }

    // ---------------- 弹窗内容 ----------------

    private fun buildContent(activity: Activity, current: Int): View {
        val appName = appLabel(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 2), dp(activity, 20), 0)
        }

        col.addView(body(
            activity,
            "系统会冻结后台应用：短信到了却唤不醒本应用，看起来就是「收不到、也没记录」。" +
                "请按下面找到你的手机品牌，做一次设置，之后一直有效。"
        ))

        vendors.forEachIndexed { i, v ->
            col.addView(vendorBlock(activity, v, i == current, appName))
        }

        col.addView(body(
            activity,
            "找不到入口时，可在设置顶部搜索「自启动」「耗电管理」「后台运行」；" +
                "其他品牌也是同样的两个开关。\n改完直接返回即可，不需要回到这里确认。"
        ).apply { setPadding(0, dp(activity, 12), 0, dp(activity, 4)) })

        return ScrollView(activity).apply {
            isFillViewport = false
            addView(col, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            // 内容比屏幕长，给弹窗一个固定高度让它自己滚，不然按钮会被挤出屏幕
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.52f).toInt()
            )
        }
    }

    /** 一个品牌一节：品牌名 + 编号步骤；本机品牌用蓝色并标注「当前设备」 */
    private fun vendorBlock(
        activity: Activity, v: Vendor, isCurrent: Boolean, appName: String
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(activity, 10), 0, 0)

        addView(TextView(activity).apply {
            text = if (isCurrent) "${v.name}　当前设备" else v.name
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isCurrent) BLUE else Color.BLACK)
        })

        v.steps.forEachIndexed { i, step ->
            addView(TextView(activity).apply {
                text = "${i + 1}. " + step.replace("%s", appName)
                textSize = 13f
                setTextColor(BODY)
                setLineSpacing(dp(activity, 2).toFloat(), 1f)
                setPadding(0, dp(activity, 2), 0, 0)
            })
        }
    }

    private fun body(activity: Activity, text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 13f
        setTextColor(BODY)
        setLineSpacing(dp(activity, 2).toFloat(), 1f)
    }

    // ---------------- 小工具 ----------------

    private val BLUE = 0xFF1976D2.toInt()
    private val BODY = 0xFF424242.toInt()

    /** 当前设备属于哪一家（-1 = 不在名单里，四家全都不高亮） */
    private fun currentVendor(): Int {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return vendors.indexOfFirst { v -> v.keys.any { brand.contains(it) } }
    }

    /**
     * 安装/更新时间戳：新装与覆盖升级都会变，同一个包不变。
     * 用它当标记，既覆盖「首次安装」，也覆盖「更新后首次打开」。
     */
    private fun installStamp(activity: Activity): Long = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).lastUpdateTime
    } catch (_: Exception) { 0L }

    private fun appLabel(activity: Activity): String = try {
        val pm = activity.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(activity.packageName, 0)).toString()
    } catch (_: Exception) { "本应用" }

    /** 应用详情页：省电策略、自启动多数机型都能从这里进 */
    private fun openAppSettings(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null)
        )
        try {
            activity.startActivity(intent)
        } catch (_: Exception) { /* 极少数 ROM 没有该页面，忽略 */ }
    }

    private fun dp(context: Context, v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
        ).toInt()
}
