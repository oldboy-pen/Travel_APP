package com.example.myfirstapp.mapsources

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.myfirstapp.offline.OfflineRegion
import com.example.myfirstapp.offline.OfflineRegionStatus
import com.example.myfirstapp.offline.OfflineRegionStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 图源仓库：自定义图源、天地图 Key、当前底图/叠加层选择的持久化存储。
 *
 * 状态用 Compose mutableStateOf 暴露，UI 与地图页直接观察；
 * 持久化到 filesDir/map_sources.json（应用内私有目录，不会被系统清理）。
 */
object MapSourceStore {

    /** 任何配置变化时 +1，供地图页 LaunchedEffect 监听统一重刷图层 */
    var revision by mutableStateOf(0)
        private set

    var customSources by mutableStateOf<List<MapSource>>(emptyList())
        private set

    var tiandituKey by mutableStateOf("")
        private set

    /** 当前底图 id（内置或自定义），默认高德矢量 */
    var activeBaseId by mutableStateOf("amap.normal")
        private set

    /** 当前叠加层 id，null=无叠加层 */
    var activeOverlayId by mutableStateOf<String?>(null)
        private set

    /** 运动页是否自动叠加等高线图层（进入看地图时自动叠，退出还原） */
    var autoContourEnabled by mutableStateOf(true)
        private set

    /**
     * 运动页自动叠加的等高线源 id（优先级）：
     * 1. 用户填了镜像地址 → 自定义叠加源 "contour.override"（WGS-84，可达）；
     * 2. 配置了天地图 Key → 用天地图「等高线注记」(tdt.cta)，国内节点、GCJ-02 对齐、最稳；
     * 3. 否则回落内置 OpenTopoMap（WGS-84，境外服务器，国内可能不可达）。
     */
    val contourSourceId: String
        get() = when {
            customOf("contour.override") != null -> "contour.override"
            tiandituKey.isNotBlank() -> "tdt.cta"
            else -> "opentopomap"
        }

    @Volatile
    private var loaded = false
    private var appContext: Context? = null

    private val file: File?
        get() = appContext?.let { File(it.filesDir, "map_sources.json") }

