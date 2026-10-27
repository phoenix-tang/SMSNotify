package phoenix.smsnotify.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * 短信接收器：只做「收短信 + 拼长短信」这一件和业务无关的事，然后交给 [SmsRouter]。
 *
 * 具体怎么解析、入库、发通知，都在各功能模块里（当前是
 * [phoenix.smsnotify.delivery.DeliverySmsHandler]）。
 * 这样以后加"验证码自动复制""银行动账记录"之类的功能，这个文件完全不用动。
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (msgs.isEmpty()) return

        val sender = msgs[0].originatingAddress ?: return
        // 长短信会被拆成多条 message，按序拼接
        val body = msgs.joinToString("") { it.displayMessageBody ?: "" }
        SmsRouter.dispatch(context, sender, body)
    }
}
