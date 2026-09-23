package com.example.myfirstapp.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.BusRouteResult
import com.amap.api.services.route.DriveRouteResult
import com.amap.api.services.route.RideRouteResult
import com.amap.api.services.route.RouteSearch
import com.amap.api.services.route.WalkRouteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 地图页 UI 状态（一次快照，交给 Compose 渲染） */
data class MapUiState(
    val myLocation: LatLng? = null,      // 我的实时位置
    val locationText: String? = null,    // 位置文字描述
    val destination: LatLng? = null,     // 目的地（长按地图设置）
    val routePoints: List<LatLng> = emptyList(), // 规划出的驾车路线
    val routeInfo: String? = null,       // "x.x 公里 · 约 x 分钟"
    val message: String? = null          // Toast 消息
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

    private var locationClient: AMapLocationClient? = null
    private val routeSearch = RouteSearch(application)

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
    }

    /** 开始持续定位（需已获得定位权限且用户已同意隐私政策） */
    fun startLocation() {
        if (locationClient != null) return
        locationClient = AMapLocationClient(getApplication<Application>()).apply {
            setLocationOption(AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = false            // 持续定位
                interval = 3000                   // 每 3 秒一次
                isNeedAddress = true              // 返回文字地址
            })
            setLocationListener { loc ->
                if (loc.errorCode == 0) {
                    _uiState.update {
                        it.copy(
                            myLocation = LatLng(loc.latitude, loc.longitude),
                            locationText = loc.poiName?.ifEmpty { null } ?: loc.address,
                            message = null
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(message = "定位失败：${loc.errorInfo}（code=${loc.errorCode}）")
                    }
                }
            }
            startLocation()
        }
    }

    /** 长按地图设置目的地 */
    fun setDestination(latLng: LatLng) {
        _uiState.update {
            it.copy(
                destination = latLng,
                routePoints = emptyList(),   // 清除旧路线
                routeInfo = null
            )
        }
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
        _uiState.update { it.copy(message = "正在规划路线…", routePoints = emptyList()) }

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
                it.copy(message = "路线规划失败（code=$errorCode）", routePoints = emptyList())
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
        locationClient?.stopLocation()
        locationClient?.onDestroy()
        locationClient = null
        super.onCleared()
    }
}
