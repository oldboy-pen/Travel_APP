package com.example.myfirstapp.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 离线瓦片下载的前台服务。
 *
 * ★ 为什么要前台服务：一个中等城市的 z10-15 有上万张瓦片，几分钟到十几分钟不等，
 *   用户一定会切走甚至锁屏。放在 Activity/ViewModel 的协程里，进程一退到后台就被杀，
 *   下到一半的区域就成了永远补不完的半张图。前台服务 + 常驻通知是唯一稳的做法
 *   （与轨迹记录用的是同一套思路）。
 *
 * ★ 串行队列：同时下多个区域会互相抢带宽，也容易把瓦片服务器惹毛（限流/封 IP），
 *   所以这里排队一个个来，UI 上后面的区域显示"排队中"。
 */
class OfflineDownloadService : Service() {

    private val queue = ConcurrentLinkedQueue<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 服务可能比任何界面先起来（比如下载被系统重启后恢复），存储目录要先建好
        OfflineStorage.init(this)
        OfflineRegionStore.ensureLoaded(this)
        createChannel()
        // 常驻通知：先给一个占位，真正的进度在下载开始后更新
        startForeground(NOTIFICATION_ID, notification("离线地图", "准备下载…", 0, 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val regionId = intent?.getStringExtra(EXTRA_REGION_ID)
        if (!regionId.isNullOrBlank()) {
            queue.add(regionId)
            OfflineRegionStore.byId(regionId)?.let {
                if (it.status != OfflineRegionStatus.DOWNLOADING) {
                    OfflineRegionStore.updateStatus(regionId, OfflineRegionStatus.QUEUED)
                }
            }
            pump()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        OfflineDownloader.cancelAll()
        super.onDestroy()
    }

    /** 拿队列头出来跑；跑完继续下一个 */
    private fun pump() {
        if (OfflineDownloader.activeRegionId != null) return   // 有任务在跑，等它结束回调
        val next = queue.poll() ?: run {
            // 队列空了：所有下载都结束，撤掉前台状态并停服务
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
            return
        }
        val region = OfflineRegionStore.byId(next) ?: run { pump(); return }
        updateNotification(region.name, 0, 1)
        OfflineDownloader.start(
            regionId = next,
            onProgress = { p ->
                // 通知最多 1 秒刷一次：瓦片下载每秒能完成几十张，频繁 notify 会拖慢前台
                val now = System.currentTimeMillis()
                if (now - lastNotifyAt > 1000 || p.done >= p.total) {
                    lastNotifyAt = now
                    updateNotification(region.name, p.done, p.total)
                }
            },
            onFinish = { status, err ->
                val msg = when (status) {
                    OfflineRegionStatus.READY -> "下载完成"
                    OfflineRegionStatus.PAUSED -> "已暂停，可继续"
                    OfflineRegionStatus.FAILED -> err ?: "下载失败"
                    else -> "已结束"
                }
                updateNotification(region.name + " · " + msg, 1, 1)
                mainHandler.post { pump() }
            }
        )
    }

    private fun updateNotification(title: String, done: Int, total: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val percent = if (total <= 0) 0 else (done * 100 / total).coerceIn(0, 100)
        runCatching { nm.notify(NOTIFICATION_ID, notification("离线地图下载", title, percent, total)) }
    }

    private fun notification(title: String, text: String, percent: Int, total: Int): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (total > 0) builder.setProgress(100, percent, false)
        else builder.setProgress(100, 0, true)
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "离线地图下载", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "下载离线地图瓦片时的进度通知" }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "offline_download"
        private const val NOTIFICATION_ID = 9201
        private const val EXTRA_REGION_ID = "regionId"

        /** 入队一个区域的下载（不需要立即拿到结果，UI 观察 [OfflineRegionStore]） */
        fun start(context: Context, regionId: String) {
            val i = Intent(context, OfflineDownloadService::class.java)
                .putExtra(EXTRA_REGION_ID, regionId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
