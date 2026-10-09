package com.example.myfirstapp.mapsources

import org.json.JSONObject

/**
 * 图源分享码内容编解码。
 * 条码（Code128）载荷：「URL|<urlTemplate>」，短小好扫，parse 可识别。
 * JSON 形式：字段 name/url/crs/minZoom/maxZoom/subdomains/overlay（长度超条码容量时用二维码）。
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
        .put("layers", org.json.JSONArray().apply { s.layers.forEach { put(it) } })
        .toString()

    /**
     * 条码（Code128）载荷：只带 URL，长度短、条带粗、易扫。
     * 复合图层取首个子层 URL；扫码端按「URL|xxx」识别，其余字段走默认值（对话框里可改）。
     */
    fun toBarcodePayload(s: MapSource): String = "URL|${s.layers.firstOrNull() ?: s.urlTemplate}"

    /**
     * 解析扫码内容。
     * @return 预填好的 MapSource（id 为空=新增）；内容无法识别返回 null。
     */
    fun parse(content: String): MapSource? {
        val text = content.trim()
        if (text.isEmpty()) return null
        // 两步路分享码：…/ntbulu?n=名称&d=&c=坐标系&u=<加密瓦片地址>&h=&mi=最小&ma=最大&t=&v=1
        // u 参数为两步路私有加密，离线无法解出瓦片地址；预填其余字段，URL 由用户在对话框里粘贴
        val ntIdx = text.indexOf("ntbulu?")
        if (ntIdx >= 0) {
            val params = runCatching {
                text.substring(ntIdx + "ntbulu?".length)
                    .split('&')
                    .mapNotNull { seg ->
                        val i = seg.indexOf('=')
                        if (i <= 0) null
                        else java.net.URLDecoder.decode(seg.substring(0, i), "UTF-8") to
                                java.net.URLDecoder.decode(seg.substring(i + 1), "UTF-8")
                    }.toMap()
            }.getOrDefault(emptyMap())
            val crs = when (params["c"]?.lowercase()) {
                "wgs84", "wgs-84" -> TileCrs.WGS84
                "bd09", "bd-09" -> TileCrs.BD09
                else -> TileCrs.GCJ02
            }
            return MapSource(
                id = "",
                name = params["n"]?.ifBlank { null }?.let { "$it(两步路)" } ?: "两步路图源",
                urlTemplate = "",
                crs = crs,
                minZoom = params["mi"]?.toIntOrNull()?.coerceIn(1, 22) ?: 3,
                maxZoom = params["ma"]?.toIntOrNull()?.coerceIn(1, 22) ?: 18
            )
        }
        // 条码载荷形式：URL|https://...
        if (text.startsWith("URL|")) {
            val url = text.substring(4)
            if (url.isBlank()) return null
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "扫码图源"
            return MapSource(
                id = "",
                name = host.removePrefix("www."),
                urlTemplate = url,
                crs = TileCrs.WGS84  // 裸 URL 多为国际源，默认 WGS84；确认对话框里可改
            )
        }
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
                val layers = runCatching {
                    val arr = o.optJSONArray("layers")
                    if (arr == null) emptyList() else
                        (0 until arr.length()).map { arr.getString(it) }
                }.getOrDefault(emptyList())
                return MapSource(
                    id = "",
                    name = o.optString("name", "扫码图源").ifBlank { "扫码图源" },
                    urlTemplate = url,
                    subdomains = o.optString("subdomains", ""),
                    crs = crs,
                    minZoom = o.optInt("minZoom", 3).coerceIn(1, 22),
                    maxZoom = o.optInt("maxZoom", 18).coerceIn(1, 22),
                    isOverlay = o.optBoolean("overlay", false),
                    layers = layers
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
