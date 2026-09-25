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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.PolylineOptions
import com.example.myfirstapp.data.MapUiState
import com.example.myfirstapp.data.MapViewModel

/**
 * 地图页：地图显示 + 蓝点定位 + 长按选目的地 + 驾车路线规划 + 一键导航
 *
 * 使用方式：
 * 1. 进入页面自动申请定位权限并开始定位（地图上显示蓝色小圆点）
 * 2. 长按地图任意位置 → 打一个目的地标记
 * 3. 点"规划驾车路线" → 画出蓝色路线，显示里程与时间
 * 4. 点"开始导航" → 拉起高德地图App（未安装则打开网页版）
 */
@Composable
fun MapScreen(viewModel: MapViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
        AMapView(state = state, onMapLongClick = viewModel::setDestination)

        // ---- 底部信息 & 操作面板 ----
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
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

/**
 * 把高德 MapView（传统 View 体系）嵌入 Compose：
 * - remember 保存 MapView 实例，避免重组时重建
 * - DisposableEffect 绑定生命周期（MapView 必须收到 onCreate/onResume/onPause/onDestroy）
 * - 状态变化通过 LaunchedEffect 转换为地图上的覆盖物
 */
@Composable
private fun AMapView(state: MapUiState, onMapLongClick: (LatLng) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 池化复用：切 Tab 不销毁地图（高德 SDK 频繁销毁-重建会 native 崩溃）
    val mapView = remember { com.example.myfirstapp.ui.components.AMapViewPool.get("map", context) }
    val aMap = remember { mapView.map }

    // 生命周期绑定
    DisposableEffect(lifecycleOwner) {
        com.example.myfirstapp.ui.components.AMapViewPool.ensureCreated("map", context)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY ->
                    com.example.myfirstapp.ui.components.AMapViewPool.destroy("map")
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // 切走页面：只解除监听 + pause，不销毁地图（实例留在池中复用）
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        onRelease = { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
        }
    )

    // 初始化地图：蓝点连续定位 + 右下角定位按钮 + 长按设目的地
    LaunchedEffect(Unit) {
        aMap.apply {
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isMyLocationButtonEnabled = true
            myLocationStyle = MyLocationStyle().apply {
                myLocationType(MyLocationStyle.LOCATION_TYPE_FOLLOW) // 跟随模式
                interval(3000)
                showMyLocation(true)
            }
            isMyLocationEnabled = true
            setOnMapLongClickListener { onMapLongClick(it) }
        }
    }

    // 目的地 / 路线变化 → 重绘地图覆盖物
    LaunchedEffect(state.destination, state.routePoints) {
        aMap.clear()  // 清除旧 Marker 和 Polyline（蓝点不受影响）
        state.destination?.let {
            aMap.addMarker(MarkerOptions().position(it).title("目的地"))
        }
        if (state.routePoints.isNotEmpty()) {
            aMap.addPolyline(
                PolylineOptions()
                    .addAll(state.routePoints)
                    .width(20f)
                    .color(0xFF1E88E5.toInt())
            )
            // 让镜头自动框住整条路线
            val bounds = LatLngBounds.builder().apply {
                state.routePoints.forEach { include(it) }
            }.build()
            aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 64))
        }
    }
}

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
