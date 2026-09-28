package com.example.myfirstapp.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amap.api.maps.model.LatLng
import com.example.myfirstapp.data.MapViewModel
import com.example.myfirstapp.map.GeoPoint
import com.example.myfirstapp.map.MapSurface
import com.example.myfirstapp.map.MapSurfaceState
import com.example.myfirstapp.map.MapUiSettings
import com.example.myfirstapp.map.rememberMapSurfaceState
import com.example.myfirstapp.ui.components.MapLayerSwitcher

/**
 * 地图页：地图显示 + 蓝点定位 + 长按选目的地 + 驾车路线规划 + 一键导航
 *
 * 使用方式：
 * 1. 进入页面自动申请定位权限并开始定位（地图上显示蓝色小圆点）
 * 2. 长按地图任意位置 → 打一个目的地标记
 * 3. 点"规划驾车路线" → 画出蓝色路线，显示里程与时间
 * 4. 点"开始导航" → 拉起高德地图App（未安装则打开网页版）
 *
 * 地图本身由 [MapSurface] 接管：当前底图选到腾讯/百度时，整张地图就是那家
 * 厂商原生 SDK 渲染出来的（见 map 包）。本页所有的画点/画线/移动镜头操作
 * 都走 [MapSurfaceState.engine]，不再依赖任何一家地图 SDK 的类。
 */
@Composable
fun MapScreen(viewModel: MapViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapState = rememberMapSurfaceState("map")
    // 首次拿到定位时把镜头拉到身边一次，之后不再打扰用户手动拖的位置
    var autoCentered by remember { mutableStateOf(false) }

    // ---- 运行时定位权限申请（Android 6.0+ 必须） ----
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
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

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- 地图就绪后：开蓝点（跟随模式） ----
        LaunchedEffect(mapState.engine) {
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
            uiSettings = MapUiSettings(myLocationButton = true),
            onLongClick = { point -> viewModel.setDestination(point.toLatLng()) },
            overlay = {
                MapLayerSwitcher(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 10.dp)
                )
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
                Text(
                    "我的位置：${state.locationText ?: "定位中…"}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "目的地：${
                        state.destination?.let {
                            "%.5f, %.5f".format(it.latitude, it.longitude)
                        } ?: "长按地图任意位置选择"
                    }",
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
                        onClick = { state.destination?.let { startNavi(context, it) } },
                        enabled = state.destination != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("开始导航") }
                }
            }
        }
    }
}

/** GeoPoint → 高德 LatLng：ViewModel 的定位/路径规划仍基于高德 SDK，需要在边界上转一手 */
private fun GeoPoint.toLatLng(): LatLng = LatLng(latitude, longitude)

/** 一键导航：优先拉起高德地图App（驾车），未安装则打开网页版 */
private fun startNavi(context: Context, dest: LatLng) {
    val installed = try {
        context.packageManager.getPackageInfo("com.autonavi.minimap", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    if (installed) {
        // amapuri 协议：t=0 驾车 / 1 步行 / 2 骑行 / 3 公交，dev=0 不偏航
        val uri = "amapuri://route/plan/?sourceApplication=MyFirstApp" +
                "&dlat=${dest.latitude}&dlon=${dest.longitude}&dname=目的地&dev=0&m=0&t=0"
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage("com.autonavi.minimap")
        )
    } else {
        val webUri = "https://uri.amap.com/navigation" +
                "?to=${dest.longitude},${dest.latitude},目的地&mode=car&policy=1&src=MyFirstApp&callnative=0"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUri)))
    }
}
