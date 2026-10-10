package com.example.myfirstapp.offline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.example.myfirstapp.mapsources.MapSource
import com.example.myfirstapp.mapsources.MapSourceStore
import com.example.myfirstapp.mapsources.TileHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 离线瓦片下载引擎。
 *
 * 职责：把 [OfflineRegion]（bbox × 级别 × 图源）展开成瓦片坐标流，并发抓取，
 * 按图源配置合成（复合图层）后写进该区域的 MBTiles 存档。
 *
 * ★ 设计取舍：
 * - **并发不贪多**：瓦片服务器（尤其天地图）对并发敏感，开太高会被限流甚至封 IP。
 *   固定 6 路，实测在 4G 下 1~2 分钟能下一个中等城市的 z10-15；
 * - **批量落盘**：每攒够 [BATCH] 张提交一个事务，SQLite 单条 insert 的事务开销远大于 BLOB 写入本身；
 * - **续传**：开始前先问存档里有没有这张瓦片，有就跳过 —— 中断/失败后重开不会重复抓；
 * - **失败容忍**：单张失败（404/超时）只计数不中断，连续失败超过 [MAX_CONSECUTIVE_FAILS] 判定为网络故障，整体失败。
 *
 * 调用方是 [OfflineDownloadService]（前台服务），本类只管抓取逻辑，不碰 Android 生命周期。
 */
object OfflineDownloader {

    private const val CONCURRENCY = 6
    private const val BATCH = 60
    /** 连续失败这么多张就认为网络不可用，提前失败（避免几万张空跑） */
    private const val MAX_CONSECUTIVE_FAILS = 40

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var currentRegionId: String? = null

    /** 正在下载的 regionId（UI 判断是否显示进度条） */
    val activeRegionId: String? get() = currentRegionId

    fun isRunning(regionId: String): Boolean = currentRegionId == regionId && job?.isActive == true

    /**
     * 开始（或续传）一个区域。
     *
     * @param regionId 已写入 [OfflineRegionStore] 的区域 id
     * @param onProgress 进度回调（done/total/bytes），在 IO 线程回调，UI 侧自行切回主线程
     * @param onFinish 结束回调，参数为最终状态与错误说明
     */
    fun start(
        regionId: String,
        onProgress: (OfflineProgress) -> Unit = {},
        onFinish: (OfflineRegionStatus, String?) -> Unit = { _, _ -> }
    ) {
        if (isRunning(regionId)) return
        val region = OfflineRegionStore.byId(regionId) ?: run {
            onFinish(OfflineRegionStatus.FAILED, "区域不存在")
            return
        }
        val source = MapSourceStore.findSource(region.sourceId) ?: run {
            onFinish(OfflineRegionStatus.FAILED, "图源已不存在：${region.sourceName}")
            return
        }
        if (!source.isTileSource) {
            // 厂商原生底图（高德/腾讯/百度）不走瓦片：请用户改用"官方离线城市包"
            onFinish(OfflineRegionStatus.FAILED, "该图源由厂商 SDK 渲染，请改用官方离线城市包")
            return
        }

        val cancel = AtomicBoolean(false)
        currentRegionId = regionId
        OfflineRegionStore.updateStatus(regionId, OfflineRegionStatus.DOWNLOADING)

        job = scope.launch {
            val store = MbTiles.open(OfflineStorage.regionFile(regionId))
            store.putMeta("sourceId", region.sourceId)
            store.putMeta("sourceName", region.sourceName)
            store.putMeta("bounds", "${region.minLon},${region.minLat},${region.maxLon},${region.maxLat}")
            store.putMeta("minzoom", region.minZoom.toString())
            store.putMeta("maxzoom", region.maxZoom.toString())

            val templates = if (source.layers.isNotEmpty()) source.layers else listOf(source.urlTemplate)
            val tk = MapSourceStore.tiandituKey
            val headers = source.headers

            // 先算总量（含复合图层放大后的抓图次数）
            val total = TileGrid.countTiles(
                region.minLat, region.minLon, region.maxLat, region.maxLon,
                region.minZoom, region.maxZoom, templates.size
            ).toInt()

            val done = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val bytes = AtomicLong(0)
            val consecutive = AtomicInteger(0)
            val buffer = ArrayList<Pair<TileCoord, ByteArray>>(BATCH)

            fun flush() {
                if (buffer.isEmpty()) return
                store.putTiles(buffer)
                buffer.clear()
            }

            val result: OfflineRegionStatus = try {
                // ★ 惰性枚举 + 分块：一个城市的 z10-15 有几十万张瓦片，
                //   若先 toList() 一次性展开，光 TileCoord 对象就要几十 MB（低端机直接 OOM）。
                //   Sequence.chunked 是惰性的，内存里同时只有一块（CONCURRENCY*4 张）。
                val chunks = TileGrid.enumerate(
                    region.minLat, region.minLon, region.maxLat, region.maxLon,
                    region.minZoom, region.maxZoom
                ).chunked(CONCURRENCY * 4)

                // 分块并发：块内并行、块间串行，保证顺序可控且内存有界
                for (chunk in chunks) {
                    if (!isActive || cancel.get()) break
                    val sem = Semaphore(CONCURRENCY)
                    val deferred = chunk.map { coord ->
                        async {
                            // 续传：已有就跳过
                            if (store.hasTile(coord.zoom, coord.x, coord.y)) {
                                done.addAndGet(templates.size)
                                return@async null
                            }
                            sem.acquire()
                            try {
                                if (!isActive || cancel.get()) return@async null
                                val tile = fetchTile(coord, templates, source, tk, headers)
                                if (tile != null) {
                                    consecutive.set(0)
                                    bytes.addAndGet(tile.size.toLong())
                                    Pair(coord, tile)
                                } else {
                                    failed.incrementAndGet()
                                    if (consecutive.incrementAndGet() >= MAX_CONSECUTIVE_FAILS) {
                                        cancel.set(true)
                                    }
                                    null
                                }
                            } finally {
                                sem.release()
                            }
                        }
                    }
                    deferred.awaitAll().filterNotNull().forEach {
                        buffer.add(it)
                        done.addAndGet(templates.size)
                        if (buffer.size >= BATCH) flush()
                    }
                    flush()
                    onProgress(OfflineProgress(regionId, done.get(), total, bytes.get(), failed.get()))
                    OfflineRegionStore.updateProgress(regionId, done.get(), total, bytes.get())
                }

                flush()
                // 高程：同一批瓦片坐标抓 terrarium 栅格（全球统一源，与图源无关）。
                // terrarium 官方源最高到 z15，超过没有更细的数据，这里封顶 12 控制体积
                // （z12 一格约 10 km，高程查询做双线性插值后精度足够）。
                if (region.includeElevation && !cancel.get()) {
                    ElevationStore.download(
                        region.minLat, region.minLon, region.maxLat, region.maxLon,
                        region.minZoom.coerceAtMost(ElevationStore.MAX_ZOOM),
                        region.maxZoom.coerceAtMost(ElevationStore.MAX_ZOOM)
                    )
                }
                store.close()

                when {
                    cancel.get() && failed.get() >= MAX_CONSECUTIVE_FAILS ->
                        OfflineRegionStatus.FAILED
                    cancel.get() -> OfflineRegionStatus.PAUSED
                    else -> OfflineRegionStatus.READY
                }
            } catch (e: CancellationException) {
                runCatching { store.close() }
                OfflineRegionStatus.PAUSED
            } catch (t: Throwable) {
                runCatching { store.close() }
                OfflineRegionStatus.FAILED
            }

            val errorMsg = if (result == OfflineRegionStatus.FAILED) "网络或存储异常，可重试续传" else null
            OfflineRegionStore.updateStatus(regionId, result, errorMsg)
            currentRegionId = null
            job = null
            onFinish(result, errorMsg)
        }
    }

