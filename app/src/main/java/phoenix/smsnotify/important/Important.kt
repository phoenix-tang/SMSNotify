package phoenix.smsnotify.important

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import phoenix.smsnotify.MainActivity
import phoenix.smsnotify.sms.SmsHandler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「重要短信提醒」的数据层与短信链路。
 *
 *     短信（公共的 sms/SmsReceiver）→ [ImportantSmsHandler] → 规则匹配 → [ImportantStore] 入库 → [ImportantNotify] 响铃提醒
 *
 * 与快递模块的关系：两者都是 [SmsHandler]，同一条短信会被两个模块各看一遍，
 * 互不影响 —— 一条短信既可以是取件短信，也可以同时命中重要短信规则。
 *
 * 规则模型刻意做得很轻：**名称 + 若干段文字 + 开关**，没有正则、没有前后缀拼接，
 * 用户写「余额」「到账」这种大白话就能用。
 */

/**
 * 一条重要短信规则。
 *
 * @param name     规则名称（类别），「最新通知」里显示的就是它；留空时用第一段文字兜底
 * @param keywords 匹配文字，每一段都出现在短信里才算命中（AND）
 * @param enabled  右侧开关，停用后不再记录（历史记录保留）
 * @param createdAt 添加时间，列表按它倒序（最新添加的在最上面）
 */
data class ImportantRule(
    val id: Long,
    val name: String,
    val keywords: List<String>,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 展示用名称：没填名称时退回第一段匹配文字，再不行给个占位 */
    val label: String
        get() = name.ifBlank {
            keywords.firstOrNull()?.ifBlank { "" }.orEmpty().ifBlank { "未命名规则" }
        }
}

/** 一次命中：哪条规则、哪个号码、什么内容、什么时候 */
data class ImportantHit(
    val id: Long,
    val ruleId: Long,
    val sender: String,
    val body: String,
    val time: Long
)

/** 规则 + 命中记录的持久化与匹配（JSON 文件放在应用私有目录，随卸载删除） */
object ImportantStore {
    private const val FILE = "important_sms.json"

    /**
     * 单条规则最多保留多少条命中。
     * 重要短信往往来得频繁（验证码、动账都可能每天都来），不设上限文件会无限长；
     * 超出的按时间淘汰最旧的 —— 界面上本来也只关心近期的。
     */
    private const val MAX_HITS_PER_RULE = 200

    /** 同一条短信被重复投递时（长短信分片等）的去重窗口 */
    private const val DEDUPE_WINDOW_MS = 2000L

    private val rules = mutableListOf<ImportantRule>()
    private val hits = mutableListOf<ImportantHit>()
    private var nextRuleId = 1L
    private var nextHitId = 1L
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    /** 数据变更监听：页面在前台时收到短信可即时刷新 */
    var listener: (() -> Unit)? = null

    private fun changed() {
        persist()
        listener?.invoke()
    }

