package com.example.myfirstapp.map

import android.content.Context
import android.content.pm.PackageManager

/**
 * 三家地图厂商 Key 的读取与可用性判断。
 *
 * Key 统一写在 AndroidManifest.xml 的 meta-data 里（各 SDK 自己也是从这里读），
 * 好处：换 Key 只改一个地方，不会出现"SDK 读的是 A、我们判断可用读的是 B"的错位。
 *
 * meta-data 名称（已用 javap 从 SDK 内部常量核实）：
 * - 高德：com.amap.api.v2.apikey
 * - 腾讯："TencentMapSDK"（com.tencent.map.tools.Util.META_NAME_API_KEY）
 * - 百度：com.baidu.lbsapi.API_KEY
 */
object MapEngineKeys {

    private const val META_AMAP = "com.amap.api.v2.apikey"
    private const val META_TENCENT = "TencentMapSDK"
    private const val META_BAIDU = "com.baidu.lbsapi.API_KEY"

    /** 占位符前缀：Manifest 里没换掉的 KitKat 都以此开头 */
    private const val PLACEHOLDER_PREFIX = "YOUR_"

    @Volatile
    private var appContext: Context? = null

    /** 幂等绑定 ApplicationContext，任何入口都能安全调用 */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun metaNameOf(kind: MapEngineKind): String = when (kind) {
        MapEngineKind.AMAP -> META_AMAP
        MapEngineKind.TENCENT -> META_TENCENT
        MapEngineKind.BAIDU -> META_BAIDU
    }

    /** 读取到的原始 Key（未配置则返回空串） */
    fun rawOf(kind: MapEngineKind): String {
        val ctx = appContext ?: return ""
        val appInfo = runCatching {
            ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
        }.getOrNull() ?: return ""
        return appInfo.metaData?.getString(metaNameOf(kind)).orEmpty()
    }

    /** Key 是否已真正配置（非空、且不是占位符） */
    fun isConfigured(kind: MapEngineKind): Boolean {
        val v = rawOf(kind)
        return v.isNotBlank() && !v.startsWith(PLACEHOLDER_PREFIX)
    }

    val amapKey: String get() = rawOf(MapEngineKind.AMAP)
    val tencentKey: String get() = rawOf(MapEngineKind.TENCENT)
    val baiduKey: String get() = rawOf(MapEngineKind.BAIDU)

    /** 给用户看的缺失提示 */
    fun missingHint(kind: MapEngineKind): String =
        "尚未配置${kind.label}地图 Key：请在 AndroidManifest.xml 里把 ${metaNameOf(kind)} 换成你申请的 Key"
}
