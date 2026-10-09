package com.example.myfirstapp.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.view.View
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.example.myfirstapp.mapsources.GeoTransform
import com.example.myfirstapp.mapsources.MapSource
import com.example.myfirstapp.mapsources.MapSourceStore
import com.example.myfirstapp.mapsources.TileHttp
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline as OsmPolyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/**
 * osmdroid 引擎（开源地图引擎，org.osmdroid 6.1.20）—— 第四家地图引擎，与腾讯/百度对称接入。
 *
 * ★ 坐标系（最关键，理解错就偏几百米）：
 *   - osmdroid 原生就是 WGS-84（标准 Web 墨卡托网格），与天地图 / OpenTopoMap 等 WGS84 瓦片
 *     图源天然同坐标系，因此这些图源由本引擎【原生渲染】：直接按标准 XYZ 网格拼 URL 下载瓦片、
 *     原样贴图，不做任何逐像素重投影。这正是相对"高德引擎上逐像素 GCJ→WGS 反算"的改进。
 *   - App 内部坐标仍是 GCJ-02（与高德/腾讯一致，见 GeoPoint）。所以业务层传进来的 GeoPoint
 *     （相机/标记/轨迹/蓝点）都在边界处统一转成 WGS-84 再交给 osmdroid；回调出去的（长按点）
 *     则转回 GCJ-02，保持接口契约不变。
 *   - 这与百度不同：百度靠 SDK 的 setCoordType(GCJ02) 在 SDK 内部对齐，osmdroid 没有这种开关，
 *     必须在边界自己换算（参考 GeoTransform 的 gcj↔wgs）。
 *
 * ★ 渲染结构：
 *   - 底图 = 第一层瓦片模板，设为 MapView 主瓦片源（不透明，盖住默认 OSM）；
 *   - 复合图层（如天地图"矢量+注记"）的余下模板，作为透明 TilesOverlay 叠在主源之上；
 *   - 半透明叠加层（等高线/天地图标注）也用 TilesOverlay，透明背景让底图透出。
 *
 * ★ 生命周期：与另外三家一样，由 MapEnginePool 管理（只创建不销毁）；本引擎额外需要
 *   [configure] 在首次创建 MapView 前配置缓存目录/User-Agent（写到应用私有目录，无需存储权限）。
 */
class OsmdroidEngine(context: Context) : MapEngine {

    override val kind: MapEngineKind = MapEngineKind.OSMDROID

    private val appContext = context.applicationContext
    private val mapView = MapView(appContext)
    override val view: View get() = mapView

    private val controller = mapView.controller

    /** 业务层画过的覆盖物（标记/轨迹线/精度圈/蓝点）—— clearOverlays 只清这些，不碰底图瓦片层 */
    private val appOverlays = mutableListOf<Any>()
    /** 底图复合图层的额外瓦片叠加层（如天地图"矢量+注记"里的注记层） */
    private val baseLayers = mutableListOf<TileLayer>()
    /** 半透明叠加层（等高线/天地图标注等） */
    private val overlayLayers = mutableListOf<TileLayer>()

    private var longClickCb: ((GeoPoint) -> Unit)? = null
    private var gestureCb: (() -> Unit)? = null

    // ---- 当前位置（osmdroid 官方 MyLocationNewOverlay + 内置高德定位源）----
    /**
     * 内置定位源。osmdroid 自身不带任何定位能力（不像高德/腾讯 SDK 自带定位客户端），
     * 以前只能等业务层喂点，而运动页这类没有喂点的页面一切到天地图就完全没有"我在哪"。
     * 这里补一个内置源（高德定位，与 App 其它页面同一套位置来源，室内也能定位），
     * 由它驱动官方的位置图层 [myLocationOverlay]。
     */
    private val locationProvider = AMapLocationProvider(appContext)

