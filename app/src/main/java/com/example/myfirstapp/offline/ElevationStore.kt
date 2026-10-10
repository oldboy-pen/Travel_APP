package com.example.myfirstapp.offline

import android.graphics.BitmapFactory
import android.util.LruCache
import com.example.myfirstapp.mapsources.TileHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor

/**
 * 离线高程（DEM）库：把 terrarium 高程栅格缓存到本地，供**无网查海拔**。
 *
 * ### 数据源与编码
 * terrarium 是 Mapzen / AWS 公开的地形栅格，每个像素用 RGB 编码一个海拔：
 *
 * ```
 * meters = (R * 256 + G + B / 256) - 32768
 * ```
 *
 * 覆盖全球、可直接 XYZ 取瓦片，且是**单张 PNG**（不像某些源要拆高低字节），
 * 移动端解码成本最低，所以选它。
 *
 * ### 为什么单独一个库
 * 高程与地图图源无关（同一个高程库服务所有图源），而且查询路径完全不同：
 * 地图瓦片交给 osmdroid 渲染，高程是我们自己解码算数值。混进区域存档会让
 * "删掉某个城市的离线地图"误删高程数据。
 *
 * ### 精度
 * 官方源最高 z15，但 z15 全球数据量过大，这里默认封顶 z12（一格约 10 km、
 * 每像素约 40 m）。查询时做双线性插值，实际海拔误差通常在几米量级，
 * 对户外 App 的"我在多少米海拔"足够。
 */
object ElevationStore {

    /** 下载/查询的最高级别：z13 ≈ 每像素 19 m，已接近 SRTM 原始 30 m 分辨率，再细没有真实信息 */
    const val MAX_ZOOM = 13

    /** 查询时向下找可用级别的最多尝试次数 */
    private const val ZOOM_FALLBACK = 4

    const val TILE_SIZE = 256

    /**
     * terrarium 瓦片模板（标准 XYZ）。国内直连 AWS S3 可能不稳，
     * 可在「离线地图 → 高程源」里换成自建镜像（保持 {z}/{x}/{y} 占位符）。
     */
    @Volatile
    var urlTemplate: String = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

    @Volatile
    private var db: MbTiles? = null

    /** 解码后的栅格缓存（一张 256×256 的 FloatArray = 256KB，缓存 24 张 ≈ 6MB） */
    private val cache = object : LruCache<String, FloatArray>(24) {
        override fun sizeOf(key: String, value: FloatArray): Int = value.size
    }

    private fun db(): MbTiles {
        db?.let { return it }
        val d = MbTiles.open(OfflineStorage.elevationFile)
        db = d
        return d
    }

    /** 覆盖模板后必须调用（清掉旧缓存句柄） */
    fun invalidate() {
        cache.evictAll()
        runCatching { db?.close() }
        db = null
    }

    fun clear() {
        invalidate()
        runCatching { MbTiles.open(OfflineStorage.elevationFile).deleteFile() }
    }

    fun count(): Long = runCatching { db().count() }.getOrDefault(0L)
    fun sizeBytes(): Long = runCatching { db().sizeBytes() }.getOrDefault(0L)

    // ==================== 下载 ====================

    /**
     * 下载一块区域的高程栅格。
     * @return 成功写入的瓦片数
     */
    suspend fun download(
        minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        minZoom: Int, maxZoom: Int,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        val store = db()
        val zMin = minZoom.coerceIn(1, MAX_ZOOM)
        val zMax = maxZoom.coerceIn(zMin, MAX_ZOOM)
        var done = 0
        val total = TileGrid.countTiles(minLat, minLon, maxLat, maxLon, zMin, zMax).toInt()
        val batch = ArrayList<Pair<TileCoord, ByteArray>>(64)
        TileGrid.enumerate(minLat, minLon, maxLat, maxLon, zMin, zMax).forEach { c ->
            if (store.hasTile(c.zoom, c.x, c.y)) {
                done++
                return@forEach
            }
            val url = urlTemplate
                .replace("{z}", c.zoom.toString())
                .replace("{x}", c.x.toString())
                .replace("{y}", c.y.toString())
            val bytes = TileHttp.download(url)
            if (bytes != null) {
                batch.add(c to bytes)
                done++
                if (batch.size >= 64) {
                    store.putTiles(batch)
                    batch.clear()
                }
            }
            onProgress?.invoke(done, total)
        }
        if (batch.isNotEmpty()) store.putTiles(batch)
        done
    }

