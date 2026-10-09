package com.example.myfirstapp.globe

/**
 * 运动页 → 3D 地球的临时数据（与 PendingNavTrack 一个套路，纯内存、不落库）。
 * 从运动页进地球时把「当前记录的轨迹 + 已叠加的参考轨迹 + 当前位置」带过来。
 */
object PendingGlobeData {

    @Volatile private var linesVar: List<GlobeLine> = emptyList()
    @Volatile private var markerVar: LonLat? = null

    val lines: List<GlobeLine> get() = linesVar
    val marker: LonLat? get() = markerVar

    fun set(lines: List<GlobeLine>, marker: LonLat?) {
        linesVar = lines
        markerVar = marker
    }

    fun clear() {
        linesVar = emptyList()
        markerVar = null
    }

    /** 一次性取走并清空（进页面时用它，避免返回后残留） */
    fun take(): Pair<List<GlobeLine>, LonLat?> = Pair(linesVar, markerVar).also { clear() }
}