    // ---------- 读写 ----------

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, FILE)
        if (!file!!.exists()) return
        try {
            val root = JSONObject(file!!.readText())

            val rs = root.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until rs.length()) {
                val o = rs.getJSONObject(i)
                val kws = o.optJSONArray("keywords") ?: JSONArray()
                val id = o.optLong("id", 0L)
                rules.add(
                    ImportantRule(
                        id = id,
                        name = o.optString("name", ""),
                        keywords = (0 until kws.length())
                            .map { kws.optString(it, "") }
                            .filter { it.isNotBlank() },
                        enabled = o.optBoolean("enabled", true),
                        createdAt = o.optLong("createdAt", 0L)
                    )
                )
                if (id >= nextRuleId) nextRuleId = id + 1
            }

            val hs = root.optJSONArray("hits") ?: JSONArray()
            for (i in 0 until hs.length()) {
                val o = hs.getJSONObject(i)
                val id = o.optLong("id", 0L)
                hits.add(
                    ImportantHit(
                        id = id,
                        ruleId = o.optLong("ruleId", 0L),
                        sender = o.optString("sender", ""),
                        body = o.optString("body", ""),
                        time = o.optLong("time", 0L)
                    )
                )
                if (id >= nextHitId) nextHitId = id + 1
            }
        } catch (_: Exception) {
            // 文件损坏：当作空数据重新开始，不要让主页因为一个坏文件打不开
            rules.clear(); hits.clear()
        }
    }

    private fun persist() {
        val rArr = JSONArray()
        rules.forEach { r ->
            rArr.put(
                JSONObject()
                    .put("id", r.id).put("name", r.name)
                    .put("keywords", JSONArray(r.keywords))
                    .put("enabled", r.enabled).put("createdAt", r.createdAt)
            )
        }
        val hArr = JSONArray()
        hits.forEach { h ->
            hArr.put(
                JSONObject()
                    .put("id", h.id).put("ruleId", h.ruleId)
                    .put("sender", h.sender).put("body", h.body).put("time", h.time)
            )
        }
        file?.writeText(JSONObject().put("rules", rArr).put("hits", hArr).toString())
    }

    // ---------- 查询 ----------

    /** 界面用：按添加时间倒序（最新添加的规则在最上面） */
    fun rules(): List<ImportantRule> =
        rules.sortedWith(compareByDescending<ImportantRule> { it.createdAt }.thenByDescending { it.id })

    fun find(id: Long): ImportantRule? = rules.find { it.id == id }

    /** 某条规则的命中记录，按时间倒序（最新的在最上面） */
    fun hitsOf(ruleId: Long): List<ImportantHit> =
        hits.filter { it.ruleId == ruleId }.sortedWith(compareByDescending<ImportantHit> { it.time }.thenByDescending { it.id })

    fun hitCount(ruleId: Long): Int = hits.count { it.ruleId == ruleId }

    /** 最新一条命中（含所属规则）；规则已被删掉的历史记录会被忽略 */
    fun latest(): Pair<ImportantRule, ImportantHit>? {
        val h = hits.maxWithOrNull(compareBy<ImportantHit> { it.time }.thenBy { it.id }) ?: return null
        val r = find(h.ruleId) ?: return null
        return r to h
    }

    fun fmtTime(t: Long): String = fmt.format(Date(t))

    // ---------- 增删改 ----------

    /** 新增或更新一条规则，返回它的 id */
    fun save(rule: ImportantRule): Long {
        val idx = rules.indexOfFirst { it.id == rule.id }
        if (idx >= 0) {
            // 编辑：保留原来的添加时间，列表顺序不会因为改个名字就跳上去
            rules[idx] = rule.copy(createdAt = rules[idx].createdAt)
            changed()
            return rule.id
        }
        val id = nextRuleId++
        rules.add(rule.copy(id = id, createdAt = System.currentTimeMillis()))
        changed()
        return id
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        val idx = rules.indexOfFirst { it.id == id }
        if (idx < 0 || rules[idx].enabled == enabled) return
        rules[idx] = rules[idx].copy(enabled = enabled)
        changed()
    }

    /** 删除规则，连同它的历史记录一起清掉 */
    fun remove(id: Long) {
        if (rules.removeAll { it.id == id }) {
            hits.removeAll { it.ruleId == id }
            changed()
        }
    }

    /** 只清记录，保留规则本身 */
    fun clearHits(ruleId: Long) {
        if (hits.removeAll { it.ruleId == ruleId }) changed()
    }

    // ---------- 匹配 ----------

    /**
     * 找出第一条命中的规则：规则里的**每一段**文字都要出现在短信正文里才算命中（AND）。
     *
     * 按**添加时间正序**尝试（先加的优先），和列表展示的倒序不同 ——
     * 列表是给人看的（新的在上面），匹配是给程序看的（先建的多半更通用）。
     * 忽略大小写，方便写「OTP」「otp」这类英文关键词。
     */
    fun match(body: String): ImportantRule? {
        if (body.isEmpty()) return null
        return rules
            .filter { it.enabled && it.keywords.isNotEmpty() }
            .sortedWith(compareBy<ImportantRule> { it.createdAt }.thenBy { it.id })
            .firstOrNull { r -> r.keywords.all { body.contains(it, ignoreCase = true) } }
    }

    /** 记录一次命中；同一规则 + 同一内容在极短时间内重复到达时忽略（防长短信分片重复） */
    fun addHit(ruleId: Long, sender: String, body: String): Boolean {
        val now = System.currentTimeMillis()
        val dup = hits.any { it.ruleId == ruleId && it.body == body && now - it.time < DEDUPE_WINDOW_MS }
        if (dup) return false

        hits.add(ImportantHit(nextHitId++, ruleId, sender, body, now))
        // 超出上限按时间淘汰最旧的
        val ofRule = hits.filter { it.ruleId == ruleId }
        if (ofRule.size > MAX_HITS_PER_RULE) {
            val drop = ofRule.sortedBy { it.time }.take(ofRule.size - MAX_HITS_PER_RULE).map { it.id }.toSet()
            hits.removeAll { it.id in drop }
        }
        changed()
        return true
    }
}

