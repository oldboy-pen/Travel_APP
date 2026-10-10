package com.example.myfirstapp.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.myfirstapp.location.AppLocationSource
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
 * - 进程被杀（划卡 / 系统回收 / 崩溃 / 强制停止）后，状态由 [RecordingSessionStore]
 *   落盘兜底：记录期间每 3 秒写一次快照，下次启动由 [restore] 原样接回。
 *   时长按 SystemClock.elapsedRealtime 计算，进程不在的那段时间天然计入
 *   （超过 5 分钟的部分不计，避免隔天再开时时长暴涨）
 */
object TrackRecorder {

    private const val TAG = "TrackRecorder"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 快照落盘专用：单线程串行，避免大轨迹（上万点）连续写盘互相穿插 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val _data = MutableStateFlow(RecordingData())
    val data: StateFlow<RecordingData> = _data.asStateFlow()

    /** 统一位置源：高德混合定位为主，无网时自动回退到系统 GPS（见 AppLocationSource） */
    private var locationClient: AppLocationSource? = null
    private var motionHelper: MotionSensorHelper? = null
    private var tickerJob: kotlinx.coroutines.Job? = null
    private var segmentStartElapsed = 0L     // 本段（两次暂停之间）开始时间
    private var accumulatedDuration = 0L     // 暂停前已累计时长
    private var lastGpsFixAt = 0L            // 最近一次 GPS 来源定位的时刻（网络点展示/入轨门槛用）

    // ---- 跨进程恢复相关 ----
    private var appContext: Context? = null
    private var lastPersistAt = 0L
    private var stepBaseline = 0             // 恢复时接回的已累计步数（新计步器以此为起点）

    /**
     * 本次进程是否由磁盘快照恢复而来。
     * UI 用它决定启动时是否直接落到「运动」页（真正被杀过才跳，Activity 单纯重建不跳）。
     */
    @Volatile
    var restoredFromDisk: Boolean = false
        private set

    val isRecording: Boolean get() = _data.value.state != RecorderState.IDLE

    /** 开始或继续记录 */
    fun start(context: Context, activityType: ActivityType = _data.value.activityType) {
        appContext = context.applicationContext
        _data.value = _data.value.copy(activityType = activityType)
        when (_data.value.state) {
            RecorderState.IDLE -> startNewSegment(context, isNewTrack = true)
            RecorderState.PAUSED -> startNewSegment(context, isNewTrack = false)
            RecorderState.RECORDING -> Unit
        }
    }

