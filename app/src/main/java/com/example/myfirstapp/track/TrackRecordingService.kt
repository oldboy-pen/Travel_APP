package com.example.myfirstapp.track

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.example.myfirstapp.R
import com.example.myfirstapp.utils.MapSdkPrivacy

/**
 * 轨迹记录前台服务（类似两步路"运动中"的常驻通知）：
 * - 让系统在息屏后不冻结记录进程（foregroundServiceType=location）
 * - 通知栏实时显示 距离/时长，点击回到 App
 * - 记录期间持有 PARTIAL_WAKE_LOCK：息屏后 CPU 不深睡，GPS/计步持续回调
 * - 最近任务被划掉后通过 AlarmManager 拉回自己（部分国产 ROM 划卡即杀进程）
 *
 * 职责划分：TrackRecorder 管数据，本类只管"保活 + 通知"。
 */
class TrackRecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Android 12+ 若被后台 FGS 启动限制拦下，startForeground 会抛
        // ForegroundServiceStartNotAllowedException——拉不回就优雅退出，不崩溃
        runCatching { startForeground(NOTI_ID, buildNotification("正在记录轨迹")) }
            .onFailure { stopSelf(); return }

        // 进程被杀后服务被系统/闹钟重建：本进程是全新的，TrackRecorder 单例已丢，
        // 先把落盘的快照接回来（记录点/距离/时长/暂停态一并复活）。
        // 高德合规初始化必须补——后台拉起时不经过 MainActivity，隐私未同意则定位一律失败。
        if (!TrackRecorder.isRecording) {
            MapSdkPrivacy.init(this)
            TrackRecorder.restore(this, startService = false)
        }
        // 恢复失败（无快照）时保持原行为：collect 收到 IDLE 会自行 stopSelf

        // 订阅记录器状态 → 更新通知 + 持有/释放 WakeLock + 整公里语音播报
        scope.launch {
            VoiceAnnouncer.ensureInit(this@TrackRecordingService) // 提前初始化，等 1km 时引擎已就绪
            var lastAnnouncedKm = 0
            TrackRecorder.data.collect { d ->
                if (d.state == RecorderState.RECORDING) acquireWakeLock() else releaseWakeLock()

                // ---- 语音播报：距离每跨过一个整公里报一次（暂停/结束不补报）----
                when (d.state) {
                    RecorderState.IDLE -> lastAnnouncedKm = 0 // 新一次记录重新计数
                    RecorderState.RECORDING -> {
                        val km = (d.distanceMeters / 1000).toInt()
                        if (km > lastAnnouncedKm) {
                            lastAnnouncedKm = km
                            VoiceAnnouncer.announce(
                                this@TrackRecordingService,
                                VoiceAnnouncer.buildKmMessage(d, km)
                            )
                        }
                    }
                    RecorderState.PAUSED -> Unit
                }

                val text = when (d.state) {
                    RecorderState.RECORDING ->
                        "${GeoUtils.formatDistance(d.distanceMeters)} · " +
                                "${GeoUtils.formatDuration(d.durationMillis)} · " +
                                "均速 %.1f km/h".format(
                                    if (d.durationMillis > 0)
                                        d.distanceMeters / (d.durationMillis / 1000.0) * 3.6 else 0.0
                                )
                    RecorderState.PAUSED -> "已暂停 · ${GeoUtils.formatDistance(d.distanceMeters)}"
                    RecorderState.IDLE -> "记录结束"
                }
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTI_ID, buildNotification(text))
                if (d.state == RecorderState.IDLE) stopSelf() // 记录结束 → 服务退场
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null：服务被系统杀死后重建（START_STICKY）。此时进程往往是新的，
        // TrackRecorder 单例状态已丢，先尝试从磁盘快照接回；接不回再退场，
        // 避免留下"僵尸通知"（通知在、记录却没了）。
        if (!TrackRecorder.isRecording) {
            MapSdkPrivacy.init(this)
            TrackRecorder.restore(this, startService = false)
        }
        if (!TrackRecorder.isRecording) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * 用户从最近任务划掉 App：原生 Android 不会杀正在运行的前台服务，但部分
     * 国产 ROM（MIUI/EMUI 等）会连进程一起杀。安排一个 1.5 秒后的闹钟拉回
     * 本服务——拉得回就续命，拉不回（后台启动限制）也已是尽力而为。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (TrackRecorder.isRecording) {
            TrackRecorder.flush()   // 划卡后进程随时可能被杀，先把最新状态落盘
            scheduleRestart()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        cancelRestart()
        VoiceAnnouncer.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 后台保活 ----

    /**
     * 息屏后 CPU 会进入深睡（Doze），哪怕前台服务也可能被限流。
     * PARTIAL_WAKE_LOCK 只锁 CPU 不点亮屏幕，是轨迹类 App 的标准做法。
     * setReferenceCounted(false)：onDestroy 统一释放，防止重复 acquire 计数泄漏。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "myfirstapp:trackRecording"
        ).apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    /** 划卡后的"复活闹钟"：用非精确版避开 Android 12+ 的 SCHEDULE_EXACT_ALARM 限制 */
    private fun scheduleRestart() {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val pi = restartPendingIntent() ?: return
        runCatching {
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1500,
                pi
            )
        }
    }

    private fun cancelRestart() {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        restartPendingIntent()?.let { runCatching { am.cancel(it) } }
    }

    private fun restartPendingIntent(): PendingIntent? {
        val intent = Intent(this, TrackRecordingService::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            PendingIntent.getForegroundService(this, RESTART_REQ_CODE, intent, flags)
        else
            PendingIntent.getService(this, RESTART_REQ_CODE, intent, flags)
    }

    // ---- 通知 ----

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)  // 简单矢量图标
            .setContentTitle("轨迹记录中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    packageManager.getLaunchIntentForPackage(packageName),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "轨迹记录", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "户外轨迹记录进行中的常驻通知" }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "track_recording"
        private const val NOTI_ID = 1001
        private const val RESTART_REQ_CODE = 1002

        /** 从 UI 启动本服务 */
        fun start(context: Context) {
            val intent = Intent(context, TrackRecordingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackRecordingService::class.java))
        }

        /** 是否已在系统"电池优化"白名单中（不受 Doze 限流，国产 ROM 后台杀的主要开关） */
        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val pm = context.getSystemService(POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }

        /**
         * 拉起系统弹窗，请求把本 App 加入电池优化白名单（"不受限制"）。
         * 需要同时在 manifest 声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限。
         */
        fun requestIgnoreBatteryOptimizations(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                )
            }
        }
    }
}
