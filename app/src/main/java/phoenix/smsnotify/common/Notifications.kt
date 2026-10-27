package phoenix.smsnotify.common

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import phoenix.smsnotify.MainActivity

/**
 * 系统通知的公共部分：**建渠道、点通知进 App、发通知、撤通知**。
 *
 * 快递和重要短信两个模块各自的通知语义完全不同（一个是常驻的待取件状态，一个是
 * 一次性响铃事件），但底层这四步一模一样，之前是两边各抄一份：
 *  - `createNotificationChannel` 的模板代码；
 *  - `PendingIntent.getActivity` 连 flags 都写全了；
 *  - `notify()` 外面套 `try/catch (SecurityException)`（没给通知权限时不能崩）；
 *  - `IMPORTANCE_*` 常量、渠道描述这些。
 *
 * 所以这里只抽「机制」，不抽「策略」：渠道长什么样由调用方用 [ChannelSpec] 声明，
 * 通知内容由调用方在 [post] 的 lambda 里拼。两个模块的风格差异（重要性、声音、振动、
 * 是常驻还是一次性）仍然留在各自文件里，看得见。
 */
object AppNotify {

    /**
     * 一条通知渠道的声明。
     *
     * 注意渠道参数**只在第一次创建时固化**（之后同 id 再建只更新名称/描述），
     * 开发阶段改了参数卸载重装即可。
     */
    class ChannelSpec(
        /** 渠道 id，只在 App 内唯一 */
        val id: String,
        /** 系统设置里显示的名字 */
        val name: String,
        /** 点击后展开的说明，用户判断"要不要关掉它"全靠这句话 */
        val description: String,
        val importance: Int,
        /**
         * 提示音走哪个音频流。null = 不指定，用系统默认通知音（走 STREAM_NOTIFICATION）。
         * 传 `AudioAttributes.USAGE_ALARM` 则走闹钟流，静音模式下也能出声。
         */
        val soundUsage: Int? = null,
        /**
         * 渠道完全不出声。声音由 App 自己放时（长响：要循环、要按秒数停）必须打开，
         * 否则系统那一声短提示会和循环铃声叠在一起，听起来像"卡了两下"。
         */
        val silent: Boolean = false,
        /** 振动节奏（毫秒，`[0, 400, 200, 400]` 这种），null = 不振动 */
        val vibration: LongArray? = null,
        val lights: Boolean = false,
        val visibility: Int = Notification.VISIBILITY_PRIVATE
    )

    /** 创建或更新渠道。重复调用是安全的，内部不判断是否存在 */
    fun ensureChannel(ctx: Context, spec: ChannelSpec) {
        val ch = NotificationChannel(spec.id, spec.name, spec.importance).apply {
            description = spec.description
            if (spec.silent) {
                setSound(null, null)
            } else if (spec.soundUsage != null) {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(spec.soundUsage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            enableVibration(spec.vibration != null)
            spec.vibration?.let { vibrationPattern = it }
            enableLights(spec.lights)
            lockscreenVisibility = spec.visibility
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /**
     * "点通知打开 App 的某一屏"的 PendingIntent。
     *
     * flags 是关键：SINGLE_TOP + CLEAR_TOP 让已有的 MainActivity 走 `onNewIntent`
     * 切 Tab，而不是再压一个新 Activity 到栈上。
     */
    fun openApp(ctx: Context, tab: Int = MainActivity.TAB_DELIVERY): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_TAB, tab)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 发一条通知。内容由 [fill] 填，返回是否真的发出去了。
     *
     * 覆盖两种常见失败：用户关了通知权限（抛 SecurityException）、渠道被用户删除后
     * 往旧 id 发（系统静默丢弃）。前者吞掉异常，后者无法感知 —— 所以渠道 id 只能一处定义。
     */
    fun post(
        ctx: Context,
        channelId: String,
        notifId: Int,
        fill: (Notification.Builder) -> Unit
    ): Boolean {
        val builder = Notification.Builder(ctx, channelId)
        fill(builder)
        return try {
            ctx.getSystemService(NotificationManager::class.java).notify(notifId, builder.build())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** 撤掉通知（例如待取件数量归零时） */
    fun cancel(ctx: Context, notifId: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(notifId)
    }
}
