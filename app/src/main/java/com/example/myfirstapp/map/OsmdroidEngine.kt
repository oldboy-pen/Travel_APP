package com.example.myfirstapp.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.View
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

    // ---- 蓝点（自绘 Marker + 精度圈 Polygon）----
    private val deviceMarker: Marker
    private val accuracyCircle: Polygon
    private var followOn = false
    /** 业务侧是否要求显示蓝点（[setMyLocationEnabled] 的开关，与"有没有位置"分开记） */
    private var locationRequested = false
    private var lastWgs: OsmGeoPoint? = null
    private var lastAcc = 0f
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
            override fun onScroll(e: ScrollEvent?): Boolean { gestureCb?.invoke(); return false }
            override fun onZoom(e: ZoomEvent?): Boolean { gestureCb?.invoke(); return false }
        })

        // 蓝点 + 精度圈（默认隐藏，开定位后由 updateDeviceLocation / setMyLocationEnabled 控制）
        deviceMarker = Marker(mapView).apply {
            setAnchor(0.5f, 0.5f)
            icon = BitmapDrawable(appContext.resources, blueDotBitmap())
            isEnabled = false
        }
        accuracyCircle = Polygon().apply {
            fillColor = 0x141E88E5.toInt()
            strokeColor = 0x661E88E5.toInt()
            strokeWidth = 2f
            isEnabled = false
        }
        mapView.overlays.add(accuracyCircle)
        mapView.overlays.add(deviceMarker)
    }

    // ==================== 生命周期 ====================

    // osmdroid 的 MapView 在 6.1.20 没有 onCreate(Bundle)/onDestroy()：构造即完成初始化，
    // 清理由 onDetach() 负责。这里只实现接口契约，onCreate 保持空实现。
    override fun onCreate() = Unit
    override fun onStart() = Unit
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onStop() = Unit

    override fun onDestroy() {
        clearOverlays()
        removeLayers(baseLayers)
        removeLayers(overlayLayers)
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

        // 业务覆盖物 = 精度圈 + 蓝点 + 业务层画过的痕迹（轨迹线/标记/精度圆）
        val business = ArrayList<Overlay>(appOverlays.size + 2)
        business.add(accuracyCircle)
        business.add(deviceMarker)
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

    // ==================== 定位蓝点 ====================

    /**
     * 开关自绘蓝点。osmdroid 没有内置定位客户端，必须由业务侧 [updateDeviceLocation] 喂位置
     * （与百度引擎同理；高德/腾讯自带定位，留空实现）。
     */
    override fun setMyLocationEnabled(enabled: Boolean, follow: Boolean) {
        locationRequested = enabled
        followOn = follow
        syncBlueDot()
    }

    /**
     * 按当前状态刷新蓝点/精度圈。
     *
     * ★ 位置（lastWgs）还没到位时绝不把 Marker 打开：osmdroid 的 Marker 在 position 为 null
     *   时 `pj.toPixels(mPosition,…)` 直接 NPE，异常会从 onDraw 抛出去，连累整幅地图不再刷新
     *   （表现就是"地图白/灰，定位点也不出现"）。所以先攒位置、后显示。
     */
    private fun syncBlueDot() {
        val w = lastWgs
        val show = locationRequested && w != null
        deviceMarker.isEnabled = show
        accuracyCircle.isEnabled = show
        if (show && w != null) {
            deviceMarker.position = w
            deviceMarker.rotation = lastBearing
            accuracyCircle.points = circlePoints(w.latitude, w.longitude, lastAcc.toDouble())
        }
        // 位置变了必须主动重绘：osmdroid 的 Marker/Polygon 自身不会触发 invalidate，
        // 镜头不动时（follow=false 或镜头已到位）蓝点会一直停在上一次的画面里。
        mapView.invalidate()
    }

    /** 百度式空实现：osmdroid 没有 SDK 自己产出的定位回调，位置由上游 updateDeviceLocation 流入 */
    override fun setLocationChangeListener(listener: ((GeoPoint) -> Unit)?) = Unit

    /**
     * 喂位置给自绘蓝点。point 为 GCJ-02，边界处转 WGS-84 后定位；follow=true 时镜头跟随居中。
     */
    override fun updateDeviceLocation(point: GeoPoint, accuracyMeters: Float, bearingDeg: Float) {
        val w = toWgs(point)
        lastWgs = w
        lastAcc = accuracyMeters
        lastBearing = bearingDeg
        // 先落位置再画蓝点：position 为空时 Marker 绘制会 NPE（见 syncBlueDot 注释）
        syncBlueDot()
        if (followOn) controller.animateTo(w)
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

    /** 自绘蓝色定位点（实心蓝圆 + 白环） */
    private fun blueDotBitmap(): Bitmap {
        val density = appContext.resources.displayMetrics.density
        val size = (24 * density).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = size / 2f
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt()
        }
        canvas.drawCircle(r, r, r - 1, white)
        val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = 0xFF1E88E5.toInt()
        }
        canvas.drawCircle(r, r, r * 0.62f, blue)
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
