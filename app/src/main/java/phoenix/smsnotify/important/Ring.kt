package phoenix.smsnotify.important

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import phoenix.smsnotify.common.AppNotify

/**
 * 响铃时长：每条规则自己选。
 *
 * 为什么不是一个全局开关：「余额到账」和「验证码」的打扰成本完全不同 ——
 * 前者可以像闹钟一样一直响，后者响一遍就够了，不该被迫共用一个值。
 *
 * **这里刻意没有「只响一声」那一档**。它曾经存在过（交给系统通知渠道响一遍），
 * 但和系统短信通知本身完全重合，而且不经过 [AlarmRing]，于是没有胶囊、没有 App 里的
 * 响铃卡片、通知上也没有「停止响铃」—— 是一套只剩通知栏的残废路径。
 * 每一档都走同一条链路，才能保证无论打扰强度多低，用户都找得到「怎么让它停下」。
 *
 * [ms] 是契约：`> 0` = 由 App 自己循环播放这么久；`-1` = 一直响到用户停。
 * 用毫秒而不是枚举名存盘，以后加「5 分钟」只要加一个枚举项，老数据不用迁移。
 */
enum class RingMode(val ms: Long, val label: String) {
    S10(10_000L, "10 秒"),
    S30(30_000L, "30 秒"),
    M1(60_000L, "1 分钟"),
    M3(180_000L, "3 分钟"),
    FOREVER(-1L, "无限");

    /**
     * 卡片角标、列表摘要用的短语。
     *
     * 不能就地拼「响 ${label}」再统一处理：可数时长读作「响 30 秒」没问题，
     * 无限那条会变成「响 无限」，两个「响」撞在一起读不通。
     *
     * 「手动停止」不再写进选项名 —— 它本来就不是无限模式的特性，
     * 每一档都能随时停（见 [AlarmRing.stop]），标在其中一个选项上会让人以为别的不行。
     */
    val badge: String get() = if (this == FOREVER) "无限响铃" else "响 $label"

    companion object {
        /**
         * 没选过时长时的默认档：新建规则、认不出的存盘值都落在这里。
         *
         * 取最短的一档，是因为「默认值必须是最不打扰的那个」—— 用户没表达偏好时，
         * 不该替他做更吵的选择。
         */
        val DEFAULT = S10

        /**
         * 存盘值 → 枚举。
         *
         * 认不出的毫秒数一律落到 [DEFAULT]：用户当初把这条规则设成重要，就是为了被叫到，
         * 让一条数据不对的规则变成哑巴比响 10 秒更难接受。
         */
        fun of(ms: Long): RingMode = entries.firstOrNull { it.ms == ms } ?: DEFAULT
    }
}

/**
 * 正在响的这一条。
 *
 * App 里那张「响铃中」卡片照着它渲染：点胶囊或点通知进来时**铃声不停**，页面要能说清
 * 「哪条规则、哪条短信、响了多久」，并且就地给出停止键 —— 和系统闹钟的做法一致
 * （点进时钟 App 后闹钟仍在响，按停止才停）。
 */
data class RingSubject(
    /** 形如「重要短信 · 银行动账」，胶囊和通知用的是同一串 */
    val title: String,
    /** 摘要，多段用 `\n` 分隔，和 [ImportantHit.excerpt] 是同一份裁剪结果 */
    val excerpt: String,
    /** 命中规则的关键字，卡片渲染时用来标红（和记录行同一套 [highlightKeywords]） */
    val keywords: List<String>,
    /** 开始响的时刻，`SystemClock.elapsedRealtime()` */
    val since: Long
)

