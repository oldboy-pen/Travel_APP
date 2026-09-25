package com.example.myfirstapp.track

import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轨迹记录器（单例）：
 * - 持有高德定位客户端，1秒一次高精度定位（高德 SDK 连续定位的最小间隔，
 *   记录频率已达上限；小位移捕获靠按运动方式放宽降噪阈值实现）
 * - 降噪过滤后累积轨迹点，实时计算 距离/时长/爬升/速度
 * - 前台 Service 只负责"保活 + 通知"，本类负责全部记录逻辑，
 *   因此 App 进程存活期间（含息屏）状态不丢
 */
object TrackRecorder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _data = MutableStateFlow(RecordingData())
    val data: StateFlow<RecordingData> = _data.asStateFlow()

    private var locationClient: AMapLocationClient? = null
    private var motionHelper: MotionSensorHelper? = null
    private var tickerJob: kotlinx.coroutines.Job? = null
    private var segmentStartElapsed = 0L     // 本段（两次暂停之间）开始时间
    private var accumulatedDuration = 0L     // 暂停前已累计时长

    val isRecording: Boolean get() = _data.value.state != RecorderState.IDLE

    /** 开始或继续记录 */
    fun start(context: Context, activityType: ActivityType = _data.value.activityType) {
        _data.value = _data.value.copy(activityType = activityType)
        when (_data.value.state) {
            RecorderState.IDLE -> startNewSegment(context, isNewTrack = true)
            RecorderState.PAUSED -> startNewSegment(context, isNewTrack = false)
            RecorderState.RECORDING -> Unit
        }
    }

    private fun startNewSegment(context: Context, isNewTrack: Boolean) {
        val appContext = context.applicationContext
        if (!isNewTrack) { // 从暂停恢复：接上之前的时长
            accumulatedDuration += SystemClock_elapsed() - segmentStartElapsed
        } else {
            accumulatedDuration = 0
            _data.value = RecordingData(activityType = _data.value.activityType) // 重置保留运动方式
        }
        segmentStartElapsed = SystemClock_elapsed()

        if (locationClient == null) {
            locationClient = AMapLocationClient(appContext).apply {
                setLocationOption(AMapLocationClientOption().apply {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    interval = 1000              // 1 秒一次（轨迹记录需要高频率）
                    isMockEnable = false
                })
                setLocationListener { loc ->
                    if (loc.errorCode != 0) {
                        // 把 SDK 的失败原因透传给 UI（附 detail 便于区分环境问题与配置问题）
                        _data.value = _data.value.copy(
                            locationError = "定位失败(code=${loc.errorCode})：${loc.errorInfo}" +
                                    "｜${loc.locationDetail}"
                        )
                        return@setLocationListener
                    }
                    if (_data.value.state != RecorderState.RECORDING) return@setLocationListener
                    onNewFix(loc.latitude, loc.longitude, loc.altitude, loc.speed, loc.time, loc.accuracy, loc.locationType)
                }
            }
        }
        locationClient?.startLocation()

        // 硬件计步器：告诉降噪过滤"用户此刻是否真的在走"。
        // 无传感器/无 ACTIVITY_RECOGNITION 权限时静默降级为纯 GPS 过滤
        if (motionHelper == null) motionHelper = MotionSensorHelper(appContext)
        motionHelper?.start()

        _data.value = _data.value.copy(state = RecorderState.RECORDING)

        // 每秒刷新一次计时（定位回调不触发时，表也要走）
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && _data.value.state == RecorderState.RECORDING) {
                _data.value = _data.value.copy(
                    durationMillis = accumulatedDuration + (SystemClock_elapsed() - segmentStartElapsed)
                )
                delay(1000)
            }
        }
    }

    /**
     * 处理一次有效定位：来源+精度双重过滤 → 计步器辅助降噪 → 累积距离/爬升
     *
     * 定位来源 locationType：1=GPS、2=前次缓存、4=WiFi、5=基站（高德定义）
     * GPS 点精度天然 5-15 米；WiFi/基站点精度 20-100 米，是 20 米级位置偏差
     * 的主因——只有精度极佳（≤15 米）时才允许进轨迹，否则只更新"最后位置"
     * 供打点参考。
     *
     * 计步器辅助（MotionSensorHelper）：
     * - 确认在走（本周期有新步子或最近 4 秒内有步子）→ 静止漂移过滤跳过，
     *   GPS 精度门槛 30 → 50 米（密林/峡谷中 GPS 精度普遍 30~50 米，
     *   动起来了就宁可要粗糙的点也不要空窗）
     * - 确认静止（休息点）→ 维持严格过滤，防里程虚增
     * - 跳点阈值按定位间隔缩放：GPS 丢锁恢复后真实大位移不再被永久丢弃
     *
     * 降噪阈值取自当前运动方式的 RecordingProfile：徒步/登山为 SENSITIVE
     * （抖动阈值封顶 1 米），1 米级小位移也能入轨；被丢弃的位移会在后续
     * 定位中相对"上一个记录点"补回，不丢里程。
     */
    private fun onNewFix(
        lat: Double, lng: Double, altitude: Double,
        speed: Float, time: Long, accuracy: Float, locationType: Int
    ) {
        val current = _data.value
        val last = current.points.lastOrNull()

        // ---- 计步器：本周期新增步数 + 是否正在走 ----
        val helper = motionHelper
        val stepsSinceLastFix = helper?.consumePendingSteps() ?: 0
        val motionConfirmed = helper != null && (stepsSinceLastFix > 0 || helper.isWalking())

        val isGps = locationType == 1
        // 精度门槛：GPS 点 ≤30 米（计步器确认移动中放宽到 ≤50 米）；非 GPS 点恒 ≤15 米
        val maxAccuracy = when {
            isGps && motionConfirmed -> 50f
            isGps -> 30f
            else -> 15f
        }
        if (accuracy > 0f && accuracy > maxAccuracy) {
            // 精度差的点不进轨迹，但仍更新"最后位置"，保证打点标记可用
            _data.value = current.copy(lastLatitude = lat, lastLongitude = lng)
            return
        }

        val newTime = if (time > 0) time else System.currentTimeMillis()
        // 与上一记录点的间隔：GPS 丢锁恢复后天然变大，跳点判定据此缩放（上限 10 分钟）
        val elapsedMs = if (last != null)
            (newTime - last.time).coerceIn(0L, 10 * 60_000L) else 1_000L
        if (GeoUtils.isNoise(
                last, lat, lng, speed, accuracy,
                current.activityType.profile, elapsedMs, motionConfirmed
            )
        ) return

        val point = TrackPoint(lat, lng, newTime, altitude, speed)
        val addDistance = if (last == null) 0.0
        else GeoUtils.distance(last.latitude, last.longitude, lat, lng)
        // 爬升：只累计正海拔差（过滤 <1 米的抖动）
        val addClimb = if (last != null && altitude - last.altitude > 1.0) altitude - last.altitude else 0.0

        _data.value = current.copy(
            points = current.points + point,
            distanceMeters = current.distanceMeters + addDistance,
            climbMeters = current.climbMeters + addClimb,
            currentSpeed = speed,
            lastLatitude = lat,
            lastLongitude = lng,
            fixCount = current.fixCount + 1,
            stepCount = helper?.stepCount() ?: current.stepCount,
            locationError = null   // 收到有效定位，清除错误提示
        )
    }

    /** 暂停（计时停止，定位停止） */
    fun pause() {
        if (_data.value.state != RecorderState.RECORDING) return
        accumulatedDuration += SystemClock_elapsed() - segmentStartElapsed
        _data.value = _data.value.copy(
            state = RecorderState.PAUSED,
            durationMillis = accumulatedDuration,
            currentSpeed = 0f
        )
        tickerJob?.cancel()
        motionHelper?.stop()
        locationClient?.stopLocation()
    }

    /**
     * 结束并生成轨迹（保存由调用方调用 TrackRepository.saveTrack 完成）。
     * 点数过少视为误操作，返回 null。
     */
    fun stop(): Track? {
        if (_data.value.state == RecorderState.IDLE) return null
        pause()
        tickerJob?.cancel()
        motionHelper?.stop()
        motionHelper = null
        locationClient?.stopLocation()
        locationClient?.onDestroy()
        locationClient = null

        val d = _data.value
        return if (d.points.size < 2) {
            _data.value = RecordingData() // 点太少，直接丢弃
            null
        } else {
            val track = Track(
                id = "track_" + d.points.first().time,
                name = "${d.activityType.label} ${SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
                    .format(Date(d.points.first().time))}",
                startTime = d.points.first().time,
                endTime = d.points.last().time,
                points = d.points,
                waypoints = d.waypoints,
                distanceMeters = d.distanceMeters,
                durationMillis = d.durationMillis,
                climbMeters = d.climbMeters,
                activityType = d.activityType
            )
            _data.value = RecordingData()
            track
        }
    }

    /** 记录中打一个途经点 */
    fun addWaypoint(
        type: WaypointType = WaypointType.TEXT,
        name: String = "",
        text: String? = null,
        mediaUri: String? = null
    ) {
        val d = _data.value
        val lat = d.lastLatitude ?: return
        val lng = d.lastLongitude ?: return
        val markerName = when (type) {
            WaypointType.TEXT -> name.ifBlank { "文字标记 ${d.waypoints.size + 1}" }
            WaypointType.PHOTO -> name.ifBlank { "照片标记 ${d.waypoints.size + 1}" }
            WaypointType.VIDEO -> name.ifBlank { "视频标记 ${d.waypoints.size + 1}" }
            WaypointType.VOICE -> name.ifBlank { "语音标记 ${d.waypoints.size + 1}" }
        }
        val markerText = when (type) {
            WaypointType.TEXT -> text ?: name
            WaypointType.PHOTO -> text ?: "图片标记"
            WaypointType.VIDEO -> text ?: "视频标记"
            WaypointType.VOICE -> text ?: "语音标记"
        }
        _data.value = d.copy(
            waypoints = d.waypoints + Waypoint(
                name = markerName,
                latitude = lat,
                longitude = lng,
                time = System.currentTimeMillis(),
                type = type,
                text = markerText,
                mediaUri = if (type == WaypointType.TEXT) null else (mediaUri ?: "")
            )
        )
    }

    /** SystemClock.elapsedRealnode 的替身（单例中不可用 Context） */
    private fun SystemClock_elapsed(): Long = android.os.SystemClock.elapsedRealtime()
}
