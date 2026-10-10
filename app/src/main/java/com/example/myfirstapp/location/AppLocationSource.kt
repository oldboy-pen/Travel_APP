package com.example.myfirstapp.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.example.myfirstapp.offline.OfflineNet

/**
 * 一个定位结果（已统一成 WGS-84 之外的业务口径：高德/腾讯同款的 GCJ-02）。
 *
 * @param provider 实际来源：`amap`（高德混合定位）或 `gps`（系统卫星定位），
 *                 UI 上显示"离线 GPS"还是"网络定位"就靠它
 */
data class AppLocation(
    val latitude: Double,
    val longitude: Double,
    /** 水平精度（米），<=0 表示未知 */
    val accuracy: Float,
    val bearing: Float,
    /** 海拔（米）。GPS 给的是相对 WGS-84 椭球高，误差通常 ±10 m，仅供参考 */
    val altitude: Double,
    val speed: Float,
    val provider: String,
    /** 定位时间戳（毫秒）。GPS 给的是卫星授时，比系统时间更准，轨迹记录直接用它 */
    val time: Long = System.currentTimeMillis(),
    val address: String? = null
) {
    companion object {
        const val PROVIDER_AMAP = "amap"
        const val PROVIDER_GPS = "gps"
    }
}

/**
 * 统一位置源：**高德混合定位为主，系统 GPS 为无网兜底**。
 *
 * ### 为什么必须加 GPS 兜底
 * 高德定位是"WiFi + 基站 + 卫星"的混合定位，前两者**必须联网**（要把扫描到的 WiFi/基站
 * 发给服务端查库）。在山区、无人区这些真正需要离线地图的场景里，网络通常没有，
 * 高德此时要么一直不回调，要么返回错误码 —— 表现就是"地图出来了，但我在哪不知道"。
 * 而系统 GPS 是纯卫星定位，完全不依赖网络，只要有天空视野就能出坐标。
 *
 * ### 切换策略
 * 1. 启动时同时判定：网络不可用 → **立刻**启动 GPS（不等超时）；
 * 2. 有网时先只用高德（它更快、室内也能定位），但若 [WATCHDOG_MS] 内一个点都没给出来，
 *    补开 GPS（弱网下高德可能一直卡在鉴权/查询）；
 * 3. 上报择优：GPS 一旦有有效点就以 GPS 为准（野外场景下卫星比基站准得多）；
 *    GPS 长时间没更新（[GPS_STALE_MS]）时才退回高德。
 *
 * 这样有网时行为与原来完全一致（不会对现有功能产生回归），只在"高德给不出点"时兜底。
 *
 * @param needAddress 是否要高德返回文字地址（更费流量，只在地图页展示用）
 */
class AppLocationSource(
    private val context: Context,
    private val intervalMs: Long = 2000,
    private val once: Boolean = false,
    private val needAddress: Boolean = false,
    private val onLocation: (AppLocation) -> Unit,
    private val onError: ((String) -> Unit)? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var amapClient: AMapLocationClient? = null
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @Volatile
    private var gpsRegistered = false

    @Volatile
    private var lastGpsAt = 0L

    @Volatile
    private var lastAmapAt = 0L

    @Volatile
    private var running = false

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!running) return
            lastGpsAt = System.currentTimeMillis()
            onLocation.invoke(
                AppLocation(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = if (location.hasAccuracy()) location.accuracy else -1f,
                    bearing = if (location.hasBearing()) location.bearing else 0f,
                    altitude = if (location.hasAltitude()) location.altitude else Double.NaN,
                    speed = if (location.hasSpeed()) location.speed else 0f,
                    provider = AppLocation.PROVIDER_GPS,
                    time = if (location.time > 0) location.time else System.currentTimeMillis()
                )
            )
        }

        @Deprecated("旧版回调，minSdk 24 上仍会被调用")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun start() {
        if (running) return
        running = true
        startAmap()
        // 网络不可用 → 不再等高德，直接开 GPS（这是离线的主路径）
        if (!OfflineNet.isNetworkAvailable(context)) {
            startGps()
        } else {
            // 有网：给高德一次机会，超时没出点再补开 GPS
            mainHandler.postDelayed({ if (running && lastAmapAt == 0L) startGps() }, WATCHDOG_MS)
        }
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            amapClient?.stopLocation()
            amapClient?.onDestroy()
        }
        amapClient = null
        stopGps()
    }

    // ==================== 高德 ====================

    private fun startAmap() {
        if (!hasLocationPermission()) {
            onError?.invoke("无定位权限")
            return
        }
        runCatching {
            amapClient = AMapLocationClient(context).apply {
                setLocationOption(AMapLocationClientOption().apply {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isOnceLocation = once
                    interval = intervalMs
                    isNeedAddress = needAddress
                })
                setLocationListener { loc ->
                    if (!running) return@setLocationListener
                    if (loc.errorCode != 0) {
                        // 高德失败（无网时多半是这一步）：转 GPS
                        startGps()
                        onError?.invoke(loc.errorInfo ?: "定位失败(${loc.errorCode})")
                        return@setLocationListener
                    }
                    lastAmapAt = System.currentTimeMillis()
                    // GPS 还在有效更新 → 以 GPS 为准，不覆盖
                    if (System.currentTimeMillis() - lastGpsAt < GPS_STALE_MS) return@setLocationListener
                    onLocation.invoke(
                        AppLocation(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            accuracy = loc.accuracy,
                            bearing = loc.bearing,
                            altitude = if (loc.altitude.isNaN()) Double.NaN else loc.altitude,
                            speed = loc.speed,
                            provider = AppLocation.PROVIDER_AMAP,
                            time = if (loc.time > 0) loc.time else System.currentTimeMillis(),
                            address = if (needAddress) {
                                loc.poiName?.ifEmpty { null } ?: loc.address
                            } else null
                        )
                    )
                }
                startLocation()
            }
        }.onFailure {
            startGps()
            onError?.invoke("高德定位启动失败：${it.message}")
        }
    }

    // ==================== 系统 GPS ====================

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsRegistered || !running) return
        if (!hasLocationPermission()) return
        val lm = locationManager ?: return
        val enabled = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)
        if (!enabled) {
            onError?.invoke("系统 GPS 未开启")
            return
        }
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs.coerceAtLeast(1000L),
                0f,
                gpsListener,
                Looper.getMainLooper()
            )
            gpsRegistered = true
            // 冷启动时有上一次的 GPS 位置，先抛出来让 UI 立刻有东西显示
            runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
                .getOrNull()?.let { last ->
                    if (last.time > System.currentTimeMillis() - 10 * 60 * 1000) {
                        gpsListener.onLocationChanged(last)
                    }
                }
        }.onFailure {
            onError?.invoke("GPS 定位失败：${it.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopGps() {
        if (!gpsRegistered) return
        runCatching { locationManager?.removeUpdates(gpsListener) }
        gpsRegistered = false
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** 有网时给高德的宽限时间，超时没出点就补开 GPS */
        private const val WATCHDOG_MS = 6000L

        /** GPS 多久没更新就算过期（过期后才让高德的点上报） */
        private const val GPS_STALE_MS = 8000L
    }
}
