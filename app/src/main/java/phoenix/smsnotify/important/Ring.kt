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
import android.util.Log
import phoenix.smsnotify.common.AppNotify

/**
 * 响铃时长：每条规则自己选。
 *
 * 为什么不是一个全局开关：「余额到账」和「验证码」的打扰成本完全不同 ——
 * 前者可以像闹钟一样一直响，后者响一遍就够了，不该被迫共用一个值。
 *
 * [ms] 是契约：`0` = 交给系统通知渠道响一遍（不经过 [AlarmRing]）；
 * `> 0` = 由 App 自己循环播放这么久；`-1` = 一直响到用户停。
 * 用毫秒而不是枚举名存盘，以后加「5 分钟」只要加一个枚举项，老数据不用迁移。
 */
enum class RingMode(val ms: Long, val label: String) {
    ONCE(0L, "单次提示"),
    S10(10_000L, "10 秒"),
    S30(30_000L, "30 秒"),
    M1(60_000L, "1 分钟"),
    M3(180_000L, "3 分钟"),
    FOREVER(-1L, "无限");

    /**
     * 卡片角标、列表摘要用的短语。
     *
     * 不能就地拼「响 ${label}」：可数时长读作「响 30 秒」没问题，
     * 无限那条会变成「响 无限」，两个「响」撞在一起读不通。
     *
     * 「手动停止」不再写进选项名 —— 它本来就不是无限模式的特性，
     * 所有非单次的时长都能随时停（见 [AlarmRing.stop]），标在其中一个选项上会让人以为别的不行。
     */
    val badge: String get() = if (this == FOREVER) "无限响铃" else "响 $label"

    companion object {
        /** 存盘值 → 枚举；遇到不认识的毫秒数按「单次」处理，不让一条怪数据把提醒变成哑巴 */
        fun of(ms: Long): RingMode = entries.firstOrNull { it.ms == ms } ?: ONCE
    }
}

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

    val ringing: Boolean get() = ringtone?.isPlaying == true

    /**
     * 开始响铃。**总是先停掉正在响的那一条** —— 连着来两条重要短信时，
     * 用户听到的应该是后一条的时长，而不是两条铃声叠在一起。
     *
     * @param title 胶囊上显示的规则名，形如「重要短信 · 银行动账」
     * @return 胶囊是否已经浮出来。调用方拿它决定通知要不要弹横幅：胶囊在的时候横幅是重复的，
     *   而且横幅的窗口层级在悬浮窗之上，会正好把胶囊盖住。
     */
    fun start(ctx: Context, mode: RingMode, title: String): Boolean {
        stop()
        if (mode.ms == 0L) return false          // 单次提示走系统渠道，这里不放音也不出胶囊

        val app = ctx.applicationContext
        val rt = openRingtone(app) ?: run {
            Log.w(TAG, "没有可用的系统铃声，放弃播放")
            return false
        }
        rt.isLooping = true
        ringtone = rt
        holdFocus(app)
        rt.play()
        Log.i(TAG, "start mode=${mode.name} ms=${mode.ms}")

        // 有悬浮窗权限时顶上浮出胶囊；没有就只剩通知栏那条，不影响响铃
        val island = RingIsland.show(app, title)

        if (mode.ms > 0) {
            val t = Runnable {
                Log.i(TAG, "timeout after ${mode.ms}ms")
                stop()
            }
            timeout = t
            handler.postDelayed(t, mode.ms)
        }
        return island
    }

    /** 停止响铃；没在响时调用是安全的（按钮可能被连点，或者超时和手动停止撞在一起） */
    fun stop() {
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
 * 三处都走这里 —— 通知上的按钮、划掉通知、胶囊上的红点。分开写的话很容易漏掉其中一步
 * （比如停了声音却留着一条写着「响铃中」的通知），用户会以为停不下来。
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
