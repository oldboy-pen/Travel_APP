package com.example.myfirstapp.track

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 运动传感器辅助（解决徒步/登山小位移被 GPS 降噪误杀的问题）：
 *
 * GPS 无法区分"真移动"与"静止漂移"——徒步 1 秒只走 1~1.5 米，和 GPS 噪声
 * 抖动同量级，只能靠保守阈值取舍。硬件计步器（TYPE_STEP_DETECTOR）恰好
 * 补上这块拼图：每走一步触发一次事件，直接回答"用户在不在走"。
 *
 * 用途（见 TrackRecorder.onNewFix）：
 * - 计步器确认在走 → 小位移不再被当静止漂移丢弃，GPS 精度门槛放宽到 50 米
 * - 计步器确认静止（休息点） → 维持严格漂移过滤，防里程虚增
 *
 * 线程模型：计步事件回调在主线程，onNewFix 在高德定位线程，
 * 因此计数值全部使用原子类型，可安全跨线程读取。
 *
 * 降级：无计步传感器（极老机型）或未授予 ACTIVITY_RECOGNITION 权限时
 * start() 返回 false、isWalking() 恒为 false，记录逻辑自动回退到
 * 纯 GPS 阈值过滤（即上一版行为），不会崩溃或报错。
 *
 * 注意：陀螺仪只能测转动（手机转了多大角度），测不了平移，无法直接
 * 感知"人往前走了几米"；"步数 × 步长 × 航向"的惯性推算（PDR）误差
 * 随时间快速累积，不适合直接生成轨迹点，故不采用。
 */
class MotionSensorHelper(context: Context) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** 硬件计步传感器：每走一步上报一次事件（区别于累计式的 STEP_COUNTER） */
    private val stepDetector: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    /** 传感器是否真实在监听（需传感器存在 + ACTIVITY_RECOGNITION 权限） */
    @Volatile
    var running: Boolean = false
        private set

    /** 本段记录累计步数 */
    private val totalSteps = AtomicInteger(0)

    /** 距上次消费以来的新增步数（供每次定位回调查询后清零） */
    private val pendingSteps = AtomicInteger(0)

    /** 最近一步的系统时刻（elapsedRealtime，息屏不受影响） */
    private val lastStepAt = AtomicLong(0L)

    /** 开始监听计步（记录开始/恢复时调用）。无传感器或无权限时返回 false。 */
    fun start(): Boolean {
        val sm = sensorManager ?: return false
        val sensor = stepDetector ?: return false
        if (running) return true
        running = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        return running
    }

    /** 停止监听（暂停/结束时调用） */
    fun stop() {
        sensorManager?.unregisterListener(this)
        running = false
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
        if (event?.sensor?.type != Sensor.TYPE_STEP_DETECTOR) return
        totalSteps.incrementAndGet()
        pendingSteps.incrementAndGet()
        lastStepAt.set(SystemClock.elapsedRealtime())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** 判定"仍在走"的滑动窗口：覆盖慢速徒步约 2 步/步频的间隔 */
        private const val WINDOW_MS = 4_000L
    }
}
