package com.example.myfirstapp.utils

import android.content.Context
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer
import com.example.myfirstapp.map.MapEngineKeys
import com.tencent.tencentmap.mapsdk.maps.TencentMapInitializer

/**
 * 三家地图 SDK 的统一合规初始化。
 *
 * ★ 合规顺序不能变：必须先弹隐私弹窗拿到用户同意，再调用各家的 agree/setAgreePrivacy，
 *   最后才是 initialize / 创建 MapView。任何一家少调一步，地图都会白屏或打「未鉴权」水印。
 *
 * 各家的要求：
 * - 高德：MapsInitializer / AMapLocationClient / ServiceSettings 三处都要 updatePrivacyAgree
 * - 腾讯：TencentMapInitializer.setAgreePrivacy(context, true)，之后不需要额外 initialize
 * - 百度：SDKInitializer.setAgreePrivacy(context, true) → setApiKey → setCoordType → initialize
 *
 * 调用时机：MainActivity 里用户点了隐私弹窗的"同意"之后（或已同意过的二次进入），
 * 内部已串行完成三家初始化，任意一家失败都不影响主流程（都用 runCatching 兜住）。
 */
object MapSdkPrivacy {

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            MapEngineKeys.init(appCtx)

            // ---- 高德（地图 + 定位 + 搜索）----
            AMapPrivacy.init(appCtx)

            // ---- 腾讯：同意隐私即可，MapView 构造时会自行完成鉴权 ----
            runCatching { TencentMapInitializer.setAgreePrivacy(appCtx, true) }

            // ---- 百度：必须 initialize 过才能 new MapView，否则直接崩溃 ----
            // setCoordType(GCJ02) 让整个百度 SDK 的输入输出坐标系统一为 GCJ-02，
            // 与我们 App 内部（高德/Tencent 同为 GCJ-02）的轨迹点、标记点对齐，
            // 无需在业务层做 GCJ↔BD09 换算。若某些机型（或部分 SDK 版本）出现偏移，
            // 把这里改成 CoordType.BD09LL 并开启 BaiduMapEngine.convertInputCrs 即可。
            runCatching {
                if (!SDKInitializer.isInitialized()) {
                    SDKInitializer.setAgreePrivacy(appCtx, true)
                    com.example.myfirstapp.map.MapEngineKeys.baiduKey
                        .takeIf { it.isNotBlank() }
                        ?.let { SDKInitializer.setApiKey(it) }
                    SDKInitializer.setCoordType(CoordType.GCJ02)
                    SDKInitializer.initialize(appCtx)
                }
            }

            // ---- osmdroid：WGS84 引擎（天地图等图源）。必须设置 User-Agent，
            //      否则部分瓦片服务器会拒绝服务（osmdroid 默认 UA 会被识别为异常流量）。
            //      瓦片缓存路径默认在应用外部私有目录 /osmdroid，随卸载自动清理。 ----
            runCatching {
                val cfg = org.osmdroid.config.Configuration.getInstance()
                if (cfg.userAgentValue.isNullOrBlank()) {
                    cfg.userAgentValue = appCtx.packageName
                }
                cfg.load(
                    appCtx,
                    appCtx.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
                )
            }

            initialized = true
        }
    }
}
