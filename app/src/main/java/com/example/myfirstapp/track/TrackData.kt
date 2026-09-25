package com.example.myfirstapp.track

import kotlin.math.max
import kotlin.math.min

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

/**
 * 记录灵敏度参数：不同运动方式的移动速度差异巨大，需要不同的降噪阈值。
 *
 * 关键设计：isNoise 中的 d 始终是"与上一个已记录点"的距离，被丢弃的定位
 * 不会打断累计——位移会在后续定位中补回来，因此放宽过滤只影响点的密度，
 * 不会造成里程损失；反之过滤过严（如低速模式下套用 accuracy/2 抖动规则）
 * 会让 1 米级的小位移被误杀，这正是徒步/登山里程偏少的主因。
 *
 * @param maxJumpMeters 单次位移超过该值视为 GPS 跳点丢弃（1 秒间隔基准值，
 *   实际判定会按定位间隔与 [plausibleSpeedMps] 动态放宽，见 isNoise）
 * @param staticDriftMeters 位移小于该值且速度低于 [staticSpeedLimit] 视为静止漂移丢弃
 * @param staticSpeedLimit 静止判定的速度上限（GPS 多普勒测速，静止时可靠地接近 0）
 * @param jitterAccuracyDivisor 位移小于 accuracy/该系数 视为误差圆内抖动丢弃
 * @param jitterCapMeters 抖动阈值上限：低速模式下收紧，保证慢速位移不被误杀
 * @param plausibleSpeedMetersPerSec 该运动的可信最大速度：GPS 丢锁恢复后定位间隔
 *   变大、位移天然变大，跳点阈值按 plausibleSpeed × 间隔 放宽，避免真实位移
 *   被按 1 秒基准的固定阈值误杀（山中峡谷 GPS 间隙 30~60 秒很常见）
 */
data class RecordingProfile(
    val maxJumpMeters: Double,
    val staticDriftMeters: Double,
    val staticSpeedLimit: Float,
    val jitterAccuracyDivisor: Float,
    val jitterCapMeters: Double,
    val plausibleSpeedMetersPerSec: Double
) {
    companion object {
        /**
         * 快速模式（越野跑/骑行/摩托/自驾）：速度高、单次位移大，
         * 沿用较严的过滤防止 GPS 噪声虚增里程（历史默认行为）。
         */
        val FAST = RecordingProfile(
            maxJumpMeters = 60.0,        // 1 秒 60 米 ≈ 216 km/h
            staticDriftMeters = 3.0,
            staticSpeedLimit = 1.0f,
            jitterAccuracyDivisor = 2f,
            jitterCapMeters = Double.MAX_VALUE,  // 抖动阈值完全跟随定位精度
            plausibleSpeedMetersPerSec = 45.0    // 162 km/h：自驾上限，GPS 间隙后按此放宽跳点阈值
        )

        /**
         * 低速高灵敏模式（徒步/登山）：保证 1 米级小位移能被记录。
         * - 静止判定速度降到 0.5 m/s（登山缓行常低于 1 m/s，不能一刀切当漂移）
         * - 抖动阈值封顶 1 米：只要真实位移 ≥ 1 米，不再因"精度的一半"被丢弃
         *   （GPS 精度常为 5~15 米，旧规则会把 <2.5~7.5 米的位移全部丢弃）
         */
        val SENSITIVE = RecordingProfile(
            maxJumpMeters = 30.0,        // 1 秒 30 米 ≈ 108 km/h，仍远超人力速度
            staticDriftMeters = 3.0,     // 休息点漂移窗口保留
            staticSpeedLimit = 0.5f,
            jitterAccuracyDivisor = 2f,
            jitterCapMeters = 1.0,
            plausibleSpeedMetersPerSec = 3.0     // 10.8 km/h：徒步/登山可信上限
        )
    }
}

/** 运动方式：徒步、登山、越野跑、骑行、摩托车、自驾（各自携带记录灵敏度参数） */
enum class ActivityType(
    val label: String,
    val profile: RecordingProfile = RecordingProfile.FAST
) {
    WALKING("徒步", RecordingProfile.SENSITIVE),
    HIKING("登山", RecordingProfile.SENSITIVE),
    TRAIL_RUNNING("越野跑"),
    CYCLING("骑行"),
    MOTORCYCLE("摩托车"),
    DRIVING("自驾");

    companion object {
        val DEFAULT: ActivityType = WALKING
    }
}

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
    val climbMeters: Double,        // 累计爬升
    val activityType: ActivityType = ActivityType.DEFAULT
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
    val fixCount: Int = 0,         // GPS 有效定位次数（信号质量参考）
    val stepCount: Int = 0,        // 计步器累计步数（无传感器/无权限时恒为 0）
    val locationError: String? = null, // 定位失败原因（如 Key 无效），null 表示正常
    val activityType: ActivityType = ActivityType.DEFAULT
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
     * 判断新定位点是否值得加入轨迹（降噪），阈值由运动方式的
     * [RecordingProfile] 决定（徒步/登山用 SENSITIVE，其余用 FAST）：
     * - 与上一点距离超过动态跳点阈值（maxJumpMeters 与 plausibleSpeed × 间隔
     *   取大者）视为 GPS 跳点，丢弃 —— 山中 GPS 丢锁 60 秒后恢复，真实位移
     *   完全可能超过固定阈值，按间隔缩放避免整段轨迹被永久丢弃
     * - 位移小于 staticDriftMeters 且速度低于 staticSpeedLimit 视为静止漂移，
     *   丢弃；[motionConfirmed]（计步器确认在走）时跳过本条 —— 缓行/碎步
     *   挪动（速度 <0.5 m/s、位移 <3 米）是小位移监控的核心场景
     * - 位移小于 accuracy/jitterAccuracyDivisor（不超过 jitterCapMeters）视为
     *   误差圆内抖动，丢弃 —— 该条保留：GPS 噪声抖动与步频同量级，
     *   即便确认在走也不能全收，否则里程被锯齿噪声虚增
     *
     * 注意：被丢弃的点不更新"上一个记录点"，位移会在后续定位中补回，
     * 因此过滤只影响轨迹点密度，不丢里程。
     */
    fun isNoise(
        last: TrackPoint?, lat: Double, lng: Double,
        speed: Float, accuracy: Float = 0f,
        profile: RecordingProfile = RecordingProfile.FAST,
        elapsedMs: Long = 1_000L,
        motionConfirmed: Boolean = false
    ): Boolean {
        if (last == null) return false
        val d = distance(last.latitude, last.longitude, lat, lng)
        // 跳点阈值随定位间隔缩放：GPS 间隙后的真实大位移 ≠ 跳点
        val dynamicJump = max(
            profile.maxJumpMeters,
            profile.plausibleSpeedMetersPerSec * elapsedMs / 1000.0
        )
        if (d > dynamicJump) return true
        if (!motionConfirmed && d < profile.staticDriftMeters && speed < profile.staticSpeedLimit) return true
        // 抖动阈值：取"精度/系数"与模式上限中较小者
        val jitterThreshold = min(
            accuracy / profile.jitterAccuracyDivisor,
            profile.jitterCapMeters.toFloat()
        )
        if (accuracy > 0f && d < jitterThreshold) return true
        return false
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