    /** 幂等初始化；各地图页/入口调用 */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            appContext = context.applicationContext
            runCatching {
                val f = file ?: return
                if (!f.exists()) return
                val root = JSONObject(f.readText())
                tiandituKey = root.optString("tiandituKey", "")
                activeBaseId = root.optString("activeBaseId", "amap.normal")
                activeOverlayId = root.optString("activeOverlayId", "").ifEmpty { null }
                autoContourEnabled = root.optBoolean("autoContour", true)
                val arr = root.optJSONArray("customSources") ?: JSONArray()
                val list = ArrayList<MapSource>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        MapSource(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            name = o.optString("name", "未命名图源"),
                            urlTemplate = o.optString("url", ""),
                            subdomains = o.optString("subdomains", ""),
                            crs = runCatching { TileCrs.valueOf(o.optString("crs", "GCJ02")) }.getOrDefault(TileCrs.GCJ02),
                            minZoom = o.optInt("minZoom", 3),
                            maxZoom = o.optInt("maxZoom", 18),
                            isOverlay = o.optBoolean("overlay", false),
                            needsKey = o.optBoolean("needsKey", false),
                            builtin = false,
                            headers = runCatching {
                                val h = o.optJSONObject("headers")
                                if (h == null) emptyMap() else h.keys().asSequence()
                                    .associateWith { h.optString(it) }
                            }.getOrDefault(emptyMap()),
                            layers = runCatching {
                                val arr = o.optJSONArray("layers")
                                if (arr == null) emptyList() else
                                    (0 until arr.length()).map { arr.getString(it) }
                            }.getOrDefault(emptyList())
                        )
                    )
                }
                customSources = list
            }
            // 校正：内置/自定义里都查不到才回退默认。
            // ★ 离线图源（"offline:" 前缀）特意不在这里回退：它们是从 OfflineRegionStore
            //   现算出来的，而两个 store 的 ensureLoaded 互不依赖、谁先谁后不定；
            //   在区域清单还没加载时判定"不存在"会把用户已选的离线底图误清成高德。
            if (!isOfflineId(activeBaseId) &&
                MapSource.find(activeBaseId) == null &&
                customOf(activeBaseId) == null
            ) activeBaseId = "amap.normal"
            if (activeOverlayId != null && findSource(activeOverlayId) == null) activeOverlayId = null
            loaded = true
        }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val root = JSONObject()
            root.put("tiandituKey", tiandituKey)
            root.put("activeBaseId", activeBaseId)
            root.put("activeOverlayId", activeOverlayId ?: "")
            val arr = JSONArray()
            customSources.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("name", it.name)
                        .put("url", it.urlTemplate)
                        .put("subdomains", it.subdomains)
                        .put("crs", it.crs.name)
                        .put("minZoom", it.minZoom)
                        .put("maxZoom", it.maxZoom)
                        .put("overlay", it.isOverlay)
                        .put("needsKey", it.needsKey)
                        .put("headers", JSONObject().apply { it.headers.forEach { (k, v) -> put(k, v) } })
                        .put("layers", JSONArray().apply { it.layers.forEach { put(it) } })
                )
            }
            root.put("customSources", arr)
            root.put("autoContour", autoContourEnabled)
            f.writeText(root.toString())
        }
        revision++
    }

    // ==================== 查询 ====================

    fun allBases(): List<MapSource> =
        MapSource.BUILTINS.filter { !it.isOverlay } +
            customSources.filter { !it.isOverlay } +
            offlineSources()

    fun allOverlays(): List<MapSource> =
        MapSource.BUILTINS.filter { it.isOverlay } + customSources.filter { it.isOverlay }

    fun customOf(id: String): MapSource? = customSources.firstOrNull { it.id == id }

    fun findSource(id: String?): MapSource? {
        if (id == null) return null
        val regionId = MapSource.regionIdOf(id)
        if (regionId != null) {
            // 离线图源不落盘：按 id 反查区域现算（区域被删 / 没下完 = 这张图不存在）
            val r = OfflineRegionStore.byId(regionId) ?: return null
            return offlineSourceOf(r)
        }
        return MapSource.find(id) ?: customOf(id)
    }

    /** 该 id 是否是离线区域派生出来的图源 */
    fun isOfflineId(id: String?): Boolean = MapSource.regionIdOf(id) != null

    // ==================== 离线区域 → 独立图源 ====================

    /**
     * 由「已就绪的离线区域」动态派生出的独立底图，直接出现在图层切换器里。
     *
     * ★ **不落盘**（不写进 map_sources.json）：区域是动态数据（会下完、会删掉），
     *   图源列表必须随它实时变；写盘就会出现"清单里有这张图、存档早没了"的幽灵图源。
     *
     * ★ **只派生 WGS-84 网格的区域**：只有 osmdroid 引擎能读 MBTiles 存档，而它本身是
     *   WGS-84 网格；GCJ-02 存档贴上去会整体偏移几百米。GCJ-02 的离线数据照样有用 ——
     *   它作为原图源的"离线加速"（OsmdroidEngine 按图源 id 找存档），只是不单独成图源。
     *
     * ★ 选中后的语义是"只读这块存档、不联网"（见 OsmdroidEngine.applyOfflineArchive）：
     *   用户看到的画面 = 该区域真正下载到了什么，区域外/级别外一律空白，
     *   不会被在线瓦片偷偷补齐而误以为下载完整。
     */
    fun offlineSources(): List<MapSource> =
        OfflineRegionStore.regions.mapNotNull { offlineSourceOf(it) }

    /** 区域 → 图源；不满足条件（未就绪 / 非 WGS-84 网格）返回 null */
    private fun offlineSourceOf(region: OfflineRegion): MapSource? {
        if (region.status != OfflineRegionStatus.READY) return null
        // 老清单没记 CRS：按原图源现查一次。查不到就当非 WGS-84 —— 宁可不派生，也不偏移
        val wgs = region.isWgs84 ||
            (region.tileCrs.isBlank() && findSource(region.sourceId)?.crs == TileCrs.WGS84)
        if (!wgs) return null
        return MapSource(
            id = MapSource.offlineSourceId(region.id),
            name = "离线 · ${region.name}",
            crs = TileCrs.WGS84,
            minZoom = region.minZoom,
            maxZoom = region.maxZoom,
            builtin = false,
            attribution = "离线存档 · ${region.sourceName.ifBlank { "未知图源" }}",
            offlineRegionId = region.id
        )
    }

    /**
     * 离线区域被删除时的回调（OfflineRegionStore.remove / clearAll 调用）：
     * 清掉指向它的底图选择，否则图层面板会显示成"一张都没选中"。
     */
    fun onOfflineRegionRemoved(regionId: String) {
        if (MapSource.regionIdOf(activeBaseId) == regionId) {
            activeBaseId = "amap.normal"
            save()
        }
    }

    fun activeBase(): MapSource = findSource(activeBaseId) ?: MapSource.find("amap.normal")!!

    fun activeOverlay(): MapSource? = findSource(activeOverlayId)

    // ==================== 修改 ====================

    fun selectBase(id: String) {
        if (findSource(id) == null) return
        activeBaseId = id
        save()
    }

    fun selectOverlay(id: String?) {
        activeOverlayId = if (id != null && findSource(id) != null) id else null
        save()
    }

    fun updateTiandituKey(key: String) {
        tiandituKey = key.trim()
        save()
    }

    // ==================== 运动页自动等高线 ====================

    /** 当前运动页自动叠加的等高线源（含用户镜像回退内置） */
    fun contourSource(): MapSource =
        findSource(contourSourceId) ?: MapSource.find("opentopomap")!!

    /** 开关运动页自动等高线 */
    fun setContourEnabled(on: Boolean) {
        if (autoContourEnabled == on) return
        autoContourEnabled = on
        save()
    }

    /**
     * 设置等高线镜像地址：
     * - 非空 → 写入/更新自定义叠加源 "contour.override"（WGS-84，半透明叠加层）；
     * - 空   → 移除镜像，回落内置 OpenTopoMap（国内网络不可达，仅海外可用）。
     *
     * 说明：OpenTopoMap 官方瓦片服务器在大陆被墙，要把 URL 换成可达的 XYZ 镜像
     * （如自建反代）才能在国内显示等高线。占位符 {z}/{x}/{y}。
     */
    fun setContourOverride(url: String) {
        val u = url.trim()
        if (u.isBlank()) {
            if (customSources.any { it.id == "contour.override" }) removeCustom("contour.override")
            return
        }
        val normalized = MapSourceImporter.normalizeUrl(u)
        val base = customOf("contour.override")
            ?: MapSource(id = "contour.override", name = "等高线(镜像)")
        val src = base.copy(
            urlTemplate = normalized,
            crs = TileCrs.WGS84,
            minZoom = 3,
            maxZoom = 17,
            isOverlay = true,
            builtin = false,
            headers = emptyMap()
        )
        upsertCustom(src)
    }

    /** 新增或更新自定义图源；id 为空则视为新增（自动分配 UUID） */
    fun upsertCustom(source: MapSource) {
        val s = source.copy(id = source.id.ifBlank { UUID.randomUUID().toString() }, builtin = false)
        val list = customSources.toMutableList()
        val idx = list.indexOfFirst { it.id == s.id }
        if (idx >= 0) list[idx] = s else list.add(s)
        customSources = list
        save()
    }

    fun removeCustom(id: String) {
        customSources = customSources.filter { it.id != id }
        if (activeBaseId == id) {
            activeBaseId = "amap.normal"
        }
        if (activeOverlayId == id) {
            activeOverlayId = null
        }
        save()
    }
}