    /** 取消/暂停当前区域（续传时跳过已下过的瓦片，所以"暂停"实现为取消 job） */
    fun cancel(regionId: String) {
        if (currentRegionId != regionId) return
        job?.cancel()
        job = null
        currentRegionId = null
        val r = OfflineRegionStore.byId(regionId)
        if (r != null && r.status == OfflineRegionStatus.DOWNLOADING) {
            OfflineRegionStore.updateStatus(regionId, OfflineRegionStatus.PAUSED)
        }
    }

    fun cancelAll() {
        job?.cancel()
        job = null
        currentRegionId = null
    }

    /**
     * 抓一张瓦片。复合图层（如天地图"矢量+注记"）逐层下载后**合成为一张 PNG**：
     * MBTiles 一格只能存一张图，而离线渲染时 osmdroid 只会取一次，所以必须在下载阶段合并。
     */
    private fun fetchTile(
        coord: TileCoord,
        templates: List<String>,
        source: MapSource,
        tk: String,
        headers: Map<String, String>
    ): ByteArray? {
        if (templates.size == 1) {
            val url = renderUrl(templates[0], coord, source, tk)
            return TileHttp.download(url, headers)
        }
        val parts = ArrayList<ByteArray>(templates.size)
        for (tpl in templates) {
            val url = renderUrl(tpl, coord, source, tk)
            val b = TileHttp.download(url, headers) ?: return null   // 任一层失败 → 整张放弃
            parts.add(b)
        }
        return composeLayers(parts)
    }

    private fun renderUrl(tpl: String, coord: TileCoord, source: MapSource, tk: String): String =
        TileHttp.renderUrl(
            tpl,
            coord.x.toString(),
            coord.y.toString(),
            coord.zoom,
            coord.x,
            coord.y,
            source.subdomains,
            tk
        )

    /**
     * 把多层 PNG 叠成一张（前者在下、后者在上）。
     * 透明层（注记层 cva_w/cia_w/cta_w）本身带 alpha，直接 drawBitmap 即可正确叠加。
     */
    fun composeLayers(parts: List<ByteArray>): ByteArray? {
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts[0]
        return runCatching {
            var base: Bitmap? = null
            for (p in parts) {
                val bmp = BitmapFactory.decodeByteArray(p, 0, p.size) ?: return null
                if (base == null) {
                    base = bmp.copy(Bitmap.Config.ARGB_8888, true)
                    bmp.recycle()
                } else {
                    val canvas = Canvas(base)
                    canvas.drawBitmap(bmp, 0f, 0f, null)
                    bmp.recycle()
                }
            }
            val b = base ?: return null
            val bos = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.PNG, 100, bos)
            b.recycle()
            bos.toByteArray()
        }.getOrNull()
    }
}
