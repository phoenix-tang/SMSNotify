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

    /** 次要文字（时间、说明） */
    val subText = 0xFF757575.toInt()

    /** 占位/未选中（图标未选中态、hint） */
    val hint = 0xFF8E8E93.toInt()

    /**
     * 说明文字、停用态（列表条目被关掉后的整行颜色）。
     * 比 [subText] 再浅一档：它常常整段出现，太深会抢正文的注意力。
     */
    val muted = 0xFF9E9E9E.toInt()

    /** 分隔发丝线 */
    val divider = 0xFFEBEBEF.toInt()

    /** 页面与导航栏底色 */
    val surface = 0xFFFFFFFF.toInt()

    /** 成功/开关开启绿 */
    val green = 0xFF00C375.toInt()

    /** 错误、关键字高亮红 */
    val red = 0xFFE53935.toInt()
}
