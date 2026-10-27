package phoenix.smsnotify.delivery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * 规则的「数据 + 逻辑」层，界面在 [RulesActivity] / [RuleEditActivity] 里。
 *
 * 一个提取规则只描述「柜架号」和「取件码」各自的前后缀，真正的正则由代码拼出来，
 * 用户在界面上填的是字面量，不需要会写正则。
 *
 * 三条链路：
 *   1. [ExtractRule]  一条规则的数据（界面编辑的就是它）
 *   2. [RuleStore]    规则的持久化 + 拼接正则 + 逐条匹配
 *   3. [PickupParser] 短信解析入口，把原文交给规则列表跑一遍
 */

/** 一条提取规则：柜架号 / 取件码各自的字面量前后缀 */
data class ExtractRule(
    val id: Long,
    val name: String,        // 规则名称。预设规则用它当唯一标识，所以预设的名称不可改
    val cabPrefix: String,   // 柜架号前缀（字面量）
    val cabSuffix: String,   // 柜架号后缀（字面量）
    val codePrefix: String,  // 取件码前缀（字面量）
    val codeSuffix: String,  // 取件码后缀（字面量）
    val enabled: Boolean = true,
    /**
     * true = 预设规则：名称和四个字段由 assets/preset_rules.json 提供，**只读**，
     * 每次启动按 [name] 与文件对齐（只能停用/启用，[enabled] 会被保留）。
     *
     * false = 自建规则：完全归用户，编辑、删除、改名都随意，**永不随应用更新变化**。
     */
    val preset: Boolean = false
)

/** 提取规则的存储与匹配（低代码拼接 → 正则） */
object RuleStore {
    private const val FILE = "rules.json"

    /**
     * 预设规则文件（assets/preset_rules.json）。
     *
     * 它是预设规则的**唯一权威来源**：每次 [init] 都按名称与本地比对一次，
     * 文件里改了什么，装着新版应用的用户就会得到什么；文件里删掉的规则，
     * 本地对应的预设规则也随之消失。用户自建规则完全不受这个过程影响。
     *
     * 本地只保存预设规则的**启用状态**，字段以文件为准。
     */
    private const val SEED_ASSET = "preset_rules.json"

    private val rules = mutableListOf<ExtractRule>()
    private var nextId = 1L
    private var file: File? = null

    /** 老数据里靠 builtin 推断出来的预设规则，名称可能是旧文案，需要用位置兜底认领新名字 */
    private val legacyIds = mutableSetOf<Long>()

