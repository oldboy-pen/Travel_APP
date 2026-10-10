package com.example.myfirstapp.ui.screens

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amap.api.maps.model.LatLng
import com.example.myfirstapp.data.MapViewModel
import com.example.myfirstapp.map.GeoPoint
import com.example.myfirstapp.map.MapSurface
import com.example.myfirstapp.map.MapUiSettings
import com.example.myfirstapp.map.rememberMapSurfaceState
import com.example.myfirstapp.track.ActivityType
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.PendingNavTrack
import com.example.myfirstapp.track.Track
import com.example.myfirstapp.track.TrackPoint
import com.example.myfirstapp.ui.components.MapLayerSwitcher

/**
 * 地图页：地图显示 + 蓝点定位 + 目的地（长按/手动输入）+ 驾车路线规划 + App 内导航
 *
 * 使用方式：
 * 1. 进入页面自动申请定位权限并开始定位（地图上显示蓝色小圆点）
 * 2. 选目的地有两种方式：
 *    - 长按地图任意位置 → 直接打一个目的地标记；
 *    - 底部输入框输入「地点名」（如"北京南站"）或「纬度,经度」→ 点搜索。
 * 3. 点"规划驾车路线" → 画出蓝色路线，显示里程与时间
 * 4. 点"App内导航" → 在 App 内沿该路线导航（剩余里程 / 偏离预警 / 语音播报），
 *    不再跳转高德地图 App。
 *
 * 地图本身由 [MapSurface] 接管：当前底图选到腾讯/百度时，整张地图就是那家
 * 厂商原生 SDK 渲染出来的（见 map 包）。本页所有的画点/画线/移动镜头操作
 * 都走 [MapSurfaceState.engine]，不再依赖任何一家地图 SDK 的类。
 */
