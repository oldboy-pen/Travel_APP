package com.example.myfirstapp.track

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 轨迹颜色偏好（单例，SharedPreferences 持久化）：
 * - [navColor]：轨迹导航页"参考轨迹"（要走的路线）的线色，已走过/未走过用
 *   不透明/半透明同色区分；
 * - [liveColor]：实际生成轨迹的线色——记录页实时轨迹、详情页历史轨迹、
 *   导航中实际走过的轨迹线，三处统一使用。
 *
 * 默认两色不同（导航参考=绿、实际生成=蓝），均可由用户在图层面板自定义。
 * 颜色值是 ARGB Int（与 MapEngine.addPolyline 的 colorArgb 一致）。
 *
 * 读取方式：UI 订阅 [navColor]/[liveColor]（StateFlow），颜色一变即重绘；
 * 绘制函数内部也可直接 .value 取当前值（导航页 2 秒轮询重绘就是这么用的）。
 */
object TrackColorStore {

    private const val PREFS = "track_colors"
    private const val KEY_NAV = "nav_track_color"
    private const val KEY_LIVE = "live_track_color"

    /** 默认：导航参考轨迹 = 绿（延续旧版已走过部分的颜色） */
    const val DEFAULT_NAV = 0xFF2E7D32.toInt()

    /** 默认：实际生成轨迹 = 蓝（与定位蓝点/方向箭头同色系，和导航参考区分） */
    const val DEFAULT_LIVE = 0xFF1E88E5.toInt()

    private val _navColor = MutableStateFlow(DEFAULT_NAV)
    val navColor: StateFlow<Int> = _navColor.asStateFlow()

    private val _liveColor = MutableStateFlow(DEFAULT_LIVE)
    val liveColor: StateFlow<Int> = _liveColor.asStateFlow()

    private var loaded = false

    /** 从 SharedPreferences 载入（各地图页与图层面板进入时调用，重复调用无副作用） */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _navColor.value = prefs.getInt(KEY_NAV, DEFAULT_NAV)
        _liveColor.value = prefs.getInt(KEY_LIVE, DEFAULT_LIVE)
    }

    fun setNavColor(context: Context, argb: Int) {
        _navColor.value = argb
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_NAV, argb).apply()
    }

    fun setLiveColor(context: Context, argb: Int) {
        _liveColor.value = argb
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LIVE, argb).apply()
    }

    /** 把某颜色降为半透明（用于导航参考线"未走过"部分，保持同色系区分已走/未走） */
    fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
}
