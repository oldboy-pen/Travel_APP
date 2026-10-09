package com.example.myfirstapp.mapsources

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * 瓦片 URL 模板渲染 + HTTP 下载的引擎无关工具。
 *
 * 从 CustomTileProvider 抽出（原样搬迁，逻辑未动），供两条瓦片管线共用：
 * - 高德引擎：CustomTileProvider（TileRasterizer 目标网格 GCJ-02）
 * - osmdroid 引擎：原生 OnlineTileSourceBase（模板渲染）+ TileRasterizer（重投影叠加层）
 */
object TileHttp {

    private const val TAG = "TileHttp"

    /**
     * 渲染 XYZ URL 模板。占位符：
     * {z} {x} {y}（标准 XYZ）/{-y}（TMS 反转行）/{s}（子域名轮换）/
     * {sx} {sy}（腾讯 sateTiles 半瓦片索引）/{tk}（天地图 Key）
     *
     * @param xStr/yStr 允许覆盖字符串形式（百度 M 前缀负号用）；rawX/rawY 用于 {s}/{sx}/{sy} 计算
     */
    fun renderUrl(
        template: String,
        xStr: String,
        yStr: String,
        zoom: Int,
        rawX: Int,
        rawY: Int,
        subdomains: String,
        tk: String
    ): String {
        val tmsY = if (zoom in 0..30) GeoTransform.tmsY(rawY, zoom) else rawY
        var url = template
            .replace("{z}", zoom.toString())
            .replace("{x}", xStr)
            .replace("{y}", yStr)
            .replace("{-y}", tmsY.toString())
            .replace("{sx}", (abs(rawX) shr 4).toString())
            .replace("{sy}", (abs(tmsY) shr 4).toString())
            .replace("{tk}", tk)
        if (url.contains("{s}")) {
            val subs = subdomains
            val s = if (subs.isEmpty()) "0" else subs[(abs(rawX) + abs(rawY)) % subs.length].toString()
            url = url.replace("{s}", s)
        }
        return url
    }

    /**
     * 下载瓦片：失败自动重试一次（弱网/CDN 偶发 RST 场景明显提升成功率）。
     * 仍失败时打日志（不抛异常，上层按无瓦片处理显示空白/透明）。
     */
    fun download(url: String, headers: Map<String, String> = emptyMap()): ByteArray? {
        repeat(2) { attempt ->
            val bytes = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                // 自定义请求头（防盗链）：Referer / 自定义 UA / Authorization 等，覆盖默认值
                for ((k, v) in headers) {
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