/**
 * 循环响铃。系统通知渠道只会响「一遍」且时长不可控，想响几十秒到几分钟只能自己放。
 *
 * 用 `Ringtone`（而不是 `MediaPlayer`）：
 *  - 它只要一个系统铃声 URI，不用自带音频资源、不用管生命周期和流类型；
 *  - API 28 起有 `isLooping`，一行就是「一直响」；
 *  - `USAGE_ALARM` 走闹钟流，静音模式下照样响（和重要短信渠道同一套语义）。
 *
 * **不做前台服务保活**：最初打算用 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 死守进程，
 * 但长响一共也就几分钟，而闹钟流本身已经能穿过静音、穿过勿扰（用户勾了屏蔽闹钟除外），
 * 剩下的风险只有「厂商把进程冻住导致声音提前停」。为一个几十秒到三分钟的声音常驻一条
 * 前台服务通知、还要在 Android 12+ 的后台启动限制里找空子，代价明显大于收益 ——
 * 真被冻结了，用户看到的是「响得短了点」，而不是「没响」：通知在、胶囊也在。
 *
 * 进程真被回收时胶囊会一起消失，但通知还在通知栏里，也有「停止响铃」按钮，
 * 所以任何一种死法都留着至少一条能让用户按到「停」的路。
 *
 * TODO(锁屏闹钟界面)：屏幕锁着时用 `USE_FULL_SCREEN_INTENT` + 全屏 Activity 盖住锁屏
 * （现在只有 [RingIsland] 的胶囊和通知，锁屏上按停仍要解锁）。
 */
object AlarmRing {

    private const val TAG = "AlarmRing"

    /** 所有回调都在主线程：`Ringtone.stop()` 和 `Handler` 都要求有 Looper */
    private val handler = Handler(Looper.getMainLooper())

    private var ringtone: Ringtone? = null
    private var timeout: Runnable? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    val ringing: Boolean get() = current != null

    /** 正在响的那一条；null = 没在响。重要短信页的响铃卡片靠它渲染 */
    var current: RingSubject? = null
        private set

    /**
     * App 里那张响铃卡片此刻是否真的在屏幕上（主界面 + 重要短信这一屏）。
     *
     * 在，胶囊就让位：卡片本身写着「正在响铃」还带停止键，胶囊再浮上去只会压住页面标题栏。
     * 不在（App 在后台、或者停在其他 Tab），胶囊才是唯一看得见的提醒。
     * 由 [phoenix.smsnotify.MainActivity] 在 `onResume` / `onPause` / 切 Tab 时同步过来。
     */
    private var cardVisible = false

    /** @see cardVisible */
    fun onRingCardVisible(ctx: Context, visible: Boolean) {
        if (cardVisible == visible) return
        cardVisible = visible
        val subject = current ?: return
        if (visible) RingIsland.hide() else RingIsland.show(ctx, subject.title)
    }

    /**
     * 响铃起停的监听。页面在 `attach` 时挂、`detach` 时摘，
     * 用来让那张卡片跟着出现/消失 —— 划掉通知、超时自停、按停止键都会走到这里。
     */
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        if (listener !in listeners) listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 复制一份再遍历：监听器里可能顺手把自己摘掉 */
    private fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    /**
     * 开始响铃。**总是先停掉正在响的那一条** —— 连着来两条重要短信时，
     * 用户听到的应该是后一条的时长，而不是两条铃声叠在一起。
     *
     * @param title 胶囊和 App 卡片上显示的规则名，形如「重要短信 · 银行动账」
     * @param excerpt 摘要，交给 App 的响铃卡片显示
     * @param keywords 命中规则的关键字，卡片上用来标红
     * @return 提醒此刻是否已经在屏幕上可见（胶囊浮出来了，或者 App 里的响铃卡片正显示着）。
     *   调用方拿它决定通知要不要弹横幅：已经看得见的时候横幅是重复的，
     *   而且横幅的窗口层级在悬浮窗之上，会正好把胶囊盖住。
     */
    fun start(
        ctx: Context,
        mode: RingMode,
        title: String,
        excerpt: String,
        keywords: List<String>
    ): Boolean {
        // 换新的一条时不通知「停了」：否则页面会先闪一次卡片消失再出现。
        // 但下面「拿不到铃声」这条提前返回路径并没有再响起来，必须补一次通知 —— 否则页面
        // 会一直停在「正在响铃」的红卡片上，而铃声其实早就停了。
        val hadRing = current != null
        stopInternal(notify = false)

        val app = ctx.applicationContext
        val rt = openRingtone(app) ?: run {
            Log.w(TAG, "没有可用的系统铃声，放弃播放")
            if (hadRing) notifyChanged()
            return false
        }
        rt.isLooping = true
        ringtone = rt
        holdFocus(app)
        rt.play()
        current = RingSubject(title, excerpt, keywords, SystemClock.elapsedRealtime())
        Log.i(TAG, "start mode=${mode.name} ms=${mode.ms}")

        // 提醒落在哪：页面正显示着响铃卡片就不必再浮胶囊，否则胶囊浮上去正好压住页面标题栏。
        // 有悬浮窗权限时（且不在那个页面）才顶上浮出来；都没有就只剩通知栏那条，不影响响铃。
        val visible = cardVisible || RingIsland.show(app, title)

        if (mode.ms > 0) {
            val t = Runnable {
                Log.i(TAG, "timeout after ${mode.ms}ms")
                // 走 stopRinging 而不是 stop：到点自停也要把那条写着「响铃中」的通知带走
                stopRinging(app)
            }
            timeout = t
            handler.postDelayed(t, mode.ms)
        }
        notifyChanged()
        return visible
    }

