package com.example.myfirstapp

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import android.util.Log
import android.widget.Toast
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.RecorderState
import com.example.myfirstapp.track.TrackRecorder
import com.example.myfirstapp.track.TrackRecordingService
import com.example.myfirstapp.offline.OfflineRegionStore
import com.example.myfirstapp.offline.OfflineStorage
import com.example.myfirstapp.ui.navigation.AppRoot
import com.example.myfirstapp.ui.theme.MyFirstAppTheme
import com.example.myfirstapp.utils.MapSdkPrivacy

/**
 * App 入口 Activity
 *
 * 启动流程：隐私弹窗（首次）→ 同意后初始化高德/腾讯/百度三家SDK → 进入主界面
 * （三家都要求：必须先获用户同意，SDK 才能初始化）
 */
/** 恢复链路与记录器共用一个 tag，adb logcat 过滤这一个就能看到完整链路 */
private const val TAG = "TrackRecorder"

class MainActivity : ComponentActivity() {

    /**
     * 恢复出来的记录需要补拉前台服务，但**不能**在 onCreate 里拉：
     * 进程刚 fork 时 importance 还没升到前台，华为/部分 ROM 会按"后台启动"直接拒绝
     * startForegroundService（实测：onCreate 里拉起静默失败，通知一次都没出现）。
     * 因此推迟到 onResume —— 此时 Activity 已可见，进程是前台优先级。
     */
    private var pendingServiceStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 离线存储目录 + 区域清单：下载服务可能在任何界面之前被拉起，
        // 这里提前初始化，保证 OfflineStorage 拿得到 filesDir
        OfflineStorage.init(this)
        OfflineRegionStore.ensureLoaded(this)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val agreed = prefs.getBoolean(KEY_PRIVACY, false)
        if (agreed) {
            MapSdkPrivacy.init(this)  // 之前已同意过，直接初始化三家 SDK
            pendingServiceStart = restorePendingRecording(this)
        }

        setContent {
            MyFirstAppTheme {
                var isAgreed by remember { mutableStateOf(agreed) }
                if (isAgreed) {
                    AppRoot()
                } else {
                    PrivacyDialog(onAgree = {
                        prefs.edit().putBoolean(KEY_PRIVACY, true).apply()
                        MapSdkPrivacy.init(this)
                        pendingServiceStart = restorePendingRecording(this)
                        isAgreed = true
                    })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!pendingServiceStart) return
        pendingServiceStart = false
        runCatching { TrackRecordingService.start(this) }
            .onFailure { Log.w(TAG, "onResume: 补拉前台服务失败", it) }
    }

    override fun onPause() {
        // 退后台（按 Home / 被别的页面盖住）时立刻补一次落盘，缩短被杀时的丢失窗口
        runCatching { TrackRecorder.flush() }
        super.onPause()
    }

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_PRIVACY = "privacy_agreed"
    }
}

/**
 * 冷启动恢复：上一次记录若被强制杀进程打断（划卡 / 崩溃 / 强制停止 / 系统回收），
 * 这里把退出那一刻的状态原样接回来（记录中 → 继续记录；已暂停 → 停在暂停态）。
 *
 * 必须在 SDK 合规初始化之后调用，否则高德定位拿不到结果。
 *
 * 注意：这里刻意传 startService = false —— 前台服务由 MainActivity.onResume 补拉，
 * 在 onCreate 里拉会被 ROM 当后台启动拒掉（见 [MainActivity.pendingServiceStart]）。
 *
 * @return 是否真的恢复了记录（调用方据此决定是否补拉前台服务）
 */
private fun restorePendingRecording(context: Context): Boolean {
    val info = runCatching { TrackRecorder.restore(context, startService = false) }
        .onFailure { Log.e(TAG, "恢复上次记录失败", it) }
        .getOrNull() ?: return false
    val msg = buildString {
        append("已恢复上次未结束的记录")
        if (info.pointCount > 0) {
            append("：${GeoUtils.formatDistance(info.distanceMeters)} · ${info.pointCount} 个点")
        } else {
            append("（当时还没记录到轨迹点）")
        }
        if (info.creditedMillis > 0) {
            append(" · 中断 ${GeoUtils.formatDuration(info.creditedMillis)}已计入时长")
        }
        when {
            info.needsPermission -> append(" · 定位权限已失效，已停在暂停态")
            info.state == RecorderState.PAUSED -> append(" · 当时已暂停，点继续可接着记")
        }
    }
    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    return true
}

/** 首次启动的隐私政策弹窗（合规必需，拒绝则退出） */
@Composable
private fun PrivacyDialog(onAgree: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.privacy_title)) },
        text = {
            Text(stringResource(R.string.privacy_body))
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("同意") } },
        dismissButton = {
            TextButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                Text("退出")
            }
        }
    )
}
