package com.example.myfirstapp.track

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 轨迹仓库：本地持久化 + GPX 导入导出（两步路轨迹库的精简版）
 *
 * 存储：内部存储 filesDir/tracks/{id}.json（org.json 手动序列化，无需第三方库）
 */
class TrackRepository private constructor(context: Context) {

    private val dir: File = File(context.filesDir, "tracks").apply { mkdirs() }
    private val gpxDir: File = File(context.cacheDir, "gpx").apply { mkdirs() }

    // ---------- 本地存储 ----------

    fun save(track: Track) {
        val json = JSONObject().apply {
            put("id", track.id)
            put("name", track.name)
            put("startTime", track.startTime)
            put("endTime", track.endTime)
            put("distance", track.distanceMeters)
            put("duration", track.durationMillis)
            put("climb", track.climbMeters)
            put("points", JSONArray(track.points.map { p ->
                JSONObject().apply {
                    put("lat", p.latitude); put("lng", p.longitude)
                    put("t", p.time); put("alt", p.altitude); put("spd", p.speed.toDouble())
                }
            }))
            put("waypoints", JSONArray(track.waypoints.map { w ->
                JSONObject().apply {
                    put("name", w.name); put("lat", w.latitude)
                    put("lng", w.longitude); put("t", w.time)
                }
            }))
        }
        File(dir, "${track.id}.json").writeText(json.toString())
    }

    fun list(): List<Track> =
        dir.listFiles { f -> f.extension == "json" }?.mapNotNull { runCatching { read(it) }.getOrNull() }
            ?.sortedByDescending { it.startTime } ?: emptyList()

    fun load(id: String): Track? = File(dir, "$id.json").takeIf { it.exists() }?.let { runCatching { read(it) }.getOrNull() }

    fun delete(id: String) { File(dir, "$id.json").delete() }

    private fun read(f: File): Track {
        val json = JSONObject(f.readText())
        return Track(
            id = json.getString("id"),
            name = json.getString("name"),
            startTime = json.getLong("startTime"),
            endTime = json.getLong("endTime"),
            points = buildList {
                val arr = json.getJSONArray("points")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(TrackPoint(o.getDouble("lat"), o.getDouble("lng"), o.getLong("t"), o.getDouble("alt"), o.getDouble("spd").toFloat()))
                }
            },
            waypoints = buildList {
                val arr = json.getJSONArray("waypoints")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(Waypoint(o.getString("name"), o.getDouble("lat"), o.getDouble("lng"), o.getLong("t")))
                }
            },
            distanceMeters = json.getDouble("distance"),
            durationMillis = json.getLong("duration"),
            climbMeters = json.getDouble("climb")
        )
    }

    // ---------- GPX 导出（可导入两步路/奥维/Garmin/Basecamp 等） ----------

    fun exportGpx(track: Track): File {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="MyFirstApp" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata><name>${track.name}</name></metadata>
""")
        // 途经点 → <wpt>
        track.waypoints.forEach { w ->
            sb.append("  <wpt lat=\"${w.latitude}\" lon=\"${w.longitude}\">\n")
            sb.append("    <name>${w.name}</name>\n")
            sb.append("    <time>${fmt.format(Date(w.time))}</time>\n")
            sb.append("  </wpt>\n")
        }
        // 轨迹 → <trk><trkseg><trkpt>
        sb.append("  <trk><name>${track.name}</name><trkseg>\n")
        track.points.forEach { p ->
            sb.append("    <trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\">")
            sb.append("<ele>${"%.1f".format(p.altitude)}</ele>")
            sb.append("<time>${fmt.format(Date(p.time))}</time>")
            sb.append("</trkpt>\n")
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")

        val file = File(gpxDir, "${track.name.replace(Regex("[\\\\/:*?\"<>| ]"), "_")}.gpx")
        file.writeText(sb.toString())
        return file
    }

    // ---------- GPX 导入（选择 .gpx 文件解析入库） ----------

    fun importGpx(context: Context, uri: Uri): Track? = runCatching {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(context.contentResolver.openInputStream(uri), null)
        }
        val points = ArrayList<TrackPoint>()
        val wpts = ArrayList<Waypoint>()
        var name = "导入的轨迹"
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "trkpt" -> points.add(
                        TrackPoint(
                            latitude = parser.getAttributeValue(null, "lat").toDouble(),
                            longitude = parser.getAttributeValue(null, "lon").toDouble(),
                            time = 0L, altitude = 0.0, speed = 0f
                        )
                    )
                    "wpt" -> wpts.add(
                        Waypoint(
                            "途经点",
                            parser.getAttributeValue(null, "lat").toDouble(),
                            parser.getAttributeValue(null, "lon").toDouble(),
                            0L
                        )
                    )
                    "name" -> if (points.isEmpty() && wpts.isEmpty()) name = parser.nextText()
                }
            }
            event = parser.next()
        }
        require(points.size >= 2) { "GPX 中没有轨迹点" }

        // 导入的 GPX 可能无时间/海拔，重新计算距离
        var distance = 0.0
        for (i in 1 until points.size) {
            distance += GeoUtils.distance(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude
            )
        }
        Track(
            id = "import_" + System.currentTimeMillis(),
            name = name,
            startTime = 0L,
            endTime = 0L,
            points = points,
            waypoints = wpts,
            distanceMeters = distance,
            durationMillis = 0L,
            climbMeters = 0.0
        ).also { save(it) }
    }.getOrNull()

    companion object {
        @Volatile private var instance: TrackRepository? = null

        fun get(context: Context): TrackRepository =
            instance ?: synchronized(this) {
                instance ?: TrackRepository(context.applicationContext).also { instance = it }
            }
    }
}
