package com.example.myfirstapp.mapsources

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 瓦片栅格化核心（引擎无关）：按「目标瓦片坐标系」渲染出 256×256 ARGB 像素数组。
 *
 * 目前仅被高德引擎的 [CustomTileProvider] 使用（目标网格固定 GCJ-02）：
 * - GCJ02 源：与高德同网格，直接下载原样返回；
 * - BD09 源：GCJ→BD09→百度墨卡托网格逐像素重投影。
 *
 * ★ WGS84 图源不再经过这里：它们由 **osmdroid 引擎原生渲染**（osmdroid 本就是 WGS-84 网格，
 *   直接按标准 XYZ 拼 URL 下载贴图，无需重投影），坐标系对齐在引擎边界用 GeoTransform 的点换算完成。
 *
 * 重投影性能（BD09 场景）：目标瓦片内坐标系偏移场变化平缓，先在 17×17 网格点做精确换算，
 * 中间像素双线性插值（误差远小于 1 像素），单瓦片耗时毫秒级。
 *
 * 允许在子线程调用（阻塞式下载，高德 SDK 的瓦片加载线程即如此）。
 */
object TileRasterizer {

    private const val TAG = "TileRasterizer"
    const val TILE = 256
    private const val GRID_STEP = 16          // 网格点间距（像素）
    private const val N_GRID = TILE / GRID_STEP + 1

    /** 源瓦片像素缓存（全图源共享）：key = "cacheKey:z:x:y"。
     *  sizeOf 按 KB 计（256×256×4 ≈ 256KB/张），maxSize 16MB ≈ 64 张。
     *  注意 maxSize 必须远大于单张 KB 数，否则 put 即被逐出、缓存完全失效。 */
    private val tileCache = object : LruCache<String, IntArray>(16 * 1024) {
        override fun sizeOf(key: String, value: IntArray) = value.size / 1024 // KB
    }

