package com.example.myfirstapp.offline

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 离线区域清单的持久化仓库。
 *
 * 与 [com.example.myfirstapp.mapsources.MapSourceStore] 同一套写法：
 * Compose 可观察状态 + JSON 落盘到应用私有目录，UI 直接观察即可。
 *
 * ★ 只存**元数据**（bbox / 级别 / 图源 / 状态），瓦片本体在各自的 mbtiles 文件里；
 *   清单与存档文件用 regionId 关联，删区域时两边都要清。
 */
object OfflineRegionStore {

    /** 区域清单（Compose 可观察） */
    val regions = mutableStateListOf<OfflineRegion>()

    /** 清单版本号：任何增删改都会 +1，供地图页重刷离线瓦片层 */
    var revision by mutableStateOf(0)
        private set

    /**
     * 全局「离线模式」开关：打开后地图**只**读本地存档，完全不发起瓦片网络请求
     * （飞行模式 / 省流量 / 验证离线覆盖率的场景）。
     *
     * ★ 为什么写成自定义 getter/setter 而不是 `var x by mutableStateOf() private set` + 一个
     *   `fun setX(on: Boolean)`：属性委托会生成 JVM setter `setX(Z)V`，与同名的函数
     *   **Platform declaration clash**（本次实测报错）。把持久化收进 setter 后，
     *   外部直接 `OfflineRegionStore.offlineOnly = true` 即可，也不再需要第二个函数。
     */
    var offlineOnly: Boolean
        get() = _offlineOnly
        set(value) {
            if (_offlineOnly == value) return
            _offlineOnly = value
            // 走 SharedPreferences：这是全局偏好，不该混在区域清单 JSON 里
            runCatching { prefs()?.edit()?.putBoolean(KEY_OFFLINE_ONLY, value)?.apply() }
            revision++   // 换 provider 链，地图页据此重刷底图
        }

    private var _offlineOnly by mutableStateOf(false)

    @Volatile
    private var loaded = false
    private var appContext: Context? = null

    private val file: File?
        get() = appContext?.let { File(it.filesDir, "offline_regions.json") }

