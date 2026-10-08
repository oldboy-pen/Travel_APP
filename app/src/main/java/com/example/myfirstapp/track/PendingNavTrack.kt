package com.example.myfirstapp.track

/**
 * 地图页「App 内导航」的临时路线载体。
 *
 * 地图页把规划好的驾车路线（polyline）构建成一条内存中的 [Track] 放这里，
 * 再跳到导航页 [com.example.myfirstapp.ui.screens.TrackNavigationScreen] 读取并导航。
 * 全程不落库，因此不会在「足迹」里留下一条"规划路线"；只有到达后实际行驶的
 * 轨迹会被 [TrackNavigator.takeActualTrack] 存盘。
 *
 * 注意：取出用 [get]（只读取、不清除），清除由导航页退出时统一调用 [clear]，
 * 这样屏幕旋转/重组时单例仍持有路线，不会误退出。
 */
object PendingNavTrack {
    private var track: Track? = null

    fun set(t: Track) {
        track = t
    }

    /** 读取（不清除）；取出后应由 [clear] 在退出时清理 */
    fun get(): Track? = track

    fun clear() {
        track = null
    }
}
