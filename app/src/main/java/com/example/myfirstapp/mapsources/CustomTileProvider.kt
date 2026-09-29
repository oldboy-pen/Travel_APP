package com.example.myfirstapp.mapsources

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.amap.api.maps.model.Tile
import com.amap.api.maps.model.TileProvider
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 自定义瓦片 Provider：把 XYZ URL 模板图源接入高德地图。
 *
 * 三种坐标系处理：
 * - GCJ02：URL 直接替换占位符下载（腾讯/高德系瓦片，网格与高德一致）；
 * - WGS84：天地图等。高德按 GCJ-02 请求瓦片，需逐像素 GCJ→WGS 反算后
 *   在 WGS84 网格中取样，否则偏 300~600 米；
 * - BD09：百度。需 GCJ→BD09→百度墨卡托(LL2MC)→百度瓦片网格（原点在赤道/
 *   本初子午线，y 向上，z18=1m/px）逐像素重投影。
 *
 * 重投影性能：目标瓦片内坐标系偏移场变化平缓，先在 17×17 网格点做精确换算，
 * 中间像素双线性插值（误差远小于 1 像素），单瓦片耗时毫秒级。
 *
 * 高德 SDK 在子线程调用 getTile（其自带 UrlTileProvider 即阻塞网络下载），
 * 本类同样允许阻塞。
 */