@Composable
fun MapScreen(viewModel: MapViewModel = viewModel(), onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapState = rememberMapSurfaceState("map")
    // 首次拿到定位时把镜头拉到身边一次，之后不再打扰用户手动拖的位置
    var autoCentered by remember { mutableStateOf(false) }
    // 目的地输入框（手动输入用）
    var destInput by remember { mutableStateOf("") }
    // 一键导航：点"App内导航"时若还没规划路线，先规划、规划完成自动进导航
    var navigatePending by remember { mutableStateOf(false) }

    // ---- 运行时定位权限申请（Android 6.0+ 必须） ----
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocationPermission = result.values.any { it }
        if (hasLocationPermission) viewModel.startLocation()
    }

    LaunchedEffect(Unit) {
        if (hasLocationPermission) {
            viewModel.startLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // ViewModel 的 message → Toast
    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    // 一键导航：规划完成（拿到路线）即进 App 内导航；规划失败则提示
    LaunchedEffect(state.routePoints, state.planFailed) {
        if (!navigatePending) return@LaunchedEffect
        when {
            state.routePoints.isNotEmpty() -> {
                navigatePending = false
                state.myLocation?.let { my ->
                    PendingNavTrack.set(buildRouteTrack(my, state.routePoints, state.destinationName ?: "目的地"))
                    onNavigate("navDest")
                }
            }
            state.planFailed -> {
                navigatePending = false
                Toast.makeText(context, "路线规划失败，无法导航", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- 地图就绪后：开蓝点（跟随模式） ----
        // key 带上权限：首次进入是先弹权限框再拿定位，引擎可能在拿到权限前就开了定位开关
        // （osmdroid 引擎没有权限就起不来内置定位），权限到位后要重新开一次。
        LaunchedEffect(mapState.engine, hasLocationPermission) {
            mapState.engine?.setMyLocationEnabled(true, follow = true)
        }

        // ---- 每次拿到新定位都喂给地图（百度引擎必须靠这个才有蓝点）----
        // 只有第一次定位把镜头拉到身边，之后不再打扰用户手动拖动的位置
        LaunchedEffect(mapState.engine, state.myLocation) {
            val engine = mapState.engine ?: return@LaunchedEffect
            val loc = state.myLocation ?: return@LaunchedEffect
            val p = GeoPoint(loc.latitude, loc.longitude)
            engine.updateDeviceLocation(p, 0f, 0f)
            if (autoCentered.not()) {
                autoCentered = true
                engine.moveCamera(p, 16f)
            }
        }

        // ---- 目的地 / 路线变化 → 重绘覆盖物 ----
        LaunchedEffect(mapState.engine, state.destination, state.routePoints) {
            val engine = mapState.engine ?: return@LaunchedEffect
            engine.clearOverlays()   // 只清自己画过的东西，不动底图瓦片层
            state.destination?.let { dest ->
                engine.addMarker(GeoPoint(dest.latitude, dest.longitude), title = "目的地")
            }
            if (state.routePoints.isNotEmpty()) {
                val route = state.routePoints.map { GeoPoint(it.latitude, it.longitude) }
                engine.addPolyline(route, widthPx = 20f, colorArgb = 0xFF1E88E5.toInt())
                // 让镜头自动框住整条路线
                engine.fitBounds(route, paddingPx = 64, animate = true)
            }
        }

        MapSurface(
            state = mapState,
            pageKey = "map",
            modifier = Modifier.fillMaxSize(),
            uiSettings = MapUiSettings(myLocationButton = true), // 指北针用自绘的 PhoneCompass（跟随手机转动），不用 SDK 自带的
            onLongClick = { point -> viewModel.setDestination(point.toLatLng()) },
            overlay = {
                // 物理指北针：左上角，跟随手机转动，始终指向真实北方
                com.example.myfirstapp.ui.components.PhoneCompass(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 10.dp, top = 10.dp)
                )
                MapLayerSwitcher(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 10.dp)
                )
                // 离线地图入口：下载离线区域 / 官方城市包 / 高程海拔
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = { onNavigate("offline") },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 10.dp, top = 56.dp)
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Default.DownloadForOffline,
                        contentDescription = "离线地图"
                    )
                }
            }
        )

        // ---- 底部信息 & 操作面板 ----
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                // 当前图源署名：切换到底图/叠加层是第三方瓦片时，
                // 左下角 SDK logo 始终显示「高德地图」，这里明确真正的数据来源
                com.example.myfirstapp.ui.components.MapAttribution(
                    floating = false,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                // ---- 目的地手动输入：地点名 或 纬度,经度 ----
                Text("目的地", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = destInput,
                        onValueChange = { destInput = it },
                        placeholder = { Text("输入地点名或 纬度,经度", fontSize = 13.sp) },
                        singleLine = true,
                        leadingIcon = { androidx.compose.material3.Icon(Icons.Default.Search, contentDescription = "搜索") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.searchDestination(destInput) },
                        enabled = destInput.isNotBlank()
                    ) { Text("搜索") }
                }
                Spacer(Modifier.height(10.dp))

                // 海拔优先取离线 DEM（无网也能查），没有才退回 GPS 椭球高；
                // 位置来源标出"离线GPS"，让用户知道现在是靠卫星而非网络定位
                val altTag = state.altitudeMeters?.let { " · 海拔 ${"%.0f".format(it)} m" } ?: ""
                val srcTag = if (state.locationProvider == "gps") " · 离线GPS" else ""
                Text(
                    "我的位置：${state.locationText ?: "定位中…"}$altTag$srcTag",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                // 先捕获到局部 val（state 是 by 委托属性，不能对其字段做 smart cast）
                val destName = state.destinationName
                val dest = state.destination
                val destAlt = state.destAltitudeMeters?.let { " · 海拔 ${"%.0f".format(it)} m" } ?: ""
                Text(
                    "已选目的地：${
                        when {
                            destName != null -> destName
                            dest != null ->
                                "%.5f, %.5f".format(dest.latitude, dest.longitude)
                            else -> "长按地图或上方输入"
                        }
                    }$destAlt",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                state.routeInfo?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "路线：$it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(
                        onClick = viewModel::planRoute,
                        enabled = state.myLocation != null && state.destination != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("规划驾车路线") }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = {
                            val my = state.myLocation
                            val pts = state.routePoints
                            if (my != null && pts.isNotEmpty()) {
                                // 已规划好路线：直接进 App 内导航
                                PendingNavTrack.set(buildRouteTrack(my, pts, state.destinationName ?: "目的地"))
                                onNavigate("navDest")
                            } else if (my != null && state.destination != null) {
                                // 还没规划：先规划，规划完成由上面的 LaunchedEffect 自动进导航
                                navigatePending = true
                                viewModel.planRoute()
                            } else {
                                Toast.makeText(context, "请先选择目的地", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = state.myLocation != null && state.destination != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("App内导航") }
                }
            }
        }
    }
}

/** GeoPoint → 高德 LatLng：ViewModel 的定位/路径规划仍基于高德 SDK，需要在边界上转一手 */
private fun GeoPoint.toLatLng(): LatLng = LatLng(latitude, longitude)

/**
 * 把"我的位置 → 目的地"的驾车路线（polyline）构建成一条内存中的 [Track]，
 * 供 App 内导航（[com.example.myfirstapp.track.TrackNavigator]）沿其行进。
 * 全程不落库，到达后只保存实际行驶轨迹。
 */
private fun buildRouteTrack(my: LatLng, points: List<LatLng>, name: String): Track {
    val now = System.currentTimeMillis()
    val tps = buildList {
        add(TrackPoint(my.latitude, my.longitude, now, 0.0, 0f))
        points.forEachIndexed { i, p -> add(TrackPoint(p.latitude, p.longitude, now + i + 1, 0.0, 0f)) }
    }
    var dist = 0.0
    for (i in 1 until tps.size) {
        dist += GeoUtils.distance(
            tps[i - 1].latitude, tps[i - 1].longitude, tps[i].latitude, tps[i].longitude
        )
    }
    return Track(
        id = "route_" + now,
        name = name,
        startTime = now,
        endTime = now,
        points = tps,
        waypoints = emptyList(),
        distanceMeters = dist,
        durationMillis = 0,
        climbMeters = 0.0,
        activityType = ActivityType.DRIVING
    )
}
