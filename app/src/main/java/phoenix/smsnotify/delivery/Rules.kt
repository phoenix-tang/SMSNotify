package phoenix.smsnotify.delivery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
    val name: String,        // 规则名称
    val cabPrefix: String,   // 柜架号前缀（字面量）
    val cabSuffix: String,   // 柜架号后缀（字面量）
    val codePrefix: String,  // 取件码前缀（字面量）
    val codeSuffix: String,  // 取件码后缀（字面量）
    val enabled: Boolean = true,
    /** 由内置文件播种而来，只用于列表上标注「内置」；可修改、可删除、可停用 */
    val builtin: Boolean = false
)

/** 提取规则的存储与匹配（低代码拼接 → 正则） */
object RuleStore {
    private const val FILE = "rules.json"

    /**
     * 内置规则文件（assets/preset_rules.json）。
     * 它只负责「首次安装时播种」：导入后规则就变成普通规则，有自己的编号，
     * 可以在界面上改参数、停用、删除，之后不再与这个文件保持联动。
     * 文件里的 `version` 用于以后新增内置规则时做增量补种。
     */
    private const val SEED_ASSET = "preset_rules.json"

    private val rules = mutableListOf<ExtractRule>()
    private var nextId = 1L
    private var file: File? = null

    /** 已经播种过的内置文件版本号 */
    private var seededVersion = 0

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, FILE)
        if (file!!.exists()) load()
        seedFromAsset(ctx)
        normalizeOrder()
    }

    // ---------- 读写 ----------

    private fun load() {
        try {
            val text = file!!.readText()
            val arr: JSONArray
            if (text.trimStart().startsWith("{")) {
                val root = JSONObject(text)
                seededVersion = root.optInt("seedVersion", 0)
                arr = root.optJSONArray("rules") ?: JSONArray()
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
                rules.add(
                    ExtractRule(
                        id = id,
                        name = o.optString("name", ""),
                        cabPrefix = o.optString("cabPrefix", ""),
                        cabSuffix = o.optString("cabSuffix", ""),
                        codePrefix = o.optString("codePrefix", ""),
                        codeSuffix = o.optString("codeSuffix", ""),
                        enabled = o.optBoolean("enabled", true),
                        builtin = o.optBoolean("builtin", false)
                    )
                )
            }
        } catch (_: Exception) { }
    }

    /** 从内置文件读取规则；文件缺失或格式错误返回 null */
    private fun readSeedAsset(ctx: Context): Pair<Int, List<ExtractRule>>? = try {
        val text = ctx.assets.open(SEED_ASSET).bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val arr = root.optJSONArray("rules") ?: JSONArray()
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ExtractRule(
                id = 0L,
                name = o.optString("name", "内置规则"),
                cabPrefix = o.optString("cabPrefix", ""),
                cabSuffix = o.optString("cabSuffix", ""),
                codePrefix = o.optString("codePrefix", ""),
                codeSuffix = o.optString("codeSuffix", ""),
                enabled = o.optBoolean("enabled", true),
                builtin = true
            )
        }
        root.optInt("version", 1) to list
    } catch (_: Exception) { null }

    /** 首次安装（或内置文件版本升级）时把内置规则导入成普通规则 */
    private fun seedFromAsset(ctx: Context) {
        val (version, builtinRules) = readSeedAsset(ctx) ?: return
        if (version <= seededVersion) return
        var changed = false
        for (b in builtinRules) {
            // 按名字去重：用户删掉的内置规则不会被悄悄塞回来（除非文件版本升级）
            if (rules.none { it.name == b.name }) {
                rules.add(b.copy(id = nextId++))
                changed = true
            }
        }
        seededVersion = version
        if (changed || !file!!.exists()) persist()
    }

    private fun persist() {
        val arr = JSONArray()
        rules.forEach { arr.put(toJson(it)) }
        file?.writeText(
            JSONObject().put("seedVersion", seededVersion).put("rules", arr).toString()
        )
    }

    private fun toJson(r: ExtractRule): JSONObject = JSONObject()
        .put("id", r.id).put("name", r.name)
        .put("cabPrefix", r.cabPrefix).put("cabSuffix", r.cabSuffix)
        .put("codePrefix", r.codePrefix).put("codeSuffix", r.codeSuffix)
        .put("enabled", r.enabled).put("builtin", r.builtin)

    // ---------- 增删改查 ----------

    /** 列表顺序 = 匹配顺序：自定义规则在前（先匹配），内置规则兜底排在最后 */
    fun all(): List<ExtractRule> = rules.toList()

    fun find(id: Long): ExtractRule? = rules.find { it.id == id }

    fun save(r: ExtractRule) {
        val idx = rules.indexOfFirst { it.id == r.id }
        if (idx >= 0) {
            // 保留原来的 builtin 标记，避免编辑内置规则后被当成自定义规则
            rules[idx] = r.copy(id = r.id, builtin = rules[idx].builtin)
        } else {
            rules.add(r.copy(id = nextId++, builtin = false))
        }
        normalizeOrder()
        persist()
    }

    fun remove(id: Long) {
        if (rules.removeAll { it.id == id }) persist()
    }

    /** 开关切换：只改 enabled，不动其他字段 */
    fun setEnabled(id: Long, enabled: Boolean) {
        val idx = rules.indexOfFirst { it.id == id }
        if (idx < 0 || rules[idx].enabled == enabled) return
        rules[idx] = rules[idx].copy(enabled = enabled)
        persist()
    }

    /** 内置规则永远排在自定义规则之后 */
    private fun normalizeOrder() {
        if (rules.size < 2) return
        val custom = rules.filter { !it.builtin }
        val builtin = rules.filter { it.builtin }
        rules.clear()
        rules.addAll(custom)
        rules.addAll(builtin)
    }

    // ---------- 拼接正则 ----------

    /** 柜架号：只捕获数字，"号柜"这类文字交给后缀字段（后缀是字面量，不进捕获组） */
    const val CAB_CAPTURE = "(\\d+)"
    /** 取件码：数字，可含连字符，如 205936 / 3-2-5016 */
    const val CODE_CAPTURE = "((?:\\d+-)*\\d{3,})"
    /** 前缀和取值之间通常夹着几个字，如"取件码为205936"里的"为" */
    const val GAP = "[^0-9]{0,4}"

    fun cabinetPattern(r: ExtractRule): String =
        prefixPart(r.cabPrefix) + CAB_CAPTURE + lit(r.cabSuffix)

    fun codePattern(r: ExtractRule): String =
        prefixPart(r.codePrefix) + CODE_CAPTURE + lit(r.codeSuffix)

    /** 字面量转义；前缀后面允许少量非数字间隔 */
    private fun prefixPart(p: String): String = if (p.isEmpty()) "" else lit(p) + GAP

    /**
     * 只转义真正的正则元字符。
     * 不用 Regex.escape/Pattern.quote —— 它们会把每个字面量包成 \Q号柜\E，预览页读起来很乱；
     * 中文和普通字符本身在正则里没有特殊含义，原样输出即可，预览就是 `(\d+)号柜`。
     */
    private fun lit(s: String): String {
        if (s.isEmpty()) return ""
        val meta = "\\^$.|?*+()[]{}"
        val sb = StringBuilder(s.length + 4)
        for (c in s) {
            if (c in meta) sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

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
 * 原先这里还有一套硬编码的兜底解析（"号柜" + 取件码的启发式），已并入内置规则，
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
