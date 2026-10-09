package com.example.myfirstapp.mapsources

import com.example.myfirstapp.map.MapEngineKind
import com.example.myfirstapp.map.MapEngineKeys

/**
 * 用厂商原生 SDK 渲染的地图类型。
 *
 * @param kind 由哪家 SDK 负责渲染
 * @param code 传给对应引擎的样式标识（各引擎内部 switch）
 */
enum class NativeMapType(val kind: MapEngineKind, val code: String) {
    AMAP_NORMAL(MapEngineKind.AMAP, "normal"),
    AMAP_SATELLITE(MapEngineKind.AMAP, "satellite"),
    AMAP_NIGHT(MapEngineKind.AMAP, "night"),
    AMAP_SAT_ROAD(MapEngineKind.AMAP, "sat_road"),
    TENCENT_NORMAL(MapEngineKind.TENCENT, "normal"),
    TENCENT_SATELLITE(MapEngineKind.TENCENT, "satellite"),
    TENCENT_DARK(MapEngineKind.TENCENT, "dark"),
    BAIDU_NORMAL(MapEngineKind.BAIDU, "normal"),
    BAIDU_SATELLITE(MapEngineKind.BAIDU, "satellite")
}

/**
 * 瓦片图源坐标系。
 * - GCJ02：高德/腾讯，与 App 地图（高德）同坐标系，直接叠加无偏差；
 * - WGS84：天地图/OpenTopoMap 等，由 **osmdroid 引擎原生渲染**（osmdroid 本身就是 WGS-84
 *   网格，原样下载贴图，无需逐像素重投影；业务层 GCJ-02 坐标在引擎边界转 WGS-84）；
 * - BD09：百度，仅自定义 BD09 图源时在【高德引擎】上逐像素重投影。
 *
 * 注意：本枚举只对【瓦片叠加】有意义；走厂商原生 SDK 的图源（nativeType != null）
 * 不需要 CRS 换算 —— SDK 渲染引擎自己处理坐标系。
 */
enum class TileCrs(val label: String) {
    GCJ02("GCJ-02（高德/腾讯，无偏差）"),
    WGS84("WGS-84（天地图/OSM，osmdroid 原生渲染）"),
    BD09("BD-09（百度，高德上重投影）")
}

/**
 * 一个瓦片图源。
 *
 * @param id 唯一标识；内置图源固定 id（如 amap.normal），自定义图源用 UUID
 * @param name 显示名
 * @param urlTemplate URL 模板，支持占位符：
 *   {z} 级别、{x} 列、{y} 行（标准 XYZ，自上而下）、{-y} TMS 反转行（腾讯用）、
 *   {s} 子域名（取 subdomains 逐个轮换）、{sx}/{sy} 腾讯 sateTiles 用的 x>>4 / 反转y>>4、
 *   {tk} 天地图 Key（needsKey=true 时替换）
 * @param subdomains {s} 轮换的子域名字符集，如 "0123"
 * @param crs 图源坐标系
 * @param minZoom/maxZoom 可用级别范围（超出返回 NO_TILE，露出下层底图兜底）
 * @param isOverlay true=半透明叠加层（叠在底图上），false=不透明底图
 * @param needsKey 模板含 {tk}，需在图源管理里配置天地图 Key
 * @param builtin true=内置图源（不可编辑/删除）
 * @param attribution 版权署名，显示在地图左下角「当前图源」标签上（第三方瓦片合规要求）
 * @param nativeType 非空=用该厂商的原生 SDK 渲染（此时 urlTemplate 留空，不再抓瓦片）；
 *                   为空且 urlTemplate 非空=瓦片图源，叠加在高德矢量底图之上（旧路径）
 */
