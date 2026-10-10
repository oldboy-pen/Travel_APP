package com.example.myfirstapp.offline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 网络可用性判定。
 *
 * ★ 注意"有网络连接"≠"能访问互联网"：连上一个需要登录的 WiFi、或者一个没有出口的
 *   局域网，[isNetworkAvailable] 会返回 true 但瓦片一张都下不到。所以离线场景的判定
 *   一律以「瓦片实际抓不到」为准（下载器里连续失败计数），这里的功能只用于
 *   **启动时的初始决策**（要不要立刻开 GPS、要不要进入离线渲染），不用于精确判断。
 */
object OfflineNet {

    /**
     * 当前是否连着任何网络（不保证能通公网）。
     * 判定失败（权限/服务异常）时返回 **true**：宁可先尝试联网，也不要误判成离线
     * 把在线功能关掉 —— 误判离线的代价（完全不出图）远大于误判在线（浪费一点流量）。
     */
    fun isNetworkAvailable(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_NETWORK_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) return true
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val nw = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(nw) ?: return true
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                info?.isConnected ?: false
            }
        }.getOrDefault(true)
    }

    /** 是否是按流量计费的网络（蜂窝）：用于提示"离线下载会消耗流量" */
    fun isMetered(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cm.isActiveNetworkMetered
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.type.let { it == ConnectivityManager.TYPE_MOBILE }
            }
        }.getOrDefault(false)
    }
}
