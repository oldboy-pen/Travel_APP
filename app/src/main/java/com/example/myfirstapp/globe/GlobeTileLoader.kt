package com.example.myfirstapp.globe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.example.myfirstapp.mapsources.TileCrs
import com.example.myfirstapp.mapsources.TileHttp
import com.example.myfirstapp.mapsources.TileRasterizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 瓦片种类：地表贴图 / 高程数据 */
enum class GlobeTileKind { SURFACE, DEM }

/** 一张已经解好（可以直接上传纹理）的瓦片位图 */
data class DecodedTile(
    val gen: Int,
    val kind: GlobeTileKind,
    val key: TileKey,
    val bitmap: Bitmap
)

/** 地表图集里的一格尺寸（像素） */
const val SURFACE_TILE_PX = 256
/** 高程图集里的一格尺寸（像素）：只需要粗糙的高低起伏，64 足够做山体阴影 */
const val DEM_TILE_PX = 64

/**
 * 瓦片下载 + 解码，全程在工作线程，不碰 GL。
 *
 * 解好的图丢进 [ready] 队列，由 GL 线程在每帧开头一次性取走上传。
 * 切图源时调用 [bumpGeneration]，旧任务的产物直接丢弃（generation 不匹配）。
 */
class GlobeTileLoader {