data class MapSource(
    val id: String,
    val name: String,
    val urlTemplate: String = "",
    val subdomains: String = "",
    val crs: TileCrs = TileCrs.GCJ02,
    val minZoom: Int = 3,
    val maxZoom: Int = 18,
    val isOverlay: Boolean = false,
    val needsKey: Boolean = false,
    val builtin: Boolean = false,
    val attribution: String = "",
    /** 自定义请求头（防盗链）：如 Referer / User-Agent / Authorization，下载瓦片时注入 */
    val headers: Map<String, String> = emptyMap(),
    val nativeType: NativeMapType? = null,
    /**
     * 复合图层：多个 XYZ 瓦片 URL 模板，按列表顺序从下到上合成一张瓦片。
     * 例如天地图「地形(晕渲+等高线注记)」= ter_w（底，晕渲）+ cta_w（上，等高线注记）。
     * 非空时覆盖 [urlTemplate] 单独渲染；所有子层共享 subdomains / crs / minZoom /
     * maxZoom / needsKey / headers。
     * 路由遵循 [engineKind]：WGS84 子层（天地图矢量/卫星/地形）由 osmdroid 引擎原生渲染，
     * 不再走 [CustomTileProvider] 逐像素重投影；GCJ02/BD09 子层仍由高德 CustomTileProvider 叠加。
     */
    val layers: List<String> = emptyList()
) {
    /** 这张图由哪家引擎渲染：
     *  厂商原生图源 → 对应厂商 SDK；
     *  WGS84 瓦片图源（天地图/OpenTopoMap/自定义 WGS）→ osmdroid 原生渲染（免重投影）；
     *  其余瓦片图源（GCJ02/BD09）→ 高德引擎 + CustomTileProvider 叠加 */
    val engineKind: MapEngineKind
        get() = when {
            nativeType != null -> nativeType!!.kind
            isTileSource && crs == TileCrs.WGS84 -> MapEngineKind.OSMDROID
            else -> MapEngineKind.AMAP
        }

    /** 是否是"抓瓦片叠加"的图源（没有原生 SDK 可走） */
    val isTileSource: Boolean get() = nativeType == null && (urlTemplate.isNotBlank() || layers.isNotEmpty())

    companion object {
        /** 内置图源。nativeType 非空=走该厂商原生 SDK；空=瓦片叠加在高德底图上 */
        val BUILTINS: List<MapSource> = listOf(
            MapSource(
                "amap.normal", "高德矢量", "", builtin = true,
                attribution = "© 高德地图", nativeType = NativeMapType.AMAP_NORMAL
            ),
            MapSource(
                "amap.satellite", "高德卫星", "", builtin = true,
                attribution = "© 高德地图", nativeType = NativeMapType.AMAP_SATELLITE
            ),
            MapSource(
                "amap.night", "高德夜景", "", builtin = true,
                attribution = "© 高德地图", nativeType = NativeMapType.AMAP_NIGHT
            ),
            MapSource(
                "amap.sat_road", "高德卫星路网", "", builtin = true,
                attribution = "© 高德地图", nativeType = NativeMapType.AMAP_SAT_ROAD
            ),
            // ---- 腾讯：改用腾讯地图 SDK 原生渲染，不再抓 rt{s}.map.gtimg.com 瓦片 ----
            MapSource(
                "tencent.street", "腾讯街道", "", builtin = true,
                attribution = "© 腾讯地图", nativeType = NativeMapType.TENCENT_NORMAL
            ),
            MapSource(
                "tencent.satellite", "腾讯卫星", "", builtin = true,
                attribution = "© 腾讯地图", nativeType = NativeMapType.TENCENT_SATELLITE
            ),
            MapSource(
                "tencent.dark", "腾讯暗色", "", builtin = true,
                attribution = "© 腾讯地图", nativeType = NativeMapType.TENCENT_DARK
            ),
            // ---- 百度：改用百度地图 SDK 原生渲染，不再抓 bdimg 瓦片 ----
            MapSource(
                "baidu.street", "百度街道", "", builtin = true,
                attribution = "© 百度地图", nativeType = NativeMapType.BAIDU_NORMAL
            ),
            MapSource(
                "baidu.satellite", "百度卫星", "", builtin = true,
                attribution = "© 百度地图", nativeType = NativeMapType.BAIDU_SATELLITE
            ),
            // ---- 天地图：不接 SDK，直接走官方瓦片 REST API（tdt.* 图源）。
            //      服务地址 https://t{0-7}.tianditu.gov.cn/DataServer?T=<图层>&x=&y=&l=&tk=<Key>，
            //      图层代码：vec_w 矢量 / img_w 卫星 / ter_w 地形晕渲 /
            //               cva_w 矢量注记 / cia_w 卫星注记 / cta_w 等高线注记（均 Web 墨卡托 WGS84）。
            //      WGS84 图源由 **osmdroid 引擎原生渲染**（同坐标系，无需逐像素重投影）；
            //      业务层 GCJ-02 坐标在引擎边界转 WGS-84，轨迹/蓝点与底图天然对齐。
            //      Key（tk）在「图源管理」面板填写，与任何 SDK 无关。
            MapSource(
                "tdt.vec", "天地图矢量",
                layers = listOf(
                    "https://t{s}.tianditu.gov.cn/DataServer?T=vec_w&x={x}&y={y}&l={z}&tk={tk}",
                    "https://t{s}.tianditu.gov.cn/DataServer?T=cva_w&x={x}&y={y}&l={z}&tk={tk}"
                ),
                subdomains = "01234567", crs = TileCrs.WGS84, minZoom = 3, maxZoom = 18,
                needsKey = true, builtin = true, attribution = "© 天地图"
            ),
            MapSource(
                "tdt.img", "天地图卫星",
                layers = listOf(
                    "https://t{s}.tianditu.gov.cn/DataServer?T=img_w&x={x}&y={y}&l={z}&tk={tk}",
                    "https://t{s}.tianditu.gov.cn/DataServer?T=cia_w&x={x}&y={y}&l={z}&tk={tk}"
                ),
                subdomains = "01234567", crs = TileCrs.WGS84, minZoom = 3, maxZoom = 18,
                needsKey = true, builtin = true, attribution = "© 天地图"
            ),
            MapSource(
                "tdt.cva", "天地图标注",
                "https://t{s}.tianditu.gov.cn/DataServer?T=cva_w&x={x}&y={y}&l={z}&tk={tk}",
                subdomains = "01234567", crs = TileCrs.WGS84, minZoom = 3, maxZoom = 18,
                isOverlay = true, needsKey = true, builtin = true, attribution = "© 天地图"
            ),
            // 天地图地形注记（cta_w）：等高线 + 高程注记，透明层，可叠在任何底图上（GCJ-02 对齐）
            MapSource(
                "tdt.cta", "天地图等高线注记",
                "https://t{s}.tianditu.gov.cn/DataServer?T=cta_w&x={x}&y={y}&l={z}&tk={tk}",
                subdomains = "01234567", crs = TileCrs.WGS84, minZoom = 3, maxZoom = 18,
                isOverlay = true, needsKey = true, builtin = true, attribution = "© 天地图"
            ),
            // 天地图地形（ter_w + cta_w 双图层复合）：晕渲打底 + 等高线注记叠加，合成一张不透明地形图；
            // 可作为底图单独选用，由 osmdroid 引擎原生渲染（WGS84 同坐标系，业务层 GCJ-02 在引擎
            // 边界转 WGS-84，无需逐像素重投影）。
            MapSource(
                "tdt.terrain", "天地图地形(晕渲+等高线)",
                layers = listOf(
                    "https://t{s}.tianditu.gov.cn/DataServer?T=ter_w&x={x}&y={y}&l={z}&tk={tk}",
                    "https://t{s}.tianditu.gov.cn/DataServer?T=cta_w&x={x}&y={y}&l={z}&tk={tk}"
                ),
                subdomains = "01234567", crs = TileCrs.WGS84, minZoom = 3, maxZoom = 13,
                needsKey = true, builtin = true, attribution = "© 天地图"
            ),
            // 等高线：OpenTopoMap（OSM+SRTM，全球覆盖，z17）。WGS84 图源，由 **osmdroid 引擎原生渲染**
            // （路由到 osmdroid 底图时与 WGS84 底图天然对齐，无需重投影）；国内服务器在境外，弱网/被墙时
            // 可改用「等高线镜像」(MapSourceStore.setContourOverride) 或上面的天地图等高线注记（同样 WGS84，走 osmdroid）。
            MapSource(
                "opentopomap", "等高线(OpenTopoMap)",
                "https://tile.opentopomap.org/{z}/{x}/{y}.png",
                crs = TileCrs.WGS84, minZoom = 3, maxZoom = 17, isOverlay = true, builtin = true,
                attribution = "© OpenStreetMap contributors, SRTM | © OpenTopoMap (CC-BY-SA)"
            )
        )

        fun find(id: String?): MapSource? = if (id == null) null else BUILTINS.firstOrNull { it.id == id }
    }
}