    /** 停止响铃；没在响时调用是安全的（按钮可能被连点，或者超时和手动停止撞在一起） */
    fun stop() = stopInternal(notify = true)

    private fun stopInternal(notify: Boolean) {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        ringtone?.let {
            if (it.isPlaying) {
                it.stop()
                Log.i(TAG, "stop")
            }
        }
        ringtone = null
        releaseFocus()
        RingIsland.hide()

        val wasRinging = current != null
        current = null
        // 本来就没在响时不必叫醒页面（连点两下停止是常事）
        if (wasRinging && notify) notifyChanged()
    }

    /**
     * 取一个可用的系统铃声：闹钟音 → 铃声 → 通知音。
     *
     * 优先闹钟音，是因为它本身就是「叫醒」语义，而且不随铃声/通知音量变化。
     * 个别设备（尤其精简 ROM、模拟器）没设闹钟音，逐级退下去总比一声不响强。
     */
    private fun openRingtone(ctx: Context): Ringtone? {
        val uris = listOf(
            RingtoneManager.TYPE_ALARM,
            RingtoneManager.TYPE_RINGTONE,
            RingtoneManager.TYPE_NOTIFICATION
        ).mapNotNull { RingtoneManager.getDefaultUri(it) }

        for (uri in uris) {
            val rt = RingtoneManager.getRingtone(ctx, uri) ?: continue
            rt.audioAttributes = alarmAttrs()
            return rt
        }
        return null
    }

    private fun alarmAttrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * 申请音频焦点。除了让别的播放器避让，它还有一份副作用是我们要的：
     * 持有焦点的 App 不会被当成 cached 进程轻易冻结 —— 长响时这一点很关键。
     *
     * 失去焦点时**不暂停**：这是闹钟语义，来电/别的闹钟响起时我们被系统静音是正常的，
     * 用户按掉之后还应该继续响，而不是悄无声息地提前结束。
     */
    private fun holdFocus(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(alarmAttrs())
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(req)
        audioManager = am
        focusRequest = req
    }

    private fun releaseFocus() {
        val am = audioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        audioManager = null
        focusRequest = null
    }
}

/**
 * 「别响了」的唯一入口：停声音 + 收通知。
 *
 * 五处都走这里 —— 通知上的按钮、划掉通知、胶囊上的红点、App 里的「停止响铃」、到点自停。
 * 分开写的话很容易漏掉其中一步（比如停了声音却留着一条写着「响铃中」的通知），
 * 用户会以为停不下来。唯一不经过这里的是「换一条新的响」：那时 [AlarmRing.start]
 * 内部停掉旧的，通知紧接着被新的覆盖。
 */
internal fun stopRinging(ctx: Context) {
    AlarmRing.stop()
    AppNotify.cancel(ctx, ImportantNotify.NOTIF_ID)
}

/**
 * 「停止响铃」：通知上的动作按钮和划掉通知都走这里。
 *
 * 用广播而不是再开一个 Activity —— 用户要的是「别响了」，不该为此跳进 App。
 */
class StopRingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        stopRinging(context)
    }
}
