package com.example.myfirstapp.map

import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.CircleOptions
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.MyLocationData
import com.baidu.mapapi.map.Overlay
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.map.Stroke
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.model.LatLngBounds
import com.example.myfirstapp.mapsources.MapSource
import com.example.myfirstapp.mapsources.NativeMapType

/**
 * 百度地图引擎（BaiduMapSDK_Map 8.2.x）。
 *
 * ★ 坐标系：百度原生是 BD-09，但我们在 MapSdkPrivacy 里调用了
 *   SDKInitializer.setCoordType(CoordType.GCJ02)，整个 SDK 的输入输出被改成 GCJ-02，
 *   因此这里和腾讯一样是坐标直传，业务层不用换算。
 *   如果发现轨迹跟底图有几百米偏差（说明该版本 SDK 的 setCoordType 未完全生效），
 *   把 MapSdkPrivacy 里的 CoordType.GCJ02 改成 BD09LL，并在这里的两个
 *   [toBaidu] / [LatLng.toGeo] 里补 GeoTransform 的 GCJ↔BD09 换算即可。
 *
 * ★ 必须用 MapView（普通 GL 视图）而不是 TextureMapView：虽然 8.2 两种都提供，
 *   但 MapView 在 Compose AndroidView 里 attach/detach 更稳。
 *
 * ★ 百度没有内置取位置的逻辑：蓝点靠业务侧调 [updateDeviceLocation] 喂数据。
 */
class BaiduMapEngine(context: Context) : MapEngine {

    override val kind: MapEngineKind = MapEngineKind.BAIDU

    private val mapView = MapView(context)
    override val view: View get() = mapView
    private val baiduMap: BaiduMap = mapView.map

    private val overlays = mutableListOf<Overlay>()

    private var longClickCb: ((GeoPoint) -> Unit)? = null
    private var gestureCb: (() -> Unit)? = null

    override fun onCreate() = Unit // 百度 8.x 没有 onCreate

    override fun onStart() = Unit
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onStop() = Unit

    override fun onDestroy() {
        clearOverlays()
        mapView.onDestroy()
    }

    // ==================== 底图 ====================

    override fun applyBase(source: MapSource) {
        baiduMap.mapType = when (source.nativeType) {
            NativeMapType.BAIDU_SATELLITE -> BaiduMap.MAP_TYPE_SATELLITE
            NativeMapType.BAIDU_NORMAL -> BaiduMap.MAP_TYPE_NORMAL
            // 瓦片图源（天地图/自定义）不会路由到百度引擎；兜底给矢量图
            else -> BaiduMap.MAP_TYPE_NORMAL
        }
    }

    override fun applyOverlay(source: MapSource?) = Unit // 百度引擎不支持第三方瓦片叠加

    // ==================== 控件 / 相机 ====================

    override fun setUiSettings(settings: MapUiSettings) {
        // ★ 各家 API 不在同一个类上（javap 核实过 8.2.0.2 的真实签名）：
        //   指北针 → BaiduMap.getUiSettings().setCompassEnabled(boolean)
        //   缩放按钮 → MapView.showZoomControls(boolean)，UiSettings 里根本没有这个方法
        baiduMap.uiSettings.setCompassEnabled(settings.compass)
        mapView.showZoomControls(settings.zoomControls)
        // 百度没有"回到我的位置"按钮，由页面上的定位 FAB 代替
    }

    override fun moveCamera(target: GeoPoint, zoom: Float) {
        baiduMap.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(target.toBaidu(), zoom))
    }

    override fun animateCamera(target: GeoPoint) {
        baiduMap.animateMapStatus(MapStatusUpdateFactory.newLatLng(target.toBaidu()))
    }

    override fun animateCamera(target: GeoPoint, zoom: Float) {
        baiduMap.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(target.toBaidu(), zoom))
    }

    override fun fitBounds(points: List<GeoPoint>, paddingPx: Int, animate: Boolean) {
        if (points.isEmpty()) return
        val builder = LatLngBounds.Builder()
        points.forEach { builder.include(it.toBaidu()) }
        val update = MapStatusUpdateFactory.newLatLngBounds(builder.build(), paddingPx, paddingPx)
        if (animate) baiduMap.animateMapStatus(update) else baiduMap.setMapStatus(update)
    }

    // ==================== 覆盖物 ====================

    override fun addPolyline(points: List<GeoPoint>, widthPx: Float, colorArgb: Int) {
        if (points.size < 2) return
        overlays += baiduMap.addOverlay(
            PolylineOptions()
                .points(points.map { it.toBaidu() })
                .width(widthPx)
                .color(colorArgb)
        )
    }

    override fun addMarker(
        point: GeoPoint,
        bitmap: Bitmap?,
        title: String?,
        anchorU: Float,
        anchorV: Float,
        zIndex: Float
    ) {
        val options = MarkerOptions()
            .position(point.toBaidu())
            .anchor(anchorU, anchorV)
            .zIndex(zIndex.toInt())
        bitmap?.let { options.icon(BitmapDescriptorFactory.fromBitmap(it)) }
        title?.let { options.title(it) }
        overlays += baiduMap.addOverlay(options)
    }

    override fun addCircle(
        center: GeoPoint,
        radiusMeters: Double,
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Float
    ) {
        overlays += baiduMap.addOverlay(
            CircleOptions()
                .center(center.toBaidu())
                .radius(radiusMeters.toInt())
                .fillColor(fillColor)
                .stroke(Stroke(strokeWidthPx, strokeColor))
        )
    }

    override fun clearOverlays() {
        overlays.forEach { runCatching { it.remove() } }
        overlays.clear()
    }

    // ==================== 定位 ====================

    override fun setMyLocationEnabled(enabled: Boolean, follow: Boolean) {
        baiduMap.isMyLocationEnabled = enabled
    }

    /** 百度没有"SDK 自己产出的定位回调"，业务侧改用 setLocationChangeListener 的上游数据流 */
    override fun setLocationChangeListener(listener: ((GeoPoint) -> Unit)?) = Unit

    /**
     * 喂位置给百度蓝点。百度 SDK 不自己取位置，不喂就没有蓝点。
     * accuracy 单位是米；百度要求 accuracy >= 0，给 0 表示未知。
     */
    override fun updateDeviceLocation(point: GeoPoint, accuracyMeters: Float, bearingDeg: Float) {
        if (!baiduMap.isMyLocationEnabled) return
        baiduMap.setMyLocationData(
            MyLocationData.Builder()
                .latitude(point.latitude)
                .longitude(point.longitude)
                .accuracy(accuracyMeters.coerceAtLeast(0f))
                .direction(bearingDeg)
                .build()
        )
    }

    // ==================== 交互 ====================

    override fun setLongClickListener(listener: ((GeoPoint) -> Unit)?) {
        longClickCb = listener
        if (listener == null) baiduMap.setOnMapLongClickListener(null)
        else baiduMap.setOnMapLongClickListener { longClickCb?.invoke(it.toGeo()) }
    }

    override fun setUserGestureListener(listener: (() -> Unit)?) {
        gestureCb = listener
        if (listener == null) {
            baiduMap.setOnMapTouchListener(null)
            return
        }
        baiduMap.setOnMapTouchListener { event ->
            // 拖动过程中触发；抬起不算，避免点击误判成"退出跟随"
            if (event.action == MotionEvent.ACTION_MOVE) gestureCb?.invoke()
        }
    }
}

private fun GeoPoint.toBaidu(): LatLng = LatLng(latitude, longitude)
private fun LatLng.toGeo(): GeoPoint = GeoPoint(latitude, longitude)
