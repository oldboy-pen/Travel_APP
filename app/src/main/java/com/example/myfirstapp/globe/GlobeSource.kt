package com.example.myfirstapp.globe

import android.content.Context
import com.example.myfirstapp.mapsources.MapSource
import com.example.myfirstapp.mapsources.MapSourceStore
import com.example.myfirstapp.mapsources.TileCrs
import com.example.myfirstapp.mapsources.TileHttp

/**
 * 3D 地球能用的瓦片图源（必须是 XYZ 瓦片 URL）。
 *
 * 与 [MapSource] 的区别：厂商原生 SDK 图源（nativeType != null，如高德/腾讯/百度地图）
 * 在 2D 地图上由 SDK 渲染拿不到 URL，但这些厂商的 **Web 瓦片服务本身是公开可直连的**
 * （高德 webst/webrd 实测 HTTP 200），所以球面可以按 URL 模板直接抓。
 *
 * @param crs 瓦片网格坐标系：高德/腾讯是 GCJ-02，天地图/OpenTopoMap 是 WGS-84。
 *            球面按 WGS-84 网格渲染，GCJ-02 源由 [GlobeTileLoader] 走 TileRasterizer
 *            逐像素重投影（偏移场在一个瓦片内变化平缓，误差远小于 1 像素）。
 * @param templates 一到多层 URL 模板，按列表顺序自下而上合成一张瓦片
 *                  （天地图「地形晕渲+等高线注记」就是这种复合层）
 */
data class GlobeSource(
    val id: String,
    val name: String,
    val templates: List<String>,
    val subdomains: String = "",
    val tk: String = "",
    val headers: Map<String, String> = emptyMap(),
    val minZoom: Int = 0,
    val maxZoom: Int = 17,
    val crs: TileCrs = TileCrs.WGS84,
    val attribution: String = ""
) {
    /** 第 layerIndex 层在这一块瓦片上的完整 URL（仅 WGS-84 直连路径用；GCJ-02 源走重投影） */
    fun url(t: TileKey, layerIndex: Int): String = TileHttp.renderUrl(
        template = templates[layerIndex],
        xStr = t.x.toString(),
        yStr = t.y.toString(),
        zoom = t.z,
        rawX = t.x,
        rawY = t.y,
        subdomains = subdomains,
        tk = tk
    )
}

object GlobeSources {

    /** 高程数据源：AWS Terrarium 编码（PNG 的 RGB 打包成海拔高度），z<=15 */
    const val DEM_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
    const val DEM_MAX_ZOOM = 15

    /**
     * 球面专属图源：高德 Web 瓦片（GCJ-02 网格，国内直连稳定，实测 HTTP 200）。
     * 2D 地图这些样式走高德 SDK 原生渲染（[MapSource] 里没有对应瓦片条目），只有球面用。
     * 实测结论（2026-10-10，webst 域名）：
     * - style=6  卫星影像（无注记，JPEG）—— 「实地影像」的主力源；
     * - style=8  卫星影像 + 路网地名注记（PNG）；
     * - style=7/9/10 是矢量系；`ltype=11` 参数无效（返回内容与 style=6 完全相同），
     *   高德 Web 瓦片没有独立「地形晕渲」层，地形起伏靠 DEM 山体阴影开关。
     * 瓦片覆盖以中国及周边为主，境外只有低级别概况。
     */
    private val BUILTIN_GLOBE = listOf(
        GlobeSource(
            id = "amap.globe.satellite", name = "高德卫星影像",
            templates = listOf("https://webst0{s}.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}"),
            subdomains = "1234", crs = TileCrs.GCJ02, minZoom = 3, maxZoom = 17,
            attribution = "© 高德地图"
        ),
        GlobeSource(
            id = "amap.globe.satroad", name = "高德卫星路网",
            templates = listOf("https://webst0{s}.is.autonavi.com/appmaptile?style=8&x={x}&y={y}&z={z}"),
            subdomains = "1234", crs = TileCrs.GCJ02, minZoom = 3, maxZoom = 17,
            attribution = "© 高德地图"
        ),
        GlobeSource(
            id = "amap.globe.vector", name = "高德矢量地图",
            templates = listOf("https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x={x}&y={y}&z={z}"),
            subdomains = "1234", crs = TileCrs.GCJ02, minZoom = 3, maxZoom = 18,
            attribution = "© 高德地图"
        )
    )

    /**
     * 所有可贴到球面上的图源：球面专属 + 内置 XYZ 图源 + 用户自定义图源。
     * 天地图系列没有填 Key 也会被列出来（用户点进去会看到失败提示，比直接消失好排查）。
     */
    fun candidates(context: Context): List<GlobeSource> {
        MapSourceStore.ensureLoaded(context)
        val tk = MapSourceStore.tiandituKey
        val all = MapSource.BUILTINS + MapSourceStore.customSources
        val list = BUILTIN_GLOBE + all.mapNotNull { it.toGlobe(tk) }
        // 实地影像排最前（默认源），地形/等高线其次，其余按名字排
        return list.sortedWith(
            compareByDescending<GlobeSource> { rank(it.id) }
                .thenBy { it.name }
        )
    }

    /**
     * 默认图源：高德卫星影像（无需 Key、国内直连稳定，直接解决「球面没有实地影像」）。
     * 用户手动切换后本次会话内不再回到默认。
     */
    fun preferred(context: Context): GlobeSource {
        val list = candidates(context)
        return list.firstOrNull { it.id == "amap.globe.satellite" }
            ?: list.firstOrNull { it.id == "tdt.terrain" && MapSourceStore.tiandituKey.isNotBlank() }
            ?: list.firstOrNull { it.id == "opentopomap" }
            ?: list.firstOrNull { !it.id.startsWith("tdt.") }
            ?: list.first()
    }

    private fun rank(id: String): Int = when (id) {
        "amap.globe.satellite" -> 9
        "amap.globe.satroad" -> 8
        "tdt.terrain" -> 7
        "tdt.img" -> 6
        "opentopomap" -> 5
        "amap.globe.vector" -> 4
        "tdt.vec" -> 3
        else -> 0
    }

    /** XYZ 瓦片图源才能上球；原生 SDK 图源（无 URL）直接排除 */
    private fun MapSource.toGlobe(tk: String): GlobeSource? {
        val templates = when {
            layers.isNotEmpty() -> layers
            urlTemplate.isNotBlank() -> listOf(urlTemplate)
            else -> return null     // nativeType 类图源（高德/腾讯/百度）
        }
        return GlobeSource(
            id = id,
            name = name,
            templates = templates,
            subdomains = subdomains,
            tk = if (needsKey) tk else "",
            headers = headers,
            minZoom = minZoom.coerceAtMost(3),
            maxZoom = maxZoom,
            // 自定义图源默认 GCJ02（与 MapSource 默认一致），此前被当成 WGS-84 贴球会偏移
            crs = crs,
            attribution = attribution
        )
    }
}