    /**
     * 当前位置图层（osmdroid 官方组件）：负责画定位点与精度圈，位置有两个来源——
     * 内置定位源（[locationProvider]）或业务层喂进来的点（[updateDeviceLocation]）。
     * 图标换成与高德一致的蓝色箭头（组件默认是个蓝色小人形）。
     */
    private val myLocationOverlay: MyLocationNewOverlay = MyLocationNewOverlay(
        locationProvider, mapView
    ).apply {
        // 两种状态（有/无航向）都用同一支箭头：有航向时组件按 bearing 旋转，
        // 没航向时箭头朝正北 —— 与高德地图的蓝点表现一致
        val arrow = locationArrowBitmap()
        setPersonIcon(arrow)
        setPersonAnchor(0.5f, 0.5f)
        setDirectionIcon(arrow)
        setDirectionAnchor(0.5f, 0.5f)
        setDrawAccuracyEnabled(true)
        setEnableAutoStop(false)   // 跟随的开关完全交给 App（手势退出跟随见 init 里的 MapListener）
        isEnabled = true
    }

    /** 业务侧注册的位置回调（用于"首次定位自动回中"）：只有内置定位触发，外部喂点是回声 */
    private var locationCb: ((GeoPoint) -> Unit)? = null
    private var followOn = false
    /** 业务侧是否要求显示当前位置（[setMyLocationEnabled] 的开关） */
    private var locationRequested = false
    /** 最近一次有效航向：定位暂时给不出 bearing 时沿用，避免箭头一下子弹回正北 */
    private var lastBearing = 0f

