package com.example.myfirstapp.mapsources

/**
 * 瓦片图源坐标系。
 * - GCJ02：高德/腾讯，与 App 地图（高德）同坐标系，直接叠加无偏差；
 * - WGS84：天地图/OpenTopoMap 等，需逐像素 GCJ→WGS 反算重投影；
 * - BD09：百度，需 GCJ→BD09→百度墨卡托逐像素重投影。
 */
enum class TileCrs(val label: String) {
    GCJ02("GCJ-02（高德/腾讯，无偏差）"),
    WGS84("WGS-84（天地图/OSM，自动纠偏）"),
    BD09("BD-09（百度，自动纠偏）")
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
 */
data class MapSource(
    val id: String,
    val name: String,
    val urlTemplate: String,
    val subdomains: String = "",
    val crs: TileCrs = TileCrs.GCJ02,
    val minZoom: Int = 3,
    val maxZoom: Int = 18,
    val isOverlay: Boolean = false,
    val needsKey: Boolean = false,
    val builtin: Boolean = false
) {
    companion object {
        /** 内置图源。id 为 amap.* 的走高德 SDK 原生底图，不下载瓦片 */
        val BUILTINS: List<MapSource> = listOf(
            MapSource("amap.normal", "高德矢量", "", builtin = true),
            MapSource("amap.satellite", "高德卫星", "", builtin = true),
            MapSource("amap.night", "高德夜景", "", builtin = true),
            MapSource(
                "amap.sat_road", "高德卫星路网", "", builtin = true,
                isOverlay = false  // 特殊组合：卫星底图+路网标注，applyMapSources 特殊处理
            ),
            MapSource(
                "tencent.street", "腾讯街道",
                "https://rt{s}.map.gtimg.com/realtimerender?z={z}&x={x}&y={-y}&style=0&scene=0",
                subdomains = "0123", crs = TileCrs.GCJ02, minZoom = 3, maxZoom = 19, builtin = true
            ),
            MapSource(
                "tencent.satellite", "腾讯卫星",
                "https://p{s}.map.gtimg.com/sateTiles/{z}/{sx}/{sy}/{x}_{-y}.jpg",
                subdomains = "0123", crs = TileCrs.GCJ02, minZoom = 3, maxZoom = 19, builtin = true
            ),
            MapSource(
                "baidu.street", "百度街道",
                "https://online{s}.map.bdimg.com/onlinelabel/?qt=tile&x={x}&y={y}&z={z}&styles=pl&scaler=1&p=1",
                subdomains = "0123", crs = TileCrs.BD09, minZoom = 4, maxZoom = 18, builtin = true
            ),
            MapSource(
                "baidu.satellite", "百度卫星",
                "https://shangetu{s}.map.bdimg.com/it/u=x={x};y={y};z={z};v=009;type=sate&fm=46",
                subdomains = "0123", crs = TileCrs.BD09, minZoom = 4, maxZoom = 18, builtin = true
            ),
            MapSource(
                "tdt.vec", "天地图矢量",
                "https://t{s}.tianditu.gov.cn/DataServer?T=vec_w&x={x}&y={y}&l={z}&tk={tk}",
                subdomains = "01234567", crs = TileCrs.WGS84, minZoom = 3, maxZoom = 18,
                needsKey = true, builtin = true
            ),
            MapSource(
                "tdt.img", "天地图卫星",
                "https://t{s}.tianditu.gov.cn/DataServer?T=img_w&x={x}&y={y}&l={z}&tk={tk}",
                subdomains = "01234567", crs = TileCrs.WGS84, minZoom = 3, maxZoom = 18,
                needsKey = true, builtin = true
            ),
            MapSource(
                "tdt.ter", "天地图地形",
                "https://t{s}.tianditu.gov.cn/DataServer?T=ter_w&x={x}&y={y}&l={z}&tk={tk}",
                subdomains = "01234567", crs = TileCrs.WGS84, minZoom = 3, maxZoom = 13,
                needsKey = true, builtin = true
            ),
            MapSource(
                "tdt.cva", "天地图标注",
                "https://t{s}.tianditu.gov.cn/DataServer?T=cva_w&x={x}&y={y}&l={z}&tk={tk}",
                subdomains = "01234567", crs = TileCrs.WGS84, minZoom = 3, maxZoom = 18,
                isOverlay = true, needsKey = true, builtin = true
            ),
            // 等高线：OpenTopoMap（国内网络不可达，保留；后续可换天地图 ter）
            MapSource(
                "opentopomap", "等高线(OpenTopoMap)",
                "https://tile.opentopomap.org/{z}/{x}/{y}.png",
                crs = TileCrs.WGS84, minZoom = 3, maxZoom = 17, isOverlay = true, builtin = true
            )
        )

        fun find(id: String?): MapSource? = if (id == null) null else BUILTINS.firstOrNull { it.id == id }
    }
}
