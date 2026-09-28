package com.example.myfirstapp.track

import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * ============ 轨迹导入导出格式（KML 轨迹 / KML 路径 / KMZ / GPX） ============
 *
 * - KML（轨迹）：<gx:Track>，每个坐标带 <when> 时间戳，适合 Google Earth 时间轴回放
 * - KML（路径）：<LineString>，纯几何折线，兼容性最好（奥维/图新地球等）
 * - KMZ：KML 的 ZIP 压缩包（单文件 doc.kml）
 * - GPX：见 [TrackRepository] 原有实现
 *
 * 本文件只放纯函数（构建 / 解析），不碰 Android Context，方便单元测试。
 */

/** 支持的轨迹文件格式 */
enum class TrackFileFormat(val label: String, val extension: String, val mimeType: String) {
    GPX("GPX 格式", "gpx", "application/gpx+xml"),
    KML_TRACK("KML 格式（轨迹）", "kml", "application/vnd.google-earth.kml+xml"),
    KML_PATH("KML 格式（路径）", "kml", "application/vnd.google-earth.kml+xml"),
    KMZ("KMZ 格式", "kmz", "application/vnd.google-earth.kmz");

    companion object {
        /** 按文件名后缀推断格式，无法识别时返回 null */
        fun fromFileName(name: String): TrackFileFormat? {
            val lower = name.lowercase(Locale.ROOT)
            return when {
                lower.endsWith(".gpx") -> GPX
                lower.endsWith(".kmz") -> KMZ
                lower.endsWith(".kml") -> KML_TRACK // 导入时轨迹/路径都会解析，格式仅作提示
                else -> null
            }
        }
    }
}

/** 文件解析结果：名称 + 轨迹点 + 途经点（GPX / KML / KMZ 通用） */
data class ParsedTrack(
    val name: String?,
    val points: List<TrackPoint>,
    val waypoints: List<Waypoint>
)

object TrackFormatIO {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    // ---------- KML 构建 ----------

    /**
     * 构建 KML 文本。
     * @param gxTrack true = 轨迹（gx:Track，带时间轴）；false = 路径（LineString，纯几何）
     */
    fun buildKml(track: Track, gxTrack: Boolean): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append('\n')
        sb.append("""<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">""")
        sb.append('\n')
        sb.append("<Document>\n  <name>${xmlEscape(track.name)}</name>\n")

        // 途经点 → Point Placemark
        track.waypoints.forEach { w ->
            sb.append("  <Placemark>\n")
            sb.append("    <name>${xmlEscape(w.name)}</name>\n")
            sb.append("    <Point><coordinates>${w.longitude},${w.latitude},0</coordinates></Point>\n")
            sb.append("  </Placemark>\n")
        }

