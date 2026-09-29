package com.example.myfirstapp.track

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.myfirstapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 轨迹导航前台服务：让导航在息屏/退到后台后依然活着。
 *
 * 与 [TrackRecordingService] 同构，职责划分一致：
 * [TrackNavigator] 管定位与算法，本类只管"保活 + 常驻通知"。
 * 通知栏实时显示剩余里程与偏离状态，导航结束（到达终点/用户结束）自动退场。
 */
class TrackNavigationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        runCatching { startForeground(NOTI_ID, buildNotification("正在启动导航")) }
            .onFailure { stopSelf(); return }

        scope.launch {
            VoiceAnnouncer.ensureInit(this@TrackNavigationService)
            TrackNavigator.state.collect { s ->
                // 息屏后 CPU 深睡会让定位回调间隔拉长 → 偏离预警变迟钝，锁一手
                if (s.status == NavigationStatus.NAVIGATING) acquireWakeLock() else releaseWakeLock()

                val text = when (s.status) {
                    NavigationStatus.NAVIGATING ->
                        "剩余 ${GeoUtils.formatDistance(s.remainingMeters)}" +
                                if (s.severeOffTrack) " · 严重偏离 ${s.deviationMeters.toInt()} 米"
                                else if (s.offTrack) " · 已偏离 ${s.deviationMeters.toInt()} 米"
                                else " · 偏离 ${s.deviationMeters.toInt()} 米"
                    NavigationStatus.ARRIVED -> "已到达终点"
                    NavigationStatus.IDLE -> "导航已结束"
                }
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTI_ID, buildNotification(text))

                // 到达终点 / 手动结束 → 服务退场（通知随之消失）
                if (s.status != NavigationStatus.NAVIGATING) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null 表示被系统杀后重建：此时 TrackNavigator 单例状态已丢（IDLE），
        // 残留一个"僵尸通知"没有意义，直接退场。
        if (intent == null && !TrackNavigator.isNavigating) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 只锁 CPU 不点亮屏幕，导航类 App 的标准做法 */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "myfirstapp:trackNavigation"
        ).apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("轨迹导航中")
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
                CHANNEL_ID, "轨迹导航", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "沿轨迹导航进行中的常驻通知" }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "track_navigation"
        private const val NOTI_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, TrackNavigationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackNavigationService::class.java))
        }
    }
}
