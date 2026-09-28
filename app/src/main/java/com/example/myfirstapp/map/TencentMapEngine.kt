package com.example.myfirstapp.map

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import com.example.myfirstapp.mapsources.MapSource
import com.example.myfirstapp.mapsources.NativeMapType
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory
import com.tencent.tencentmap.mapsdk.maps.TextureMapView
import com.tencent.tencentmap.mapsdk.maps.TencentMap
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory
import com.tencent.tencentmap.mapsdk.maps.model.CameraPosition
import com.tencent.tencentmap.mapsdk.maps.model.CircleOptions
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.tencent.tencentmap.mapsdk.maps.model.LatLngBounds
import com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions
import com.tencent.tencentmap.mapsdk.maps.model.MyLocationStyle
import com.tencent.tencentmap.mapsdk.maps.model.PolylineOptions

/**
 * 腾讯地图引擎（tencent-map-vector-sdk 6.13.x）。
 *
 * 与高德同为 GCJ-02，业务坐标可直接透传，不需要任何换算。
 *
 * ★ 生命周期与另外两家不同：腾讯的 MapView 没有 onCreate()，只有
 *   onStart/onResume/onPause/onStop/onDestroy（这在 MapEngine 接口里已经统一）。
 * ★ LatLng 类在 foundation 库里（com.tencent.openmap:foundation），
 *   只引地图主包会编译不过 —— 这就是 build.gradle 里两个依赖都要加的原因。
 */
class TencentMapEngine(context: Context) : MapEngine {

    override val kind: MapEngineKind = MapEngineKind.TENCENT

    private val mapView = TextureMapView(context)
    override val view: View get() = mapView
    private val map: TencentMap = mapView.map

    private val overlays = mutableListOf<com.tencent.gaya.foundation.api.interfaces.Removable>()

    private var longClickCb: ((GeoPoint) -> Unit)? = null
    private var gestureCb: (() -> Unit)? = null
    private var locationCb: ((GeoPoint) -> Unit)? = null

    override fun onCreate() = Unit // 腾讯没有 onCreate，构造完即可用

    override fun onStart() = mapView.onStart()
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onStop() = mapView.onStop()

    override fun onDestroy() {
        clearOverlays()
        mapView.onDestroy()
    }

    // ==================== 底图 ====================

    override fun applyBase(source: MapSource) {
        map.mapType = when (source.nativeType) {
            NativeMapType.TENCENT_SATELLITE -> TencentMap.MAP_TYPE_SATELLITE
            NativeMapType.TENCENT_DARK -> TencentMap.MAP_TYPE_DARK
            NativeMapType.TENCENT_NORMAL -> TencentMap.MAP_TYPE_NORMAL
            // 瓦片图源（天地图/自定义）不该走到腾讯引擎；万一走到，用矢量底图兜底
            else -> TencentMap.MAP_TYPE_NORMAL
        }
    }

    override fun applyOverlay(source: MapSource?) = Unit // 腾讯引擎不支持第三方瓦片叠加

    // ==================== 控件 / 相机 ====================

    override fun setUiSettings(settings: MapUiSettings) {
        val ui = map.uiSettings
        ui.setZoomControlsEnabled(settings.zoomControls)
        ui.setMyLocationButtonEnabled(settings.myLocationButton)
        ui.setCompassEnabled(settings.compass)
    }