        // 轨迹本体
        sb.append("  <Placemark>\n    <name>${xmlEscape(track.name)}</name>\n")
        if (gxTrack) {
            sb.append("    <gx:Track>\n")
            track.points.forEach { p ->
                if (p.time > 0) sb.append("      <when>${isoFormat.format(Date(p.time))}</when>\n")
                sb.append("      <gx:coord>${p.longitude} ${p.latitude} ${"%.1f".format(p.altitude)}</gx:coord>\n")
            }
            sb.append("    </gx:Track>\n")
        } else {
            sb.append("    <LineString>\n      <tessellate>1</tessellate>\n      <coordinates>\n")
            track.points.forEach { p ->
                sb.append("${p.longitude},${p.latitude},${"%.1f".format(p.altitude)} ")
            }
            sb.append("\n      </coordinates>\n    </LineString>\n")
        }
        sb.append("  </Placemark>\n</Document>\n</kml>\n")
        return sb.toString()
    }

    // ---------- GPX / KML / KMZ 解析 ----------

    /** 解析 GPX 流（trkpt/rtept + wpt，含 ele/time），返回 null 表示没有可用轨迹 */
    fun parseGpx(input: InputStream): ParsedTrack? {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(input, null)
        }
        val points = ArrayList<TrackPoint>()
        val waypoints = ArrayList<Waypoint>()
        var name: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "trkpt" -> parseGpxPoint(parser, points)
                    "rtept" -> parseGpxPoint(parser, points) // 路线点也并入轨迹
                    "wpt" -> parseGpxWaypoint(parser, waypoints)
                    "name" -> if (name == null && points.isEmpty() && waypoints.isEmpty()) {
                        name = parser.nextText()
                    }
                }
            }
            event = parser.next()
        }
        if (points.size < 2) return null
        return ParsedTrack(name, points, waypoints)
    }

    /** 解析 <trkpt lat= lon=>（含 <ele>/<time> 子元素），rtept 同构复用 */
    private fun parseGpxPoint(parser: XmlPullParser, out: MutableList<TrackPoint>) {
        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: return
        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: return
        var ele = 0.0
        var time = 0L
        var depth = 1
        var event = parser.next()
        while (depth > 0 && event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    // nextText() 会连 END_TAG 一起消费，不参与深度计数
                    "ele" -> ele = parser.nextText().toDoubleOrNull() ?: 0.0
                    "time" -> time = parseIsoTime(parser.nextText()) ?: 0L
                    else -> depth++
                }
                XmlPullParser.END_TAG -> depth--
            }
            event = parser.next()
        }
        out.add(TrackPoint(lat, lon, time, ele, 0f))
    }

    /** 解析 <wpt>（含 <name>/<time> 子元素） */
    private fun parseGpxWaypoint(parser: XmlPullParser, out: MutableList<Waypoint>) {
        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: return
        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: return
        var name: String? = null
        var time = 0L
        var depth = 1
        var event = parser.next()
        while (depth > 0 && event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "name" -> name = parser.nextText()
                    "time" -> time = parseIsoTime(parser.nextText()) ?: 0L
                    else -> depth++
                }
                XmlPullParser.END_TAG -> depth--
            }
            event = parser.next()
        }
        out.add(Waypoint(name ?: "途经点", lat, lon, time, WaypointType.TEXT))
    }

    /** 解析 KML 流，返回 null 表示文件中没有可用轨迹 */
    fun parseKml(input: InputStream): ParsedTrack? {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(input, null)
        }
        val points = ArrayList<TrackPoint>()
        val waypoints = ArrayList<Waypoint>()
        var docName: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    // KML 命名空间解析：gx:Track 的 name 可能带前缀
                    "Placemark" -> parsePlacemark(parser, points, waypoints)?.let { placemarkName ->
                        if (docName == null) docName = placemarkName
                    }
                    "name" -> if (docName == null && points.isEmpty()) {
                        docName = parser.nextText()
                    }
                }
            }
            event = parser.next()
        }
        if (points.size < 2) return null
        return ParsedTrack(docName, points, waypoints)
    }

    /** 解析 KMZ（ZIP 包），取包内第一个 .kml 条目解析 */
    fun parseKmz(input: InputStream): ParsedTrack? {
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith(".kml")) {
                    // 读出来再解析（zis 不能重复用）
                    return parseKml(zis.readBytes().inputStream())
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    /**
     * 解析一个 <Placemark> 子树：
     * - <Point><coordinates> → 途经点
     * - <LineString><coordinates> → 轨迹点（无时间）
     * - <gx:Track>（<when> + <gx:coord>）→ 轨迹点（带时间/海拔）
     * 返回 Placemark 名称（供整条轨迹命名）。
     */
    private fun parsePlacemark(
        parser: XmlPullParser,
        points: MutableList<TrackPoint>,
        waypoints: MutableList<Waypoint>
    ): String? {
        var name: String? = null
        var pointCoord: String? = null      // Point 的 coordinates 文本
        var depth = 1
        // gx:Track 解析状态
        var inGxTrack = false
        var inLineString = false
        var inPoint = false
        var pendingWhen: Long? = null

        fun addGxCoord(text: String) {
            val parts = text.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val lon = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                val alt = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                if (lon != null && lat != null) {
                    points.add(TrackPoint(lat, lon, pendingWhen ?: 0L, alt, 0f))
                }
            }
            pendingWhen = null
        }

        var event = parser.next()
        while (depth > 0 && event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    // 纯文本子元素：nextText() 连 END_TAG 一起消费，不参与深度计数
                    "name" -> if (name == null) name = parser.nextText()
                    "when" -> if (inGxTrack) pendingWhen = parseIsoTime(parser.nextText())
                    "gx:coord", "coord" -> if (inGxTrack) addGxCoord(parser.nextText())
                    "coordinates" -> {
                        val text = parser.nextText()
                        when {
                            inGxTrack -> { /* gx:Track 旧式 coordinates，忽略 */ }
                            inLineString -> points.addAll(parseLineStringCoords(text))
                            inPoint -> pointCoord = text
                        }
                    }
                    else -> {
                        depth++
                        when (parser.name) {
                            "gx:Track", "Track" -> inGxTrack = true
                            "LineString" -> inLineString = true
                            "Point" -> inPoint = true
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    depth--
                    when (parser.name) {
                        "gx:Track", "Track" -> inGxTrack = false
                        "LineString" -> inLineString = false
                        "Point" -> inPoint = false
                    }
                }
            }
            event = parser.next()
        }

        // Point → 途经点
        pointCoord?.trim()?.split(",")?.let { parts ->
            if (parts.size >= 2) {
                val lon = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                if (lon != null && lat != null) {
                    waypoints.add(
                        Waypoint(
                            name = name ?: "途经点",
                            latitude = lat,
                            longitude = lon,
                            time = 0L,
                            type = WaypointType.TEXT
                        )
                    )
                }
            }
        }
        return name
    }

    /** "lon,lat,alt lon,lat,alt ..." → 轨迹点列表 */
    private fun parseLineStringCoords(text: String): List<TrackPoint> =
        text.trim().split(Regex("\\s+")).mapNotNull { token ->
            val parts = token.split(",")
            if (parts.size < 2) return@mapNotNull null
            val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val alt = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            TrackPoint(lat, lon, 0L, alt, 0f)
        }

    /** 兼容常见 ISO8601 变体：整秒/毫秒 + Z 后缀/时区偏移/无时区（按 UTC）+ 纯日期 */
    private val isoParsers: List<SimpleDateFormat> = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd"
    ).map {
        SimpleDateFormat(it, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
    }

    private fun parseIsoTime(text: String): Long? {
        val t = text.trim()
        for (p in isoParsers) {
            runCatching { p.parse(t) }.getOrNull()?.let { return it.time }
        }
        return null
    }
}
