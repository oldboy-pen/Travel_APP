package com.example.myfirstapp.mapsources

import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 第三方图源导入解析器。
 *
 * 支持的来源（按内容自动识别，无需用户选格式）：
 * 1. 两步路 `.xms/.xml`：`<onlinemaps><onlinemap>` 结构，可含多个图层；
 * 2. 奥维旧版自定义地图 `.xml`：宽松识别 `<url>/<name>/<minLevel>` 等；
 * 3. 奥维新版 `.ovmap`：JSON 结构 `{"name","url","minLevel","maxLevel"}`；
 * 4. 通用 XYZ 文本：每行一个含 `{x}/{y}/{z}` 的瓦片地址。
 *
 * 统一做四件事：
 * - 占位符归一化：`{$x}`/`{$z}`/`{$s}`/`{host}` → 本 App 的 `{x}`/`{z}`/`{s}`，`&amp;` → `&`；
 * - 多服务器/多 URL 合并为 `{s}` 子域轮换；
 * - 坐标系按字段/文本粗判（默认 GCJ-02，国内源最常用）；
 * - 多图层（两步路多个 onlinemap）输出多个 MapSource。
 */
object MapSourceImporter {

    /** 入口：根据文本自动识别格式，返回解析出的图源（可能为空） */
    fun importText(raw: String): List<MapSource> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        return runCatching {
            when {
                text.startsWith("{") || text.startsWith("[") -> parseJson(text)
                text.startsWith("<") -> parseXml(text)
                else -> parseXyzLines(text)
            }
        }.getOrDefault(emptyList())
    }

    // ==================== 占位符 / 坐标系工具 ====================

    fun normalizeUrl(url: String): String = url
        .replace("&amp;", "&", ignoreCase = true)
        // 奥维/两步路的 {$x}/{$y}/{$z}/{$s}（大小写不敏感）→ 本 App 的 {x}/{y}/{z}/{s}
        .replace(Regex("\\{\\$?([xyzts])\\}", RegexOption.IGNORE_CASE)) { m -> "{${m.groupValues[1].lowercase()}}" }
        // 两步路 {host} 占位符 → {s}（子域轮换由解析器在 url 外补 subdomains）
        .replace(Regex("\\{host\\}", RegexOption.IGNORE_CASE), "{s}")

    private fun crsFromText(s: String): TileCrs {
        val t = s.lowercase()
        return when {
            t.contains("bd09") || t.contains("baidu") -> TileCrs.BD09
            t.contains("wgs") || t.contains("4326") || t.contains("epsg") -> TileCrs.WGS84
            else -> TileCrs.GCJ02
        }
    }

    /** 多 URL/多服务器合并：仅当差异是单字符时合并成 {s}+子域，否则退化取第一个 */
    private fun mergeUrls(urls: List<String>): Pair<String, String> {
        if (urls.size == 1) return urls[0] to ""
        val first = urls[0]
        val last = urls.last()
        var pre = 0
        while (pre < first.length && pre < last.length && first[pre] == last[pre]) pre++
        var suf = 0
        while (suf < first.length - pre && suf < last.length - pre &&
            first[first.length - 1 - suf] == last[last.length - 1 - suf]
        ) suf++
        val prefix = first.substring(0, pre)
        val suffix = first.substring(first.length - suf)
        val mids = urls.map { it.substring(pre, it.length - suf) }
        return if (mids.all { it.length == 1 }) {
            (prefix + "{s}" + suffix) to mids.joinToString("")
        } else {
            first to ""
        }
    }

    /** 两步路 servers 字符串提取子域（差异单字符才合并），否则返回 null 由调用方退化处理 */
    private fun buildSubdomains(subs: List<String>): String? {
        if (subs.size < 2) return null
        val (_, sub) = mergeUrls(subs)
        return sub.ifEmpty { null }
    }

    // ==================== XML：两步路 + 奥维旧版 ====================

    private fun parseXml(text: String): List<MapSource> {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(StringReader(text))
        val out = ArrayList<MapSource>()
        var inMap = false
        var parentTag = ""
        var name = ""; var url = ""; var minZ = 3; var maxZ = 18
        var crs = TileCrs.GCJ02; var servers = ""

        var event = p.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = p.getName()
                    if (tag.equals("onlinemap", true)) {
                        inMap = true
                        name = ""; url = ""; minZ = 3; maxZ = 18; crs = TileCrs.GCJ02; servers = ""
                    }
                    parentTag = tag
                }
                XmlPullParser.TEXT -> {
                    val t = p.text.trim()
                    if (t.isNotEmpty()) {
                        when (parentTag.lowercase()) {
                            "onlinemaps" -> Unit // 容器，无数据
                            "name" -> if (inMap) name = t
                            "url" -> if (inMap) url = t
                            "minzoom", "minlevel", "min_z", "minz" -> if (inMap) minZ = t.toIntOrNull()?.coerceIn(1, 22) ?: minZ
                            "maxzoom", "maxlevel", "max_z", "maxz" -> if (inMap) maxZ = t.toIntOrNull()?.coerceIn(1, 22) ?: maxZ
                            "coordinate", "crs", "gcj02", "wgs84", "mercator" -> if (inMap) crs = crsFromText(t)
                            "server", "servers", "host", "hosts" -> if (inMap) servers = t
                            // 奥维旧版字段（不在 onlinemap 内）
                            "mapname", "title" -> if (!inMap) name = t
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.getName().equals("onlinemap", true)) {
                        if (url.isNotBlank()) out += buildTwoBulu(name, url, minZ, maxZ, crs, servers)
                        inMap = false
                    }
                }
            }
            event = p.next()
        }
        // 没解析到 onlinemap，尝试奥维旧版 XML（宽松识别 url/name/minLevel）
        if (out.isEmpty()) out += parseOruxXml(text)
        return out
    }

    private fun buildTwoBulu(
        name: String, urlRaw: String, minZ: Int, maxZ: Int, crs: TileCrs, servers: String
    ): MapSource {
        var url = normalizeUrl(urlRaw)
        val subs = servers.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val finalSubs = if (url.contains("{s}")) {
            when {
                subs.size >= 2 -> buildSubdomains(subs) ?: run { url = url.replaceFirst("{s}", subs[0]); "" }
                subs.size == 1 -> { url = url.replaceFirst("{s}", subs[0]); "" }
                else -> "" // 无服务器列表：引擎默认 {s}→"0"
            }
        } else ""
        return MapSource(
            id = "",
            name = name.ifBlank { "导入图源" },
            urlTemplate = url,
            subdomains = finalSubs,
            crs = crs,
            minZoom = minZ,
            maxZoom = maxZ,
            isOverlay = false
        )
    }

    /** 奥维旧版自定义地图 XML（字段名多样，宽松匹配） */
    private fun parseOruxXml(text: String): List<MapSource> {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(StringReader(text))
        var name = ""
        val urls = ArrayList<String>()
        var minZ = 3; var maxZ = 18; var parentTag = ""
        var hasUrl = false

        var event = p.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> parentTag = p.getName()
                XmlPullParser.TEXT -> {
                    val t = p.text.trim()
                    if (t.isNotEmpty()) when (parentTag.lowercase()) {
                        "name", "mapname", "title" -> name = t
                        "url", "tileurl", "tilesurl" -> { if (!urls.contains(t)) urls += t; hasUrl = true }
                        "minzoom", "minlevel", "min_zoom", "min" -> minZ = t.toIntOrNull()?.coerceIn(1, 22) ?: minZ
                        "maxzoom", "maxlevel", "max_zoom", "max" -> maxZ = t.toIntOrNull()?.coerceIn(1, 22) ?: maxZ
                        "projection", "mercator", "coordinate", "crs" -> Unit // 坐标系在循环后按全文粗判
                    }
                }
            }
            event = p.next()
        }
        if (!hasUrl || urls.isEmpty()) return emptyList()
        var crs = TileCrs.GCJ02
        if (crsFromText(text) != TileCrs.GCJ02) crs = crsFromText(text)
        val (url, subs) = mergeUrls(urls)
        return listOf(
            MapSource(
                id = "",
                name = name.ifBlank { "奥维图源" },
                urlTemplate = normalizeUrl(url),
                subdomains = subs,
                crs = crs,
                minZoom = minZ,
                maxZoom = maxZ,
                isOverlay = false
            )
        )
    }

    // ==================== JSON：奥维 .ovmap ====================

    private fun parseJson(text: String): List<MapSource> {
        val o = JSONObject(text)
        val arr = if (o.optJSONArray("maps") != null) o.getJSONArray("maps") else null
        val objs = if (arr != null) (0 until arr.length()).map { arr.getJSONObject(it) } else listOf(o)
        return objs.mapNotNull { jo ->
            val url = jo.optString("url", "").ifBlank { return@mapNotNull null }
            val name = jo.optString("name", "奥维图源").ifBlank { "奥维图源" }
            val minZ = (jo.optInt("minLevel", -1).takeIf { it > 0 } ?: jo.optInt("minZoom", 3)).coerceIn(1, 22)
            val maxZ = (jo.optInt("maxLevel", -1).takeIf { it > 0 } ?: jo.optInt("maxZoom", 18)).coerceIn(1, 22)
            val crs = crsFromText(jo.optString("crs", "") + jo.optString("projection", "") + jo.optString("mercator", ""))
            MapSource(
                id = "",
                name = name,
                urlTemplate = normalizeUrl(url),
                crs = crs,
                minZoom = minZ,
                maxZoom = maxZ,
                isOverlay = false
            )
        }
    }

    // ==================== 纯文本 XYZ 行 ====================

    private fun parseXyzLines(text: String): List<MapSource> {
        val lines = text.lines().map { it.trim() }
            .filter { it.contains("http") && it.contains("{") }
        if (lines.isEmpty()) return emptyList()
        return lines.mapIndexed { i, u ->
            val host = runCatching { java.net.URI(u).host }.getOrNull() ?: "自定义图源$i"
            MapSource(
                id = "",
                name = host.removePrefix("www."),
                urlTemplate = normalizeUrl(u),
                crs = TileCrs.WGS84, // 通用 XYZ 多为国际源（OSM/天地图类），导入确认框可改
                isOverlay = false
            )
        }
    }
}