    // ==================== 查询 ====================

    /**
     * 查询某点海拔（米）。
     *
     * @param lat/lon **WGS-84** 坐标；业务层的 GCJ-02 请先转好再传进来。
     * @return 海拔（米），该点无离线数据则返回 null。
     */
    fun elevationAt(lat: Double, lon: Double): Double? {
        val store = runCatching { db() }.getOrNull() ?: return null
        // 从最细的可用级别往下找：先试 z13，没有就 z12……（下载时可能只下了低级别）
        for (z in MAX_ZOOM downTo (MAX_ZOOM - ZOOM_FALLBACK)) {
            if (z < 1) break
            val world = TileGrid.size(z).toDouble() * TILE_SIZE
            val px = (lon + 180.0) / 360.0 * world
            val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
            // 标准 Web 墨卡托：ln(tan(φ) + sec(φ))
            val merc = kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad))
            val py = (1.0 - merc / Math.PI) / 2.0 * world
            val h = sampleBilinear(store, z, px, py)
            if (h != null) return h.toDouble()
        }
        return null
    }

    /** 该点是否有高程覆盖（不考虑插值，只要所在瓦片在库里） */
    fun hasCoverage(lat: Double, lon: Double, zoom: Int = MAX_ZOOM): Boolean {
        val store = runCatching { db() }.getOrNull() ?: return false
        val (x, y) = tileOf(lat, lon, zoom)
        return store.hasTile(zoom, x, y)
    }

    /** 已下载的级别（升序） */
    fun zooms(): List<Int> = runCatching { db().zooms() }.getOrDefault(emptyList())

    private fun tileOf(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> =
        TileGrid.lonToTileX(lon, zoom) to TileGrid.latToTileY(lat, zoom)

    /**
     * 在世界像素坐标 (px, py) 处做双线性插值。
     * 四个采样点可能落在 4 张不同瓦片上，逐点取（有 LRU 缓存，成本可控）。
     */
    private fun sampleBilinear(store: MbTiles, zoom: Int, px: Double, py: Double): Float? {
        val x0 = floor(px).toInt()
        val y0 = floor(py).toInt()
        val fx = (px - x0).toFloat()
        val fy = (py - y0).toFloat()
        val v00 = samplePixel(store, zoom, x0, y0) ?: return null
        val v10 = samplePixel(store, zoom, x0 + 1, y0) ?: return v00
        val v01 = samplePixel(store, zoom, x0, y0 + 1) ?: return v00
        val v11 = samplePixel(store, zoom, x0 + 1, y0 + 1) ?: return v00
        return (v00 * (1 - fx) * (1 - fy)) +
            (v10 * fx * (1 - fy)) +
            (v01 * (1 - fx) * fy) +
            (v11 * fx * fy)
    }

    /** 取世界像素 (wx, wy) 的高程；跨瓦片时自动定位到正确的瓦片 */
    private fun samplePixel(store: MbTiles, zoom: Int, wx: Int, wy: Int): Float? {
        val n = TileGrid.size(zoom)
        var tx = wx / TILE_SIZE
        var ty = wy / TILE_SIZE
        // 经度环绕 / 纬度越界：直接判为无数据（极区 terrarium 也无有效值）
        if (ty < 0 || ty >= n) return null
        tx = ((tx % n) + n) % n
        val ix = wx - tx * TILE_SIZE
        val iy = wy - ty * TILE_SIZE
        val grid = grid(store, zoom, tx, ty) ?: return null
        val idx = iy * TILE_SIZE + ix
        if (idx < 0 || idx >= grid.size) return null
        return grid[idx]
    }

    /** 解码（带缓存）一张 terrarium 瓦片为 256×256 的高程网格 */
    private fun grid(store: MbTiles, zoom: Int, x: Int, y: Int): FloatArray? {
        val key = "$zoom/$x/$y"
        cache.get(key)?.let { return it }
        val bytes = store.getTile(zoom, x, y) ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (bmp.width != TILE_SIZE || bmp.height != TILE_SIZE) {
            bmp.recycle()
            return null
        }
        val pixels = IntArray(TILE_SIZE * TILE_SIZE)
        bmp.getPixels(pixels, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
        bmp.recycle()
        val out = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = (r * 256.0f + g + b / 256.0f) - 32768.0f
        }
        cache.put(key, out)
        return out
    }
}
