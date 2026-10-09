package com.example.myfirstapp.globe

import android.content.Context
import com.example.myfirstapp.mapsources.MapSource
import com.example.myfirstapp.mapsources.MapSourceStore
import com.example.myfirstapp.mapsources.TileHttp

/**
 * 3D 地球能用的瓦片图源（必须是 XYZ 瓦片 URL）。
 *
 * 与 [MapSource] 的区别：厂商原生 SDK 图源（nativeType != null，如高德/腾讯/百度地图）
 * 拿不到瓦片 URL，没法贴到 OpenGL 球面上，因此这里只收 URL 模板类图源。
 *
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
    val attribution: String = ""
) {
    /** 第 layerIndex 层在这一块瓦片上的完整 URL */
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
     * 所有可贴到球面上的图源：内置 XYZ 图源 + 用户自定义图源。
     * 天地图系列没有填 Key 也会被列出来（用户点进去会看到 401 提示，比直接消失好排查）。
     */
    fun candidates(context: Context): List<GlobeSource> {
        MapSourceStore.ensureLoaded(context)
        val tk = MapSourceStore.tiandituKey
        val all = MapSource.BUILTINS + MapSourceStore.customSources
        val list = all.mapNotNull { it.toGlobe(tk) }
        // 地形类排前面（这个页面就是用来看地形的），其余按名字排
        return list.sortedWith(
            compareByDescending<GlobeSource> { terrainScore(it.id) }
                .thenBy { it.name }
        )
    }

    /**
     * 默认图源优先级：天地图地形（填了 Key 才有内容）→ OpenTopoMap 等高线地形 → 第一条。
     */
    fun preferred(context: Context): GlobeSource {
        val list = candidates(context)
        val tdtOk = MapSourceStore.tiandituKey.isNotBlank()
        return list.firstOrNull { it.id == "tdt.terrain" && tdtOk }
            ?: list.firstOrNull { it.id == "opentopomap" }
            ?: list.firstOrNull { !it.id.startsWith("tdt.") }
            ?: list.first()
    }

    private fun terrainScore(id: String): Int = when (id) {
        "tdt.terrain" -> 4
        "opentopomap" -> 3
        "tdt.img" -> 2
        "tdt.vec" -> 1
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
            attribution = attribution
        )
    }
}
