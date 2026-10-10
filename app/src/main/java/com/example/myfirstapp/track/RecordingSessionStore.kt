package com.example.myfirstapp.track

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 记录会话快照的落盘与恢复（记录态跨进程存活的关键）。
 *
 * 用途：轨迹记录期间进程被强制杀掉（最近任务划卡、系统回收、崩溃、设置里
 * 强制停止）后，App 下次启动时把「退出那一刻」的记录状态原样接回来——
 * 轨迹点、途经点、距离、爬升、运动方式、步数，以及当时是「记录中」还是「已暂停」。
 *
 * 存储：内部存储根目录下的 recording_session.json
 * （org.json 手写序列化，与 TrackRepository 同口径，不引第三方库）
 *
 * 落盘用「先写 .tmp 再 rename」：进程可能在写一半时被杀，同一目录下的 rename
 * 是原子操作，保证读到的永远是上一次完整的快照（不会出现半截 JSON）。
 */
object RecordingSessionStore {

    // 文件名常量（内部存储 filesDir 下）
    private const val FILE_NAME = "recording_session.json"

    /** 一次记录的完整可恢复快照 */
    data class Snapshot(
        val state: RecorderState,
        val activityType: ActivityType,
        val points: List<TrackPoint>,
        val waypoints: List<Waypoint>,
        val distanceMeters: Double,
        val climbMeters: Double,
        val fixCount: Int,
        val stepCount: Int,
        /** 暂停前已累计的时长（不含本段） */
        val accumulatedDuration: Long,
        /** 本段起点：SystemClock.elapsedRealtime，仅 [state] = RECORDING 时有意义 */
        val segmentStartElapsed: Long,
        /** 落盘时刻（elapsedRealtime）：与当前值相减即"进程不在"的时长 */
        val savedAtElapsed: Long,
        /** 落盘时刻（wall clock）：设备重启后 elapsedRealtime 归零时的兜底 */
        val savedAtWall: Long,
        val lastLatitude: Double?,
        val lastLongitude: Double?,
        /** 最近一次 GPS 定位时刻：恢复后继续沿用，避免网络点立刻带偏轨迹 */
        val lastGpsFixAt: Long
    )

    fun save(context: Context, snap: Snapshot) {
        runCatching {
            val dir = context.filesDir
            val tmp = File(dir, "$FILE_NAME.tmp")
            val target = File(dir, FILE_NAME)
            tmp.writeText(toJson(snap).toString())
            // 原子替换；个别文件系统 rename 失败时退回直接写（最坏情况丢一次快照）
            if (!tmp.renameTo(target)) target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun load(context: Context): Snapshot? = runCatching {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.exists() || f.length() == 0L) null else parse(JSONObject(f.readText()))
    }.getOrNull()

    fun clear(context: Context) {
        runCatching {
            File(context.filesDir, FILE_NAME).delete()
            File(context.filesDir, "$FILE_NAME.tmp").delete()
        }
    }

    private fun toJson(s: Snapshot): JSONObject = JSONObject().apply {
        put("state", s.state.name)
        put("activityType", s.activityType.name)
        put("distance", s.distanceMeters)
        put("climb", s.climbMeters)
        put("fixCount", s.fixCount)
        put("stepCount", s.stepCount)
        put("accumulatedDuration", s.accumulatedDuration)
        put("segmentStartElapsed", s.segmentStartElapsed)
        put("savedAtElapsed", s.savedAtElapsed)
        put("savedAtWall", s.savedAtWall)
        put("lastGpsFixAt", s.lastGpsFixAt)
        if (s.lastLatitude != null) put("lastLat", s.lastLatitude)
        if (s.lastLongitude != null) put("lastLng", s.lastLongitude)
        put("points", JSONArray(s.points.map { p ->
            JSONObject().apply {
                put("lat", p.latitude); put("lng", p.longitude)
                put("t", p.time); put("alt", p.altitude); put("spd", p.speed.toDouble())
            }
        }))
        put("waypoints", JSONArray(s.waypoints.map { w ->
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

    private fun parse(o: JSONObject): Snapshot {
        val points = buildList {
            val arr = o.optJSONArray("points") ?: return@buildList
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                add(
                    TrackPoint(
                        p.getDouble("lat"), p.getDouble("lng"), p.getLong("t"),
                        p.getDouble("alt"), p.getDouble("spd").toFloat()
                    )
                )
            }
        }
        val waypoints = buildList {
            val arr = o.optJSONArray("waypoints") ?: return@buildList
            for (i in 0 until arr.length()) {
                val w = arr.getJSONObject(i)
                val type = runCatching {
                    WaypointType.valueOf(w.optString("type", WaypointType.TEXT.name))
                }.getOrDefault(WaypointType.TEXT)
                add(
                    Waypoint(
                        name = w.optString("name", "标记"),
                        latitude = w.getDouble("lat"),
                        longitude = w.getDouble("lng"),
                        time = w.getLong("t"),
                        type = type,
                        text = w.optString("text", "").takeIf { it.isNotEmpty() },
                        mediaUri = w.optString("mediaUri", "").takeIf { it.isNotEmpty() }
                    )
                )
            }
        }
        return Snapshot(
            state = runCatching { RecorderState.valueOf(o.optString("state", RecorderState.IDLE.name)) }
                .getOrDefault(RecorderState.IDLE),
            activityType = runCatching {
                ActivityType.valueOf(o.optString("activityType", ActivityType.DEFAULT.name))
            }.getOrDefault(ActivityType.DEFAULT),
            points = points,
            waypoints = waypoints,
            distanceMeters = o.optDouble("distance", 0.0),
            climbMeters = o.optDouble("climb", 0.0),
            fixCount = o.optInt("fixCount", 0),
            stepCount = o.optInt("stepCount", 0),
            accumulatedDuration = o.optLong("accumulatedDuration", 0L),
            segmentStartElapsed = o.optLong("segmentStartElapsed", 0L),
            savedAtElapsed = o.optLong("savedAtElapsed", 0L),
            savedAtWall = o.optLong("savedAtWall", 0L),
            lastGpsFixAt = o.optLong("lastGpsFixAt", 0L),
            lastLatitude = if (o.has("lastLat")) o.getDouble("lastLat") else null,
            lastLongitude = if (o.has("lastLng")) o.getDouble("lastLng") else null
        )
    }
}
