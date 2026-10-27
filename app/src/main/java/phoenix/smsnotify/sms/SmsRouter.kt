package phoenix.smsnotify.sms

import android.content.Context

/**
 * 短信功能模块的接口。
 *
 * 一个模块（快递、银行动账、验证码……）收到短信后自己判断关不关心，
 * 不关心就直接 return，不用返回“是否消费了这条短信”。
 * 这样 [SmsRouter] 不必知道有多少个功能，加功能时只改注册那一行。
 */
interface SmsHandler {
    fun onSms(context: Context, sender: String, body: String)
}

/**
 * 短信分发中心。
 *
 * 这里是「公共的短信读取层」和「各个功能」之间唯一的接线点：
 *  - [SmsReceiver] 只负责收短信、拼长短信，然后交给本对象；
 *  - 各功能模块在 `App.onCreate` 里注册自己。
 *
 * 依赖方向是单向的：sms 包不认识任何具体功能，功能包才认识 sms。
 */
object SmsRouter {

    private val handlers = mutableListOf<SmsHandler>()

    /** 在 Application.onCreate 里调用；重复注册同一个模块不会重复执行 */
    fun register(handler: SmsHandler) {
        if (handler !in handlers) handlers.add(handler)
    }

    /**
     * 依次交给所有模块。
     * 单个模块抛异常不影响其他模块（比如快递的规则文件坏了，不该连累验证码模块）。
     */
    fun dispatch(context: Context, sender: String, body: String) {
        for (h in handlers.toList()) {
            try {
                h.onSms(context, sender, body)
            } catch (_: Exception) {
                // 静默：接收器里崩溃会导致系统提示"短信应用已停止"
            }
        }
    }
}