    /** 「离线模式」开关走 SharedPreferences：它是全局偏好，不该混在区域清单 JSON 里 */
    private fun prefs(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences("offline_prefs", Context.MODE_PRIVATE)

    /**
     * ★ 状态修改一律切回主线程。
     * 下载进度是在 IO 线程里高频回调的（每秒几十次），而 [regions] 是 Compose 的
     * SnapshotStateList —— 跨线程写虽然"技术上"被 snapshot 系统允许，但会让重组发生在
     * 非预期线程，偶发 "Reading a state that was created after the snapshot was taken"。
     * 这里统一 post 到主线程，代价可忽略。
     */
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private fun postOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    fun ensureLoaded(context: Context) {
        OfflineStorage.init(context)
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            appContext = context.applicationContext
            runCatching {
                val f = file ?: return
                if (!f.exists()) return
                val arr = JSONArray(f.readText())
                val list = ArrayList<OfflineRegion>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        OfflineRegion(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            name = o.optString("name", "离线区域"),
                            sourceId = o.optString("sourceId", ""),
                            sourceName = o.optString("sourceName", ""),
                            minLat = o.optDouble("minLat"),
                            minLon = o.optDouble("minLon"),
                            maxLat = o.optDouble("maxLat"),
                            maxLon = o.optDouble("maxLon"),
                            minZoom = o.optInt("minZoom", 10),
                            maxZoom = o.optInt("maxZoom", 15),
                            status = runCatching {
                                OfflineRegionStatus.valueOf(o.optString("status", "QUEUED"))
                            }.getOrDefault(OfflineRegionStatus.QUEUED),
                            totalTiles = o.optInt("total", 0),
                            doneTiles = o.optInt("done", 0),
                            bytes = o.optLong("bytes", 0L),
                            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                            includeElevation = o.optBoolean("elev", false),
                            error = o.optString("error", "").ifEmpty { null }
                        )
                    )
                }
                regions.clear()
                regions.addAll(list)
            }
            // 磁盘校正：存档文件没了就不再是 READY（用户手动清过目录/卸载重装过）
            for (i in regions.indices) {
                val r = regions[i]
                val fixed = when {
                    r.status == OfflineRegionStatus.READY &&
                        !OfflineStorage.regionFile(r.id).exists() ->
                        r.copy(status = OfflineRegionStatus.FAILED, error = "存档丢失")
                    // 进程被杀留下的"下载中"僵尸态：重启后按暂停处理，让用户自己决定续还是删
                    r.status == OfflineRegionStatus.DOWNLOADING ||
                        r.status == OfflineRegionStatus.QUEUED ->
                        r.copy(status = OfflineRegionStatus.PAUSED)
                    else -> null
                }
                if (fixed != null) regions[i] = fixed
            }
            // 直接写后端字段：走 setter 会顺带 revision++ 和回写 prefs，加载期都不该发生
            _offlineOnly = prefs()?.getBoolean(KEY_OFFLINE_ONLY, false) ?: false
            loaded = true
        }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val arr = JSONArray()
            regions.forEach { r ->
                arr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("name", r.name)
                        .put("sourceId", r.sourceId)
                        .put("sourceName", r.sourceName)
                        .put("minLat", r.minLat)
                        .put("minLon", r.minLon)
                        .put("maxLat", r.maxLat)
                        .put("maxLon", r.maxLon)
                        .put("minZoom", r.minZoom)
                        .put("maxZoom", r.maxZoom)
                        .put("status", r.status.name)
                        .put("total", r.totalTiles)
                        .put("done", r.doneTiles)
                        .put("bytes", r.bytes)
                        .put("createdAt", r.createdAt)
                        .put("elev", r.includeElevation)
                        .put("error", r.error ?: "")
                )
            }
            f.writeText(arr.toString())
        }
        revision++
    }

    // ==================== 查询 ====================

    fun byId(id: String): OfflineRegion? = regions.firstOrNull { it.id == id }

    /** 某图源已就绪的离线区域（渲染时按图源取存档） */
    fun readyFor(sourceId: String): List<OfflineRegion> =
        regions.filter { it.sourceId == sourceId && it.status == OfflineRegionStatus.READY }

    fun hasOffline(sourceId: String): Boolean = readyFor(sourceId).isNotEmpty()

    /** 是否下载过任何高程数据 */
    fun hasElevation(): Boolean = regions.any { it.includeElevation && it.status == OfflineRegionStatus.READY }

    /** 全部区域占用字节数（含高程库） */
    fun totalBytes(): Long = regions.sumOf { it.bytes } + OfflineStorage.elevationFile.length()

    // ==================== 修改 ====================

    /** 新增一个区域（返回 id） */
    fun add(region: OfflineRegion): String {
        val r = region.copy(id = region.id.ifBlank { UUID.randomUUID().toString() })
        regions.removeAll { it.id == r.id }
        regions.add(0, r)
        save()
        return r.id
    }

    /** 更新进度（高频调用，只改内存 + 节流落盘） */
    fun updateProgress(id: String, done: Int, total: Int, bytes: Long) {
        postOnMain {
            val idx = regions.indexOfFirst { it.id == id }
            if (idx < 0) return@postOnMain
            val old = regions[idx]
            regions[idx] = old.copy(doneTiles = done, totalTiles = total, bytes = bytes)
            // 每 5% 或每 200 张落一次盘，避免几十万次写文件
            val step = (total / 20).coerceAtLeast(200)
            if (done % step == 0 || done >= total) save()
        }
    }

    fun updateStatus(id: String, status: OfflineRegionStatus, error: String? = null) {
        postOnMain {
            val idx = regions.indexOfFirst { it.id == id }
            if (idx < 0) return@postOnMain
            regions[idx] = regions[idx].copy(status = status, error = error)
            save()
        }
    }

    /** 删除区域：清单 + 存档文件 */
    fun remove(id: String) {
        regions.removeAll { it.id == id }
        runCatching { MbTiles.open(OfflineStorage.regionFile(id)).deleteFile() }
        save()
    }

    /** 清空全部离线数据 */
    fun clearAll() {
        regions.forEach { r ->
            runCatching { MbTiles.open(OfflineStorage.regionFile(r.id)).deleteFile() }
        }
        regions.clear()
        runCatching { MbTiles.open(OfflineStorage.elevationFile).deleteFile() }
        OfflineStorage.clearAll()
        save()
    }

    private const val KEY_OFFLINE_ONLY = "offlineOnly"

    /** 新建区域时的默认命名：「图源名 · 月日 时:分」 */
    fun suggestName(sourceName: String): String {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return "$sourceName · ${sdf.format(java.util.Date())}"
    }
}