    private val generation = AtomicInteger(0)
    private val exec = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "globe-tile").apply { isDaemon = true }
    }
    /** key = pack(kind, z, x, y)，避免同一块瓦片重复下载 */
    private val inflight = ConcurrentHashMap<Long, Boolean>()
    /** 同一块瓦片失败后的冷却到期时间，防止失败的请求被无限重试 */
    private val failures = ConcurrentHashMap<Long, Long>()

    /** GL 线程从这里取货 */
    val ready = ConcurrentLinkedQueue<DecodedTile>()

    /** 下载失败的回包（工作线程回调）：渲染器据此判断是否干脆关掉山体阴影 */
    var failureListener: ((GlobeTileKind) -> Unit)? = null

    /** 还在跑的任务 / 待上传的结果：渲染器用它决定要不要再下一帧 */
    fun isBusy(): Boolean = inflight.isNotEmpty() || ready.isNotEmpty()

    /** 切图源 / 页面退出时自增，旧任务的产物不再上传 */
    fun bumpGeneration(): Int = generation.incrementAndGet()

    /** 请求一张地表瓦片 */
    fun requestSurface(key: TileKey, source: GlobeSource) {
        val gen = generation.get()
        if (!claim(GlobeTileKind.SURFACE, key)) return
        exec.execute {
            try {
                val bmp = decodeSurface(key, source)
                if (bmp != null) ready.add(DecodedTile(gen, GlobeTileKind.SURFACE, key, bmp))
                else onFailure(GlobeTileKind.SURFACE, key)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "decode surface $key failed", t)
                onFailure(GlobeTileKind.SURFACE, key)
            } finally {
                release(GlobeTileKind.SURFACE, key)
            }
        }
    }

    /** 请求一块高程瓦片（Terrarium 编码） */
    fun requestDem(key: TileKey) {
        val gen = generation.get()
        if (!claim(GlobeTileKind.DEM, key)) return
        exec.execute {
            try {
                val bmp = decodeDem(key)
                if (bmp != null) ready.add(DecodedTile(gen, GlobeTileKind.DEM, key, bmp))
                else onFailure(GlobeTileKind.DEM, key)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "decode dem $key failed", t)
                onFailure(GlobeTileKind.DEM, key)
            } finally {
                release(GlobeTileKind.DEM, key)
            }
        }
    }

    /** 页面退出：不再接受新任务 */
    fun shutdown() {
        generation.incrementAndGet()
        inflight.clear()
        exec.shutdownNow()
        drainReady()
    }

    /** 丢弃所有已解好但还没上传的位图（页面退出时避免 Bitmap 泄漏） */
    fun drainReady() {
        while (true) {
            val t = ready.poll() ?: break
            runCatching { t.bitmap.recycle() }
        }
    }

    // ==================== 内部 ====================

    /**
     * 复合图层按序合成到一张图上。
     * 底层先铺一层浅灰底：像天地图 cta_w 这种只画等高线和注记的透明层，
     * 直接叠在黑底上会变成一张黑图。
     */
    private fun decodeSurface(key: TileKey, source: GlobeSource): Bitmap? =
        if (source.crs == TileCrs.GCJ02) decodeReprojected(key, source) else decodeDirect(key, source)

    /**
     * GCJ-02 图源（高德 Web 瓦片）：逐像素重投影到球面的 WGS-84 墨卡托网格。
     *
     * 不能只做「瓦片号重映射」——GCJ 偏移约 500 m，z=11 一块瓦片约 19.6 km，
     * 偏移仅占 0.025 个瓦片号，取整后落回同一格，残余偏差仍有 7 像素左右，
     * 叠加的 GPS 轨迹会肉眼可见地偏离道路。复用 2D 地图的 [TileRasterizer]
     * （17×17 网格点精确换算 + 双线性插值，误差远小于 1 像素），源瓦片自带 LRU 缓存。
     */
    private fun decodeReprojected(key: TileKey, source: GlobeSource): Bitmap? {
        val pixels = TileRasterizer.rasterize(
            templates = source.templates,
            x = key.x,
            y = key.y,
            zoom = key.z,
            sourceCrs = TileCrs.GCJ02,
            targetCrs = TileCrs.WGS84,
            subdomains = source.subdomains,
            headers = source.headers,
            cacheKeyPrefix = "globe:${source.id}:${source.tk.hashCode()}",
            tkProvider = { source.tk }
        ) ?: return null
        return TileRasterizer.toBitmap(pixels)
    }

    /** WGS-84 图源（天地图/OpenTopoMap/自定义 XYZ）：网格一致，直接下载合成 */
    private fun decodeDirect(key: TileKey, source: GlobeSource): Bitmap? {
        val out = Bitmap.createBitmap(SURFACE_TILE_PX, SURFACE_TILE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(BLANK_COLOR)
        var any = false
        source.templates.indices.forEach { i ->
            val bytes = TileHttp.download(source.url(key, i), source.headers) ?: return@forEach
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@forEach
            val tile = Bitmap.createScaledBitmap(raw, SURFACE_TILE_PX, SURFACE_TILE_PX, true)
            if (tile !== raw) raw.recycle()
            canvas.drawBitmap(tile, 0f, 0f, null)
            tile.recycle()
            any = true
        }
        if (!any) {
            out.recycle()
            return null
        }
        return out
    }

    private fun decodeDem(key: TileKey): Bitmap? {
        val url = TileHttp.renderUrl(
            template = GlobeSources.DEM_URL,
            xStr = key.x.toString(),
            yStr = key.y.toString(),
            zoom = key.z,
            rawX = key.x,
            rawY = key.y,
            subdomains = "",
            tk = ""
        )
        val bytes = TileHttp.download(url) ?: return null
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val small = Bitmap.createScaledBitmap(raw, DEM_TILE_PX, DEM_TILE_PX, true)
        if (small !== raw) raw.recycle()
        return small
    }

    private fun claim(kind: GlobeTileKind, key: TileKey): Boolean {
        // 刚失败过的先压 20 秒：否则每一帧都会重试一次失败的请求（网络稳了还会自己恢复）
        val until = failures[pack(kind, key)] ?: 0L
        if (System.currentTimeMillis() < until) return false
        return inflight.putIfAbsent(pack(kind, key), true) == null
    }

    private fun onFailure(kind: GlobeTileKind, key: TileKey) {
        failures[pack(kind, key)] = System.currentTimeMillis() + FAIL_COOLDOWN_MS
        failureListener?.invoke(kind)
    }

    private fun release(kind: GlobeTileKind, key: TileKey) {
        inflight.remove(pack(kind, key))
    }

    private fun pack(kind: GlobeTileKind, key: TileKey): Long {
        val k = if (kind == GlobeTileKind.SURFACE) 0L else 1L
        return (k shl 56) or (key.z.toLong() shl 48) or (key.x.toLong() shl 24) or key.y.toLong()
    }

    companion object {
        const val TAG = "GlobeTileLoader"
        const val BLANK_COLOR = 0xFFECE9E4.toInt()
        const val FAIL_COOLDOWN_MS = 20_000L
    }
}
