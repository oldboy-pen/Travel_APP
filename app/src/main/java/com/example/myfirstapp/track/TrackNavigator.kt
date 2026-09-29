package com.example.myfirstapp.track

import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

/** 导航状态机：未开始 → 导航中 → 已到达（结束后回到 IDLE） */
enum class NavigationStatus { IDLE, NAVIGATING, ARRIVED }

/** 偏离方向：相对轨迹的前进方向，用于播报"向左/向右返回" */
enum class DeviationSide { NONE, LEFT, RIGHT }

/** 导航过程中的实时状态（UI 直接订阅） */
data class NavigationState(
    val status: NavigationStatus = NavigationStatus.IDLE,
    val trackId: String = "",
    val trackName: String = "",
    val located: Boolean = false,          // 是否已有一次有效定位
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val bearing: Float = 0f,               // 航向角（度，正北顺时针）
    val accuracyMeters: Float = 0f,        // 定位精度（米）
    val speed: Float = 0f,                 // 当前速度（米/秒）
    // ---- 偏离 ----
    val deviationMeters: Double = 0.0,     // 到轨迹线的垂直距离
    val offTrack: Boolean = false,         // 已进入"偏离"状态（> 一级阈值并连续确认）
    val severeOffTrack: Boolean = false,   // 严重偏离（> 二级阈值）：播报实际距离、补播更密
    val deviationSide: DeviationSide = DeviationSide.NONE,
    val rejoinLatitude: Double = 0.0,      // 回归点：轨迹上离当前位置最近的点
    val rejoinLongitude: Double = 0.0,
    val alertCount: Int = 0,               // 已播报的偏离预警次数
    val lastAlertText: String? = null,
    val lastAlertAt: Long = 0L,
    // ---- 进度 ----
    val matchedIndex: Int = 0,             // 当前匹配到的轨迹段下标
    val coveredMeters: Double = 0.0,       // 沿轨迹已走里程
    val remainingMeters: Double = 0.0,     // 沿轨迹剩余里程
    val totalMeters: Double = 0.0,
    val progress: Float = 0f,              // 0..1
    // ---- 途经点 ----
    val nextWaypointName: String? = null,
    val nextWaypointDistance: Double = 0.0, // 沿轨迹到下一个途经点的距离
    val passedWaypointCount: Int = 0,
    val totalWaypointCount: Int = 0,
    // ---- 其它 ----
    val elapsedMillis: Long = 0L,
    val error: String? = null
)

/**
 * 轨迹导航器（单例）：拿一条已保存的轨迹当"路线"来走，实时给出
 * 剩余里程 / 偏离距离 / 下一个途经点，并按两档阈值用 [VoiceAnnouncer] 语音预警：
 * 偏离 > [OFF_TRACK_ALERT_METERS]（20 米）开始预警；> [OFF_TRACK_SEVERE_METERS]
 * （50 米）播报实际偏离距离并提高补播频率。
 *
 * 与 [TrackRecorder] 同构：本类只管"算 + 播报"，保活交给
 * [TrackNavigationService]，因此息屏后状态与预警都不丢。
 *
 * 核心算法（每次定位都跑一遍）：
 * 1. 把当前位置投影到轨迹折线上，得到"偏离距离 + 沿轨迹里程"；
 * 2. 偏离 > 20 米且连续 2 次确认 → 进入偏离态并播报，回到 15 米内才解除
 *    （迟滞设计：不设回差会在阈值附近来回抖动、反复播报）；
 * 3. 偏离中的补播按**走过的里程**触发（不是按时间，走得快就报得勤）：
 *    20~50 米每 1 公里一次；> 50 米每 200 米一次，并带上实际偏离距离；
 *    档位升级/降级会立刻补播一次，让人马上知道严重程度变了；
 * 4. 剩余里程跨过整公里播报一次；终点 300 米内提示"即将到达"；
 * 5. 距终点 30 米内（或剩余里程 < 20 米）判定到达，播报后自动结束。
 *
 * 坐标说明：轨迹点与高德定位同为 GCJ-02，无需换算。
 */
object TrackNavigator {

    // ==================== 阈值（可调） ====================

