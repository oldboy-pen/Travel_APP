package com.example.myfirstapp.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeQuery
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeResult
import com.amap.api.services.route.BusRouteResult
import com.amap.api.services.route.DriveRouteResult
import com.amap.api.services.route.RideRouteResult
import com.amap.api.services.route.RouteSearch
import com.amap.api.services.route.WalkRouteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.example.myfirstapp.location.AppLocationSource
import com.example.myfirstapp.mapsources.GeoTransform
import com.example.myfirstapp.offline.ElevationStore

/** 地图页 UI 状态（一次快照，交给 Compose 渲染） */
data class MapUiState(
    val myLocation: LatLng? = null,      // 我的实时位置
    val locationText: String? = null,    // 位置文字描述
    val destination: LatLng? = null,     // 目的地（长按地图设置）
    val destinationName: String? = null, // 目的地名称（手动输入/搜索得到，长按则为 null）
    val routePoints: List<LatLng> = emptyList(), // 规划出的驾车路线
    val routeInfo: String? = null,       // "x.x 公里 · 约 x 分钟"
    val planFailed: Boolean = false,     // 最近一次路线规划是否失败（App内导航据此提示）
    val message: String? = null,         // Toast 消息
    /** 当前位置的海拔（米）：优先取离线 DEM，没有则退回 GPS 椭球高 */
    val altitudeMeters: Double? = null,
    /** 位置来源："gps"=系统卫星（离线可用）/ "amap"=高德混合定位（需联网） */
    val locationProvider: String? = null,
    /** 目的地海拔（米），长按选点时从离线 DEM 查 */
    val destAltitudeMeters: Double? = null
)

/**
 * 地图页 ViewModel：定位 + 驾车路径规划
 *
 * 为什么用 AndroidViewModel？高德的定位/搜索 SDK 都需要 Context，
 * 使用 Application 避免内存泄漏（绝不能把 Activity 传给长生命周期对象）。
 */
