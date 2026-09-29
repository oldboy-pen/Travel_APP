package com.example.myfirstapp.map

import android.graphics.Bitmap
import android.view.View
import com.example.myfirstapp.mapsources.MapSource

/** 地图厂商（= 用哪家的原生 SDK 渲染） */
enum class MapEngineKind(val label: String) {
    AMAP("高德"),
    TENCENT("腾讯"),
    BAIDU("百度")
}

/** 地图控件的开关（各家控件名字不一样，统一抽出来） */
data class MapUiSettings(
    val zoomControls: Boolean = false,        // 右下角缩放按钮
    val myLocationButton: Boolean = false,    // 右上角/右下角回到我位置的按钮（百度/腾讯不一定有）
    val compass: Boolean = false              // 指北针
)

/**
 * 地图引擎抽象层：屏蔽高德 / 腾讯 / 百度三家 SDK 的 API 差异。
 *
 * 使用纪律（很重要，破坏了就失去抽象的意义）：
 * 1. 业务层只能用本接口 + GeoPoint，**不得 import 任何一家地图 SDK 的类**；
 * 2. 传入的坐标、回调出来的坐标，一律是 GCJ-02；
 * 3. clearOverlays() 只清除本接口画上去的东西，**不能**连带清掉底图瓦片层
 *    （各家 SDK 的 clear() 都会连瓦片层一起清，因此引擎内部需要自己记账逐个 remove）。
 */
interface MapEngine {

    val kind: MapEngineKind

    /** 嵌入 Compose AndroidView 的原生地图容器 View */
    val view: View

    // ==================== 生命周期 ====================
    /** 首次创建时调用（幂等）。高德必须要 onCreate 才能出图，腾讯/百度不需要 */
    fun onCreate()
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    /** 页面真正销毁 / Activity 销毁时释放，之后不可再用 */
    fun onDestroy()

    // ==================== 底图 / 叠加层 ====================
    /**
     * 应用底图。
     * - source.nativeType 属于本引擎 → 切原生地图类型（矢量/卫星/夜景…）
     * - source 是瓦片图源（天地图 / 自定义 XYZ）→ 只有高德引擎支持叠加，其余引擎忽略
     */
    fun applyBase(source: MapSource)

    /** 应用半透明叠加层（null=无）。瓦片叠加同样只有高德引擎支持 */
    fun applyOverlay(source: MapSource?)

    // ==================== 控件 ====================
    fun setUiSettings(settings: MapUiSettings)

    // ==================== 相机 ====================
    fun moveCamera(target: GeoPoint, zoom: Float)
    /** 平移到某点，保持当前缩放级别 */
    fun animateCamera(target: GeoPoint)
    fun animateCamera(target: GeoPoint, zoom: Float)
    /** 把一批点完整地框进视野 */
    fun fitBounds(points: List<GeoPoint>, paddingPx: Int, animate: Boolean = false)

    // ==================== 覆盖物 ====================
    /** 画轨迹线。widthPx 为屏幕像素，colorArgb 为 ARGB 值 */
    fun addPolyline(points: List<GeoPoint>, widthPx: Float, colorArgb: Int)
    /**
     * 打点。bitmap 为空则用 SDK 默认图标。
     * @param rotateDeg 图标旋转角（度），从正北方向顺时针（与 GPS bearing 同向），
     *                  用于"箭头指向行进方向"；0 = 不旋转（bitmap 原样朝上）
     */
    fun addMarker(
        point: GeoPoint,
        bitmap: Bitmap? = null,
        title: String? = null,
        anchorU: Float = 0.5f,
        anchorV: Float = 0.5f,
        zIndex: Float = 0f,
        rotateDeg: Float = 0f
    )

    /** 精度圈 */
    fun addCircle(
        center: GeoPoint,
        radiusMeters: Double,
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Float
    )

    /** 清除本引擎画过的全部覆盖物（不含底图瓦片层） */
    fun clearOverlays()

    // ==================== 定位蓝点 ====================
    /**
     * 开关 SDK 自带蓝点。
     * @param follow true=跟随（镜头持续居中）/ false=只显示位置不移动镜头
     */
    fun setMyLocationEnabled(enabled: Boolean, follow: Boolean = false)

    /**
     * SDK 自带蓝点的位置回调（用于"首次定位自动回中"）。
     * 不支持该回调的引擎（百度）保持空实现，业务层需有兜底策略。
     */
    fun setLocationChangeListener(listener: ((GeoPoint) -> Unit)?)

    /**
     * 把 App 自己拿到的定位喂给地图蓝点。
     *
     * 百度 SDK 自身没有取位置的逻辑，必须由业务侧喂 MyLocationData 才会出现蓝点；
     * 高德/腾讯自带定位客户端，留空实现即可（喂了也不会更准）。
     */
    fun updateDeviceLocation(point: GeoPoint, accuracyMeters: Float, bearingDeg: Float)

    // ==================== 交互 ====================
    fun setLongClickListener(listener: ((GeoPoint) -> Unit)?)
    /** 用户手势（拖动/甩动/双击/缩放）回调，用于"手动拖图退出跟随模式" */
    fun setUserGestureListener(listener: (() -> Unit)?)
}