/** 短信正文压成一行摘要：没有命中的关键字时（理论不该发生）用它兜底 */
fun smsSummary(body: String, max: Int = 60): String {
    val one = body.replace(Regex("\\s+"), " ").trim()
    return if (one.length <= max) one else one.take(max) + "…"
}

// ---------------- 摘要（关键字 + 上下文） ----------------

/** 关键字前后各取多少个字 */
private const val SNIPPET_CONTEXT = 10

/** 摘要里最多展示几个关键字 */
private const val SNIPPET_MAX_KEYWORDS = 2

/**
 * 两个关键字之间不超过这么多字就并到一行。
 *
 * 正好等于「两次上下文宽度之和」（10 + 10）：间隔 ≤ 20 时两段文字是相连或重叠的，
 * 合并后不会出现断裂的空白；超过 20 说明中间还有一大段无关内容，分两行更易读。
 */
private const val SNIPPET_MERGE_GAP = 20

/** 摘要里关键字的颜色（红） */
private const val KEYWORD_COLOR = 0xFFD32F2F.toInt()

/**
 * 生成「信息摘要」：把命中的关键字连同它前后各 10 个字摘出来，关键字标红。
 *
 * 规则（按用户要求）：
 *  - 最多取 2 个关键字，按它们在短信正文里出现的先后顺序取前两个；
 *  - 两个关键字默认分两行显示；
 *  - 两个关键字间隔不超过 20 字时合并成一行（中间的文字一并保留，读起来是连贯的一句）。
 *
 * 返回的是每一行的富文本（已带红色 + 加粗 span），行数 1 或 2。
 * 关键字对比忽略大小写，但显示用的是原文（保住 "OTP" 这种大写写法的观感）。
 */
fun smsSnippet(body: String, keywords: List<String>): List<CharSequence> {
    if (body.isBlank()) return emptyList()

    // 1) 每个关键字在正文里的首次出现位置；和已选范围重叠的跳过
    //    （规则里同时写「余额」和「余额变动」时，两段会落在同一处，只保留先找到的那个）
    val occurrences = mutableListOf<Triple<Int, Int, String>>()
    for (kw in keywords) {
        if (kw.isBlank()) continue
        val start = body.indexOf(kw, ignoreCase = true)
        if (start < 0) continue
        val end = start + kw.length
        if (occurrences.any { start < it.second && end > it.first }) continue
        occurrences.add(Triple(start, end, body.substring(start, end)))
    }

    // 一个都没找到（理论上不会：能入库就说明命中了）：退回整条摘要
    if (occurrences.isEmpty()) return listOf(smsSummary(body))

    // 2) 按出现位置排序，最多取前 2 个
    val picked = occurrences.sortedBy { it.first }.take(SNIPPET_MAX_KEYWORDS)

    // 3) 合并判定：看两个「关键字本体」之间的间隔字数
    val merge = picked.size == 2 &&
        (picked[1].first - picked[0].second) <= SNIPPET_MERGE_GAP

    return if (merge) {
        val from = (picked[0].first - SNIPPET_CONTEXT).coerceAtLeast(0)
        val to = (picked[1].second + SNIPPET_CONTEXT).coerceAtMost(body.length)
        listOf(buildSnippetLine(body, from, to, picked))
    } else {
        picked.map { (s, e, _) ->
            buildSnippetLine(
                body,
                (s - SNIPPET_CONTEXT).coerceAtLeast(0),
                (e + SNIPPET_CONTEXT).coerceAtMost(body.length),
                picked
            )
        }
    }
}

