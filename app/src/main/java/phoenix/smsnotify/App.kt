package phoenix.smsnotify

import android.app.Application
import phoenix.smsnotify.delivery.DeliverySmsHandler
import phoenix.smsnotify.important.ImportantSmsHandler
import phoenix.smsnotify.sms.SmsRouter

/**
 * 应用入口：唯一职责是把各个功能模块注册到公共的短信分发中心。
 *
 * 进程被系统杀掉后，收到短信会重新拉起进程 —— Application.onCreate 一定先于
 * BroadcastReceiver.onReceive 执行，所以注册永远不会漏。
 *
 * 每个模块收到同一条短信后各自判断关不关心，互不影响：
 *  - [DeliverySmsHandler]  快递取件（规则匹配 → 取件记录 → 常驻通知）
 *  - [ImportantSmsHandler] 重要短信提醒（关键字命中 → 记录 + 响铃通知）
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SmsRouter.register(DeliverySmsHandler)
        SmsRouter.register(ImportantSmsHandler)
    }
}
