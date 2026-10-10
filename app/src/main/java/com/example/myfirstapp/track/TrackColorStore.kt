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
 *   导航中实际走过的轨迹线，三处统一使用；
 * - [overlayColors]：运动页"加载的参考轨迹"按轨迹 id 单独记的线色，
 *   没有自定义时统一用 [DEFAULT_OVERLAY]。
 *
 * 默认两色不同（导航参考=绿、实际生成=蓝），均可由用户在图层面板自定义；
 * 叠加的参考轨迹默认橙红，可在「加载轨迹」面板里逐条改色。
 * 颜色值是 ARGB Int（与 MapEngine.addPolyline 的 colorArgb 一致）。
 *
 * 读取方式：UI 订阅 [navColor]/[liveColor]/[overlayColors]（StateFlow），颜色一变即重绘；
 * 绘制函数内部也可直接 .value 取当前值（导航页 2 秒轮询重绘就是这么用的）。
 */
object TrackColorStore {

    private const val PREFS = "track_colors"
    private const val KEY_NAV = "nav_track_color"
    private const val KEY_LIVE = "live_track_color"
    /** 叠加参考轨迹按 id 存色：KEY_OVERLAY_PREFIX + trackId */
    private const val KEY_OVERLAY_PREFIX = "overlay_"

    /** 默认：导航参考轨迹 = 绿（延续旧版已走过部分的颜色） */
    const val DEFAULT_NAV = 0xFF2E7D32.toInt()

    /** 默认：实际生成轨迹 = 蓝（与定位蓝点/方向箭头同色系，和导航参考区分） */
    const val DEFAULT_LIVE = 0xFF1E88E5.toInt()

    /** 默认：运动页加载的参考轨迹 = 橙红（和上面两条都拉开距离） */
    const val DEFAULT_OVERLAY = 0xFFFF6D00.toInt()

    private val _navColor = MutableStateFlow(DEFAULT_NAV)
    val navColor: StateFlow<Int> = _navColor.asStateFlow()

    private val _liveColor = MutableStateFlow(DEFAULT_LIVE)
    val liveColor: StateFlow<Int> = _liveColor.asStateFlow()

    /**
     * 地图线色的最低不透明度（25%）。
     *
     * 背景：取色器的透明度条「按下即生效」，改色时很容易把 alpha 拖到 0，
     * 线被画成全透明 —— 表现就是"改完颜色轨迹从地图上消失了"。
     * 这里在【读写两端】都兜底，低于该值的颜色一律补成完全不透明（RGB 保留）。
     */
    const val MIN_LINE_ALPHA = 0x40

    /** 叠加参考轨迹的自定义颜色表：id → ARGB，缺失即用 [DEFAULT_OVERLAY] */
    private val _overlayColors = MutableStateFlow<Map<String, Int>>(emptyMap())
    val overlayColors: StateFlow<Map<String, Int>> = _overlayColors.asStateFlow()

    private var loaded = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 修掉"看不见的线色"：alpha 低于 [MIN_LINE_ALPHA] 时补到完全不透明，RGB 原样保留。
     *
     * 只作用于【存下来要给地图画线用】的颜色；[withAlpha] 那种运行时主动降透明的场景不经过这里。
     * 存量脏数据（alpha=0 的历史记录）在读取时也会被自动修好，无需清数据。
     */
    fun sanitize(argb: Int): Int =
        if ((argb ushr 24) < MIN_LINE_ALPHA) (argb and 0x00FFFFFF) or 0xFF000000.toInt()
        else argb

    /** 从 SharedPreferences 载入（各地图页与图层面板进入时调用，重复调用无副作用） */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        _navColor.value = sanitize(p.getInt(KEY_NAV, DEFAULT_NAV))
        _liveColor.value = sanitize(p.getInt(KEY_LIVE, DEFAULT_LIVE))
        // overlay_ 前缀的全部读出来，一次建表（轨迹条数很少，不必懒加载）
        _overlayColors.value = p.all.mapNotNull { (k, v) ->
            if (!k.startsWith(KEY_OVERLAY_PREFIX)) return@mapNotNull null
            val color = v as? Int ?: return@mapNotNull null
            k.removePrefix(KEY_OVERLAY_PREFIX) to sanitize(color)
        }.toMap()
    }

    fun setNavColor(context: Context, argb: Int) {
        val safe = sanitize(argb)
        _navColor.value = safe
        prefs(context).edit().putInt(KEY_NAV, safe).apply()
    }

    fun setLiveColor(context: Context, argb: Int) {
        val safe = sanitize(argb)
        _liveColor.value = safe
        prefs(context).edit().putInt(KEY_LIVE, safe).apply()
    }

    /** 某条叠加轨迹的线色（未自定义 → [DEFAULT_OVERLAY]；透明色会被 [sanitize] 修正） */
    fun overlayColorOf(id: String): Int = sanitize(_overlayColors.value[id] ?: DEFAULT_OVERLAY)

    /** 给某条叠加轨迹单独设色（按 id 持久化，与全局的 nav/live 互不影响） */
    fun setOverlayColor(context: Context, id: String, argb: Int) {
        val safe = sanitize(argb)
        _overlayColors.value = _overlayColors.value + (id to safe)
        prefs(context).edit().putInt(KEY_OVERLAY_PREFIX + id, safe).apply()
    }

    /** 清掉某条轨迹的自定义色（轨迹被删除时一起清理，避免残留死条目） */
    fun clearOverlayColor(context: Context, id: String) {
        _overlayColors.value = _overlayColors.value - id
        prefs(context).edit().remove(KEY_OVERLAY_PREFIX + id).apply()
    }

    /** 把某颜色降为半透明（用于导航参考线"未走过"部分，保持同色系区分已走/未走） */
    fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
}
