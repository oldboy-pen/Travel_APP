package com.example.myfirstapp.track

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        File(dir, "${track.id}.json").writeText(toJson(track).toString())
    }

    /** 序列化轨迹为 JSONObject（与服务器 /api/tracks 接收结构一致，供上传复用） */
    fun toJson(track: Track): JSONObject = JSONObject().apply {
        put("id", track.id)
        put("name", track.name)
        put("startTime", track.startTime)
        put("endTime", track.endTime)
        put("distance", track.distanceMeters)
        put("duration", track.durationMillis)
        put("climb", track.climbMeters)
        put("activityType", track.activityType.name)
        put("points", JSONArray(track.points.map { p ->
            JSONObject().apply {
                put("lat", p.latitude); put("lng", p.longitude)
                put("t", p.time); put("alt", p.altitude); put("spd", p.speed.toDouble())
            }
        }))
        put("waypoints", JSONArray(track.waypoints.map { w ->
            JSONObject().apply {
                put("name", w.name)
                put("lat", w.latitude)
                put("lng", w.longitude)
                put("t", w.time)
                put("type", w.type.name)
                put("text", w.text ?: "")
                put("mediaUri", w.mediaUri ?: "")
            }
        }))
    }

    fun list(): List<Track> =
        dir.listFiles { f -> f.extension == "json" }?.mapNotNull { runCatching { read(it) }.getOrNull() }
            ?.distinctBy { it.id }
            ?.sortedByDescending { it.startTime } ?: emptyList()

    fun load(id: String): Track? = File(dir, "$id.json").takeIf { it.exists() }?.let { runCatching { read(it) }.getOrNull() }

    fun delete(id: String) { File(dir, "$id.json").delete() }

    private fun read(f: File): Track = parseTrack(JSONObject(f.readText()))

    /**
     * 由 JSONObject 还原 Track。
     * 本地文件与服务器下发的完整轨迹 JSON 结构一致（上传用的就是 toJson），
     * 所以云端下载的轨迹也能走这里解析。
     */
    fun parseTrack(json: JSONObject): Track {
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
                    val type = runCatching { WaypointType.valueOf(o.optString("type", WaypointType.TEXT.name)) }
                        .getOrDefault(WaypointType.TEXT)
                    add(
                        Waypoint(
                            name = o.getString("name"),
                            latitude = o.getDouble("lat"),
                            longitude = o.getDouble("lng"),
                            time = o.getLong("t"),
                            type = type,
                            text = o.optString("text", "").takeIf { it.isNotEmpty() },
                            mediaUri = o.optString("mediaUri", "").takeIf { it.isNotEmpty() }
                        )
                    )
                }
            },
            distanceMeters = json.getDouble("distance"),
            durationMillis = json.getLong("duration"),
            climbMeters = json.getDouble("climb"),
            activityType = runCatching {
                ActivityType.valueOf(json.optString("activityType", ActivityType.DEFAULT.name))
            }.getOrDefault(ActivityType.DEFAULT)
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

    // ---------- 多格式导出（GPX / KML 轨迹 / KML 路径 / KMZ） ----------

    /** 按格式导出轨迹到 cacheDir/gpx（分享/另存用），文件名 = 轨迹名 + 格式后缀 */
    fun exportTrack(track: Track, format: TrackFileFormat): File {
        val safe = track.name.replace(Regex("[\\\\/:*?\"<>| ]"), "_")
        return when (format) {
            TrackFileFormat.GPX -> exportGpx(track)
            TrackFileFormat.KML_TRACK ->
                File(gpxDir, "$safe.kml").apply { writeText(TrackFormatIO.buildKml(track, gxTrack = true)) }
            TrackFileFormat.KML_PATH ->
                File(gpxDir, "$safe.kml").apply { writeText(TrackFormatIO.buildKml(track, gxTrack = false)) }
            TrackFileFormat.KMZ -> File(gpxDir, "$safe.kmz").apply {
                ZipOutputStream(outputStream().buffered()).use { zos ->
                    zos.putNextEntry(ZipEntry("doc.kml"))
                    zos.write(TrackFormatIO.buildKml(track, gxTrack = true).toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
        }
    }

    // ---------- 多格式导入（GPX / KML / KMZ，按文件名或内容自动识别） ----------

    /**
     * 从 Uri 导入轨迹：优先按文件名后缀选解析器，
     * 无法识别时嗅探内容（PK 头 = KMZ，含 <gpx = GPX，否则按 KML）。
     * 导入成功即入库（filesDir/tracks）并返回 Track，失败返回 null。
     */
    fun importTrack(context: Context, uri: Uri): Track? = runCatching {
        val displayName = queryDisplayName(context, uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取文件")

        val parsed = when (TrackFileFormat.fromFileName(displayName ?: "")) {
            TrackFileFormat.GPX -> TrackFormatIO.parseGpx(bytes.inputStream())
            TrackFileFormat.KMZ -> TrackFormatIO.parseKmz(bytes.inputStream())
            TrackFileFormat.KML_TRACK, TrackFileFormat.KML_PATH ->
                TrackFormatIO.parseKml(bytes.inputStream())
            null -> sniffAndParse(bytes)
        } ?: error("文件中没有可用轨迹")

        buildImportedTrack(
            name = parsed.name ?: displayName?.substringBeforeLast('.') ?: "导入的轨迹",
            points = parsed.points,
            waypoints = parsed.waypoints
        ).also { save(it) }
    }.getOrNull()

    /** ContentProvider 查询文件显示名（用于按后缀识别 + 兜底命名） */
    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** 无后缀时按内容嗅探：ZIP 魔数 PK → KMZ；含 <gpx → GPX；否则按 KML */
    private fun sniffAndParse(bytes: ByteArray): ParsedTrack? {
        val head = String(bytes.copyOfRange(0, minOf(1024, bytes.size)), Charsets.UTF_8)
        return when {
            bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
                TrackFormatIO.parseKmz(bytes.inputStream())
            head.contains("<gpx", ignoreCase = true) ->
                TrackFormatIO.parseGpx(bytes.inputStream())
            else -> TrackFormatIO.parseKml(bytes.inputStream())
        }
    }

    /** 由解析结果构建入库 Track：重算里程；时间/海拔齐全时补时长与爬升 */
    private fun buildImportedTrack(
        name: String,
        points: List<TrackPoint>,
        waypoints: List<Waypoint>
    ): Track {
        var distance = 0.0
        for (i in 1 until points.size) {
            distance += GeoUtils.distance(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude
            )
        }
        val timed = points.filter { it.time > 0 }
        val startTime = timed.minOfOrNull { it.time } ?: 0L
        val endTime = timed.maxOfOrNull { it.time } ?: 0L
        // 时长只在所有点都带时间时才有意义（缺时间的点会拉出虚假跨度）
        val duration =
            if (timed.size == points.size && endTime > startTime) endTime - startTime else 0L
        // 爬升：正向海拔差累加，0.5m 以下视为噪声
        val climb = if (points.any { it.altitude != 0.0 }) {
            var c = 0.0
            for (i in 1 until points.size) {
                val d = points[i].altitude - points[i - 1].altitude
                if (d > 0.5) c += d
            }
            c
        } else 0.0

        return Track(
            id = "import_" + UUID.randomUUID(),
            name = name,
            startTime = startTime,
            endTime = endTime,
            points = points,
            waypoints = waypoints,
            distanceMeters = distance,
            durationMillis = duration,
            climbMeters = climb,
            activityType = ActivityType.DEFAULT
        )
    }

    companion object {
        @Volatile private var instance: TrackRepository? = null

        fun get(context: Context): TrackRepository =
            instance ?: synchronized(this) {
                instance ?: TrackRepository(context.applicationContext).also { instance = it }
            }
    }
}
