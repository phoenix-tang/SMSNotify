package phoenix.smsnotify.important

import android.app.NotificationManager
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
    FOREVER(-1L, "持续响铃，手动停止");

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
 * 进程被冻结时声音会停 —— 这是这个方案最大的短板：`BroadcastReceiver.onReceive`
 * 返回后进程就掉回 cached，厂商的冻结机制可能连 AudioTrack 一起冻住。
 * 真正的解法是用前台服务持有播放（`FOREGROUND_SERVICE_MEDIA_PLAYBACK`），
 * 但 Android 12 起后台启动前台服务受限，需要先确认短信广播是否在临时白名单里。
 *
 * TODO(响铃保活)：改用前台服务播放，渠道类型 mediaPlayback，通知里带「停止」按钮。
 * TODO(闹钟界面)：持续响铃时全屏亮屏 + 锁屏上直接给「停止」，现在只有通知上的按钮和打开 App。
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
     */
    fun start(ctx: Context, mode: RingMode) {
        stop()
        if (mode.ms == 0L) return          // 单次提示走系统渠道，这里不放音

        val app = ctx.applicationContext
        val rt = openRingtone(app) ?: run {
            Log.w(TAG, "没有可用的系统铃声，放弃播放")
            return
        }
        rt.isLooping = true
        ringtone = rt
        holdFocus(app)
        rt.play()
        Log.i(TAG, "start mode=${mode.name} ms=${mode.ms}")

        if (mode.ms > 0) {
            val t = Runnable {
                Log.i(TAG, "timeout after ${mode.ms}ms")
                stop()
            }
            timeout = t
            handler.postDelayed(t, mode.ms)
        }
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
 * 「停止响铃」：通知上的动作按钮和划掉通知都走这里。
 *
 * 用广播而不是再开一个 Activity —— 用户要的是「别响了」，不该为此跳进 App。
 * 停掉声音的同时把通知也收掉：这条提醒的目的（引起注意）已经达成，留着只会占通知栏。
 */
class StopRingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AlarmRing.stop()
        context.getSystemService(NotificationManager::class.java)
            .cancel(ImportantNotify.NOTIF_ID)
    }
}
