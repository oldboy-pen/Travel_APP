package com.example.myfirstapp.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 计步可用性状态（供 UI 提示用户"为什么步数不动"） */
enum class StepSensorStatus {
    /** 正常计步 */
    ACTIVE,
    /** 设备没有计步传感器（DETECTOR 和 COUNTER 都没有） */
    NO_SENSOR,
    /** Android 10+ 未授予「身体活动」权限 */
    NO_PERMISSION
}

/**
 * 运动传感器辅助（解决徒步/登山小位移被 GPS 降噪误杀的问题）：
 *
 * GPS 无法区分"真移动"与"静止漂移"——徒步 1 秒只走 1~1.5 米，和 GPS 噪声
 * 抖动同量级，只能靠保守阈值取舍。硬件计步器恰好补上这块拼图：
 * 每走一步触发一次事件，直接回答"用户在不在走"。
 *
 * 传感器策略：
 * - 优先 TYPE_STEP_DETECTOR（单步式，每步一个事件）
 * - 回退 TYPE_STEP_COUNTER（累计式，报告开机以来总步数，取增量）——
 *   大量机型（部分华为/荣耀/三星）只有 COUNTER 没有 DETECTOR，
 *   只用 DETECTOR 会导致步数恒为 0
 *
 * 用途（见 TrackRecorder.onNewFix）：
 * - 计步器确认在走 → 小位移不再被当静止漂移丢弃，GPS 精度门槛放宽到 50 米
 * - 计步器确认静止（休息点） → 维持严格漂移过滤，防里程虚增
 *
 * 线程模型：计步事件回调在主线程，onNewFix 在高德定位线程，
 * 因此计数值全部使用原子类型，可安全跨线程读取。
 *
 * 降级：无计步传感器或未授予 ACTIVITY_RECOGNITION 权限时 start() 返回
 * false、isWalking() 恒为 false、status 置为 NO_SENSOR / NO_PERMISSION，
 * 记录逻辑自动回退到纯 GPS 阈值过滤（不会崩溃或报错）。
 */
class MotionSensorHelper(
    context: Context,
    /** 起始步数：进程被杀后恢复记录时接回已累计的步数（默认 0 = 全新一段） */
    initialSteps: Int = 0
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** 硬件计步传感器：每走一步上报一次事件（区别于累计式的 STEP_COUNTER） */
    private val stepDetector: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    /** 累计式计步传感器：报告开机以来总步数（DETECTOR 缺失时的回退） */
    private val stepCounter: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    /** 传感器是否真实在监听（需传感器存在 + ACTIVITY_RECOGNITION 权限） */
    @Volatile
    var running: Boolean = false
        private set

    /** 计步可用性（start() 后有效；UI 据此提示用户） */
    @Volatile
    var status: StepSensorStatus = StepSensorStatus.NO_SENSOR
        private set

    /** 本段记录累计步数（恢复场景从 [initialSteps] 起步，不丢历史步数） */
    private val totalSteps = AtomicInteger(initialSteps)

    /** 距上次消费以来的新增步数（供每次定位回调查询后清零） */
    private val pendingSteps = AtomicInteger(0)

    /** 最近一步的系统时刻（elapsedRealtime，息屏不受影响） */
    private val lastStepAt = AtomicLong(0L)

    /** STEP_COUNTER 基线：首个读数 = 开机以来总步数，之后只取增量（仅主线程访问） */
    private var counterBase = -1f

    /** 开始监听计步（记录开始/恢复时调用）。无传感器或无权限时返回 false 并置 status。 */
    fun start(): Boolean {
        val sm = sensorManager
        if (sm == null) {
            status = StepSensorStatus.NO_SENSOR
            return false
        }
        // Android 10+ 计步传感器需要「身体活动」运行时权限
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACTIVITY_RECOGNITION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            status = StepSensorStatus.NO_PERMISSION
            return false
        }
        val sensor = stepDetector ?: stepCounter
        if (sensor == null) {
            status = StepSensorStatus.NO_SENSOR
            return false
        }
        if (running) {
            status = StepSensorStatus.ACTIVE
            return true
        }
        // maxReportLatencyUs=0：不进 batch FIFO，尽力保证息屏时事件及时送达
        running = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI, 0)
        status = if (running) StepSensorStatus.ACTIVE else StepSensorStatus.NO_SENSOR
        return running
    }

    /** 停止监听（暂停/结束时调用）。累计步数保留（同一 helper 内不清零）。 */
    fun stop() {
        sensorManager?.unregisterListener(this)
        running = false
        counterBase = -1f // 下次 start 重新取基线，避免把暂停期间的步数算进来
    }

    /** 累计步数（自创建起，暂停不清零，供 UI 显示） */
    fun stepCount(): Int = totalSteps.get()

    /** 取走"自上次定位回调以来"的新增步数并清零（每次 GPS 回调调用） */
    fun consumePendingSteps(): Int = pendingSteps.getAndSet(0)

    /** 最近 [WINDOW_MS] 毫秒内是否有步子 → 用户确实在走 */
    fun isWalking(): Boolean {
        val last = lastStepAt.get()
        return last > 0 && SystemClock.elapsedRealtime() - last < WINDOW_MS
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                totalSteps.incrementAndGet()
                pendingSteps.incrementAndGet()
                lastStepAt.set(SystemClock.elapsedRealtime())
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values.firstOrNull() ?: return
                if (counterBase < 0f) {
                    counterBase = total // 首个读数作为基线（开机以来总数）
                } else {
                    val delta = (total - counterBase).toInt()
                    if (delta > 0) {
                        totalSteps.addAndGet(delta)
                        pendingSteps.addAndGet(delta)
                        lastStepAt.set(SystemClock.elapsedRealtime())
                        counterBase = total
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** 判定"仍在走"的滑动窗口：覆盖慢速徒步约 2 步/步频的间隔 */
        private const val WINDOW_MS = 4_000L
    }
}
