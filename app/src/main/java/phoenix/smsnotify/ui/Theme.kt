package phoenix.smsnotify.ui

/**
 * 全局配色。所有页面都从这里取，不要在各自文件里再写 `0xFF...` 字面量 ——
 * 之前品牌蓝散落在 7 个文件里，改一次要搜一遍，容易漏。
 *
 * 命名按「用途」而不是「颜色」：换了色值，调用方不用改名。
 */
object AppColor {

    /** 品牌蓝：选中态、链接、可点的强调文字 */
    val blue = 0xFF1976D2.toInt()

    /** 主文字（标题、正文） */
    val text = 0xFF212121.toInt()

    /**
     * 正文块：短信摘要、弹窗里的步骤说明这类「需要读进去」的段落。
     * 比 [text] 浅一档，比 [subText] 深一档 —— 整段出现时用纯黑太沉，用次要灰又看不清。
     */
    val body = 0xFF424242.toInt()

    /** 次要文字（时间、摘要、输入区下面那行说明） */
    val subText = 0xFF757575.toInt()

    /**
     * 表单字段标签。
     *
     * 用户要靠它才知道这一格该填什么，属于「第二重要」的文字，不能算装饰：
     * 原来的 #9E9E9E 对比度只有 2.7:1，低于 WCAG AA 的 4.5:1，在手机上看着像没写完。
     * 这里取 #616161（6.2:1），仍明显轻于正文 #212121（16:1），但读得清。
     */
    val label = 0xFF616161.toInt()

    /** 占位/未选中（图标未选中态、hint） */
    val hint = 0xFF8E8E93.toInt()

    /**
     * 输入框内的占位提示（「前缀」「后缀」这类）。
     * 比 [hint] 再浅一档：它和用户真正填的值同处一格，两者必须一眼能分开。
     */
    val placeholder = 0xFFBDBDBD.toInt()

    /**
     * 停用态、极次要的辅助信息（列表条目被关掉后的整行颜色）。
     * 比 [subText] 再浅一档，对比度 2.7:1 —— 只适合「本来就不打算让人细读」的文字，
     * 不要再用它写字段标签或正文说明。
     */
    val muted = 0xFF9E9E9E.toInt()

    /** 分隔发丝线 */
    val divider = 0xFFEBEBEF.toInt()

    /** 浅灰底：输入框、表单内选择行的背景，和白色页面区分出「这一块可以操作」 */
    val field = 0xFFF7F7F9.toInt()

    /** 页面与导航栏底色 */
    val surface = 0xFFFFFFFF.toInt()

    /** 成功/开关开启绿 */
    val green = 0xFF00C375.toInt()

    /** 错误、关键字高亮红 */
    val red = 0xFFE53935.toInt()

    /** 文字用的绿（匹配成功、已取件）：比 [green] 深，浅底上当文字才够清楚 */
    val greenText = 0xFF2E7D32.toInt()

    /** 文字用的红（未匹配、危险操作）：比 [red] 深，同上是给文字而不是给色块用的 */
    val redText = 0xFFC62828.toInt()

    /** 红色小角标的底色（浅粉），和 [redText] 配对使用 */
    val redBg = 0xFFFDECEA.toInt()

    /** 浅蓝卡片上的次要文字。灰字放在 #E8F1FC 上会显脏，用它做同色系的弱化 */
    val blueMuted = 0xFF5C7FA8.toInt()

    /** 浅蓝卡底（「最新通知」栏、关于页图标圆底），和 [blue] 配对使用 */
    val blueBg = 0xFFE8F1FC.toInt()

    /** 白卡片的描边，比 [divider] 略深一点，卡片边缘才立得住 */
    val cardBorder = 0xFFECECF0.toInt()

    /** 琥珀色：快递「未取」状态圆标的底色（文字用白色，对比度 2.1:1 但上面是符号不是正文） */
    val amber = 0xFFF9A825.toInt()
}