    init {
        configure(appContext)
        mapView.setMultiTouchControls(true)
        // 中国区域不重复横/纵向平铺（避免拖到国境线外出现镜像地图）
        mapView.isHorizontalMapRepetitionEnabled = false
        mapView.isVerticalMapRepetitionEnabled = false
        controller.setZoom(12.0)

        // 交互层：长按取点（其余手势由 MapListener 捕获）
        mapView.overlays.add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: OsmGeoPoint?): Boolean = false
                override fun longPressHelper(p: OsmGeoPoint?): Boolean {
                    p ?: return false
                    longClickCb?.invoke(fromWgs(p.latitude, p.longitude))
                    return true
                }
            })
        )
        // 手势监听（拖动/缩放 → 退出跟随）
        mapView.addMapListener(object : MapListener {
            override fun onScroll(e: ScrollEvent?): Boolean {
                // 手动拖图 = 退出跟随（与高德 LOCATION_TYPE_FOLLOW 的行为一致）
                if (myLocationOverlay.isFollowLocationEnabled) {
                    myLocationOverlay.disableFollowLocation()
                    followOn = false
                }
                gestureCb?.invoke()
                return false
            }

            override fun onZoom(e: ZoomEvent?): Boolean { gestureCb?.invoke(); return false }
        })

        // 当前位置图层要压在瓦片叠加层之上（见 restack）：osmdroid 按 overlays 顺序绘制，
        // 后进的画在上面，而 applyBase/applyOverlay 会往末尾追加瓦片层，所以每次刷新瓦片后重排。
        mapView.overlays.add(myLocationOverlay)
    }

    // ==================== 生命周期 ====================

    // osmdroid 的 MapView 在 6.1.20 没有 onCreate(Bundle)/onDestroy()：构造即完成初始化，
    // 清理由 onDetach() 负责。这里只实现接口契约，onCreate 保持空实现。
    override fun onCreate() = Unit
    override fun onStart() = Unit

    override fun onResume() {
        mapView.onResume()
        // 回到前台：把 onPause 时停掉的定位重新拉起（只在业务层仍要求显示位置时）
        if (locationRequested && !myLocationOverlay.isMyLocationEnabled) {
            myLocationOverlay.enableMyLocation()
            if (followOn) myLocationOverlay.enableFollowLocation()
        }
    }

    override fun onPause() {
        // 进后台就停掉内置定位（省电），MapView 的瓦片加载也一并暂停
        runCatching { myLocationOverlay.disableMyLocation() }
        mapView.onPause()
    }

    override fun onStop() = Unit

    override fun onDestroy() {
        clearOverlays()
        removeLayers(baseLayers)
        removeLayers(overlayLayers)
        runCatching { myLocationOverlay.disableMyLocation() }
        runCatching { myLocationOverlay.onDetach(mapView) }
        runCatching { locationProvider.destroy() }
        // 6.1.20 无 onDestroy()，用 onDetach() 释放瓦片提供器与监听线程（避免线程泄漏）
        runCatching { mapView.onDetach() }
    }

    // ==================== 底图 / 叠加层 ====================

    override fun applyBase(source: MapSource) {
        // 清掉旧的复合底图叠加层（叠加层由随后的 applyOverlay 重刷）
        removeLayers(baseLayers)

        val templates = when {
            source.layers.isNotEmpty() -> source.layers
            source.urlTemplate.isNotBlank() -> listOf(source.urlTemplate)
            else -> null   // 理论上 OSMDROID 底图必是瓦片源，这里兜底给 OSM
        }
        if (templates == null) {
            runCatching { mapView.setTileSource(TileSourceFactory.MAPNIK) }
            return
        }
        // 第一层作为主瓦片源（不透明，盖住默认 OSM）
        mapView.setTileSource(buildSource("${source.id}_base0", templates[0], source))
        // 其余层作为透明叠加层叠在主源之上（如天地图"矢量+注记"的注记层）
        for (i in 1 until templates.size) {
            val layer = buildLayer("${source.id}_base$i", templates[i], source)
            mapView.overlays.add(layer.overlay)
            baseLayers.add(layer)
        }
        // 新加的瓦片层默认落在列表末尾（= 画在最上面），必须重排到业务覆盖物之下
        restack()
    }

    override fun applyOverlay(source: MapSource?) {
        removeLayers(overlayLayers)
        if (source?.isTileSource != true) return
        val templates = if (source.layers.isNotEmpty()) source.layers else listOf(source.urlTemplate)
        templates.forEachIndexed { i, tpl ->
            val layer = buildLayer("${source.id}_ovl$i", tpl, source)
            mapView.overlays.add(layer.overlay)
            overlayLayers.add(layer)
        }
        restack()
    }

    /**
     * ★ 层级重排（关键）：业务覆盖物（精度圈 / 蓝点 / 轨迹线 / 标记）必须永远压在瓦片叠加层之上。
     *
     * osmdroid 的 overlays 是按添加顺序自下而上绘制的：applyBase / applyOverlay 会在底图切换、
     * 叠加层切换（含运动页自动挂等高线）时把新的 TilesOverlay 追加到列表末尾，于是天地图的
     * 注记层（cva_w/cia_w）、等高线注记（cta_w）会画在蓝点/箭头之上，把屏幕中心的定位点整个
     * 盖住 —— 现象就是"能定位到当前位置，但中心点被遮挡看不见"。
     *
     * 每次刷新瓦片层后调用本方法，把顺序固定为：[交互层] → [瓦片叠加层…] → [业务覆盖物…]。
     */
    private fun restack() {
        val overlays = mapView.overlays
        val tiles = ArrayList<Overlay>(baseLayers.size + overlayLayers.size)
        baseLayers.forEach { tiles.add(it.overlay) }
        overlayLayers.forEach { tiles.add(it.overlay) }
        if (tiles.isEmpty()) return

        // 业务覆盖物 = 当前位置图层 + 业务层画过的痕迹（轨迹线/标记/精度圆）
        val business = ArrayList<Overlay>(appOverlays.size + 1)
        business.add(myLocationOverlay)
        appOverlays.forEach { if (it is Overlay) business.add(it) }

        // 顺序已经正确（最上面那层瓦片仍压在业务层之下）就不动列表，省掉一次重绘
        val lastTile = overlays.indexOfLast { tiles.contains(it) }
        val firstBiz = overlays.indexOfFirst { business.contains(it) }
        if (lastTile < 0 || firstBiz < 0 || firstBiz > lastTile) return

        overlays.removeAll(tiles.toSet())
        overlays.removeAll(business.toSet())
        overlays.addAll(tiles)
        overlays.addAll(business)
        mapView.invalidate()
    }

    /** 按 XYZ 模板构造一个 osmdroid 在线瓦片源（WGS84 原生网格，无重投影） */
    private fun buildSource(name: String, template: String, source: MapSource): ITileSource {
        val sub = source.subdomains
        val subs = if (sub.isEmpty()) arrayOf("a") else sub.map { it.toString() }.toTypedArray()
        return object : OnlineTileSourceBase(
            name, source.minZoom, source.maxZoom, 256, "png", subs, "https://placeholder.invalid/"
        ) {
            // osmdroid 6.x 用 long 瓦片索引同时编码 x/y/zoom，必须在此边界解码后再渲染 URL
            // （注意：不是 (MapTile) 重载，那个在 6.x 已被 long 索引取代，写错会无法编译）
            override fun getTileURLString(tileIndex: Long): String {
                val x = MapTileIndex.getX(tileIndex)
                val y = MapTileIndex.getY(tileIndex)
                val z = MapTileIndex.getZoom(tileIndex)
                return TileHttp.renderUrl(
                    template,
                    x.toString(), y.toString(), z,
                    x, y, sub, MapSourceStore.tiandituKey
                )
            }
        }
    }

    /** 构造一个透明背景的瓦片叠加层 + 其瓦片提供器（用于复合底图层 / 半透明叠加源） */
    private fun buildLayer(name: String, template: String, source: MapSource): TileLayer {
        val provider = MapTileProviderBasic(appContext)
        provider.setTileSource(buildSource(name, template, source))
        val overlay = TilesOverlay(provider, appContext).apply {
            // 透明加载底色：osmdroid 6.1.20 只有 mLoadingBackgroundColor（默认黑色），
            // 不设会在瓦片加载瞬间闪黑块；该类没有 backgroundColor 字段，不要写。
            loadingBackgroundColor = Color.TRANSPARENT
        }
        return TileLayer(overlay, provider)
    }

    /** 移除并释放一组瓦片叠加层（detach 瓦片加载线程，避免线程泄漏） */
    private fun removeLayers(list: MutableList<TileLayer>) {
        list.forEach { layer ->
            mapView.overlays.remove(layer.overlay)
            runCatching { layer.provider.detach() }
        }
        list.clear()
    }

    /** 瓦片叠加层与其提供器的一对一封装 */
    private data class TileLayer(
        val overlay: TilesOverlay,
        val provider: MapTileProviderBasic
    )

    // ==================== 控件 ====================

    override fun setUiSettings(settings: MapUiSettings) {
        // osmdroid 没有"回到我的位置"按钮（App 用自绘 FAB）也没有 SDK 指北针（用 PhoneCompass）；
        // 这里只映射内置缩放按钮开关
        mapView.setBuiltInZoomControls(settings.zoomControls)
    }

    // ==================== 相机 ====================

    override fun moveCamera(target: GeoPoint, zoom: Float) {
        val w = toWgs(target)
        controller.setZoom(zoom.toDouble())
        controller.setCenter(w)
    }

    override fun animateCamera(target: GeoPoint) {
        controller.animateTo(toWgs(target))
    }

    override fun animateCamera(target: GeoPoint, zoom: Float) {
        controller.animateTo(toWgs(target), zoom.toDouble(), null)
    }

    override fun fitBounds(points: List<GeoPoint>, paddingPx: Int, animate: Boolean) {
        if (points.isEmpty()) return
        // 单点：osmdroid 的包围盒缩放对退化盒子会直接拉到 maxZoom（太近），与其他引擎的行为不一致，
        // 这里按 App 惯例固定到街道级 16，保持四家引擎表现一致。
        if (points.size == 1) {
            if (animate) animateCamera(points.first(), 16f) else moveCamera(points.first(), 16f)
            return
        }
        val box = BoundingBox.fromGeoPoints(points.map { toWgs(it) })
        // zoomToBoundingBox 是 MapView 的方法（MapController 上没有），且必须在布局完成后调用：
        // View 尚未测量时 width/height 为 0，会算出 zoom=0 的世界中心。未布局就 post 一次再执行。
        if (mapView.width > 0 && mapView.height > 0) {
            mapView.zoomToBoundingBox(box, animate, paddingPx)
        } else {
            mapView.post { mapView.zoomToBoundingBox(box, animate, paddingPx) }
        }
    }

    // ==================== 覆盖物 ====================

    override fun addPolyline(points: List<GeoPoint>, widthPx: Float, colorArgb: Int) {
        if (points.size < 2) return
        val line = OsmPolyline().apply {
            setPoints(points.map { toWgs(it) })
            color = colorArgb
            width = widthPx
        }
        mapView.overlays.add(line)
        appOverlays.add(line)
        mapView.invalidate()
    }

    override fun addMarker(
        point: GeoPoint,
        bitmap: Bitmap?,
        title: String?,
        anchorU: Float,
        anchorV: Float,
        zIndex: Float,
        rotateDeg: Float
    ) {
        val m = Marker(mapView)
        m.position = toWgs(point)
        // ★ osmdroid 的 Marker 默认图标是 null，不设 icon 就完全不画（其他三家 SDK 有默认图钉）。
        //   业务层不传 bitmap 时（目的地标记 / 叠加轨迹起点）用自绘红图钉兜底，锚点钉在尖端。
        if (bitmap != null) {
            m.icon = BitmapDrawable(appContext.resources, bitmap)
            m.setAnchor(anchorU, anchorV)
        } else {
            m.icon = BitmapDrawable(appContext.resources, defaultPinBitmap())
            m.setAnchor(0.5f, 1f)
        }
        m.rotation = rotateDeg   // 图标顺时针旋转（与 GPS bearing 同向），正北朝上
        mapView.overlays.add(m)
        appOverlays.add(m)
        // 后加的覆盖物本来就在列表末尾，但若此刻列表里还有瓦片层压在下面则无需处理；
        // 这里不调用 restack（业务层添加顺序本身已保证在最上）
        mapView.invalidate()
    }

    override fun addCircle(
        center: GeoPoint,
        radiusMeters: Double,
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Float
    ) {
        val w = toWgs(center)
        val poly = Polygon().apply {
            setPoints(circlePoints(w.latitude, w.longitude, radiusMeters))
            this.fillColor = fillColor
            this.strokeColor = strokeColor
            this.strokeWidth = strokeWidthPx
        }
        mapView.overlays.add(poly)
        appOverlays.add(poly)
        mapView.invalidate()
    }

    override fun clearOverlays() {
        for (o in appOverlays) {
            when (o) {
                is Marker -> mapView.overlays.remove(o)
                is OsmPolyline -> mapView.overlays.remove(o)
                is Polygon -> mapView.overlays.remove(o)
            }
        }
        appOverlays.clear()
        mapView.invalidate()
    }

    // ==================== 当前位置（定位蓝点） ====================

    /**
     * 开关当前位置图层。与高德/腾讯一样由引擎自己取位置（内置的高德定位源），
     * 业务层不喂点也能看到"我在哪"；喂点（[updateDeviceLocation]）时以喂进来的为准。
     */
    override fun setMyLocationEnabled(enabled: Boolean, follow: Boolean) {
        locationRequested = enabled
        followOn = follow
        if (!enabled) {
            // 停掉内置定位，且 MyLocationNewOverlay 的 draw 依赖 isMyLocationEnabled，这里一并关掉
            myLocationOverlay.disableMyLocation()
            mapView.invalidate()
            return
        }
        if (!myLocationOverlay.isMyLocationEnabled) myLocationOverlay.enableMyLocation()
        if (follow) myLocationOverlay.enableFollowLocation()
        else myLocationOverlay.disableFollowLocation()
        mapView.invalidate()
    }

    /** 内置定位拿到位置时的回调（用于"首次定位自动回中"），与高德的 setOnMyLocationChangeListener 对齐 */
    override fun setLocationChangeListener(listener: ((GeoPoint) -> Unit)?) {
        locationCb = listener
    }

    /**
     * 业务侧喂位置（比内置定位更权威时用，例如导航页的导航定位、地图页的搜索定位）。
     *
     * 走的是同一张 [myLocationOverlay]，只是把位置直接灌进去，不再另开一套自绘 Marker，
     * 避免两个图层同时画两个蓝点。point 为 GCJ-02，边界处转 WGS-84。
     */
    override fun updateDeviceLocation(point: GeoPoint, accuracyMeters: Float, bearingDeg: Float) {
        if (!locationRequested) return   // 业务层明确关掉了定位（如记录中）就不画
        val loc = wgsLocation(point.latitude, point.longitude, accuracyMeters, bearingDeg)
        // 灌进同一张位置图层（source 参数组件内部没用到，传内置源只为满足非空签名）
        myLocationOverlay.onLocationChanged(loc, locationProvider)
    }

    /** GCJ-02 → WGS-84 → android.location.Location（osmdroid 图层只认 WGS-84） */
    private fun wgsLocation(lat: Double, lng: Double, accuracy: Float, bearing: Float): Location {
        val w = GeoTransform.gcj02ToWgs84(lng, lat) // 返回 [lng, lat]
        return Location(LOCATION_PROVIDER_APP).apply {
            latitude = w[1]
            longitude = w[0]
            if (accuracy > 0f) this.accuracy = accuracy
            // 有航向就按航向旋转箭头；这一帧没有航向时沿用上一次的朝向（静止时箭头不弹回正北）
            if (bearing > 0f) lastBearing = bearing
            if (lastBearing > 0f) this.bearing = lastBearing
            time = System.currentTimeMillis()
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * 内置定位源：把高德定位包装成 osmdroid 的 [IMyLocationProvider]。
     *
     * ★ 为什么用高德而不是系统 GPS/网络定位：App 其它页面（地图页 ViewModel、轨迹记录、
     *   导航）都走高德定位，位置来源一致，切图源时蓝点不会跳；而且高德是混合定位，
     *   室内/城市峡谷也有位置，系统 NETWORK_PROVIDER 在国内基本拿不到。
     *
     * ★ [startLocationProvider] 恒返回 true：即使没有定位权限/定位失败，overlay 也处于
     *   enabled 状态，业务层后续喂进来的位置照样能画出来（官方组件 draw 的前提就是
     *   isMyLocationEnabled()，返回 false 会让整张位置图层彻底不画）。
     */
    private inner class AMapLocationProvider(private val ctx: Context) : IMyLocationProvider {

        private var consumer: IMyLocationConsumer? = null
        private var client: AMapLocationClient? = null
        private var lastLocation: Location? = null

        override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer): Boolean {
            consumer = myLocationConsumer
            startClient()
            return true
        }

        override fun stopLocationProvider() = stopClient()

        override fun getLastKnownLocation(): Location? = lastLocation

        override fun destroy() {
            stopClient()
            consumer = null
        }

        private fun startClient() {
            if (client != null || !hasLocationPermission()) return
            runCatching {
                client = AMapLocationClient(ctx).apply {
                    setLocationOption(AMapLocationClientOption().apply {
                        locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                        isOnceLocation = false      // 持续定位
                        interval = 2000             // 2 秒一次（与高德蓝点 3s 量级一致）
                        isNeedAddress = false       // 只要坐标，省流量
                    })
                    setLocationListener { loc ->
                        if (loc.errorCode != 0) return@setLocationListener
                        val l = wgsLocation(loc.latitude, loc.longitude, loc.accuracy, loc.bearing)
                        lastLocation = l
                        consumer?.onLocationChanged(l, this@AMapLocationProvider)
                        // 内置定位也要把位置抛给业务层（首次定位回中等），与高德引擎的行为对齐
                        locationCb?.invoke(GeoPoint(loc.latitude, loc.longitude))
                    }
                    startLocation()
                }
            }
        }

        private fun stopClient() {
            client?.let { c ->
                runCatching {
                    c.stopLocation()
                    c.onDestroy()
                }
            }
            client = null
        }
    }

    // ==================== 交互 ====================

    override fun setLongClickListener(listener: ((GeoPoint) -> Unit)?) {
        longClickCb = listener
    }

    override fun setUserGestureListener(listener: (() -> Unit)?) {
        gestureCb = listener
    }

    // ==================== 坐标换算（边界处 GCJ-02 ↔ WGS-84） ====================

    /** 业务 GCJ-02 → osmdroid WGS-84 */
    private fun toWgs(p: GeoPoint): OsmGeoPoint {
        if (!p.isValid) return OsmGeoPoint(p.latitude, p.longitude)
        val w = GeoTransform.gcj02ToWgs84(p.longitude, p.latitude) // 返回 [lng, lat]
        return OsmGeoPoint(w[1], w[0])
    }

    /** osmdroid WGS-84 → 业务 GCJ-02 */
    private fun fromWgs(lat: Double, lon: Double): GeoPoint {
        val g = GeoTransform.wgs84ToGcj02(lon, lat) // 返回 [lng, lat]
        return GeoPoint(g[1], g[0])
    }

    /** 以 center 为圆心生成半径 radiusMeters 的近似圆（小范围用等距矩形近似，误差可忽略） */
    private fun circlePoints(centerLat: Double, centerLon: Double, radiusMeters: Double): List<OsmGeoPoint> {
        if (radiusMeters <= 0.0) return emptyList()
        val earthR = 6378137.0
        val n = 64
        val pts = ArrayList<OsmGeoPoint>(n + 1)
        for (i in 0..n) {
            val a = 2.0 * Math.PI * i / n
            val dLat = (radiusMeters * Math.cos(a)) / earthR
            val dLon = (radiusMeters * Math.sin(a)) / (earthR * Math.cos(Math.toRadians(centerLat)))
            pts.add(OsmGeoPoint(centerLat + Math.toDegrees(dLat), centerLon + Math.toDegrees(dLon)))
        }
        return pts
    }

    /**
     * 自绘「高德风格」定位箭头：尖头朝正上（= 正北），蓝底白边，尾部圆润。
     *
     * ★ 同时用作 MyLocationNewOverlay 的 person 与 direction 图标：官方组件在有航向时
     *   画 direction 并按 bearing 旋转，没航向时画 person（不旋转）。两个都设成箭头，
     *   静止时也显示箭头（朝正北），表现与高德地图的蓝点一致。
     * ★ 锚点固定用中心（0.5/0.5），即位置点落在箭头的中心 —— 与高德一致
     *   （组件默认的人形图标锚点是脚底 0.5/0.8125，用在箭头上会整体偏上）。
     */
    private fun locationArrowBitmap(): Bitmap {
        val density = appContext.resources.displayMetrics.density
        val size = (32 * density).toInt().coerceAtLeast(6)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val halfW = size * 0.32f
        val path = android.graphics.Path().apply {
            moveTo(cx, size * 0.04f)                                            // 尖端（正北）
            quadTo(cx + halfW, size * 0.34f, cx + halfW * 0.84f, size * 0.78f)  // 右侧弧
            quadTo(cx, size * 0.99f, cx - halfW * 0.84f, size * 0.78f)          // 圆润尾部
            quadTo(cx - halfW, size * 0.34f, cx, size * 0.04f)                  // 左侧弧
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF1E88E5.toInt()          // 与高德蓝点 / 本 App 记录箭头同色
        canvas.drawPath(path, paint)
        // 白描边：在深色底图（卫星/地形）上也看得清
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * density
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(path, paint)
        return bmp
    }

    /**
     * 默认图钉（水滴形，尖头朝下）：业务层调 [addMarker] 不传 bitmap 时用。
     * osmdroid 的 Marker 默认 icon 为 null，不给图标就什么都不画 —— 地图页的"目的地"、
     * 叠加轨迹的"起点"在 osmdroid 引擎下曾经完全不显示，就是栽在这里。
     */
    private fun defaultPinBitmap(): Bitmap {
        val density = appContext.resources.displayMetrics.density
        val w = (22 * density).toInt().coerceAtLeast(2)
        val h = (32 * density).toInt().coerceAtLeast(3)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = w / 2f
        val circleR = w / 2f - density
        val cy = circleR + density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
        // 下部三角（尖头落在 h 处，锚点用 0.5/1 正好钉在目标点上）
        val tail = android.graphics.Path().apply {
            moveTo(cx, h.toFloat())
            lineTo(cx - circleR * 0.66f, cy + circleR * 0.55f)
            lineTo(cx + circleR * 0.66f, cy + circleR * 0.55f)
            close()
        }
        canvas.drawPath(tail, paint)
        canvas.drawCircle(cx, cy, circleR, paint)
        // 白描边 + 中心白点，保证在卫星图等深色底图上也看得清
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, circleR, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, circleR * 0.34f, paint)
        return bmp
    }

    companion object {
        /** 自造的 Location provider 名（仅用于标记位置来源，不对应真实 provider） */
        private const val LOCATION_PROVIDER_APP = "app"

        @Volatile
        private var configured = false

        /** 一次性配置 osmdroid（缓存目录 / User-Agent），必须在首个 MapView 创建前调用 */
        @Synchronized
        fun configure(ctx: Context) {
            if (configured) return
            val c = Configuration.getInstance()
            // 写到应用私有目录，避免 Android 9+ 外部存储权限问题
            val base = File(ctx.filesDir, "osmdroid")
            c.osmdroidBasePath = base
            c.osmdroidTileCache = File(base, "tiles")
            c.userAgentValue = "myfirstapp/1.0 (Android)"
            configured = true
        }
    }
}