class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    /** 统一位置源：高德混合定位 + 系统 GPS 无网兜底（见 AppLocationSource） */
    private var locationSource: AppLocationSource? = null
    private val routeSearch = RouteSearch(application)
    private val geocodeSearch = GeocodeSearch(application)
    private var lastGeocodeQuery = ""

    init {
        routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
            override fun onDriveRouteSearched(result: DriveRouteResult?, errorCode: Int) {
                handleDriveRoute(result, errorCode)
            }

            // 本示例只做驾车规划，其余回调留空
            override fun onBusRouteSearched(result: BusRouteResult?, errorCode: Int) = Unit
            override fun onWalkRouteSearched(result: WalkRouteResult?, errorCode: Int) = Unit
            override fun onRideRouteSearched(result: RideRouteResult?, errorCode: Int) = Unit
        })

        // 地理编码：把"地点名"转成坐标（手动输入目的地用）
        geocodeSearch.setOnGeocodeSearchListener(object :
            com.amap.api.services.geocoder.GeocodeSearch.OnGeocodeSearchListener {
            override fun onGeocodeSearched(result: GeocodeResult?, code: Int) {
                handleGeocode(result, code)
            }
            override fun onRegeocodeSearched(result: RegeocodeResult?, code: Int) = Unit
        })
    }

    /** 开始持续定位（需已获得定位权限且用户已同意隐私政策） */
    fun startLocation() {
        if (locationSource != null) return
        locationSource = AppLocationSource(
            context = getApplication(),
            intervalMs = 3000,
            once = false,
            needAddress = true,          // 返回文字地址
            onLocation = { loc ->
                _uiState.update {
                    it.copy(
                        myLocation = LatLng(loc.latitude, loc.longitude),
                        locationText = loc.address,
                        // GPS 的椭球高误差较大，有离线 DEM 时以 DEM 为准（在 refreshAltitude 里覆盖）
                        altitudeMeters = if (loc.altitude.isNaN()) null else loc.altitude,
                        locationProvider = loc.provider,
                        message = null
                    )
                }
                refreshAltitude()
            },
            onError = { msg ->
                // 无网时高德失败是常态，AppLocationSource 会自动转 GPS；
                // 只有 GPS 这条路也断了（没权限 / 系统定位没开）才提示用户，避免 Toast 刷屏
                if (msg.contains("权限") || msg.contains("GPS")) {
                    _uiState.update { it.copy(message = msg) }
                }
            }
        ).apply { start() }
    }

    /**
     * 刷新当前位置/目的地的海拔。
     *
     * ★ 优先级：离线 DEM（[ElevationStore]）> GPS 椭球高。
     *   DEM 是相对大地水准面的海拔，比 GPS 给的椭球高更接近"地图上标注的高度"，
     *   而且查的是本地文件 —— 无网时这是唯一能拿到海拔的途径。
     * 坐标为 GCJ-02（业务口径），DEM 是 WGS-84，边界处转换。
     */
    fun refreshAltitude() {
        val s = _uiState.value
        val my = s.myLocation
        val dest = s.destination
        val myAlt = if (my != null) lookupElevation(my.latitude, my.longitude)
            ?: s.altitudeMeters else null
        val destAlt = if (dest != null) lookupElevation(dest.latitude, dest.longitude) else null
        if (myAlt != s.altitudeMeters || destAlt != s.destAltitudeMeters) {
            _uiState.update { it.copy(altitudeMeters = myAlt, destAltitudeMeters = destAlt) }
        }
    }

    /** GCJ-02 → WGS-84 → 查离线 DEM */
    private fun lookupElevation(lat: Double, lng: Double): Double? {
        val w = GeoTransform.gcj02ToWgs84(lng, lat)   // 返回 [lng, lat]
        return runCatching { ElevationStore.elevationAt(w[1], w[0]) }.getOrNull()
    }

    /** 设置目的地（长按地图 / 手动输入都走这里）。改目的地会清掉旧路线 */
    fun setDestination(latLng: LatLng, name: String? = null) {
        _uiState.update {
            it.copy(
                destination = latLng,
                destinationName = name,
                routePoints = emptyList(),   // 清除旧路线
                routeInfo = null,
                planFailed = false
            )
        }
        // 顺带查一次目的地海拔（离线 DEM，无网也能查）
        _uiState.update { it.copy(destAltitudeMeters = lookupElevation(latLng.latitude, latLng.longitude)) }
    }

    /**
     * 手动输入目的地：支持两种写法
     * 1. 地点名（如"北京南站"）→ 高德地理编码转坐标；
     * 2. 经纬度（"纬度,经度" 或 "纬度 经度"，中英文逗号/空格均可）→ 直接解析。
     * 解析失败（非坐标且地理编码无结果）给出 Toast 提示。
     */
    fun searchDestination(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            _uiState.update { it.copy(message = "请输入目的地或坐标") }
            return
        }
        // 先尝试按"纬度,经度"解析，命中则无需联网
        parseCoord(q)?.let {
            setDestination(it, "手动坐标")
            return
        }
        lastGeocodeQuery = q
        _uiState.update {
            it.copy(message = "正在搜索「$q」…", routePoints = emptyList())
        }
        geocodeSearch.getFromLocationNameAsyn(GeocodeQuery(q, ""))
    }

    /** 解析"纬度,经度"：顺序不敏感（先纬度后经度，或反过来都能识别） */
    private fun parseCoord(q: String): LatLng? {
        val m = Regex("""(-?\d+(?:\.\d+)?)\s*[ ,，]\s*(-?\d+(?:\.\d+)?)""").find(q)
            ?: return null
        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val b = m.groupValues[2].toDoubleOrNull() ?: return null
        val (lat, lng) = if (a in -90.0..90.0) a to b
        else if (b in -90.0..90.0) b to a
        else return null
        if (lng < -180.0 || lng > 180.0) return null
        return LatLng(lat, lng)
    }

    /** 地理编码结果：取第一个候选，转成目的地坐标 */
    private fun handleGeocode(result: GeocodeResult?, code: Int) {
        val list = result?.geocodeAddressList
        if (code != 1000 || list.isNullOrEmpty()) {
            _uiState.update {
                it.copy(message = "未找到「$lastGeocodeQuery」，换个关键词试试", routePoints = emptyList())
            }
            return
        }
        val addr = list.first()
        val lp = addr.latLonPoint
        if (lp == null) {
            _uiState.update { it.copy(message = "未找到「$lastGeocodeQuery」") }
            return
        }
        setDestination(LatLng(lp.latitude, lp.longitude), addr.formatAddress ?: lastGeocodeQuery)
    }

    /** 规划"我的位置 → 目的地"的驾车路线 */
    fun planRoute() {
        val s = _uiState.value
        val from = s.myLocation
        val to = s.destination
        if (from == null) {
            _uiState.update { it.copy(message = "正在定位中，请稍候再试") }
            return
        }
        if (to == null) {
            _uiState.update { it.copy(message = "请先长按地图选择目的地") }
            return
        }
        _uiState.update { it.copy(message = "正在规划路线…", routePoints = emptyList(), planFailed = false) }

        val fromAndTo = RouteSearch.FromAndTo(
            LatLonPoint(from.latitude, from.longitude),
            LatLonPoint(to.latitude, to.longitude)
        )
        // 策略：速度优先（默认）；null 表示不途经；避开区域传 null；最后参数为避让道路名
        val query = RouteSearch.DriveRouteQuery(
            fromAndTo, RouteSearch.DRIVING_SINGLE_DEFAULT, null, null, ""
        )
        routeSearch.calculateDriveRouteAsyn(query)
    }

    /** 处理驾车路线结果：抽取整条路线的坐标点，供地图画线 */
    private fun handleDriveRoute(result: DriveRouteResult?, errorCode: Int) {
        val path = result?.paths?.firstOrNull()
        if (errorCode != 1000 || path == null) {   // 1000 = 成功
            _uiState.update {
                it.copy(message = "路线规划失败（code=$errorCode）", routePoints = emptyList(), planFailed = true)
            }
            return
        }
        val points = ArrayList<LatLng>()
        // 注意：搜索 SDK 返回的路线坐标是 LatLonPoint，需转换为地图 SDK 的 LatLng
        path.steps?.forEach { step ->
            step.polyline?.forEach { p -> points.add(LatLng(p.latitude, p.longitude)) }
        }

        _uiState.update {
            it.copy(
                routePoints = points,
                routeInfo = "%.1f 公里 · 约 %d 分钟".format(
                    path.distance / 1000.0,
                    path.duration / 60
                ),
                message = null
            )
        }
    }

    override fun onCleared() {
        locationSource?.stop()
        locationSource = null
        super.onCleared()
    }
}
