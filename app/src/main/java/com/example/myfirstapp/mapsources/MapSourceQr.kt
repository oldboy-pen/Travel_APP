package com.example.myfirstapp.mapsources

import org.json.JSONObject

/**
 * 图源二维码内容编解码。
 * 格式：JSON，字段 name/url/crs/minZoom/maxZoom/subdomains/overlay。
 * 兼容裸 URL（扫码结果不是 JSON 时按 URL 处理，其余字段默认）。
 */
object MapSourceQr {

    fun toJson(s: MapSource): String = JSONObject()
        .put("v", 1)
        .put("name", s.name)
        .put("url", s.urlTemplate)
        .put("crs", s.crs.name)
        .put("minZoom", s.minZoom)
        .put("maxZoom", s.maxZoom)
        .put("subdomains", s.subdomains)
        .put("overlay", s.isOverlay)
        .toString()

    /**
     * 解析扫码内容。
     * @return 预填好的 MapSource（id 为空=新增）；内容无法识别返回 null。
     */
    fun parse(content: String): MapSource? {
        val text = content.trim()
        if (text.isEmpty()) return null
        // JSON 形式
        if (text.startsWith("{")) {
            runCatching {
                val o = JSONObject(text)
                val url = o.optString("url", "")
                if (url.isBlank()) return null
                val crs = when (o.optString("crs", "GCJ02").uppercase()) {
                    "WGS84", "WGS-84", "WGS" -> TileCrs.WGS84
                    "BD09", "BD-09", "BD" -> TileCrs.BD09
                    else -> TileCrs.GCJ02
                }
                return MapSource(
                    id = "",
                    name = o.optString("name", "扫码图源").ifBlank { "扫码图源" },
                    urlTemplate = url,
                    subdomains = o.optString("subdomains", ""),
                    crs = crs,
                    minZoom = o.optInt("minZoom", 3).coerceIn(1, 22),
                    maxZoom = o.optInt("maxZoom", 18).coerceIn(1, 22),
                    isOverlay = o.optBoolean("overlay", false)
                )
            }
        }
        // 裸 URL 形式
        if (text.startsWith("http://") || text.startsWith("https://")) {
            val host = runCatching { java.net.URI(text).host }.getOrNull() ?: "扫码图源"
            return MapSource(
                id = "",
                name = host.removePrefix("www."),
                urlTemplate = text,
                crs = TileCrs.WGS84  // 裸 URL 多为国际源，默认 WGS84；确认对话框里可改
            )
        }
        return null
    }
}
