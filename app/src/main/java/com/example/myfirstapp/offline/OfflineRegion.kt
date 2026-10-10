package com.example.myfirstapp.offline

import com.example.myfirstapp.mapsources.TileCrs

/**
 * 一个离线区域：某个图源在某个经纬度矩形、某个缩放级别区间内的瓦片集合。
 *
 * ★ 一个区域只对应**一个图源**（含其复合图层）：MBTiles 规范里没有"图源"这一列，
 *   把多个图源塞进一个库会导致渲染时张冠李戴。复合图层（天地图"矢量+注记"）在下载时
 *   合成为一张瓦片再入库，所以对外仍然是一张图。
 *
 * @param id 区域 id，同时是存档文件名（`<id>.mbtiles`）
 * @param sourceId 图源 id（内置或自定义），决定用哪套 URL 模板抓瓦片
 * @param minLat/minLon/maxLat/maxLon 经纬度矩形（WGS-84 与 GCJ-02 差几百米，
 *        相对一个城市级的下载框可以忽略，统一按业务层 GCJ-02 存，抓瓦片时按图源 CRS 处理）
 * @param includeElevation 是否同时下载 terrarium 高程栅格（供无网查海拔）
 */
data class OfflineRegion(
    val id: String,
    val name: String,
    val sourceId: String,
    val sourceName: String,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val minZoom: Int,
    val maxZoom: Int,
    val status: OfflineRegionStatus = OfflineRegionStatus.QUEUED,
    /** 预计总瓦片数（复合图层按层数放大后） */
    val totalTiles: Int = 0,
    /** 已下载瓦片数 */
    val doneTiles: Int = 0,
    /** 已写入字节数 */
    val bytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val includeElevation: Boolean = false,
    /**
     * 原始图源的瓦片坐标系枚举名（"WGS84" / "GCJ02" / "BD09"）；空串 = 老清单没记。
     *
     * ★ 存在这里的理由：图源随时可能被用户删掉，但离线存档还在。存档的瓦片网格是按
     *   原图源坐标系抓的，渲染时必须知道它，否则无法判断能不能作为独立图源、
     *   以及贴到 osmdroid 的 WGS-84 网格上会不会偏移。
     * 存枚举名而不是枚举对象：JSON 落盘要稳定，枚举改名不炸。
     */
    val tileCrs: String = "",
    val error: String? = null
) {
    /** 进度 0..1（total 未知时给 0，UI 显示"计算中"） */
    val progress: Float
        get() = if (totalTiles <= 0) 0f else (doneTiles.toFloat() / totalTiles).coerceIn(0f, 1f)

    val isFinished: Boolean get() = status == OfflineRegionStatus.READY
    val isActive: Boolean get() = status == OfflineRegionStatus.DOWNLOADING

    /** 中心点（UI 定位/排序用） */
    val centerLat: Double get() = (minLat + maxLat) / 2.0
    val centerLon: Double get() = (minLon + maxLon) / 2.0

    /**
     * 是否是 WGS-84 网格的存档 —— 只有这类区域能派生出独立的离线图源（见 MapSource 说明）。
     *
     * ★ 空串（老清单没记 CRS）时这里**不能**直接断定，必须按 [sourceId] 反查原图源的
     *   CRS 才能下结论 —— 那一步在 MapSourceStore 里做（它有图源表）。本属性只走
     *   "明确记了 WGS84"的快路径，宁可返回 false（不派生图源）也不冒偏移几百米的风险。
     */
    val isWgs84: Boolean get() = tileCrs == TileCrs.WGS84.name
}

/** 离线区域状态 */
enum class OfflineRegionStatus(val label: String) {
    /** 排队中（还没开始抓） */
    QUEUED("排队中"),

    /** 正在下载 */
    DOWNLOADING("下载中"),

    /** 用户暂停 */
    PAUSED("已暂停"),

    /** 已完成，可离线使用 */
    READY("已就绪"),

    /** 失败（网络/磁盘），可重试 */
    FAILED("失败"),

    /** 被取消 */
    CANCELED("已取消")
}

/** 下载过程中的实时进度快照（供 UI 与通知栏展示） */
data class OfflineProgress(
    val regionId: String,
    val done: Int,
    val total: Int,
    val bytes: Long,
    val failed: Int = 0
) {
    val ratio: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}
