package com.example.myfirstapp.map

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.maps.model.TileOverlay
import com.amap.api.maps.model.TileOverlayOptions
import com.example.myfirstapp.mapsources.CustomTileProvider
import com.example.myfirstapp.mapsources.MapSource
import com.example.myfirstapp.mapsources.MapSourceStore
import com.example.myfirstapp.mapsources.NativeMapType

/**
 * 高德引擎。
 *
 * 除了高德自家四种底图，还负责"没有原生 SDK 的图源"的兜底渲染：
 * 天地图 / OpenTopoMap / 用户自定义 XYZ 瓦片，全部用 CustomTileProvider
 * 叠在高德矢量底图之上（叠一层拖底是为了瓦片没加载完时不露白）。
 *
 * ★ 两个踩过的坑，改动前先读：
 * 1. aMap.clear() 会连瓦片图层一起清掉，所以这里不用 clear()，而是把画过的
 *    覆盖物逐个记账 remove（[overlays] 列表）—— 这也是为什么 clearOverlays 不会毁底图；
 * 2. TextureMapView 不支持会话内频繁 destroy/create（切 Tab 时必崩 SIGABRT），
 *    生命周期由 MapEnginePool 控制：切走只 pause，Activity 销毁才 onDestroy。
 */
class AMapEngine(context: Context) : MapEngine {

    override val kind: MapEngineKind = MapEngineKind.AMAP

    /** 暴露给轨迹视频导出用（TrackVideoExporter 依赖高德的抓帧能力） */
    val textureMapView: TextureMapView = TextureMapView(context)
    override val view: View get() = textureMapView

    private val aMap: AMap get() = textureMapView.map

    /** 自己画过的覆盖物（定位标记/精度圈/轨迹线/路线），用于精准移除 */
    private val overlays = mutableListOf<Any>()
    /** 瓦片图层：底图瓦片 / 卫星路网标注 与 叠加层 分开记账，避免互相清掉 */
    private var baseTile: TileOverlay? = null
    private var overlayTile: TileOverlay? = null

    private var longClickCb: ((GeoPoint) -> Unit)? = null
    private var gestureCb: (() -> Unit)? = null
    private var locationCb: ((GeoPoint) -> Unit)? = null

    override fun onCreate() {
        textureMapView.onCreate(null)
    }

    override fun onStart() = Unit
    override fun onResume() = textureMapView.onResume()
    override fun onPause() = textureMapView.onPause()
    override fun onStop() = Unit

    override fun onDestroy() {
        clearOverlays()
        clearBaseTile()
        clearOverlayTile()
        textureMapView.onDestroy()
    }

    // ==================== 底图 ====================

    override fun applyBase(source: MapSource) {
        clearBaseTile()
        when (source.nativeType) {
            NativeMapType.AMAP_SATELLITE -> aMap.mapType = AMap.MAP_TYPE_SATELLITE
            NativeMapType.AMAP_NIGHT -> aMap.mapType = AMap.MAP_TYPE_NIGHT
            NativeMapType.AMAP_SAT_ROAD -> {
                // 卫星底图 + 高德官方路网透明层（style=7：白线道路+地名标注，无坐标系偏差）
                aMap.mapType = AMap.MAP_TYPE_SATELLITE
                baseTile = aMap.addTileOverlay(
                    TileOverlayOptions()
                        .tileProvider(object : com.amap.api.maps.model.UrlTileProvider(256, 256) {
                            override fun getTileUrl(x: Int, y: Int, zoom: Int): java.net.URL? =
                                runCatching {
                                    java.net.URL(
                                        "https://wprd0$((x + y) % 4).is.autonavi.com/appmaptile" +
                                            "?style=7&x=$x&y=$y&z=$zoom"
                                    )
                                }.getOrNull()
                        })
                        .zIndex(0f)
                        .diskCacheEnabled(true)
                        .memCacheSize(10 * 1024 * 1024)
                )
            }
            else -> {
                aMap.mapType = AMap.MAP_TYPE_NORMAL
                // 瓦片图源（天地图/自定义/其他）：铺一层不透明瓦片盖在高德矢量上
                if (source.isTileSource) {
                    baseTile = aMap.addTileOverlay(
                        TileOverlayOptions()
                            .tileProvider(CustomTileProvider(source) { MapSourceStore.tiandituKey })
                            .zIndex(0f)
                            .diskCacheEnabled(true)
                            .memCacheSize(20 * 1024 * 1024)
                    )
                }
            }
        }
    }

    override fun applyOverlay(source: MapSource?) {
        clearOverlayTile()
        // 瓦片叠加层只有这一条路可走（腾讯/百度原生引擎不支持往自己的地图上贴第三方瓦片），
        // 非瓦片图源（各家的原生样式）不参与叠加。
        if (source?.isTileSource == true) {
            overlayTile = aMap.addTileOverlay(
                TileOverlayOptions()
                    .tileProvider(CustomTileProvider(source) { MapSourceStore.tiandituKey })
                    .zIndex(1f)
                    .diskCacheEnabled(true)
                    .memCacheSize(10 * 1024 * 1024)
            )
        }
    }

    private fun clearBaseTile() {
        baseTile?.remove(); baseTile = null
    }