    /**
     * 一级偏离阈值：离开轨迹线超过该距离即开始语音预警（需求：20 米）。
     * 该档只提示"已偏离 + 往哪边切回去"，不报具体距离。
     */
    const val OFF_TRACK_ALERT_METERS = 20.0

    /**
     * 二级偏离阈值：超过该距离说明偏得较远，播报**实际偏离距离**（需求：50 米），
     * 且补播密度提高（每 [RE_ALERT_METERS_SEVERE] 米一次）。
     */
    const val OFF_TRACK_SEVERE_METERS = 50.0

    /** 二级档的迟滞解除线：掉回该距离以内才退出"严重偏离"（防 50 米附近抖动） */
    private const val OFF_TRACK_SEVERE_CLEAR_METERS = 45.0

    /** 回到该距离以内才解除偏离态（迟滞，防止阈值附近反复播报） */
    private const val OFF_TRACK_CLEAR_METERS = 15.0

    /** 连续几次超限才认定偏离（单次可能是 GPS 跳点） */
    private const val OFF_TRACK_CONFIRM_FIXES = 2

    /** 一级偏离（20~50 米）的补播间隔：每走 1 公里补播一次 */
    private const val RE_ALERT_METERS_MILD = 1000.0

    /** 二级偏离（> 50 米）的补播间隔：每走 200 米补播一次 */
    private const val RE_ALERT_METERS_SEVERE = 200.0

    /** 距终点该距离内判定到达 */
    private const val ARRIVE_RADIUS_METERS = 30.0

    /** 途经点播报半径 */
    private const val WAYPOINT_RADIUS_METERS = 40.0

    /** 终点临近提示距离 */
    private const val NEAR_END_METERS = 300.0

    // ---- 投影搜索窗口：沿"上一次匹配位置"附近找，避免每次全轨迹扫描 ----
    private const val BACK_WINDOW = 30          // 允许回看（掉头/原地徘徊）
    private const val FORWARD_WINDOW = 300      // 前看（1 秒走不了多远）
    private const val RESCAN_THRESHOLD_METERS = 200.0  // 窗口内都没匹配上 → 全轨迹重扫

    private const val M_PER_DEG_LAT = 111_320.0

    // ==================== 状态 ====================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var locationClient: AMapLocationClient? = null
    private var tickerJob: Job? = null

    private var track: Track? = null
    private var lats = DoubleArray(0)
    private var lngs = DoubleArray(0)
    private var cosLats = DoubleArray(0)   // cos(纬度)：经差→米的系数
    private var cum = DoubleArray(0)       // cum[i] = 起点→points[i] 的沿轨迹里程
    private var totalMeters = 0.0
    private var wpAlong = DoubleArray(0)   // 各途经点在轨迹上的里程位置
    private var wpAnnounced = BooleanArray(0)

    private var lastIndex = 0              // 上次匹配到的轨迹段
    private var offStreak = 0              // 连续超限次数
    private var lastAlertAt = 0L
    private var alertCount = 0
    // ---- 补播里程基准：自上次播报以来实际走过的距离（静止不动不累加）----
    private var lastFixLat = Double.NaN
    private var lastFixLng = Double.NaN
    private var walkedSinceAlert = 0.0
    private var lastAlertLevel = 0         // 上次播报时的档位：0=无 1=轻偏离 2=严重偏离
    private var lastRemainKm = Int.MAX_VALUE
    private var nearEndAnnounced = false
    private var startedAt = 0L

    val isNavigating: Boolean get() = _state.value.status == NavigationStatus.NAVIGATING

    // ==================== 生命周期 ====================

    /**
     * 开始沿 [target] 导航。重复调用会先结束上一次。
     * 轨迹点不足 2 个时直接忽略（没有可导航的线）。
     */
    fun start(context: Context, target: Track) {
        if (target.points.size < 2) return
        stop()
        val ctx = context.applicationContext
        appContext = ctx
        track = target
        prepare(target)

        VoiceAnnouncer.ensureInit(ctx)
        startedAt = android.os.SystemClock.elapsedRealtime()
        _state.value = NavigationState(
            status = NavigationStatus.NAVIGATING,
            trackId = target.id,
            trackName = target.name,
            totalMeters = totalMeters,
            remainingMeters = totalMeters,
            totalWaypointCount = target.waypoints.size
        )

        startLocation(ctx)
        startTicker()
        VoiceAnnouncer.announce(ctx, VoiceAnnouncer.buildNavStartMessage(totalMeters))
    }

