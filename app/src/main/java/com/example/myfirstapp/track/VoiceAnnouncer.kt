package com.example.myfirstapp.track

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 运动语音播报（系统 TTS 封装，单例）：
 * - 每行进 1 公里播报一次：平均速度、累计爬升、耗时
 * - 无需任何权限；息屏也能播（音频走媒体声道）
 * - 开关持久化到 SharedPreferences，记录页地图上有开关按钮
 *
 * 职责划分：本类只管"说"，播报时机由 TrackRecordingService 订阅
 * TrackRecorder.data 时判定（服务活着 = 息屏也能触发）。
 */
object VoiceAnnouncer {

    private const val PREFS = "voice_announce"
    private const val KEY_ENABLED = "enabled"

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true) // 默认开

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 初始化 TTS 引擎（幂等）。init 回调是异步的，因此要在"第一次播报之前"
     * 尽早调用（服务 onCreate 时就调），等到 1 公里时引擎早已就绪。
     */
    @Synchronized
    fun ensureInit(context: Context) {
        if (tts != null) return
        runCatching {
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // 中文不可用时（部分海外 ROM 无中文语音包）保持 ready=false，
                    // 后续 speak 全部静默跳过，不崩不吵
                    val lang = engine?.setLanguage(Locale.CHINA)
                    ready = lang != TextToSpeech.LANG_MISSING_DATA &&
                            lang != TextToSpeech.LANG_NOT_SUPPORTED
                }
            }
            tts = engine
        }
    }

    /** 播报一段文本（开关关闭 / 引擎未就绪时静默跳过） */
    fun announce(context: Context, text: String) {
        if (!isEnabled(context)) return
        ensureInit(context)
        val engine = tts ?: return
        if (!ready) return
        runCatching {
            engine.speak(
                text, TextToSpeech.QUEUE_ADD, null,
                "km_${System.currentTimeMillis()}"
            )
        }
    }

    /** 释放引擎（服务销毁时调用；下次 ensureInit 会重新创建） */
    @Synchronized
    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    // ==================== 文案 ====================

    /** 整公里播报文案：距离 + 均速 + 累计爬升 + 耗时 */
    fun buildKmMessage(d: RecordingData, km: Int): String {
        val avgKmh = if (d.durationMillis > 0)
            d.distanceMeters / (d.durationMillis / 1000.0) * 3.6 else 0.0
        return "已行进 ${km} 公里，" +
                "平均速度 ${speakDecimal(avgKmh)} 公里每小时，" +
                "累计爬升 ${d.climbMeters.toInt()} 米，" +
                "用时 ${speakDuration(d.durationMillis)}"
    }

    /** 小数转中文读法："5.2" → "5点2"（各 TTS 引擎对阿拉伯小数读法不一） */
    private fun speakDecimal(v: Double): String =
        "%.1f".format(v).replace(".", "点")

    /** 耗时转中文读法："1小时23分钟" / "45分钟"（不足 1 分钟按 1 分钟报） */
    private fun speakDuration(ms: Long): String {
        val totalMin = (ms / 60_000L).coerceAtLeast(1)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}小时${m}分钟" else "${m}分钟"
    }
}
