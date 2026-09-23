package com.example.myfirstapp.track

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.example.myfirstapp.R

/**
 * 轨迹记录前台服务（类似两步路"运动中"的常驻通知）：
 * - 让系统在息屏后不冻结记录进程（foregroundServiceType=location）
 * - 通知栏实时显示 距离/时长，点击回到 App
 *
 * 职责划分：TrackRecorder 管数据，本类只管"保活 + 通知"。
 */
class TrackRecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTI_ID, buildNotification("正在记录轨迹"))

        // 订阅记录器状态 → 更新通知
        scope.launch {
            TrackRecorder.data.collect { d ->
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
    }
}