    /** 结束导航（状态回到 IDLE，数值保留便于 UI 展示最后一帧） */
    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        runCatching { locationClient?.stopLocation() }
        runCatching { locationClient?.onDestroy() }
        locationClient = null
        offStreak = 0
        if (_state.value.status != NavigationStatus.IDLE) {
            _state.value = _state.value.copy(status = NavigationStatus.IDLE)
        }
    }

    // ==================== 预处理 ====================

    /** 建索引：坐标数组 + 累计里程 + 途经点在轨迹上的里程位置 */
    private fun prepare(t: Track) {
        val pts = t.points
        val n = pts.size
        lats = DoubleArray(n)
        lngs = DoubleArray(n)
        cosLats = DoubleArray(n)
        cum = DoubleArray(n)
        for (i in 0 until n) {
            lats[i] = pts[i].latitude
            lngs[i] = pts[i].longitude
            cosLats[i] = cos(Math.toRadians(pts[i].latitude))
        }
        totalMeters = 0.0
        for (i in 1 until n) {
            totalMeters += GeoUtils.distance(lats[i - 1], lngs[i - 1], lats[i], lngs[i])
            cum[i] = totalMeters
        }

        // 途经点的沿轨迹里程：整条线投影一次，之后只比较里程即可判断"过了没"
        wpAlong = DoubleArray(t.waypoints.size)
        wpAnnounced = BooleanArray(t.waypoints.size)
        for (i in t.waypoints.indices) {
            wpAlong[i] = projectOnTrack(t.waypoints[i].latitude, t.waypoints[i].longitude).along
        }

        lastIndex = 0
        offStreak = 0
        lastAlertAt = 0L
        alertCount = 0
        lastFixLat = Double.NaN
        lastFixLng = Double.NaN
        walkedSinceAlert = 0.0
        lastAlertLevel = 0
        // 起播报点设为整公里：避免开导航瞬间就报一次"距终点还有 N 公里"
        lastRemainKm = ceil(totalMeters / 1000.0).toInt().coerceAtLeast(1)
        nearEndAnnounced = false
    }

    // ==================== 定位 ====================

    private fun startLocation(ctx: Context) {
        locationClient = AMapLocationClient(ctx).apply {
            setLocationOption(AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                interval = 1000              // 1 秒一次（偏离判定需要连续确认）
                isMockEnable = false
            })
            setLocationListener { loc ->
                if (loc.errorCode != 0) {
                    _state.value = _state.value.copy(
                        error = "定位失败(code=${loc.errorCode})：${loc.errorInfo}"
                    )
                    return@setLocationListener
                }
                if (_state.value.status != NavigationStatus.NAVIGATING) return@setLocationListener
                onNewFix(loc.latitude, loc.longitude, loc.bearing, loc.accuracy, loc.speed)
            }
        }
        locationClient?.startLocation()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && _state.value.status == NavigationStatus.NAVIGATING) {
                _state.value = _state.value.copy(
                    elapsedMillis = android.os.SystemClock.elapsedRealtime() - startedAt
                )
                delay(1000)
            }
        }
    }

    // ==================== 每次定位的核心计算 ====================

    private fun onNewFix(
        lat: Double, lng: Double, bearing: Float, accuracy: Float, speed: Float
    ) {
        val t = track ?: return
        val ctx = appContext ?: return
        val p = project(lat, lng)
        lastIndex = p.index

        val along = p.along
        val remaining = (totalMeters - along).coerceAtLeast(0.0)
        val deviation = p.distance
        val now = System.currentTimeMillis()

        // ---- 偏离判定：两档阈值 + 迟滞 + 连续确认 ----
        //   一级（> 20 米）：提示"已偏离，往哪边切回去"，之后每走 1 公里补播一次；
        //   二级（> 50 米）：播报实际偏离距离，之后每走 200 米补播一次（偏得越远提醒越勤）。
        var offTrack = _state.value.offTrack
        var severe = _state.value.severeOffTrack
        var alertText: String? = null

        // 补播里程基准 = 自上次播报以来实际走过的距离（GPS 位移累计，原地不动不累加）
        if (!lastFixLat.isNaN()) {
            walkedSinceAlert += GeoUtils.distance(lastFixLat, lastFixLng, lat, lng)
        }
        lastFixLat = lat
        lastFixLng = lng

        if (deviation > OFF_TRACK_ALERT_METERS) {
            offStreak++
            if (!offTrack && offStreak >= OFF_TRACK_CONFIRM_FIXES) {
                // 首次确认偏离：立刻播报一次
                offTrack = true
                severe = deviation > OFF_TRACK_SEVERE_METERS
                alertText = VoiceAnnouncer.buildDeviationMessage(deviation, p.side, severe)
            } else if (offTrack) {
                // 档位判定（带迟滞）：>50 进二级，掉回 45 以内才退回一级
                if (deviation > OFF_TRACK_SEVERE_METERS) severe = true
                else if (deviation < OFF_TRACK_SEVERE_CLEAR_METERS) severe = false
                val level = if (severe) 2 else 1
                val interval = if (severe) RE_ALERT_METERS_SEVERE else RE_ALERT_METERS_MILD
                // 补播条件：走够间隔里程，或档位发生变化（升级/降级都让人立刻知道）
                if (walkedSinceAlert >= interval || level != lastAlertLevel) {
                    alertText = VoiceAnnouncer.buildDeviationMessage(deviation, p.side, severe)
                }
            }
        } else if (deviation < OFF_TRACK_CLEAR_METERS) {
            offStreak = 0
            offTrack = false
            severe = false
        }
        if (alertText != null) {
            VoiceAnnouncer.announce(ctx, alertText)
            lastAlertAt = now
            alertCount++
            walkedSinceAlert = 0.0
            lastAlertLevel = if (severe) 2 else 1
        }

        // ---- 途经点：进入 40 米播报一次；沿轨迹里程超过它即算已通过 ----
        var passed = 0
        var nextName: String? = null
        var nextDist = Double.NaN
        for (i in t.waypoints.indices) {
            val w = t.waypoints[i]
            if (wpAlong[i] <= along) passed++
            if (!wpAnnounced[i] &&
                GeoUtils.distance(lat, lng, w.latitude, w.longitude) < WAYPOINT_RADIUS_METERS
            ) {
                wpAnnounced[i] = true
                VoiceAnnouncer.announce(ctx, VoiceAnnouncer.buildWaypointMessage(w.name))
            }
        }
        // 下一个途经点：还没经过、且沿轨迹里程最小的那个
        var bestIdx = -1
        for (i in t.waypoints.indices) {
            if (wpAlong[i] > along && (bestIdx < 0 || wpAlong[i] < wpAlong[bestIdx])) bestIdx = i
        }
        if (bestIdx >= 0) {
            nextName = t.waypoints[bestIdx].name
            nextDist = wpAlong[bestIdx] - along
        }

        // ---- 剩余里程里程碑 ----
        if (remaining > NEAR_END_METERS) {
            val km = ceil(remaining / 1000.0).toInt()
            if (km < lastRemainKm) {
                lastRemainKm = km
                VoiceAnnouncer.announce(ctx, VoiceAnnouncer.buildRemainingKmMessage(km))
            }
        } else if (!nearEndAnnounced) {
            nearEndAnnounced = true
            VoiceAnnouncer.announce(ctx, VoiceAnnouncer.buildNearEndMessage(remaining))
        }

        // ---- 到达终点 ----
        // 至少沿轨迹走过 10%（最多 200 米）才允许判定到达：环形轨迹的终点与起点重合，
        // 不设这个门槛会在开局第一帧就误判"已到达"。
        val minCovered = (totalMeters * 0.1).coerceAtMost(200.0)
        val lastPoint = t.points.last()
        val distToEnd = GeoUtils.distance(lat, lng, lastPoint.latitude, lastPoint.longitude)
        val arrived = along > minCovered &&
                (distToEnd <= ARRIVE_RADIUS_METERS || remaining <= 20.0)

        _state.value = _state.value.copy(
            status = if (arrived) NavigationStatus.ARRIVED else NavigationStatus.NAVIGATING,
            located = true,
            latitude = lat,
            longitude = lng,
            bearing = bearing,
            accuracyMeters = accuracy,
            speed = speed,
            deviationMeters = deviation,
            offTrack = offTrack,
            severeOffTrack = severe,
            deviationSide = p.side,
            rejoinLatitude = p.lat,
            rejoinLongitude = p.lng,
            alertCount = alertCount,
            lastAlertText = alertText ?: _state.value.lastAlertText,
            lastAlertAt = if (alertText != null) now else _state.value.lastAlertAt,
            matchedIndex = p.index,
            coveredMeters = along,
            remainingMeters = if (arrived) 0.0 else remaining,
            progress = if (totalMeters > 0) (along / totalMeters).toFloat().coerceIn(0f, 1f) else 0f,
            nextWaypointName = nextName,
            nextWaypointDistance = nextDist,
            passedWaypointCount = passed,
            error = null
        )

        if (arrived) {
            VoiceAnnouncer.announce(ctx, VoiceAnnouncer.buildArriveMessage(t.name))
            // 停止定位与计时，状态保留 ARRIVED 供 UI 展示
            tickerJob?.cancel()
            tickerJob = null
            runCatching { locationClient?.stopLocation() }
        }
    }

    // ==================== 几何：把点投影到轨迹折线 ====================

    /** 投影结果 */
    private class Proj {
        var index = 0                      // 最近段下标 i（线段 points[i]→points[i+1]）
        var t = 0.0                        // 段内比例 0..1
        var distance = Double.MAX_VALUE    // 到该段的垂直距离（偏离距离）
        var along = 0.0                    // 沿轨迹里程
        var lat = 0.0                      // 回归点经纬度
        var lng = 0.0
        var side = DeviationSide.NONE
    }

    /** 增量投影：先在上次位置附近找，找不到再全轨迹扫描 */
    private fun project(lat: Double, lng: Double): Proj {
        val n = lats.size
        val last = (n - 2).coerceAtLeast(0)
        val from = (lastIndex - BACK_WINDOW).coerceAtLeast(0)
        val to = (lastIndex + FORWARD_WINDOW).coerceAtMost(last)
        var best = scan(lat, lng, from, to)
        if (best.distance > RESCAN_THRESHOLD_METERS && last > to) {
            val full = scan(lat, lng, 0, last)
            if (full.distance < best.distance) best = full
        }
        return best
    }

    /** 全轨迹投影（初始化途经点位置等一次性场景用） */
    private fun projectOnTrack(lat: Double, lng: Double): Proj =
        scan(lat, lng, 0, (lats.size - 2).coerceAtLeast(0))

    /**
     * 在 [from, to] 段区间内找最近的线段。
     *
     * 局部平面近似：把经纬差按 111320 米/度（经差再乘 cos(lat)）换算成米，
     * 在这个尺度下（几十公里内）误差可忽略，比反复调用 Haversine 快得多。
     */
    private fun scan(lat: Double, lng: Double, from: Int, to: Int): Proj {
        val out = Proj()
        if (to < from) return out
        for (i in from..to) {
            val dy = (lat - lats[i]) * M_PER_DEG_LAT
            val dx = (lng - lngs[i]) * M_PER_DEG_LAT * cosLats[i]
            val sy = (lats[i + 1] - lats[i]) * M_PER_DEG_LAT
            val sx = (lngs[i + 1] - lngs[i]) * M_PER_DEG_LAT * cosLats[i]
            val len2 = sx * sx + sy * sy
            val tt = if (len2 <= 0.0) 0.0 else ((dx * sx + dy * sy) / len2).coerceIn(0.0, 1.0)
            val px = dx - tt * sx
            val py = dy - tt * sy
            val d = sqrt(px * px + py * py)
            if (d < out.distance) {
                out.distance = d
                out.index = i
                out.t = tt
                out.along = cum[i] + tt * sqrt(len2)
                out.lat = lats[i] + tt * (lats[i + 1] - lats[i])
                out.lng = lngs[i] + tt * (lngs[i + 1] - lngs[i])
                // 叉积符号判定左右：>0 表示点在前进方向的左侧（面朝东时北方在左）
                out.side = if (sx * py - sy * px > 0) DeviationSide.LEFT else DeviationSide.RIGHT
            }
        }
        return out
    }
}
