package com.example.myfirstapp.map

/**
 * App 内部统一的坐标点。
 *
 * ★ 坐标系约定：App 内部一律使用 GCJ-02（火星坐标）
 *   - 定位来自高德定位 SDK（TrackRecorder / MapViewModel），本身就是 GCJ-02；
 *   - 高德地图 SDK、腾讯地图 SDK 原生就是 GCJ-02，原样传入即可；
 *   - 百度地图 SDK 原生是 BD-09，但已在 MapSdkPrivacy 里用
 *     SDKInitializer.setCoordType(CoordType.GCJ02) 把整个 SDK 的输入输出
 *     统一成 GCJ-02，业务层无需换算。
 *
 * 这样所有地图页/业务代码只用 GeoPoint，不再出现某个厂商的 LatLng 类型泄漏。
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double
) {
    /** 粗滤非法坐标（定位 SDK 偶发返回 0,0 或超出范围的值） */
    val isValid: Boolean
        get() = latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
                (latitude != 0.0 || longitude != 0.0)
}