    private fun startNewSegment(context: Context, isNewTrack: Boolean) {
        val appContext = context.applicationContext
        if (isNewTrack) {
            accumulatedDuration = 0
            _data.value = RecordingData(activityType = _data.value.activityType) // 重置保留运动方式
            lastGpsFixAt = 0L
        }
        // 从暂停恢复：本段时长已在 pause() 里结清进 accumulatedDuration，这里只重置起点
        // （旧实现在这里再累加一次"到上一暂停时刻"的跨度，会把暂停期重复计入时长）
        segmentStartElapsed = SystemClock_elapsed()

        if (locationClient == null) {
            // ★ 统一位置源（高德混合定位 + 系统 GPS 无网兜底）：
            //   野外断网时高德的 WiFi/基站定位必然失败，纯卫星的系统 GPS 是唯一出路，
            //   而"进山就没信号"恰恰是轨迹记录最典型的场景。
            locationClient = AppLocationSource(
                context = appContext,
                intervalMs = 1000,               // 1 秒一次（轨迹记录需要高频率）
                once = false,
                needAddress = false,
                onLocation = { loc ->
                    if (_data.value.state != RecorderState.RECORDING) return@AppLocationSource
                    onNewFix(
                        loc.latitude, loc.longitude,
                        if (loc.altitude.isNaN()) 0.0 else loc.altitude,
                        loc.speed, loc.time, loc.accuracy,
                        // locationType：1 = GPS/卫星定位（可信）。系统 GPS 回退时必然是卫星定位，
                        // 高德给点时沿用其原始类型（此处按可信处理，过滤逻辑在 onNewFix 内）
                        1,
                        loc.bearing
                    )
                },
                onError = { msg ->
                    // 把失败原因透传给 UI（便于区分环境问题与配置问题）
                    _data.value = _data.value.copy(locationError = msg)
                }
            )
        }
        locationClient?.start()

        // 硬件计步器：告诉降噪过滤"用户此刻是否真的在走"。
        // 无传感器/无 ACTIVITY_RECOGNITION 权限时静默降级为纯 GPS 过滤
        if (motionHelper == null) motionHelper = MotionSensorHelper(appContext, stepBaseline)
        motionHelper?.start()

        _data.value = _data.value.copy(
            state = RecorderState.RECORDING,
            stepSensorStatus = motionHelper?.status ?: StepSensorStatus.NO_SENSOR
        )

        // 每秒刷新一次计时和步数（定位回调被过滤/失败时，表要走、步数也不能冻结）
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && _data.value.state == RecorderState.RECORDING) {
                val h = motionHelper
                val d = _data.value
                _data.value = d.copy(
                    durationMillis = accumulatedDuration + (SystemClock_elapsed() - segmentStartElapsed),
                    stepCount = h?.stepCount() ?: d.stepCount,
                    stepSensorStatus = h?.status ?: d.stepSensorStatus
                )
                maybePersist()   // 顺带续期快照时间戳，进程被杀时丢失量 ≤ 3 秒
                delay(1000)
            }
        }

        persistNow()  // 状态变化立即落盘（开始/继续）
    }

    /**
     * （重新）启动计步传感器。用于运行时补授「身体活动」权限后立即生效，
     * 无需结束/重开记录。仅在记录中有效。
     */
    fun startMotionSensors() {
        if (_data.value.state != RecorderState.RECORDING) return
        motionHelper?.start()
        _data.value = _data.value.copy(stepSensorStatus = motionHelper?.status
            ?: StepSensorStatus.NO_SENSOR)
    }

    /**
     * 处理一次有效定位：显示定位（GPS 优先）→ 来源+精度双重过滤 → 计步器辅助
     * 降噪 → 累积距离/爬升
     *
     * 定位来源 locationType：1=GPS、2=前次缓存、4=WiFi、5=基站（高德定义）
     * GPS 点精度天然 5-15 米；WiFi/基站点精度 20-100 米且实测位置可偏 20~50 米
     * （"定位偏差"的主因）——因此：
     * - 显示（蓝点）：GPS 点优先；网络点仅在 GPS 失联超 30 秒后兜底展示
     * - 入轨：GPS 点按精度门槛收；网络点额外要求 GPS 已失联超 60 秒
     *   （GPS 还新鲜时 WiFi 点带偏轨迹，宁可不要）
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
     *
     * 注意：步数显示由 ticker 每秒刷新（本方法被过滤提前返回时步数也不能冻结）。
     */
    private fun onNewFix(
        lat: Double, lng: Double, altitude: Double,
        speed: Float, time: Long, accuracy: Float, locationType: Int, bearing: Float
    ) {
        var current = _data.value
        val last = current.points.lastOrNull()
        val isGps = locationType == 1
        val newTime = if (time > 0) time else System.currentTimeMillis()

        // ---- 计步器：本周期新增步数 + 是否正在走 ----
        val helper = motionHelper
        val stepsSinceLastFix = helper?.consumePendingSteps() ?: 0
        val motionConfirmed = helper != null && (stepsSinceLastFix > 0 || helper.isWalking())

        // ---- 显示定位：GPS 优先，网络点仅在 GPS 失联超 30 秒时兜底 ----
        if (isGps) {
            lastGpsFixAt = newTime
            current = current.copy(
                lastLatitude = lat, lastLongitude = lng, lastAccuracy = accuracy,
                lastBearing = bearing, lastFixIsGps = true, lastFixTime = newTime
            )
        } else if (lastGpsFixAt == 0L || newTime - lastGpsFixAt > GPS_STALE_FOR_DISPLAY_MS) {
            current = current.copy(
                lastLatitude = lat, lastLongitude = lng, lastAccuracy = accuracy,
                lastBearing = bearing, lastFixIsGps = false, lastFixTime = newTime
            )
        }
        _data.value = current

        // ---- 入轨门槛：GPS 还新鲜时，网络点一律不入轨（防 WiFi 带偏轨迹） ----
        if (!isGps && lastGpsFixAt > 0L && newTime - lastGpsFixAt <= GPS_STALE_FOR_TRACK_MS) return

        // 精度门槛：GPS 点 ≤30 米（计步器确认移动中放宽到 ≤50 米）；非 GPS 点恒 ≤15 米
        val maxAccuracy = when {
            isGps && motionConfirmed -> 50f
            isGps -> 30f
            else -> 15f
        }
        if (accuracy > 0f && accuracy > maxAccuracy) return // 差点不入轨（显示定位已更新）

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
            fixCount = current.fixCount + 1,
            locationError = null   // 收到有效定位，清除错误提示
        )
        maybePersist()
    }

    /** 暂停（计时停止，定位停止） */
    fun pause() {
        if (_data.value.state != RecorderState.RECORDING) return
        // 结清本段：之后 segmentStartElapsed 冻结在暂停时刻，暂停期不再计入
        accumulatedDuration += SystemClock_elapsed() - segmentStartElapsed
        segmentStartElapsed = SystemClock_elapsed()
        _data.value = _data.value.copy(
            state = RecorderState.PAUSED,
            durationMillis = accumulatedDuration,
            currentSpeed = 0f
        )
        tickerJob?.cancel()
        motionHelper?.stop()
        locationClient?.stop()
        persistNow()   // 暂停态也要留档：退出 App 后再打开应停在暂停态
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
        locationClient?.stop()
        locationClient = null

        val d = _data.value
        clearSession()  // 无论成不成，这次记录都已结束，快照不再需要
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
        persistNow()   // 途经点带照片/录音，丢了不可恢复 → 立即落盘
    }

    // ==================== 跨进程恢复 ====================

    /** 恢复结果（给 UI 提示用） */
    data class RestoreInfo(
        /** 恢复到的状态：记录中 / 已暂停 */
        val state: RecorderState,
        val pointCount: Int,
        val distanceMeters: Double,
        /** 进程不在期间被计入的时长（暂停态恢复时为 0） */
        val creditedMillis: Long,
        /** 权限被收回时为 true：数据已恢复但停在暂停态，需先授权才能继续 */
        val needsPermission: Boolean
    )

    /**
     * 从磁盘快照恢复上一次未结束的记录（进程被杀后的唯一入口）。
     *
     * 调用点：
     * - MainActivity：冷启动（已同意隐私协议）时
     * - TrackRecordingService：被系统/闹钟重建（intent 为 null，进程是新的）时
     *
     * 时长口径：进程不在的那段时间用 SystemClock.elapsedRealtime 差值补齐
     * （它不受进程生死影响），但最多补 5 分钟——隔天再开 App
     * 不该记出 20 小时的时长。设备重启过（elapsed 归零）时退回 wall clock 差值。
     *
     * @param startService 是否顺带拉起前台服务（Service 内部调用时传 false，
     *   避免自己给自己发 startForegroundService）
     * @return 恢复成功返回 [RestoreInfo]，没有可恢复的记录（或本进程已在记录）返回 null
     */
    fun restore(context: Context, startService: Boolean = true): RestoreInfo? {
        if (isRecording) return null          // 本进程已有进行中的记录，绝不覆盖
        val appCtx = context.applicationContext
        appContext = appCtx
        val snap = RecordingSessionStore.load(appCtx)
        if (snap == null) {
            Log.d(TAG, "restore: 无快照，跳过")
            return null
        }
        // ★ 只看状态，不看点数：刚点「开始」就被杀（室内还没等到第一个 GPS 点）时
        //   points 为空，但用户眼里的状态是"记录中"，必须照样恢复。
        //   早期版本在这里加了 points 非空门槛，导致室内/刚开跑的测试一律恢复不了。
        if (snap.state == RecorderState.IDLE) {
            RecordingSessionStore.clear(appCtx)
            Log.d(TAG, "restore: 快照为 IDLE，丢弃")
            return null
        }
        Log.d(
            TAG, "restore: 命中 state=${snap.state} points=${snap.points.size} " +
                    "dist=${snap.distanceMeters} waypoints=${snap.waypoints.size}"
        )

        val nowElapsed = SystemClock_elapsed()
        var gap = nowElapsed - snap.savedAtElapsed
        // 设备重启过：elapsedRealtime 归零 → 用 wall clock 差值兜底
        if (gap < 0) gap = (System.currentTimeMillis() - snap.savedAtWall).coerceAtLeast(0)
        val wasRecording = snap.state == RecorderState.RECORDING
        // 本段在落盘时已走过的时间 + 进程不在期间（封顶）
        val segmentSoFar =
            if (wasRecording) (snap.savedAtElapsed - snap.segmentStartElapsed).coerceAtLeast(0) else 0L
        val credited = if (wasRecording) gap.coerceIn(0L, MAX_CREDIT_GAP_MS) else 0L

        accumulatedDuration = snap.accumulatedDuration + segmentSoFar + credited
        segmentStartElapsed = nowElapsed
        lastGpsFixAt = snap.lastGpsFixAt
        stepBaseline = snap.stepCount

        // 先落到 PAUSED：状态机只有 IDLE/PAUSED 能进 start()，
        // 之后按原来的状态决定是否真的继续跑定位
        _data.value = RecordingData(
            state = RecorderState.PAUSED,
            activityType = snap.activityType,
            points = snap.points,
            waypoints = snap.waypoints,
            distanceMeters = snap.distanceMeters,
            durationMillis = accumulatedDuration,
            climbMeters = snap.climbMeters,
            fixCount = snap.fixCount,
            stepCount = snap.stepCount,
            lastLatitude = snap.lastLatitude,
            lastLongitude = snap.lastLongitude,
            lastFixTime = snap.lastGpsFixAt
        )
        restoredFromDisk = true

        val hasLocation = hasLocationPermission(appCtx)
        if (wasRecording && hasLocation) {
            runCatching { start(appCtx, snap.activityType) }
                .onFailure { Log.w(TAG, "restore: 重启定位失败，停在暂停态", it) }
        }
        if (startService) runCatching {
            TrackRecordingService.start(appCtx)
        }.onFailure { Log.w(TAG, "restore: 拉起前台服务失败", it) }
        persistNow()

        val finalState = if (wasRecording && hasLocation) RecorderState.RECORDING else RecorderState.PAUSED
        Log.d(TAG, "restore: 完成 state=$finalState credited=${credited}ms points=${snap.points.size}")
        return RestoreInfo(
            state = finalState,
            pointCount = snap.points.size,
            distanceMeters = snap.distanceMeters,
            creditedMillis = credited,
            needsPermission = wasRecording && !hasLocation
        )
    }

    /** 丢弃磁盘快照（正常结束记录、或快照已失效时） */
    private fun clearSession() {
        appContext?.let { RecordingSessionStore.clear(it) }
        restoredFromDisk = false
        stepBaseline = 0
        lastPersistAt = 0L
        Log.d(TAG, "clearSession: 快照已清除")
    }

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** 构造当前快照（拷贝的是不可变 List，可安全跨线程交给写盘协程） */
    private fun snapshot(): RecordingSessionStore.Snapshot {
        val d = _data.value
        return RecordingSessionStore.Snapshot(
            state = d.state,
            activityType = d.activityType,
            points = d.points,
            waypoints = d.waypoints,
            distanceMeters = d.distanceMeters,
            climbMeters = d.climbMeters,
            fixCount = d.fixCount,
            stepCount = d.stepCount,
            accumulatedDuration = accumulatedDuration,
            segmentStartElapsed = segmentStartElapsed,
            savedAtElapsed = SystemClock_elapsed(),
            savedAtWall = System.currentTimeMillis(),
            lastLatitude = d.lastLatitude,
            lastLongitude = d.lastLongitude,
            lastGpsFixAt = lastGpsFixAt
        )
    }

    /**
     * 立即落盘一次（App 退后台 / 被划卡时调用）。
     * 常规路径是 3 秒节流写，退后台时补一次能把丢失窗口压到最短，
     * 同时刷新快照时间戳，让恢复时的"中断时长"算得更准。
     */
    fun flush() {
        if (_data.value.state != RecorderState.IDLE) persistNow()
    }

    /** 立即落盘（状态变化：开始 / 继续 / 暂停 / 打点） */
    private fun persistNow() {
        val ctx = appContext ?: return
        if (_data.value.state == RecorderState.IDLE) return
        lastPersistAt = SystemClock_elapsed()
        val snap = snapshot()
        ioScope.launch {
            runCatching { RecordingSessionStore.save(ctx, snap) }
                .onFailure { Log.w(TAG, "persistNow: 写快照失败", it) }
        }
    }

    /** 节流落盘：每 3 秒一次（每个新定位点都会调，不能每次都写） */
    private fun maybePersist() {
        val d = _data.value
        if (d.state == RecorderState.IDLE) return
        val now = SystemClock_elapsed()
        // 长轨迹（万点级）单次序列化已达 MB 级，把间隔放宽到 10 秒换取更低 I/O 压力
        val interval = if (d.points.size > 3000) 10_000L else PERSIST_INTERVAL_MS
        if (now - lastPersistAt < interval) return
        persistNow()
    }

    /** SystemClock.elapsedRealnode 的替身（单例中不可用 Context） */
    private fun SystemClock_elapsed(): Long = android.os.SystemClock.elapsedRealtime()

    /** GPS 失联超过该时长，蓝点才兜底显示网络定位（WiFi/基站实测可偏 20~50 米） */
    private const val GPS_STALE_FOR_DISPLAY_MS = 30_000L
    /** GPS 失联超过该时长，网络定位点才允许入轨（防 WiFi 点带偏轨迹） */
    private const val GPS_STALE_FOR_TRACK_MS = 60_000L
    /** 快照落盘节流间隔：强制被杀最多丢最后 3 秒的点 */
    private const val PERSIST_INTERVAL_MS = 3_000L
    /** 进程不在期间最多补记的时长（隔天再开 App 不该记出几十小时） */
    private const val MAX_CREDIT_GAP_MS = 5 * 60_000L
}
