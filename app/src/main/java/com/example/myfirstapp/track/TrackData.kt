package com.example.myfirstapp.track

/**
 * ============ 轨迹数据模型（类似两步路的轨迹/途经点） ============
 */

/** 轨迹上的一个采样点 */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val time: Long,          // epoch millis
    val altitude: Double,    // 米（GPS海拔）
    val speed: Float         // 米/秒
)

/** 途经点类型：文本、图片、视频、语音 */
enum class WaypointType {
    TEXT,
    PHOTO,
    VIDEO,
    VOICE
}

/** 途经点：用户记录过程中手动打点标记（水源、营地、岔路口…） */
data class Waypoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val time: Long,
    val type: WaypointType = WaypointType.TEXT,
    val text: String? = null,
    val mediaUri: String? = null
)

/** 一条完整轨迹 */
data class Track(
    val id: String,
    val name: String,
    val startTime: Long,
    val endTime: Long,
    val points: List<TrackPoint>,
    val waypoints: List<Waypoint>,
    val distanceMeters: Double,
    val durationMillis: Long,      // 有效运动时长（不含暂停）
    val climbMeters: Double        // 累计爬升
)

/** 记录器状态机：空闲 → 记录中 ⇄ 暂停 → 空闲 */
enum class RecorderState { IDLE, RECORDING, PAUSED }

/** 记录过程中的实时数据（供 UI 渲染） */
data class RecordingData(
    val state: RecorderState = RecorderState.IDLE,
    val points: List<TrackPoint> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
    val distanceMeters: Double = 0.0,
    val durationMillis: Long = 0L,
    val climbMeters: Double = 0.0,
    val currentSpeed: Float = 0f,
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    val fixCount: Int = 0          // GPS 有效定位次数（信号质量参考）
)

/** 地理计算工具 */
object GeoUtils {

    /** 两点球面距离（Haversine 公式），单位：米 */
    fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0 // 地球半径（米）
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /**
     * 判断新定位点是否值得加入轨迹（降噪）：
     * - 与上一点距离 < 2 米且速度 < 0.5 m/s 视为静止漂移，丢弃
     * - 距离 > 200 米视为 GPS 跳点，丢弃
     */
    fun isNoise(last: TrackPoint?, lat: Double, lng: Double, speed: Float): Boolean {
        if (last == null) return false
        val d = distance(last.latitude, last.longitude, lat, lng)
        return (d < 2 && speed < 0.5) || d > 200
    }

    /** 格式化时长 mm:ss / h:mm:ss */
    fun formatDuration(millis: Long): String {
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** 格式化距离 m/km */
    fun formatDistance(meters: Double): String =
        if (meters < 1000) "%.0f 米".format(meters) else "%.2f 公里".format(meters / 1000)
}
