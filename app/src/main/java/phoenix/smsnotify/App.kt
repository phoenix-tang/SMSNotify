package phoenix.smsnotify

import android.app.Application
import phoenix.smsnotify.delivery.DeliverySmsHandler
import phoenix.smsnotify.sms.SmsRouter

/**
 * 应用入口：唯一职责是把各个功能模块注册到公共的短信分发中心。
 *
 * 进程被系统杀掉后，收到短信会重新拉起进程 —— Application.onCreate 一定先于
 * BroadcastReceiver.onReceive 执行，所以注册永远不会漏。
 *
 * 以后新增「验证码」「银行动账」等功能，只要在这里多加一行 register。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SmsRouter.register(DeliverySmsHandler)
    }
}