    private fun clearOverlayTile() {
        overlayTile?.remove(); overlayTile = null
    }

    // ==================== 控件 / 相机 ====================

    override fun setUiSettings(settings: MapUiSettings) {
        aMap.uiSettings.isZoomControlsEnabled = settings.zoomControls
        aMap.uiSettings.isMyLocationButtonEnabled = settings.myLocationButton
        aMap.uiSettings.isCompassEnabled = settings.compass
    }

    override fun moveCamera(target: GeoPoint, zoom: Float) {
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(target.toAmap(), zoom))
    }

    override fun animateCamera(target: GeoPoint) {
        aMap.animateCamera(CameraUpdateFactory.changeLatLng(target.toAmap()))
    }

    override fun animateCamera(target: GeoPoint, zoom: Float) {
        aMap.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition(target.toAmap(), zoom, 0f, 0f)
            )
        )
    }

    override fun fitBounds(points: List<GeoPoint>, paddingPx: Int, animate: Boolean) {
        if (points.isEmpty()) return
        val bounds = LatLngBounds.builder().apply {
            points.forEach { include(it.toAmap()) }
        }.build()
        val update = CameraUpdateFactory.newLatLngBounds(bounds, paddingPx)
        if (animate) aMap.animateCamera(update) else aMap.moveCamera(update)
    }

    // ==================== 覆盖物 ====================

    override fun addPolyline(points: List<GeoPoint>, widthPx: Float, colorArgb: Int) {
        if (points.size < 2) return
        val line = aMap.addPolyline(
            PolylineOptions()
                .addAll(points.map { it.toAmap() })
                .width(widthPx)
                .color(colorArgb)
        )
        overlays += line
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
            .position(point.toAmap())
            .anchor(anchorU, anchorV)
            .zIndex(zIndex)
        bitmap?.let { options.icon(BitmapDescriptorFactory.fromBitmap(it)) }
        title?.let { options.title(it) }
        overlays += aMap.addMarker(options)
    }

    override fun addCircle(
        center: GeoPoint,
        radiusMeters: Double,
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Float
    ) {
        overlays += aMap.addCircle(
            CircleOptions()
                .center(center.toAmap())
                .radius(radiusMeters)
                .fillColor(fillColor)
                .strokeColor(strokeColor)
                .strokeWidth(strokeWidthPx)
        )
    }

    override fun clearOverlays() {
        overlays.forEach {
            runCatching {
                when (it) {
                    is Polyline -> it.remove()
                    is Marker -> it.remove()
                    is Circle -> it.remove()
                }
            }
        }
        overlays.clear()
    }

    // ==================== 定位 ====================

    override fun setMyLocationEnabled(enabled: Boolean, follow: Boolean) {
        if (!enabled) {
            aMap.isMyLocationEnabled = false
            return
        }
        aMap.myLocationStyle = MyLocationStyle().apply {
            myLocationType(
                if (follow) MyLocationStyle.LOCATION_TYPE_FOLLOW
                else MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER
            )
            interval(3000)
            showMyLocation(true)
        }
        aMap.isMyLocationEnabled = true
    }

    override fun setLocationChangeListener(listener: ((GeoPoint) -> Unit)?) {
        locationCb = listener
        if (listener == null) {
            aMap.setOnMyLocationChangeListener(null)
            return
        }
        aMap.setOnMyLocationChangeListener { location ->
            location ?: return@setOnMyLocationChangeListener
            locationCb?.invoke(GeoPoint(location.latitude, location.longitude))
        }
    }

    /** 高德自带定位客户端，不需要业务侧喂位置 */
    override fun updateDeviceLocation(point: GeoPoint, accuracyMeters: Float, bearingDeg: Float) = Unit

    // ==================== 交互 ====================

    override fun setLongClickListener(listener: ((GeoPoint) -> Unit)?) {
        longClickCb = listener
        if (listener == null) aMap.setOnMapLongClickListener(null)
        else aMap.setOnMapLongClickListener { longClickCb?.invoke(it.toGeo()) }
    }

    override fun setUserGestureListener(listener: (() -> Unit)?) {
        gestureCb = listener
        if (listener == null) {
            aMap.setAMapGestureListener(null)
            return
        }
        aMap.setAMapGestureListener(object : com.amap.api.maps.model.AMapGestureListener {
            override fun onScroll(dx: Float, dy: Float) = gestureCb!!.invoke()
            override fun onFling(dx: Float, dy: Float) = gestureCb!!.invoke()
            override fun onDoubleTap(x: Float, y: Float) = gestureCb!!.invoke()
            override fun onSingleTap(x: Float, y: Float) = gestureCb!!.invoke()
            override fun onLongPress(x: Float, y: Float) = gestureCb!!.invoke()
            override fun onDown(x: Float, y: Float) = gestureCb!!.invoke()
            override fun onUp(x: Float, y: Float) = Unit
            override fun onMapStable() = Unit
        })
    }
}

/** GeoPoint → 高德 LatLng（两边同为 GCJ-02，直接映射） */
private fun GeoPoint.toAmap(): LatLng = LatLng(latitude, longitude)

/** 高德 LatLng → GeoPoint */
fun LatLng.toGeo(): GeoPoint = GeoPoint(latitude, longitude)
