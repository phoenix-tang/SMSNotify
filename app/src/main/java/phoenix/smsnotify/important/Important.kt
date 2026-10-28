package phoenix.smsnotify.important

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.PowerManager
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import phoenix.smsnotify.MainActivity
import phoenix.smsnotify.common.AppNotify
import phoenix.smsnotify.sms.SmsHandler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
 * @param ringMs   响铃时长（毫秒），取值见 [RingMode]
 */
data class ImportantRule(
    val id: Long,
    val name: String,
    val keywords: List<String>,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val ringMs: Long = RingMode.DEFAULT.ms
) {
    /** 展示用名称：没填名称时退回第一段匹配文字，再不行给个占位 */
    val label: String
        get() = name.ifBlank {
            keywords.firstOrNull()?.ifBlank { "" }.orEmpty().ifBlank { "未命名规则" }
        }

    val ringMode: RingMode get() = RingMode.of(ringMs)

    companion object {
        /**
         * 规则名长度上限，单位是 `displayWidth()` 的「半角宽度」：24 = 12 个汉字。
         *
         * 依据是它要出现的那几个位置，取其中最紧的那个能接受的宽松度：
         *  - 顶部胶囊：规则名最多约 9 个汉字，再长就省略号（那里本来就是扫一眼，可接受）；
         *  - App 卡片标题：单行约 16 个汉字，24 以内完整显示；
         *  - 通知标题：约 14 个汉字，24 以内完整显示。
         * 再放宽就会让卡片标题也开始省略 —— 那是规则名唯一的完整展示位，不该被截。
         */
        const val NAME_MAX_WIDTH = 24

        /**
         * 单段匹配文字的宽度上限，同样按半角计：24 = 12 个汉字。
         *
         * 这个值不是「显示」约束而是「匹配」约束：关键字要在短信原文里原样出现才算命中，
         * 一条短信平均才 60~80 字，12 个字的关键字已经要求它一字不差地占掉五分之一，
         * 再长基本只有照抄原文才命中得了。同时摘要按「关键字前后各 10 字」裁，
         * 12 字关键字裁出来的一段约 32 字，正好是卡片两行的量。
         */
        const val KEYWORD_MAX_WIDTH = 24

        /**
         * 一条规则最多几段匹配文字。
         *
         * 段与段是「且」的关系，每加一段就更难命中，超过三四段的规则基本是照着某条短信
         * 抄下来的，换个模板就失效。5 段是「够用且还能一眼看完」的量：编辑页一行一段，
         * 5 行已经占掉大半屏；再多的组合条件该交给快递模块那套带正则的规则。
         */
        const val KEYWORD_MAX_COUNT = 5
    }
}

/**
 * 一次命中：哪条规则、什么内容、什么时候。
 *
 * **不保存短信原文，也不保存发件号码**，只保存入库时按规则关键字裁好的摘要 [excerpt]
 * （多段时用 `\n` 分隔）。验证码、动账这类短信原文里全是敏感信息，而界面从来只显示
 * 关键字前后各 10 个字，留全文既没用又多一份泄露面；号码同理 —— 界面上从来没显示过它，
 * 存着只是白白多一份数据。摘要也不会因为后来改了规则关键字而变样。
 */
data class ImportantHit(
    val id: Long,
    val ruleId: Long,
    /** 已裁好的摘要：关键字前后各 10 字，最多两段，段间用 `\n` 分隔 */
    val excerpt: String,
    val time: Long,
    /**
     * 这条摘要**是被哪几个关键字裁出来的**，入库时的快照。
     *
     * 光有摘要说不清"为什么会被记下来"：规则后来改了关键字，摘要里的旧词就不再是
     * 当前条件的一部分。存一份快照，界面才能
     *  - 按当时的词给关键字上色（而不是按今天的关键字，那样会漏标或误标）；
     *  - 把"当前条件捕获的"和"旧条件捕获的"分开显示（见 [ImportantPageView]）。
     *
     * 老数据没有这个字段时，读盘会补成"那一刻规则的关键字"（见 [ImportantStore.init]），
     * 所以空列表只可能出现在"规则已被删掉"这种理论状态下，显示时按当前规则兜底处理。
     */
    val keywords: List<String> = emptyList()
)

