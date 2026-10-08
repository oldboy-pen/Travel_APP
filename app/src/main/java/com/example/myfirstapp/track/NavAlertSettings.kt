package com.example.myfirstapp.track

import android.content.Context

/**
 * 导航偏离预警的可配置参数（单例，SharedPreferences 持久化，prefs 名 `nav_alert_settings`）。
 * 原来这些是 [TrackNavigator] 里的硬编码常量，改不动；现在导航页可以随时调：
 * - [alertMeters]：一级偏离阈值，超过它开始语音预警（默认 20 米）
 * - [severeMeters]：二级偏离阈值，超过它播报**实际偏离距离**并加密补播（默认 50 米）
 * - [reAlertMildMeters]：一级偏离时每走这么多米补播一次（默认 1000 米）
 * - [reAlertSevereMeters]：二级偏离时每走这么多米补播一次（默认 200 米）
 *
 * 补播按**走过的里程**而非时间：走得快自然报得勤，原地扎营不会被反复骚扰。
 *
 * 生效方式：[TrackNavigator] 每次定位判定都直接读这里的 @Volatile 字段，
 * 所以改完设置下一次定位（1 秒内）就生效，不用重启导航。
 *
 * 约束（[save] 里已做边界收敛，UI 无需再操心）：二级 > 一级、阈值有下限、间隔不得过小。
 */
object NavAlertSettings {

    /** 默认一级偏离阈值（米） */
    const val DEFAULT_ALERT_METERS = 20.0

    /** 默认二级偏离阈值（米）：播报实际距离 */
    const val DEFAULT_SEVERE_METERS = 50.0

    /** 默认一级补播间隔（米） */
    const val DEFAULT_RE_ALERT_MILD = 1000.0

    /** 默认二级补播间隔（米） */
    const val DEFAULT_RE_ALERT_SEVERE = 200.0

    // ---- 边界 ----
    /** 一级阈值下限：再小就会被 GPS 自身误差（5~15 米）淹没，一路乱报 */
    const val MIN_ALERT_METERS = 10.0
    /** 二级阈值至少要比一级多这么多，否则两档分不开 */
    private const val MIN_SEVERE_GAP = 5.0
    /** 补播间隔下限：太小会变成一直在说话 */
    private const val MIN_RE_ALERT = 50.0

    private const val PREFS = "nav_alert_settings"
    private const val KEY_ALERT = "alert_meters"
    private const val KEY_SEVERE = "severe_meters"
    private const val KEY_RE_ALERT_MILD = "re_alert_mild_meters"
    private const val KEY_RE_ALERT_SEVERE = "re_alert_severe_meters"

    @Volatile var alertMeters: Double = DEFAULT_ALERT_METERS
        private set

    @Volatile var severeMeters: Double = DEFAULT_SEVERE_METERS
        private set

    @Volatile var reAlertMildMeters: Double = DEFAULT_RE_ALERT_MILD
        private set

    @Volatile var reAlertSevereMeters: Double = DEFAULT_RE_ALERT_SEVERE
        private set

    private var loaded = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 载入持久化值（幂等；导航开始与各页面进入时调用均可） */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        alertMeters = sanitizeAlert(
            p.getFloat(KEY_ALERT, DEFAULT_ALERT_METERS.toFloat()).toDouble()
        )
        severeMeters = p.getFloat(KEY_SEVERE, DEFAULT_SEVERE_METERS.toFloat()).toDouble()
        reAlertMildMeters =
            p.getFloat(KEY_RE_ALERT_MILD, DEFAULT_RE_ALERT_MILD.toFloat()).toDouble()
        reAlertSevereMeters =
            p.getFloat(KEY_RE_ALERT_SEVERE, DEFAULT_RE_ALERT_SEVERE.toFloat()).toDouble()
        sanitizeAll()
    }

    /**
     * 保存四项参数：先做边界收敛（一级不低于 [MIN_ALERT_METERS]、二级至少比一级大
     * [MIN_SEVERE_GAP]、补播间隔不低于 [MIN_RE_ALERT]），再落盘并更新内存值。
     * 返回收敛后的实际值，UI 用它刷新输入框即可。
     */
    @Synchronized
    fun save(
        context: Context,
        alert: Double,
        severe: Double,
        reAlertMild: Double,
        reAlertSevere: Double
    ): Result {
        ensureLoaded(context)
        alertMeters = sanitizeAlert(alert)
        severeMeters = severe
        reAlertMildMeters = reAlertMild
        reAlertSevereMeters = reAlertSevere
        sanitizeAll()
        prefs(context).edit()
            .putFloat(KEY_ALERT, alertMeters.toFloat())
            .putFloat(KEY_SEVERE, severeMeters.toFloat())
            .putFloat(KEY_RE_ALERT_MILD, reAlertMildMeters.toFloat())
            .putFloat(KEY_RE_ALERT_SEVERE, reAlertSevereMeters.toFloat())
            .apply()
        return current()
    }

    /** 恢复出厂设置（四项都回到默认） */
    fun reset(context: Context): Result =
        save(
            context,
            DEFAULT_ALERT_METERS,
            DEFAULT_SEVERE_METERS,
            DEFAULT_RE_ALERT_MILD,
            DEFAULT_RE_ALERT_SEVERE
        )

    /** 当前生效值快照 */
    fun current() = Result(alertMeters, severeMeters, reAlertMildMeters, reAlertSevereMeters)

    /**
     * 迟滞解除线：偏离掉回该距离以内才退出"偏离态"。
     * 取阈值的一定比例而不是写死，这样用户把阈值调到 100 米时不会仍在 15 米就解除。
     */
    val clearMeters: Double get() = (alertMeters * 0.75).coerceAtLeast(1.0)

    /** 二级同样按比例的迟滞解除线：掉回该距离以内才退回一级 */
    val severeClearMeters: Double get() = (severeMeters * 0.9).coerceAtLeast(clearMeters)

    private fun sanitizeAlert(v: Double) =
        if (v.isNaN() || v < MIN_ALERT_METERS) MIN_ALERT_METERS else v

    /** 收敛二级与两个间隔，保证"二级 > 一级、间隔不闹腾" */
    private fun sanitizeAll() {
        if (severeMeters.isNaN() || severeMeters < alertMeters + MIN_SEVERE_GAP) {
            severeMeters = alertMeters + MIN_SEVERE_GAP
        }
        if (reAlertMildMeters.isNaN() || reAlertMildMeters < MIN_RE_ALERT) {
            reAlertMildMeters = MIN_RE_ALERT
        }
        if (reAlertSevereMeters.isNaN() || reAlertSevereMeters < MIN_RE_ALERT) {
            reAlertSevereMeters = MIN_RE_ALERT
        }
    }

    /** 四项参数的快照（便于 UI 一次性取值/回填） */
    data class Result(
        val alertMeters: Double,
        val severeMeters: Double,
        val reAlertMildMeters: Double,
        val reAlertSevereMeters: Double
    )
}