/** 摘出 [from, to) 这段正文，落在段内的关键字标红加粗；两端被截断时补省略号 */
private fun buildSnippetLine(
    body: String, from: Int, to: Int, keywords: List<Triple<Int, Int, String>>
): CharSequence {
    val lead = if (from > 0) "…" else ""
    val trail = if (to < body.length) "…" else ""
    val spannable = SpannableString(lead + body.substring(from, to) + trail)

    for ((start, end, _) in keywords) {
        if (start < from || end > to) continue      // 不在这一段里就不标
        val s = lead.length + (start - from)
        spannable.setSpan(
            ForegroundColorSpan(KEYWORD_COLOR), s, s + (end - start),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            StyleSpan(Typeface.BOLD), s, s + (end - start),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    return spannable
}

/**
 * 命中重要规则时的系统通知：**高优先级**，会响铃/震动。
 *
 * 与快递那条常驻通知的区别：快递是「有待取件」的状态栏提示、安静且不可划掉；
 * 这里是「刚刚来了一条你在意的短信」，是一次性事件，点了就消。
 * 两条通知渠道分开，用户可以在系统设置里单独关掉其中一个。
 */
object ImportantNotify {
    private const val CHANNEL_ID = "important_sms"
    private const val NOTIF_ID = 2002   // 固定 id：后到的覆盖前一条，连收几条不会堆满通知栏

    // minSdk 31，渠道相关 API 都在 26 以上，不需要版本判断
    fun ensureChannel(ctx: Context) {
        val ch = NotificationChannel(
            CHANNEL_ID, "重要短信提醒", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "收到命中重要规则的短信时响铃提醒"
            enableVibration(true)
            enableLights(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun notifyHit(ctx: Context, rule: ImportantRule, body: String) {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_IMPORTANT)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 通知栏只有两行，摘要取「关键字 + 上下文」的第一行（两个关键字合并时也在这一行里），
        // 想看全文就展开通知。彩色 span 在通知里不生效，这里转成纯文本。
        val summary = smsSnippet(body, rule.keywords).firstOrNull()?.toString()
            ?: smsSummary(body)

        val b = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("重要短信 · ${rule.label}")
            .setContentText(summary)
            // 摘要会被截断，展开后看全文
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)   // 每来一条都要响，不能被当成"同一通知的更新"
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(open)

        try {
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, b.build())
        } catch (_: SecurityException) {
            // 用户没授予通知权限：记录照常入库，只是不弹通知
        }
    }
}

/**
 * 重要短信模块对短信的处理：命中规则就入库 + 响铃提醒。
 *
 * 由 [phoenix.smsnotify.App] 注册到 [phoenix.smsnotify.sms.SmsRouter]，
 * 和快递模块并列，互不干扰。
 */
object ImportantSmsHandler : SmsHandler {
    override fun onSms(context: Context, sender: String, body: String) {
        // 先 init：进程刚被拉起时规则还没从磁盘读出来，先匹配会拿到空列表而漏记
        ImportantStore.init(context)
        val rule = ImportantStore.match(body) ?: return
        if (ImportantStore.addHit(rule.id, sender, body)) {
            ImportantNotify.notifyHit(context, rule, body)
        }
    }
}
