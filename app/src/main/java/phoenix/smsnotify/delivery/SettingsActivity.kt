package phoenix.smsnotify.delivery

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.rowRipple

/** 设置页：多级条目入口 */
class SettingsActivity : BaseActivity() {

    private val blue = 0xFF1976D2.toInt()
    private val gray = 0xFF9E9E9E.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 统一页面骨架：左右 16dp、上下 8dp 视觉留白，系统栏高度自动避让
        val root = pageRoot()

        // 标题行：返回箭头 + 标题（统一走 BaseActivity.pageTitle，和主页同规格）
        root.addView(pageTitle("设置"))

        // 一级条目：关键字提取规则
        root.addView(entry("关键字提取规则", "自定义柜架号与取件码的抓取规则", true) {
            startActivity(Intent(this, RulesActivity::class.java))
        })

        root.addView(TextView(this).apply {
            text = "更多设置项开发中…"
            textSize = 13f
            setTextColor(gray)
            setPadding(0, 48, 0, 0)
        })

        setContentView(root)
    }

    private fun entry(title: String, subtitle: String, clickable: Boolean, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 32, 0, 32)
            if (clickable) {
                isClickable = true
                isFocusable = true
                rowRipple(cornerDp = 8)
                setOnClickListener { onClick() }
            }
            addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 17f
                setTextColor(if (clickable) Color.BLACK else gray)
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = subtitle
                textSize = 13f
                setTextColor(gray)
            })
        }
    }
}