    override fun moveCamera(target: GeoPoint, zoom: Float) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(target.toTencent(), zoom))
    }

    override fun animateCamera(target: GeoPoint) {
        // 腾讯没有 changeLatLng，用 newCameraPosition 保持当前 zoom/tilt/rotation
        val cur = map.cameraPosition
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition(target.toTencent(), cur.zoom, cur.tilt, cur.bearing)
            )
        )
    }

    override fun animateCamera(target: GeoPoint, zoom: Float) {
        val cur = map.cameraPosition
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition(target.toTencent(), zoom, cur.tilt, cur.bearing)
            )
        )
    }

    override fun fitBounds(points: List<GeoPoint>, paddingPx: Int, animate: Boolean) {
        if (points.isEmpty()) return
        val bounds = LatLngBounds.builder().apply {
            points.forEach { include(it.toTencent()) }
        }.build()
        val update = CameraUpdateFactory.newLatLngBounds(bounds, paddingPx)
        if (animate) map.animateCamera(update) else map.moveCamera(update)
    }

    // ==================== 覆盖物 ====================

    override fun addPolyline(points: List<GeoPoint>, widthPx: Float, colorArgb: Int) {
        if (points.size < 2) return
        overlays += map.addPolyline(
            PolylineOptions()
                .addAll(points.map { it.toTencent() })
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
            .position(point.toTencent())
            .anchor(anchorU, anchorV)
            .zIndex(zIndex)
        bitmap?.let { options.icon(BitmapDescriptorFactory.fromBitmap(it)) }
        title?.let { options.title(it) }
        overlays += map.addMarker(options)
    }

    override fun addCircle(
        center: GeoPoint,
        radiusMeters: Double,
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Float
    ) {
        overlays += map.addCircle(
            CircleOptions()
                .center(center.toTencent())
                .radius(radiusMeters)
                .fillColor(fillColor)
                .strokeColor(strokeColor)
                .strokeWidth(strokeWidthPx)
        )
    }

    override fun clearOverlays() {
        overlays.forEach { runCatching { it.remove() } }
        overlays.clear()
    }

    // ==================== 定位 ====================

    override fun setMyLocationEnabled(enabled: Boolean, follow: Boolean) {
        // 腾讯的定位层需要先给一个 style 才能显示
        if (enabled) {
            map.setMyLocationStyle(
                MyLocationStyle()
                    .myLocationType(
                        if (follow) MyLocationStyle.LOCATION_TYPE_FOLLOW_NO_CENTER
                        else MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER
                    )
            )
        }
        map.setMyLocationEnabled(enabled)
    }

    override fun setLocationChangeListener(listener: ((GeoPoint) -> Unit)?) {
        locationCb = listener
        if (listener == null) {
            map.setOnMyLocationChangeListener(null)
            return
        }
        map.setOnMyLocationChangeListener { location ->
            location ?: return@setOnMyLocationChangeListener
            locationCb?.invoke(GeoPoint(location.latitude, location.longitude))
        }
    }

    /** 腾讯自带定位层，不需要业务侧喂位置 */
    override fun updateDeviceLocation(point: GeoPoint, accuracyMeters: Float, bearingDeg: Float) = Unit

    // ==================== 交互 ====================

    override fun setLongClickListener(listener: ((GeoPoint) -> Unit)?) {
        longClickCb = listener
        if (listener == null) map.setOnMapLongClickListener(null)
        else map.setOnMapLongClickListener { longClickCb?.invoke(it.toGeo()) }
    }

    override fun setUserGestureListener(listener: (() -> Unit)?) {
        gestureCb = listener
        if (listener == null) {
            map.setTencentMapGestureListener(null)
            return
        }
        map.setTencentMapGestureListener(object :
            com.tencent.tencentmap.mapsdk.maps.model.SimpleMapGestureListener() {
            override fun onScroll(dx: Float, dy: Float): Boolean { gestureCb?.invoke(); return false }
            override fun onFling(dx: Float, dy: Float): Boolean { gestureCb?.invoke(); return false }
            override fun onDoubleTap(x: Float, y: Float): Boolean { gestureCb?.invoke(); return false }
            override fun onSingleTap(x: Float, y: Float): Boolean { gestureCb?.invoke(); return false }
            override fun onLongPress(x: Float, y: Float): Boolean { gestureCb?.invoke(); return false }
            override fun onDown(x: Float, y: Float): Boolean { gestureCb?.invoke(); return false }
        })
    }
}

private fun GeoPoint.toTencent(): LatLng = LatLng(latitude, longitude)
private fun LatLng.toGeo(): GeoPoint = GeoPoint(latitude, longitude)
