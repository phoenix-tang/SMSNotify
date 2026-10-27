package phoenix.smsnotify.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间显示的唯一入口。
 *
 * 快递列表、重要短信列表、「最新通知」栏都显示接收时间，格式必须一致
 * （之前两个 Store 各存一份 `SimpleDateFormat("MM-dd HH:mm")`，改格式要改两处）。
 *
 * `SimpleDateFormat` 不是线程安全的，而格式化发生在两个地方：UI 线程刷列表、
 * 短信广播线程写库后回调刷新。所以用 ThreadLocal 各持一份，别为了省一个对象引入
 * 偶发的乱码时间。
 */
object TimeFmt {

    private val shortFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    }

    /** 列表/通知里用的紧凑格式：`10-27 21:35` */
    fun short(t: Long): String = shortFmt.get()!!.format(Date(t))
}