    /** 读到的是旧格式（有 presetKey / builtin / seedVersion），同步完顺手把文件重写成新格式 */
    private var needsRewrite = false

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, FILE)
        if (file!!.exists()) load()
        syncPresets(ctx)
        if (normalizeOrder()) persist()   // 顺序被调整过就落盘，免得每次启动都要重排
    }

    // ---------- 读写 ----------

    private fun load() {
        try {
            val text = file!!.readText()
            val arr: JSONArray
            if (text.trimStart().startsWith("{")) {
                arr = JSONObject(text).optJSONArray("rules") ?: JSONArray()
            } else {
                arr = JSONArray(text)      // 老格式：纯数组
            }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                var id = o.optLong("id", 0L)
                if (id <= 0L) {
                    // 旧数据里的预设规则固定 id=0，迁移成普通编号
                    id = nextId++
                } else if (id >= nextId) {
                    nextId = id + 1
                }
                val key = o.optString("presetKey", "")
                // preset 字段是本版；presetKey 是上一版；builtin 是更早的版本
                val preset = when {
                    o.has("preset") -> o.optBoolean("preset", false)
                    key.isNotEmpty() -> true
                    else -> o.optBoolean("builtin", false)
                }
                // 没有 preset 字段说明是老数据，名称可能是旧文案，同步时要按位置认领新名字
                if (preset && !o.has("preset")) {
                    legacyIds.add(id)
                    needsRewrite = true
                }
                rules.add(
                    ExtractRule(
                        id = id,
                        name = o.optString("name", ""),
                        cabPrefix = o.optString("cabPrefix", ""),
                        cabSuffix = o.optString("cabSuffix", ""),
                        codePrefix = o.optString("codePrefix", ""),
                        codeSuffix = o.optString("codeSuffix", ""),
                        enabled = o.optBoolean("enabled", true),
                        preset = preset
                    )
                )
            }
        } catch (_: Exception) { }
    }

    /** 从预设文件读取规则；文件缺失或格式错误返回 null */
    private fun readSeedAsset(ctx: Context): List<ExtractRule>? = try {
        val text = ctx.assets.open(SEED_ASSET).bufferedReader().use { it.readText() }
        val arr = JSONObject(text).optJSONArray("rules") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val name = o.optString("name", "")
            if (name.isEmpty()) return@mapNotNull null   // 名称就是标识，没名字的条目无法同步
            ExtractRule(
                id = 0L,
                name = name,
                cabPrefix = o.optString("cabPrefix", ""),
                cabSuffix = o.optString("cabSuffix", ""),
                codePrefix = o.optString("codePrefix", ""),
                codeSuffix = o.optString("codeSuffix", ""),
                enabled = o.optBoolean("enabled", true),
                preset = true
            )
        }.distinctBy { it.name }
    } catch (_: Exception) { null }

    /**
     * 把本地预设规则和预设文件对齐。**每次启动都跑**，幂等。
     *
     * 三条规则：
     * 1. 老数据（builtin/presetKey 时代）的名称可能已经过时：名称对不上文件时，
     *    按位置认领一个还没被占用的预设；认领不到说明该预设已被移除，丢弃。
     * 2. 按名称同步字段：名称和四个前后缀一律以文件为准，只保留本地的 id 和启用状态。
     * 3. 文件里有、本地没有的 → 以默认值插入；本地有、文件里已删掉的 → 删除。
     *
     * 自建规则（preset=false）全程不参与，永不被覆盖或删除。
     */
    private fun syncPresets(ctx: Context) {
        val presets = readSeedAsset(ctx) ?: return   // 读不到就别动本地，避免误删预设
        if (presets.isEmpty()) return
        val byName = presets.associateBy { it.name }
        var changed = false

        // 1) 老数据改名：按位置认领文件里还没被占用的名称
        val claimed = rules.filter { it.preset }.map { it.name }.toMutableSet()
        for (i in rules.indices) {
            val r = rules[i]
            if (!r.preset || byName.containsKey(r.name) || r.id !in legacyIds) continue
            val hit = presets.firstOrNull { it.name !in claimed } ?: continue
            claimed.add(hit.name)
            rules[i] = hit.copy(id = r.id, enabled = r.enabled)
            changed = true
        }

        // 2) 按名称同步字段（保留 id 与启用状态），并删掉文件里已不存在的预设
        val synced = ArrayList<ExtractRule>(rules.size + presets.size)
        for (r in rules) {
            if (!r.preset) {
                synced.add(r)
                continue
            }
            val b = byName[r.name]
            if (b == null) {
                changed = true            // 新版本的预设文件删掉了这条预设
                continue
            }
            val merged = b.copy(id = r.id, enabled = r.enabled)
            if (merged != r) changed = true
            synced.add(merged)
        }

        // 3) 新增文件里有、本地没有的预设
        for (b in presets) {
            if (synced.none { it.preset && it.name == b.name }) {
                synced.add(b.copy(id = nextId++))
                changed = true
            }
        }

        if (changed || needsRewrite) {
            rules.clear()
            rules.addAll(synced)
            persist()
        }
    }

    private fun persist() {
        val arr = JSONArray()
        rules.forEach { arr.put(toJson(it)) }
        file?.writeText(JSONObject().put("rules", arr).toString())
    }

    private fun toJson(r: ExtractRule): JSONObject = JSONObject()
        .put("id", r.id).put("name", r.name)
        .put("cabPrefix", r.cabPrefix).put("cabSuffix", r.cabSuffix)
        .put("codePrefix", r.codePrefix).put("codeSuffix", r.codeSuffix)
        .put("enabled", r.enabled).put("preset", r.preset)

    // ---------- 增删改查 ----------

    /** 列表顺序 = 匹配顺序：预设规则在前，自建规则按添加顺序排在后面 */
    fun all(): List<ExtractRule> = rules.toList()

    fun find(id: Long): ExtractRule? = rules.find { it.id == id }

    /** 保存编辑结果。预设规则是只读的，这里直接忽略——界面本来就不给入口，防一层误调 */
    fun save(r: ExtractRule) {
        val idx = rules.indexOfFirst { it.id == r.id }
        if (idx >= 0) {
            if (rules[idx].preset) return
            rules[idx] = r.copy(id = r.id, preset = false)
        } else {
            rules.add(r.copy(id = nextId++, preset = false))
        }
        normalizeOrder()
        persist()
    }

    /** 删除规则。预设规则删不掉，只能停用（[setEnabled]），它随应用更新而来 */
    fun remove(id: Long) {
        val r = rules.find { it.id == id } ?: return
        if (r.preset) return
        rules.remove(r)
        persist()
    }

    /** 开关切换：只改 enabled，不动其他字段。预设规则唯一允许的修改 */
    fun setEnabled(id: Long, enabled: Boolean) {
        val idx = rules.indexOfFirst { it.id == id }
        if (idx < 0 || rules[idx].enabled == enabled) return
        rules[idx] = rules[idx].copy(enabled = enabled)
        persist()
    }

    /**
     * 预设规则排在前面，自建规则按添加顺序跟在后面（新建的永远在最后）。
     * 返回是否真的调整过顺序。
     */
    private fun normalizeOrder(): Boolean {
        val preset = rules.filter { it.preset }
        val custom = rules.filter { !it.preset }
        val ordered = preset + custom
        if (ordered == rules) return false
        rules.clear()
        rules.addAll(ordered)
        return true
    }

    // ---------- 拼接正则 ----------

    /** 柜架号：只捕获数字，"号柜"这类文字交给后缀字段（后缀是字面量，不进捕获组） */
    private const val CAB_CAPTURE = "(\\d+)"
    /** 取件码：数字，可含连字符，如 111111 / 3-2-5016 */
    private const val CODE_CAPTURE = "((?:\\d+-)*\\d{3,})"

    /**
     * 取件码前缀与数字之间的「间隔」：允许 0~4 个**非数字**字符。
     *
     * 同一家服务商的不同模板、不同服务商之间，这个位置什么字符都有：
     * 「取件码111111」「取件码为111111」「取件码：111111」「取件码 111111」。
     * 没有它就得为每种写法各建一条规则，前缀也就失去了「写一次管一类」的意义；
     * 有它则写到「取件码」为止即可，后面的连接词随便是什么都能对上。
     *
     * 只放宽前缀这一侧：取件码本身和柜架号仍要求紧贴它们的字面量（后缀侧不放宽），
     * 否则「取件码3天后失效，验证码1234」这种正文会把不相干的数字抓走。
     */
    private const val CODE_GAP = "[^0-9]{0,4}"

    private fun cabinetPattern(r: ExtractRule): String =
        lit(r.cabPrefix) + CAB_CAPTURE + lit(r.cabSuffix)

    private fun codePattern(r: ExtractRule): String =
        lit(r.codePrefix) + CODE_GAP + CODE_CAPTURE + lit(r.codeSuffix)

    /**
     * 把用户输入整体转成字面量，**一个字都不当作正则解释**。
     *
     * 用 [Pattern.quote]（`\Q...\E`）而不是逐字符挑元字符转义：它是正则里的「引号」，
     * 对任意字符都成立，连 `\` 和 `\E` 自身也不会漏；逐字符转义总有遗漏的可能，
     * 一旦漏掉就是 PatternSyntaxException 或语义跑偏，而界面上没有任何地方能看到正则，
     * 用户只会遇到「规则莫名其妙不生效」。
     *
     * 规则里唯一的正则片段是上面两个捕获组，由代码写死，用户碰不到。
     */
    private fun lit(s: String): String = if (s.isEmpty()) "" else Pattern.quote(s)

    // ---------- 匹配 ----------

    /**
     * 只提取柜架号。柜架号是**可选**信息（有些短信只给取件码不给柜号），
     * 匹配不到就返回 null，不影响取件码的提取。
     */
    fun matchCabinet(r: ExtractRule, body: String): String? = try {
        Regex(cabinetPattern(r)).find(body)?.groupValues?.get(1)?.let { it + r.cabSuffix }
    } catch (_: Exception) { null }

    /** 只提取取件码；匹配不到返回 null */
    fun matchCode(r: ExtractRule, body: String): String? = try {
        Regex(codePattern(r)).find(body)?.groupValues?.get(1)
    } catch (_: Exception) { null }

    /**
     * 用单条规则匹配短信，返回 (柜架号, 取件码)。
     *
     * 两个正则**各自独立**在原文上跑一次，不是一个拼起来的大正则：
     * 柜架号和取件码在短信里的先后顺序、间隔多少字都不固定，拼成一条反而要写一堆
     * `.*?` 去凑中间的内容，既难读也难维护。
     *
     * 取件码是必要条件（没有取件码的短信不算取件短信）；柜架号缺失时返回空串 ""。
     */
    fun matchOne(r: ExtractRule, body: String): Pair<String, String>? {
        if (!r.enabled) return null
        val code = matchCode(r, body) ?: return null
        val cabinet = matchCabinet(r, body).orEmpty()
        return cabinet to code
    }

    /** 按列表顺序逐条尝试；停用的规则会被跳过 */
    fun applyAll(body: String): PickupParser.ParseResult? {
        for (r in rules) {
            matchOne(r, body)?.let {
                return PickupParser.ParseResult(PickupParser.providerOf(body), it.first, it.second)
            }
        }
        return null
    }
}

/**
 * 短信解析入口。
 *
 * 解析逻辑现在**完全由规则列表决定**（见 [RuleStore]）：从上到下逐条尝试，
 * 停用的规则跳过。所有规则都被停用或删光时就不再解析 —— 这样界面上的开关才是有意义的，
 * 也不会出现「关了开关还在记录」的困惑。
 *
 * 原先这里还有一套硬编码的兜底解析（"号柜" + 取件码的启发式），已并入预设规则，
 * 规则列表就是全部的解析逻辑。
 */
object PickupParser {

    data class ParseResult(val provider: String, val cabinet: String, val code: String)

    /** 返回解析结果；没有任何规则命中时返回 null */
    fun parse(body: String): ParseResult? = RuleStore.applyAll(body)

    /** 服务商：取短信开头【】里的名称，如 "兔喜生活""丰巢" */
    fun providerOf(body: String): String =
        Regex("【([^】]+)】").find(body)?.groupValues?.get(1) ?: ""
}