/** 规则 + 命中记录的持久化与匹配（JSON 文件放在应用私有目录，随卸载删除） */
object ImportantStore {
    private const val FILE = "important_sms.json"

    /**
     * 单条规则最多保留多少条命中。
     *
     * 命中记录只回答"最近来过什么"，20 条正好一屏多一点，扫起来不累；再往前的
     * 本来也没人翻。超出上限按时间淘汰最旧的 —— 顺带让整份 JSON 足够小，
     * 每次变更全量重写那点开销可以忽略。
     */
    private const val MAX_HITS_PER_RULE = 20

    /** 同一条短信被重复投递时（长短信分片等）的去重窗口 */
    private const val DEDUPE_WINDOW_MS = 2000L

    private val rules = mutableListOf<ImportantRule>()
    private val hits = mutableListOf<ImportantHit>()
    private var nextRuleId = 1L
    private var nextHitId = 1L
    private var file: File? = null

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
                        createdAt = o.optLong("createdAt", 0L),
                        // 认不出的存盘值（含已删除的「单次提示」= 0、「无限」= -1）一并归一成
                        // 默认档，这样内存里的值一定是合法枚举，界面和列表摘要都不会算错
                        ringMs = RingMode.of(o.optLong("ringMs", RingMode.DEFAULT.ms)).ms
                    )
                )
                if (id >= nextRuleId) nextRuleId = id + 1
            }

            val hs = root.optJSONArray("hits") ?: JSONArray()
            for (i in 0 until hs.length()) {
                val o = hs.getJSONObject(i)
                val id = o.optLong("id", 0L)
                val ruleId = o.optLong("ruleId", 0L)
                // 老版本存的是短信原文（body）：读进来时按当前规则的关键字现算一次摘要，
                // 原文到此为止，下一次 persist 就只剩摘要了
                val excerpt = o.optString("excerpt", "").ifBlank {
                    val body = o.optString("body", "")
                    if (body.isBlank()) "" else smsExcerpt(body, find(ruleId)?.keywords.orEmpty())
                }
                hits.add(
                    ImportantHit(
                        id = id,
                        ruleId = ruleId,
                        excerpt = excerpt,
                        time = o.optLong("time", 0L),
                        // 老数据没有快照：补成"此刻这条规则的关键字"，也就是升级那一刻的条件。
                        // 不补的话，这些记录在用户下次改关键字时会被当成"新条件捕获的"，
                        // 混进当前组里，反而更说不清来历
                        keywords = o.optJSONArray("keywords").toStringList()
                            .ifEmpty { find(ruleId)?.keywords.orEmpty() }
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
                    .put("ringMs", r.ringMs)
            )
        }
        val hArr = JSONArray()
        hits.forEach { h ->
            hArr.put(
                JSONObject()
                    .put("id", h.id).put("ruleId", h.ruleId)
                    .put("excerpt", h.excerpt).put("time", h.time)
                    .put("keywords", JSONArray(h.keywords))
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

    /** 删掉单条命中记录（记录行的左滑删除） */
    fun removeHit(hitId: Long) {
        if (hits.removeAll { it.id == hitId }) changed()
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

    /**
     * 记录一次命中。**只留摘要，不留原文**（见 [ImportantHit]）。
     *
     * 去重比对的是摘要而不是原文：两条在 2 秒内到达、摘要相同的短信（长短信分片）
     * 本来就不该各记一条。摘要已经包含关键字前后各 10 字，正常的两条不同短信不会撞。
     */
    fun addHit(rule: ImportantRule, body: String): Boolean {
        val now = System.currentTimeMillis()
        val excerpt = smsExcerpt(body, rule.keywords)
        val dup = hits.any {
            it.ruleId == rule.id && it.excerpt == excerpt && now - it.time < DEDUPE_WINDOW_MS
        }
        if (dup) return false

        hits.add(ImportantHit(nextHitId++, rule.id, excerpt, now, rule.keywords))
        // 超出上限按时间淘汰最旧的
        val ofRule = hits.filter { it.ruleId == rule.id }
        if (ofRule.size > MAX_HITS_PER_RULE) {
            val drop = ofRule.sortedBy { it.time }.take(ofRule.size - MAX_HITS_PER_RULE).map { it.id }.toSet()
            hits.removeAll { it.id in drop }
        }
        changed()
        return true
    }
}

/**
 * 一套关键字的「条件指纹」：给命中记录分组用。
 *
 * 顺序和大小写都不算改动（匹配本来就是 ignoreCase，而 AND 与顺序无关），
 * 所以先小写、去重、排序再拼起来。拿它当 Map 的 key，同一套条件必然落进同一组。
 */
internal fun conditionKey(keywords: List<String>): String =
    keywords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        .distinct().sorted().joinToString("\u0000")

/** 两套关键字在语义上是否相同（只改了顺序或大小写不算改动） */
internal fun sameConditions(a: List<String>, b: List<String>): Boolean =
    conditionKey(a) == conditionKey(b)

/** JSON 数组 → 字符串列表：null 当空数组，顺带滤掉空白段 */
private fun JSONArray?.toStringList(): List<String> =
    (0 until (this?.length() ?: 0)).map { this!!.optString(it, "") }.filter { it.isNotBlank() }

/** 短信正文压成一行摘要：没有命中的关键字时（理论不该发生）用它兜底 */
fun smsSummary(body: String, max: Int = 60): String {
    val one = body.replace(Regex("\\s+"), " ").trim()
    return if (one.length <= max) one else one.take(max) + "…"
}

// ---------------- 摘要（关键字 + 上下文） ----------------

/** 关键字前后各取多少个字 */
private const val SNIPPET_CONTEXT = 10

/**
 * 摘要里最多展示几段（= 最多几行）。
 *
 * 上限 2 段是版面约束：卡片展开后摘要只占两行，再多就把卡片撑得看不过来。
 * 规则可能写了三段以上，但命中记录只要够认出「哪条短信」，不必逐段复核。
 */
private const val SNIPPET_MAX_KEYWORDS = 2

/**
 * 相邻两段的关键字之间不超过这么多字就并到一行。
 *
 * 正好等于「两次上下文宽度之和」（10 + 10）：间隔 ≤ 20 时两段文字是相连或重叠的，
 * 合并后不会出现断裂的空白；超过 20 说明中间还有一大段无关内容，分成两行更易读。
 */
private const val SNIPPET_MERGE_GAP = 20

/** 摘要里关键字的颜色（红） */
private const val KEYWORD_COLOR = 0xFFD32F2F.toInt()

/**
 * 生成「信息摘要」：把命中的关键字连同它前后各 10 个字摘出来。
 *
 * 规则（按用户要求）：
 *  - 按关键字在正文里出现的先后顺序，最多取 2 段；
 *  - 默认一段一行；
 *  - 相邻两段的关键字间隔不超过 20 字时合并成一行（中间的文字一并保留，读起来是连贯的一句）。
 *
 * 返回**纯文本**，一行一段（1~2 条），入库的就是这个结果；
 * 关键字的红字加粗交给 [highlightKeywords] 在渲染时做 —— 存进来的只有文字，不带 span。
 * 关键字对比忽略大小写，但显示用的是原文（保住 "OTP" 这种大写写法的观感）。
 */
fun smsSnippet(body: String, keywords: List<String>): List<String> {
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

    // 2) 按出现位置排序，最多取前 2 段
    val picked = occurrences.sortedBy { it.first }.take(SNIPPET_MAX_KEYWORDS)

    // 3) 相邻两段的关键字本体挨得够近就并成一段，否则各占一行
    val groups = mutableListOf<MutableList<Triple<Int, Int, String>>>()
    for (occ in picked) {
        val last = groups.lastOrNull()
        if (last != null && occ.first - last.last().second <= SNIPPET_MERGE_GAP) last.add(occ)
        else groups.add(mutableListOf(occ))
    }

    return groups.map { group ->
        val from = (group.first().first - SNIPPET_CONTEXT).coerceAtLeast(0)
        val to = (group.last().second + SNIPPET_CONTEXT).coerceAtMost(body.length)
        snippetLine(body, from, to)
    }
}

/** 摘要入库前的最终形态：多段用 `\n` 连成一个字符串 */
fun smsExcerpt(body: String, keywords: List<String>): String =
    smsSnippet(body, keywords).joinToString("\n")

/**
 * 渲染存下来的摘要：把关键字标红加粗。
 *
 * 摘要是纯文本（不带 span），所以每次显示都要重新上色；关键字按字面查找、忽略大小写，
 * 一段文字里出现多次就都标上。
 */
fun highlightKeywords(line: String, keywords: List<String>): CharSequence {
    if (line.isEmpty()) return line
    val spannable = SpannableString(line)
    var marked = false
    for (kw in keywords) {
        if (kw.isBlank()) continue
        var at = line.indexOf(kw, ignoreCase = true)
        while (at >= 0) {
            val end = at + kw.length
            spannable.setSpan(
                ForegroundColorSpan(KEYWORD_COLOR), at, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(Typeface.BOLD), at, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            marked = true
            at = line.indexOf(kw, end, ignoreCase = true)
        }
    }
    return if (marked) spannable else line
}

/** 摘出 [from, to) 这段正文；两端被截断时补省略号 */
private fun snippetLine(body: String, from: Int, to: Int): String {
    val lead = if (from > 0) "…" else ""
    val trail = if (to < body.length) "…" else ""
    return lead + body.substring(from, to) + trail
}

/**
 * 命中重要规则时的系统通知：**高优先级**，带振动，并且一定带「停止响铃」。
 *
 * 与快递那条常驻通知的区别：快递是「有待取件」的状态栏提示、安静且不可划掉；
 * 这里是「刚刚来了一条你在意的短信」，是一次响铃事件。两条通知渠道分开，
 * 用户可以在系统设置里单独关掉其中一个。
 *
 * 声音**从不交给渠道**：渠道只会响固定的一遍，系统不给「响 30 秒」这种参数，
 * 时长和「随时可停」都由 [AlarmRing] 自己控制。所以下面两条渠道都是静音的，
 * 差别只在重要性和振动：
 *  - `important_sms_ring`：HIGH。屏幕上什么都没有时用它，横幅是唯一能看见的提示；
 *  - `important_sms_ring_island`：**胶囊已经浮出来时**才用。只把重要性降到 DEFAULT ——
 *    横幅（heads-up）只有 HIGH 才会弹，这样屏幕上只剩胶囊一处提示，
 *    通知栏那条照样留着「停止响铃」。
 *
 * 两条不能合成一条，是因为横幅是**渠道级**属性：`setSilent(true)` 只压声音，
 * 压不住"要不要弹横幅"这件事。
 */
object ImportantNotify {
    /**
     * 响铃用的渠道：渠道本身不发声，声音由 [AlarmRing] 自己循环。
     *
     * 振动留在渠道上（三短一停：比系统默认的两下更容易被注意到，又不像来电那样长震）——
     * 静音的循环铃声没有"结束点"，振动是唯一还能分辨「这是一次提醒」的东西。
     */
    private val RING_CHANNEL = AppNotify.ChannelSpec(
        id = "important_sms_ring",
        name = "重要短信提醒 · 响铃",
        description = "命中重要规则时使用；声音由本应用播放，点通知上的「停止响铃」或划掉通知可中止",
        importance = NotificationManager.IMPORTANCE_HIGH,
        silent = true,
        vibration = longArrayOf(0, 400, 200, 400, 200, 400),
        lights = true
    )

    /**
     * 胶囊浮在屏幕上时用的「不弹横幅」版本。
     *
     * 唯一的关键是 `IMPORTANCE_DEFAULT`：**横幅只有 HIGH 才会弹**。降一档之后通知
     * 照旧进通知栏、照旧带「停止响铃」，只是不再从顶部盖下来 —— 否则它会正好压在胶囊上，
     * 而且"重要短信在响"这件事在屏幕上说了两遍。
     *
     * 振动要和 [RING_CHANNEL] 保持一致：少了横幅，更容易被漏看；而用户可能正盯着屏幕，
     * 手上那一下震动往往比声音更早被注意到。
     */
    private val ISLAND_CHANNEL = AppNotify.ChannelSpec(
        id = "important_sms_ring_island",
        name = "重要短信提醒 · 顶部胶囊",
        description = "顶部浮出胶囊时使用：不弹横幅，只在通知栏留一条带「停止响铃」的提醒",
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        silent = true,
        vibration = longArrayOf(0, 400, 200, 400, 200, 400),
        lights = true
    )

    internal const val NOTIF_ID = 2002   // 固定 id：后到的覆盖前一条，连收几条不会堆满通知栏

    private const val ACTION_STOP_RING = "phoenix.smsnotify.STOP_RING"

    fun ensureChannel(ctx: Context) {
        AppNotify.ensureChannel(ctx, RING_CHANNEL)
        AppNotify.ensureChannel(ctx, ISLAND_CHANNEL)
    }

    fun notifyHit(ctx: Context, rule: ImportantRule, body: String) {
        ensureChannel(ctx)

        val mode = rule.ringMode

        // 通知栏只有两行，摘要取「关键字 + 上下文」的第一行（两个关键字合并时也在这一行里），
        // 想看更多就展开通知。这里直接用纯文本摘要，彩色 span 在通知里不生效。
        val summary = smsSnippet(body, rule.keywords).firstOrNull()
            ?: smsSummary(body)

        // 先响铃、先立提醒：它到底看不看得见（胶囊浮出来了，或者 App 里的卡片正显示着），
        // 决定下面这条通知用哪条渠道。「为什么响」现在由提醒自己先说，比通知早十几毫秒，感知不到区别。
        // 标题就用规则名本身：带上「重要短信 · 」前缀只会把规则名往右挤，通知栏一截断
        // 先被切掉的恰好是用户最想看的那个名字。
        val visible = AlarmRing.start(
            ctx, mode, rule.label,
            excerpt = smsExcerpt(body, rule.keywords),
            keywords = rule.keywords
        )

        // 静默渠道只在「屏幕亮着、提醒已经在眼前」时用，目的是别让横幅压住胶囊。
        // 屏幕灭着必须走 HIGH 渠道：IMPORTANCE_DEFAULT 的通知 SystemUI 根本不会
        // 给它全屏提醒的机会 —— 表现就是「有声音、屏幕不亮」，而且没有任何报错。
        // 这里不看 cardVisible 一个值：它可能因为页面没走到 onPause 而残留为 true，
        // 那时候屏幕上其实什么都没有，再走静默渠道就等于既不亮屏也不弹横幅。
        val screenOn = ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val channelId = if (visible && screenOn) ISLAND_CHANNEL.id else RING_CHANNEL.id

        // 先撤掉上一条，再发新的。
        //
        // 系统只在通知**新增**（added）时评估全屏 intent（AOSP
        // `NotificationInterruptStateProviderImpl.shouldLaunchFullScreenIntentWhenAdded`）。
        // 同一个 id 直接 notify 会被当成"更新"，于是第一条短信能亮屏，后面几条就只剩声音。
        // 撤掉再发，等于告诉系统「这是一条全新的提醒」，灯光和全屏页都会重新走一遍。
        //
        // 顺带收走孤儿通知：进程被系统回收时，stopRinging() 里那句 cancel 永远没有机会执行，
        // 留下一条写着「响铃中」实际早就没声音的通知。下一条短信到达时正好把它清掉。
        AppNotify.cancel(ctx, NOTIF_ID)

        AppNotify.post(ctx, channelId, NOTIF_ID) { b ->
            b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(rule.label)
                .setContentText(summary)
                // 摘要会被截断，展开后看全文
                .setStyle(Notification.BigTextStyle().bigText(body))
                // 点通知只是「进 App 看」，铃声还在响 —— 通知必须留着：它上面的
                // 「停止响铃」和划掉手势都是停止入口，自动消失的话用户就只剩胶囊一条路
                .setAutoCancel(false)
                .setOnlyAlertOnce(false)   // 每来一条都要响，不能被当成"同一通知的更新"
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setContentIntent(AppNotify.openApp(ctx, MainActivity.TAB_IMPORTANT))
                // 息屏/锁屏时交给系统把 [AlarmActivity] 拉起来并点亮屏幕 —— 没有这一句，
                // 用户听到铃声抬头看，屏幕还是黑的。亮屏且解锁时系统不会用它，仍然只弹横幅；
                // 没有「全屏通知」权限时会被静默忽略（见 [canFullScreen]）。
                .setFullScreenIntent(alarmScreen(ctx), true)

            // 铃声到点自停时通知跟着消失：留一条"响铃中"却没有声音的通知只会让人困惑
            b.setTimeoutAfter(mode.ms)
            // 副标题交代"总共该响多久"，和胶囊、卡片上的「共 X」是同一个数
            b.setSubText("响铃中 · 共 ${mode.label}")
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.ic_lock_silent_mode),
                    "停止响铃",
                    stopRing(ctx, requestCode = 10)
                ).build()
            )
            // 划掉通知也当作"别响了"：闹钟响着却找不到停止的地方是最糟的体验
            b.setDeleteIntent(stopRing(ctx, requestCode = 11))
        }
        // 没授予通知权限时 AppNotify.post 会吞掉异常：记录照常入库，只是不弹通知
    }

    private fun stopRing(ctx: Context, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, StopRingReceiver::class.java).setAction(ACTION_STOP_RING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 全屏提醒页的入口，交给 [Notification.Builder.setFullScreenIntent]。
     *
     * requestCode 固定 12：同一个 PendingIntent 反复复用，不需要每次都新建。
     */
    private fun alarmScreen(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 12,
            Intent(ctx, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 系统是否允许本应用弹「全屏提醒」。这是锁屏/息屏亮屏的**唯一前提**。
     *
     * Android 14（API 34）起，`USE_FULL_SCREEN_INTENT` 只默认授予通话/闹钟类应用，
     * 其余应用即使 manifest 里声明了，系统也会把全屏 intent 静默降级成一条普通通知 ——
     * 表现正是「有声音、屏幕不亮」，而且没有任何报错。所以设置页要能显示这个状态并跳转授权，
     * 否则用户只会以为是 App 坏了。
     *
     * API 33 及以下没有这个限制，一律返回 true。
     */
    fun canFullScreen(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else true
    } catch (_: Exception) {
        // 个别 ROM 没实现这个查询接口：按「允许」处理，让通知照常带全屏 intent
        true
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
        // sender（发件号码）刻意不入库：界面从来没显示过它，摘要也足够认出是哪条短信

        // 先 init：进程刚被拉起时规则还没从磁盘读出来，先匹配会拿到空列表而漏记
        ImportantStore.init(context)
        val rule = ImportantStore.match(body) ?: return
        if (ImportantStore.addHit(rule, body)) {
            ImportantNotify.notifyHit(context, rule, body)
        }
    }
}
