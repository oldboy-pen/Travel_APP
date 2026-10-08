package com.example.myfirstapp.mapsources

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
     * 运动页自动叠加的等高线源 id：
     * 用户填了镜像地址→自定义叠加源 "contour.override"（WGS-84），否则回落内置 opentopomap。
     */
    val contourSourceId: String
        get() = if (customOf("contour.override") != null) "contour.override" else "opentopomap"

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
                            }.getOrDefault(emptyMap())
                        )
                    )
                }
                customSources = list
            }
            // 校正：activeBaseId 不存在则回退默认
            if (findSource(activeBaseId) == null) activeBaseId = "amap.normal"
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
        MapSource.BUILTINS.filter { !it.isOverlay } + customSources.filter { !it.isOverlay }

    fun allOverlays(): List<MapSource> =
        MapSource.BUILTINS.filter { it.isOverlay } + customSources.filter { it.isOverlay }

    fun customOf(id: String): MapSource? = customSources.firstOrNull { it.id == id }

    fun findSource(id: String?): MapSource? {
        if (id == null) return null
        return MapSource.find(id) ?: customOf(id)
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
