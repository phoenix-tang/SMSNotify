package phoenix.smsnotify.common

/**
 * 文本的「显示宽度」：中日韩文字、全角符号、emoji 记 2，其余（字母、数字、半角符号）记 1。
 *
 * **为什么不用 `length`**：这条线是用来保证文字塞得进界面的，而界面约束都是**像素宽度**。
 * 「iiiiiiiiii」和「银行到账提醒」都是 10 个字符，前者只占一半宽 —— 按字符数限制会让
 * 中文名早早被截断、英文名白白浪费一半额度；按字节数（UTF-8 汉字 3 字节）更是和显示无关。
 * 一个汉字约等于两个半角字符，是最接近「看起来多长」的整数近似，也正好是用户能自己
 * 心算的规则（见编辑页的计数提示）。
 *
 * 判定沿用 East Asian Width 的宽字符（W/F）大致范围，不求完整 Unicode 表：
 * 这里只用来给输入框划一条线，差一两个冷僻字不影响结果。
 *
 * 按**码点**而不是 `Char` 遍历：emoji 在 UTF-16 里占两个 `Char`，逐 `Char` 数会算成 4。
 */
fun displayWidth(text: CharSequence): Int {
    var width = 0
    var i = 0
    while (i < text.length) {
        val cp = Character.codePointAt(text, i)
        width += if (isWide(cp)) 2 else 1
        i += Character.charCount(cp)
    }
    return width
}

private fun isWide(cp: Int): Boolean = when (cp) {
    in 0x1100..0x115F -> true     // 谚文字母
    in 0x2E80..0x303E -> true     // CJK 部首、康熙部首、CJK 符号与标点
    in 0x3041..0x33FF -> true     // 假名、注音、CJK 兼容
    in 0x3400..0x4DBF -> true     // CJK 扩展 A
    in 0x4E00..0x9FFF -> true     // CJK 基本区（汉字主体）
    in 0xA000..0xA4CF -> true     // 彝文
    in 0xAC00..0xD7A3 -> true     // 谚文音节
    in 0xF900..0xFAFF -> true     // CJK 兼容汉字
    in 0xFE30..0xFE6F -> true     // CJK 兼容形式、小写变体
    in 0xFF00..0xFF60 -> true     // 全角 ASCII、全角标点（，。：等）
    in 0xFFE0..0xFFE6 -> true     // 全角符号（￥等）
    in 0x1F000..0x1FAFF -> true   // emoji 与各类图形符号
    else -> false
}