class CustomTileProvider(
    private val source: MapSource,
    private val tkProvider: () -> String
) : TileProvider {

    companion object {
        private const val TAG = "CustomTileProvider"
        private const val TILE = 256
        private const val GRID_STEP = 16          // 网格点间距（像素）
        private const val N_GRID = TILE / GRID_STEP + 1

        /** 源瓦片像素缓存（全图源共享）：key = "sourceId:z:x:y"。
         *  sizeOf 按 KB 计（256×256×4 ≈ 256KB/张），maxSize 16MB ≈ 64 张。
         *  注意 maxSize 必须远大于单张 KB 数，否则 put 即被逐出、缓存完全失效。 */
        private val tileCache = object : LruCache<String, IntArray>(16 * 1024) {
            override fun sizeOf(key: String, value: IntArray) = value.size / 1024 // KB
        }
    }

    override fun getTileWidth() = TILE
    override fun getTileHeight() = TILE

    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        if (zoom < source.minZoom || zoom > source.maxZoom) return TileProvider.NO_TILE
        return try {
            when (source.crs) {
                TileCrs.GCJ02 -> fetchDirect(x, y, zoom)
                TileCrs.WGS84, TileCrs.BD09 -> reprojected(x, y, zoom)
            }
        } catch (t: Throwable) {
            // 失败打日志便于真机排查（图源空白时过滤 "TileProvider" 即可看到具体原因）
            android.util.Log.w(TAG, "tile fail id=${source.id} z=$zoom x=$x y=$y: ${t}")
            TileProvider.NO_TILE
        }
    }

    // ==================== GCJ02：直接下载 ====================

    private fun fetchDirect(x: Int, y: Int, zoom: Int): Tile? {
        val url = renderUrl(source.urlTemplate, x, y, zoom)
        val bytes = download(url) ?: return TileProvider.NO_TILE
        return Tile.obtain(TILE, TILE, bytes)
    }

    /** 渲染 URL 模板；{s} 用 (x+y) 轮换子域名做简单负载均衡 */
    private fun renderUrl(template: String, x: Int, y: Int, zoom: Int): String =
        renderUrlWithCoords(template, x.toString(), y.toString(), zoom, x, y)

    // ==================== WGS84 / BD09：逐像素重投影 ====================

    private fun reprojected(x: Int, y: Int, zoom: Int): Tile? {
        // 1. 17×17 网格点精确换算：目标瓦片像素 → 源瓦片全球像素
        val gx = DoubleArray(N_GRID * N_GRID)
        val gy = DoubleArray(N_GRID * N_GRID)
        for (j in 0 until N_GRID) {
            for (i in 0 until N_GRID) {
                val px = (i * GRID_STEP).coerceAtMost(TILE - 1)
                val py = (j * GRID_STEP).coerceAtMost(TILE - 1)
                val sp = sourcePixelOf(x, y, zoom, px, py)
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
                dest[py * TILE + px] = sampleSourcePixel(fx, fy, zoom)
            }
        }

        // 3. 编码为 PNG 瓦片
        val bmp = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        bmp.setPixels(dest, 0, TILE, 0, 0, TILE, TILE)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return Tile.obtain(TILE, TILE, bos.toByteArray())
    }

    /**
     * 目标瓦片内像素 → 源瓦片全球像素坐标（浮点）。
     * 目标网格是高德的 GCJ-02 Web 墨卡托（y 向下）。
     */
    private fun sourcePixelOf(x: Int, y: Int, zoom: Int, px: Int, py: Int): DoubleArray {
        val gcj = GeoTransform.tilePixelToLatLng(x, y, zoom, px, py)
        return when (source.crs) {
            TileCrs.WGS84 -> {
                val wgs = GeoTransform.gcj02ToWgs84(gcj[0], gcj[1])
                GeoTransform.latLngToGlobalPixel(wgs[1], wgs[0], zoom) // y 向下
            }
            TileCrs.BD09 -> {
                val bd = GeoTransform.gcj02ToBd09(gcj[0], gcj[1])
                val mc = GeoTransform.bd09ToBd09Mc(bd[0], bd[1])
                GeoTransform.bd09McToBaiduPixel(mc[0], mc[1], zoom)    // y 向上
            }
            TileCrs.GCJ02 -> doubleArrayOf(
                x * TILE + px.toDouble(), y * TILE + py.toDouble()
            )
        }
    }

    /** 源全球像素（浮点，坐标系取决于 crs）→ 最近邻取样像素颜色；取不到返回透明 */
    private fun sampleSourcePixel(fx: Double, fy: Double, zoom: Int): Int {
        val tileX = floor(fx / TILE).toInt()
        val tileY = floor(fy / TILE).toInt()
        val inX = (fx - tileX * TILE).roundToInt().coerceIn(0, TILE - 1)
        val inY = (fy - tileY * TILE).roundToInt().coerceIn(0, TILE - 1)
        val pixels = sourceTilePixels(tileX, tileY, zoom) ?: return 0
        return pixels[inY * TILE + inX]
    }

    /** 下载源瓦片并解出像素（带全局缓存） */
    private fun sourceTilePixels(tileX: Int, tileY: Int, zoom: Int): IntArray? {
        val key = "${source.id}:$zoom:$tileX:$tileY"
        synchronized(tileCache) { tileCache.get(key) }?.let { return it }

        // BD09 网格负瓦片号需加 M 前缀；WGS84/XYZ 不会有负号（超出±180°/±85° 无瓦片）
        val xStr: String
        val yStr: String
        if (source.crs == TileCrs.BD09) {
            xStr = GeoTransform.baiduTileCoord(tileX)
            yStr = GeoTransform.baiduTileCoord(tileY)
        } else {
            if (tileX < 0 || tileY < 0 ||
                tileX >= (1 shl zoom) || (source.crs == TileCrs.WGS84 && tileY >= (1 shl zoom))
            ) return null
            xStr = tileX.toString()
            yStr = tileY.toString()
        }
        val url = renderUrlWithCoords(source.urlTemplate, xStr, yStr, zoom, tileX, tileY)
        val bytes = download(url) ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val pixels = IntArray(TILE * TILE)
        bmp.getPixels(pixels, 0, TILE, 0, 0, TILE, TILE)
        bmp.recycle()
        synchronized(tileCache) { tileCache.put(key, pixels) }
        return pixels
    }

    /** 与 renderUrl 相同，但允许覆盖 x/y 的字符串形式（百度 M 前缀用） */
    private fun renderUrlWithCoords(
        template: String, xStr: String, yStr: String, zoom: Int,
        rawX: Int, rawY: Int
    ): String {
        val tmsY = if (zoom in 0..30) GeoTransform.tmsY(rawY, zoom) else rawY
        var url = template
            .replace("{z}", zoom.toString())
            .replace("{x}", xStr)
            .replace("{y}", yStr)
            .replace("{-y}", tmsY.toString())
            .replace("{sx}", (abs(rawX) shr 4).toString())
            .replace("{sy}", (abs(tmsY) shr 4).toString())
            .replace("{tk}", tkProvider())
        if (url.contains("{s}")) {
            val subs = source.subdomains
            val s = if (subs.isEmpty()) "0" else subs[((abs(rawX) + abs(rawY)) % subs.length)].toString()
            url = url.replace("{s}", s)
        }
        return url
    }

    // ==================== 网络下载 ====================

    /**
     * 下载瓦片：失败自动重试一次（弱网/CDN 偶发 RST 场景明显提升成功率）。
     * 仍失败时打日志（不抛异常，上层按 NO_TILE 处理显示透明瓦片）。
     */
    private fun download(url: String): ByteArray? {
        repeat(2) { attempt ->
            val bytes = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                // 自定义请求头（防盗链）：Referer / 自定义 UA / Authorization 等，覆盖默认值
                for ((k, v) in source.headers) {
                    conn.setRequestProperty(k, v)
                }
                try {
                    if (conn.responseCode != 200) {
                        if (attempt == 1) android.util.Log.w(TAG, "HTTP ${conn.responseCode} $url")
                        null
                    } else {
                        conn.inputStream.use { input ->
                            val bos = ByteArrayOutputStream()
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                bos.write(buf, 0, n)
                            }
                            if (bos.size() == 0) null else bos.toByteArray()
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (bytes != null) return bytes
            if (attempt == 0) android.util.Log.w(TAG, "download fail, retry: $url")
        }
        return null
    }
}
