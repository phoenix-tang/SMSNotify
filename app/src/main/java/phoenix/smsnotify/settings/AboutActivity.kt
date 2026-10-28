package phoenix.smsnotify.settings

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import phoenix.smsnotify.ui.AppColor
import phoenix.smsnotify.ui.AppIcons
import phoenix.smsnotify.ui.BaseActivity
import phoenix.smsnotify.ui.PathIconView
import phoenix.smsnotify.ui.appTitle
import phoenix.smsnotify.ui.footNote
import phoenix.smsnotify.ui.paragraph
import phoenix.smsnotify.ui.sectionTitle

/**
 * 关于页：应用叫什么、是什么版本、做什么用、数据存在哪。
 *
 * 只陈述事实，不写"感谢使用""五星好评"那类话 —— 这一页是给用户查信息的，
 * 尤其是"数据会不会被上传"这件事，要能一眼看到答案。
 */
class AboutActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = pageRoot()

        root.addView(pageTitle("关于"))

        // 图标 + 名称 + 版本，居中排一块
        root.addView(PathIconView(this).apply {
            icon = AppIcons.bell()
            iconColor = AppColor.blue
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AppColor.blueBg)
            }
            // 用 padding 把 24dp 的图形缩到圆底中间，比再套一层 FrameLayout 省事
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(24)
            }
        })

        root.addView(appTitle("短信提醒助手"))

        root.addView(footNote(
            "版本 ${AppInfo.versionName(this@AboutActivity)}（${AppInfo.versionCode(this@AboutActivity)}）",
            textSp = 13f, topDp = 4, bottomDp = 0
        ))

        root.addView(section(
            "做什么",
            "自动从快递短信里认出柜架号和取件码，列成待取清单；" +
                "也可以自己写几段文字，收到同时包含这些文字的短信时响铃提醒，" +
                "长响可以穿透静音和勿扰。"
        ))
        root.addView(section(
            "数据放在哪",
            "取件记录、规则、命中记录都以 JSON 存在应用私有目录里，卸载即删除。" +
                "应用不联网，也不会把短信内容发到任何地方。"
        ))
        root.addView(section("包名", "phoenix.smsnotify"))

        // 把内容顶上去，短页面不要在底部留一大片空白
        root.addView(android.view.View(this), LinearLayout.LayoutParams(0, 0, 1f))

        setContentView(root)
    }

    private fun section(title: String, body: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle(title))
            addView(paragraph(body), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
}
