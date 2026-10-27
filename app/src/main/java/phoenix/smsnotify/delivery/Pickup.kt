package phoenix.smsnotify.delivery

import android.app.NotificationManager
import android.content.Context
import phoenix.smsnotify.MainActivity
import phoenix.smsnotify.common.AppNotify
import phoenix.smsnotify.sms.SmsHandler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 快递功能的数据层：一条取件记录从「收到短信」到「落盘 + 通知」的完整链路。
 *
 *     短信（公共的 sms/SmsReceiver）→ [DeliverySmsHandler] → 规则解析 → [Store] 入库 → [NotifyHelper] 刷新通知
 *
 * 四段代码互相只依赖下面这一个数据模型，耦合很紧，分散成四个文件反而要来回跳。
 */

/** 一条取件记录 */
data class PickupRecord(
    val id: Long,
    val sender: String,
    val provider: String,  // 服务商，如 "丰巢"
    val cabinet: String,   // 如 "4号柜"
    val code: String,      // 取件码
    val time: Long,
    var taken: Boolean
) {
    /**
     * 列表首行摘要：「服务商  9号柜  331122」。
     * 柜架号可能解析不到（短信里没写是哪个柜），这时自动省略，不会留下多余空格。
     */
    val summary: String
        get() = listOf(provider, cabinet, code)
            .filter { it.isNotBlank() }
            .joinToString("  ")

    /** 用于弹窗/文案：「9号柜 取件码 331122」，柜号缺失时只剩「取件码 331122」 */
    val desc: String
        get() = listOf(cabinet, "取件码 $code")
            .filter { it.isNotBlank() }
            .joinToString(" ")
}

/** 本地持久化：JSON 文件存在应用私有目录，随应用卸载删除 */
object Store {
    private const val FILE = "pickups.json"
    private val records = mutableListOf<PickupRecord>()
    private var nextId = 1L
    private var file: File? = null

    /** 数据变更监听：界面在前台时收到短信可即时刷新 */
    var listener: (() -> Unit)? = null

    private fun changed() {
        save()
        listener?.invoke()
    }

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, FILE)
        if (file!!.exists()) {
            try {
                val arr = JSONArray(file!!.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    records.add(
                        PickupRecord(
                            o.getLong("id"), o.getString("sender"),
                            o.optString("provider", ""), o.getString("cabinet"), o.getString("code"),
                            o.getLong("time"), o.getBoolean("taken")
                        )
                    )
                    if (o.getLong("id") >= nextId) nextId = o.getLong("id") + 1
                }
            } catch (_: Exception) { /* 文件损坏则当作空数据重新开始 */ }
        }
    }

    fun all(): List<PickupRecord> = records.sortedByDescending { it.time }

    /** 去重：同一发件号 + 同柜 + 同取件码且未取出的记录不重复添加 */
    fun add(sender: String, provider: String, cabinet: String, code: String): Boolean {
        if (records.any { it.sender == sender && it.cabinet == cabinet && it.code == code && !it.taken }) {
            return false
        }
        records.add(PickupRecord(nextId++, sender, provider, cabinet, code, System.currentTimeMillis(), false))
        changed()
        return true
    }

    fun toggle(id: Long) {
        records.find { it.id == id }?.let { it.taken = !it.taken; changed() }
    }

    fun remove(id: Long) {
        records.removeAll { it.id == id }; changed()
    }

    /** 批量删除（选择器模式用） */
    fun removeAll(ids: Collection<Long>) {
        records.removeAll { it.id in ids }; changed()
    }

    fun countPending(): Int = records.count { !it.taken }

    private fun save() {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id).put("sender", r.sender)
                    .put("provider", r.provider).put("cabinet", r.cabinet).put("code", r.code)
                    .put("time", r.time).put("taken", r.taken)
            )
        }
        file?.writeText(arr.toString())
    }
}

/**
 * 未取快递通知：**只在有待取快递时出现**，全部取出后自动收回。
 *
 * 它不承担保活职责（通知本身也不会让进程免于被冻结），
 * 空闲时留在通知栏只会制造噪音，所以 pending 为 0 直接 cancel。
 *
 * 渠道是「安静型」：IMPORTANCE_LOW、不振动、不亮灯。它是一块长期挂着的信息面板，
 * 不是一个事件 —— 真正需要叫醒用户的是重要短信那条，两者刻意分开，用户可以只关一条。
 */
object NotifyHelper {
    private val CHANNEL = AppNotify.ChannelSpec(
        id = "deliveries",
        name = "快递取件提醒",
        description = "有未取快递时显示，全部取出后自动消失",
        importance = NotificationManager.IMPORTANCE_LOW
    )

    private const val NOTIF_ID = 1001

    fun ensureChannel(ctx: Context) = AppNotify.ensureChannel(ctx, CHANNEL)

    fun update(ctx: Context) {
        ensureChannel(ctx)

        // 没有待取快递 → 收回通知，平时保持安静
        val pending = Store.countPending()
        if (pending <= 0) {
            AppNotify.cancel(ctx, NOTIF_ID)
            return
        }

        AppNotify.post(ctx, CHANNEL.id, NOTIF_ID) { b ->
            b.setSmallIcon(android.R.drawable.stat_notify_more)
                .setOngoing(true)          // 有待取快递时不让划掉，避免误清
                .setOnlyAlertOnce(true)    // 数量变化不重复提醒
                .setContentIntent(AppNotify.openApp(ctx, MainActivity.TAB_DELIVERY))
                .setContentTitle("未取快递 $pending 件")
                .setContentText("点击查看取件码")
        }
    }
}

/**
 * 快递功能对短信的处理：能解析出取件码就入库，并刷新常驻通知。
 *
 * 由 [phoenix.smsnotify.App] 注册到 [phoenix.smsnotify.sms.SmsRouter]，
 * 短信从哪来、怎么分发它不关心 —— 它只认「发件人 + 正文」。
 */
object DeliverySmsHandler : SmsHandler {
    override fun onSms(context: Context, sender: String, body: String) {
        // 先 init：进程刚被拉起时规则还没从磁盘读出来，先解析会拿到空列表而漏记
        RuleStore.init(context)
        Store.init(context)
        val parsed = PickupParser.parse(body) ?: return
        if (Store.add(sender, parsed.provider, parsed.cabinet, parsed.code)) {
            NotifyHelper.update(context)
        }
    }
}