    /**
     * 渲染一个目标瓦片（composite：多模板从下到上 alpha 合成；单模板直接渲染）。
     * @param templates XYZ URL 模板列表（[MapSource.layers] 或单个 [MapSource.urlTemplate]）
     * @param sourceCrs 图源坐标系
     * @param targetCrs 目标网格坐标系（高德引擎=GCJ02 / osmdroid 引擎=WGS84）
     * @param subdomains {s} 子域轮换字符集
     * @param headers 自定义请求头（防盗链）
     * @param cacheKeyPrefix 源瓦片缓存 key 前缀（保证不同图源/图层不串缓存）
     * @param tkProvider 天地图 Key 提供者（{tk} 占位符）
     * @return 256×256 ARGB 像素数组；无法取到任何瓦片时返回 null
     */
    fun rasterize(
        templates: List<String>,
        x: Int,
        y: Int,
        zoom: Int,
        sourceCrs: TileCrs,
        targetCrs: TileCrs,
        subdomains: String,
        headers: Map<String, String>,
        cacheKeyPrefix: String,
        tkProvider: () -> String
    ): IntArray? {
        return try {
            var acc: IntArray? = null
            for (tpl in templates) {
                val layer = rasterizeSingle(tpl, x, y, zoom, sourceCrs, targetCrs,
                    subdomains, headers, cacheKeyPrefix, tkProvider) ?: continue
                if (acc == null) acc = layer else blend(acc, layer)
            }
            acc
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "rasterize fail $cacheKeyPrefix z=$zoom x=$x y=$y: $t")
            null
        }
    }

    /** 单模板渲染一个目标瓦片 */
    fun rasterizeSingle(
        template: String,
        x: Int,
        y: Int,
        zoom: Int,
        sourceCrs: TileCrs,
        targetCrs: TileCrs,
        subdomains: String,
        headers: Map<String, String>,
        cacheKeyPrefix: String,
        tkProvider: () -> String
    ): IntArray? =
        if (sourceCrs == targetCrs) {
            // 同坐标系：直接下载原样返回（无重投影）
            // 仍需传 crs：源瓦片号的格式化（BD09 负号要加 M 前缀）与越界判断都依赖它
            fetchDirectPixels(template, x, y, zoom, sourceCrs, subdomains, headers, cacheKeyPrefix, tkProvider)
        } else {
            reprojectedPixels(template, x, y, zoom, sourceCrs, targetCrs,
                subdomains, headers, cacheKeyPrefix, tkProvider)
        }

    // ==================== 直接下载（同坐标系） ====================

    private fun fetchDirectPixels(
        template: String, x: Int, y: Int, zoom: Int, crs: TileCrs,
        subdomains: String, headers: Map<String, String>,
        cacheKeyPrefix: String, tkProvider: () -> String
    ): IntArray? {
        if (x < 0 || y < 0 || x >= (1 shl zoom) || y >= (1 shl zoom)) return null
        return sourceTilePixels(template, x, y, zoom, crs, subdomains, headers, cacheKeyPrefix, tkProvider)
    }

    // ==================== 逐像素重投影（跨坐标系） ====================

    /** 单图层重投影，返回 256×256 ARGB 像素数组 */
    private fun reprojectedPixels(
        template: String, x: Int, y: Int, zoom: Int,
        sourceCrs: TileCrs, targetCrs: TileCrs,
        subdomains: String, headers: Map<String, String>,
        cacheKeyPrefix: String, tkProvider: () -> String
    ): IntArray {
        // 1. 17×17 网格点精确换算：目标瓦片像素 → 源瓦片全球像素
        val gx = DoubleArray(N_GRID * N_GRID)
        val gy = DoubleArray(N_GRID * N_GRID)
        for (j in 0 until N_GRID) {
            for (i in 0 until N_GRID) {
                val px = (i * GRID_STEP).coerceAtMost(TILE - 1)
                val py = (j * GRID_STEP).coerceAtMost(TILE - 1)
                val sp = sourcePixelOf(x, y, zoom, px, py, sourceCrs, targetCrs)
                gx[j * N_GRID + i] = sp[0]
                gy[j * N_GRID + i] = sp[1]
            }
        }

        // 2. 逐像素双线性插值取样
        val dest = IntArray(TILE * TILE)
        for (py in 0 until TILE) {
            val fj = (py.toFloat() / GRID_STEP).coerceAtMost(N_GRID - 1.001f)
            val j0 = fj.toInt(); val jw = fj - j0
            for (px in 0 until TILE) {
                val fi = (px.toFloat() / GRID_STEP).coerceAtMost(N_GRID - 1.001f)
                val i0 = fi.toInt(); val iw = fi - i0
                val s00 = j0 * N_GRID + i0
                val fx = gx[s00] * (1 - iw) * (1 - jw) + gx[s00 + 1] * iw * (1 - jw) +
                        gx[s00 + N_GRID] * (1 - iw) * jw + gx[s00 + N_GRID + 1] * iw * jw
                val fy = gy[s00] * (1 - iw) * (1 - jw) + gy[s00 + 1] * iw * (1 - jw) +
                        gy[s00 + N_GRID] * (1 - iw) * jw + gy[s00 + N_GRID + 1] * iw * jw
                dest[py * TILE + px] = sampleSourcePixel(
                    template, fx, fy, zoom, sourceCrs,
                    subdomains, headers, cacheKeyPrefix, tkProvider
                )
            }
        }
        return dest
    }

    /**
     * 目标瓦片内像素 → 源瓦片全球像素坐标（浮点）。
     * 目标瓦片按 targetCrs 的标准 XYZ Web 墨卡托网格（y 向下）解析为经纬度，
     * 再换算到 sourceCrs（含 GCJ↔WGS 互转 / BD09 墨卡托网格）。
     */
    private fun sourcePixelOf(
        x: Int, y: Int, zoom: Int, px: Int, py: Int,
        sourceCrs: TileCrs, targetCrs: TileCrs
    ): DoubleArray {
        val target = GeoTransform.tilePixelToLatLng(x, y, zoom, px, py)  // [lng, lat]，targetCrs 坐标系
        return when (sourceCrs) {
            targetCrs -> doubleArrayOf(          // 同坐标系（防御分支，正常走 fetchDirect）
                x * TILE + px.toDouble(), y * TILE + py.toDouble()
            )
            TileCrs.WGS84 -> {                   // 目标必为 GCJ02：GCJ→WGS
                val wgs = GeoTransform.gcj02ToWgs84(target[0], target[1])
                GeoTransform.latLngToGlobalPixel(wgs[1], wgs[0], zoom)   // y 向下
            }
            TileCrs.GCJ02 -> {                   // 目标必为 WGS84：WGS→GCJ（正向公式，无需迭代）
                val gcj = GeoTransform.wgs84ToGcj02(target[0], target[1])
                GeoTransform.latLngToGlobalPixel(gcj[1], gcj[0], zoom)   // y 向下
            }
            TileCrs.BD09 -> {                    // 目标坐标（GCJ 或 WGS）→ BD09 → 百度墨卡托 → 百度瓦片像素
                val gcj = if (targetCrs == TileCrs.WGS84) {
                    GeoTransform.wgs84ToGcj02(target[0], target[1])
                } else target
                val bd = GeoTransform.gcj02ToBd09(gcj[0], gcj[1])
                val mc = GeoTransform.bd09ToBd09Mc(bd[0], bd[1])
                GeoTransform.bd09McToBaiduPixel(mc[0], mc[1], zoom)      // y 向上
            }
        }
    }

    /** 源全球像素（浮点，坐标系取决于 crs）→ 最近邻取样像素颜色；取不到返回透明 */
    private fun sampleSourcePixel(
        tpl: String, fx: Double, fy: Double, zoom: Int, crs: TileCrs,
        subdomains: String, headers: Map<String, String>,
        cacheKeyPrefix: String, tkProvider: () -> String
    ): Int {
        val tileX = floor(fx / TILE).toInt()
        val tileY = floor(fy / TILE).toInt()
        val inX = (fx - tileX * TILE).roundToInt().coerceIn(0, TILE - 1)
        val inY = (fy - tileY * TILE).roundToInt().coerceIn(0, TILE - 1)
        val pixels = sourceTilePixels(tpl, tileX, tileY, zoom, crs, subdomains, headers, cacheKeyPrefix, tkProvider)
            ?: return 0
        return pixels[inY * TILE + inX]
    }

    /** 下载源瓦片并解出像素（带全局缓存） */
    private fun sourceTilePixels(
        tpl: String, tileX: Int, tileY: Int, zoom: Int, crs: TileCrs,
        subdomains: String, headers: Map<String, String>,
        cacheKeyPrefix: String, tkProvider: () -> String
    ): IntArray? {
        val key = "$cacheKeyPrefix:${tpl.hashCode()}:$zoom:$tileX:$tileY"
        synchronized(tileCache) { tileCache.get(key) }?.let { return it }

        // BD09 网格负瓦片号需加 M 前缀；WGS84/XYZ 不会有负号（超出±180°/±85° 无瓦片）
        val xStr: String
        val yStr: String
        if (crs == TileCrs.BD09) {
            xStr = GeoTransform.baiduTileCoord(tileX)
            yStr = GeoTransform.baiduTileCoord(tileY)
        } else {
            if (tileX < 0 || tileY < 0 ||
                tileX >= (1 shl zoom) || (crs == TileCrs.WGS84 && tileY >= (1 shl zoom))
            ) return null
            xStr = tileX.toString()
            yStr = tileY.toString()
        }
        val url = TileHttp.renderUrl(tpl, xStr, yStr, zoom, tileX, tileY, subdomains, tkProvider())
        val bytes = TileHttp.download(url, headers) ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val pixels = IntArray(TILE * TILE)
        bmp.getPixels(pixels, 0, TILE, 0, 0, TILE, TILE)
        bmp.recycle()
        synchronized(tileCache) { tileCache.put(key, pixels) }
        return pixels
    }

    /** 把 src 以标准 source-over 方式合成到 dst 上（dst 被原地修改） */
    private fun blend(dst: IntArray, src: IntArray) {
        for (i in dst.indices) {
            val sa = (src[i] shr 24) and 0xFF
            if (sa == 0) continue
            if (sa == 255) { dst[i] = src[i]; continue }
            val da = (dst[i] shr 24) and 0xFF
            val sr = src[i] and 0xFF; val sg = (src[i] shr 8) and 0xFF; val sb = (src[i] shr 16) and 0xFF
            val dr = dst[i] and 0xFF; val dg = (dst[i] shr 8) and 0xFF; val db = (dst[i] shr 16) and 0xFF
            val na = sa + da * (255 - sa) / 255
            if (na == 0) { dst[i] = 0; continue }
            val nr = (sr * sa + dr * da * (255 - sa) / 255) / na
            val ng = (sg * sa + dg * da * (255 - sa) / 255) / na
            val nb = (sb * sa + db * da * (255 - sa) / 255) / na
            dst[i] = (na shl 24) or (nb shl 16) or (ng shl 8) or nr
        }
    }

    /** 像素数组 → Bitmap（osmdroid 侧瓦片模块用） */
    fun toBitmap(pixels: IntArray): Bitmap {
        val bmp = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, TILE, 0, 0, TILE, TILE)
        return bmp
    }
}
