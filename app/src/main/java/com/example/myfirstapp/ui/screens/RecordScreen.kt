package com.example.myfirstapp.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.PolylineOptions
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.RecorderState
import com.example.myfirstapp.track.TrackRecorder
import com.example.myfirstapp.track.TrackRecordingService
import com.example.myfirstapp.track.TrackRepository

/**
 * 运动记录页（两步路核心功能）：
 * - 大按钮 开始/暂停/继续/结束
 * - 实时数据面板：距离、时长、均速、当前速度、累计爬升、GPS信号
 * - 地图实时绘制轨迹线
 * - 记录中可打"途经点"
 * - 结束后保存到轨迹库并跳转详情
 */
@Composable
fun RecordScreen(onTrackSaved: (String) -> Unit) {
    val context = LocalContext.current
    val data by TrackRecorder.data.collectAsStateWithLifecycle()
    var noteText by remember { mutableStateOf("") }
    var showWaypointSettings by remember { mutableStateOf(false) }

    // ---- 权限：定位（Android 6+）+ 通知（Android 13+，前台服务需要） ----
    var permissionsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ok = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        permissionsGranted = ok
        if (!ok) Toast.makeText(context, "没有定位权限无法记录轨迹", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(Unit) {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(need.toTypedArray())
    }

    Box(Modifier.fillMaxSize()) {
        // ---- 地图：跟随蓝点 + 实时轨迹线 ----
        TrackingMapView(data)

        // ---- 顶部数据面板 ----
        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatCell(GeoUtils.formatDistance(data.distanceMeters), "距离")
                StatCell(GeoUtils.formatDuration(data.durationMillis), "时长")
                StatCell(
                    "%.1f".format(
                        if (data.durationMillis > 0)
                            data.distanceMeters / (data.durationMillis / 1000.0) * 3.6 else 0.0
                    ) + " km/h", "均速"
                )
                StatCell("%.0f 米".format(data.climbMeters), "爬升")
            }
        }

        // ---- 底部控制区 ----
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    when {
                        data.state == RecorderState.RECORDING && data.fixCount < 5 -> "GPS 信号弱，请在开阔处等待…"
                        data.state == RecorderState.RECORDING -> "正在记录 · 当前 %.1f km/h".format(data.currentSpeed * 3.6)
                        data.state == RecorderState.PAUSED -> "已暂停"
                        else -> "准备就绪"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (data.fixCount < 5 && data.state == RecorderState.RECORDING)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (data.state) {
                        RecorderState.IDLE -> Button(
                            onClick = {
                                if (!permissionsGranted) {
                                    Toast.makeText(context, "请先授予定位权限", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                TrackRecorder.start(context)
                                TrackRecordingService.start(context) // 前台服务保活
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Icon(Icons.Default.PlayArrow, null)
                            Text("开始记录", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }

                        RecorderState.RECORDING -> Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RecordQuickActionButton(
                                    label = "文字",
                                    icon = Icons.Default.TextFields,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    TrackRecorder.addWaypoint(
                                        type = com.example.myfirstapp.track.WaypointType.TEXT,
                                        text = noteText.ifBlank { "文字标记 ${data.waypoints.size + 1}" }
                                    )
                                    noteText = ""
                                }
                                RecordQuickActionButton(
                                    label = "拍摄",
                                    icon = Icons.Default.CameraAlt,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    TrackRecorder.addWaypoint(
                                        type = com.example.myfirstapp.track.WaypointType.PHOTO,
                                        text = "图片标记 ${data.waypoints.size + 1}",
                                        mediaUri = "photo://capture"
                                    )
                                }
                                RecordQuickActionButton(
                                    label = "语音",
                                    icon = Icons.Default.Mic,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    TrackRecorder.addWaypoint(
                                        type = com.example.myfirstapp.track.WaypointType.VOICE,
                                        text = "语音标记 ${data.waypoints.size + 1}",
                                        mediaUri = "voice://record"
                                    )
                                }
                                RecordQuickActionButton(
                                    label = "设置",
                                    icon = Icons.Default.Settings,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    showWaypointSettings = true
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            if (showWaypointSettings) {
                                OutlinedTextField(
                                    value = noteText,
                                    onValueChange = { noteText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("为当前打点添加说明…") },
                                    minLines = 2,
                                    maxLines = 3,
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FilledTonalButton(
                                    onClick = { TrackRecorder.pause() },
                                    modifier = Modifier.weight(1f)
                                ) { Icon(Icons.Default.Pause, null); Text("  暂停") }
                                Button(
                                    onClick = { finishRecording(context, onTrackSaved) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f)
                                ) { Icon(Icons.Default.Stop, null); Text("  结束") }
                            }
                        }

                        RecorderState.PAUSED -> Row {
                            Button(
                                onClick = { TrackRecorder.start(context) },
                                modifier = Modifier.weight(1f)
                            ) { Icon(Icons.Default.PlayArrow, null); Text("  继续") }
                            Spacer(Modifier.width(12.dp))
                            Button(
                                onClick = { finishRecording(context, onTrackSaved) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f)
                            ) { Icon(Icons.Default.Stop, null); Text("  结束") }
                        }
                    }
                }
            }
        }
    }
}

/** 结束记录：轨迹太少提示，正常则保存并跳详情 */
private fun finishRecording(context: Context, onTrackSaved: (String) -> Unit) {
    val track = TrackRecorder.stop()
    TrackRecordingService.stop(context)
    if (track == null) {
        Toast.makeText(context, "轨迹点太少，已丢弃", Toast.LENGTH_SHORT).show()
    } else {
        TrackRepository.get(context).save(track)
        Toast.makeText(context, "已保存：${track.name}", Toast.LENGTH_SHORT).show()
        onTrackSaved(track.id)
    }
}

/** 地图：跟随模式蓝点 + 实时轨迹线 */
@Composable
private fun TrackingMapView(data: com.example.myfirstapp.track.RecordingData) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    val aMap = remember { mapView.map }
    var cameraFollowed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val owner = lifecycleOwner
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapView.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        aMap.uiSettings.isZoomControlsEnabled = false
        aMap.uiSettings.isMyLocationButtonEnabled = true
        aMap.isMyLocationEnabled = true
        aMap.myLocationStyle = MyLocationStyle().apply {
            myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE) // 定位+旋转跟随
            interval(2000)
        }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

    // 轨迹点变化 → 重画轨迹线；首个点 → 移动镜头
    LaunchedEffect(data.points.size) {
        if (data.points.isEmpty()) { aMap.clear(); cameraFollowed = false; return@LaunchedEffect }
        if (!cameraFollowed) {
            aMap.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(data.points.first().latitude, data.points.first().longitude), 17f
                )
            )
            cameraFollowed = true
        }
        if (data.points.size >= 2) {
            aMap.clear()
            aMap.addPolyline(
                PolylineOptions()
                    .addAll(data.points.map { LatLng(it.latitude, it.longitude) })
                    .width(12f)
                    .color(0xFF2E7D32.toInt())
            )
        }
    }
}

/** 数据面板单元格 */
@Composable
private fun StatCell(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecordQuickActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp)
    ) {
        Icon(icon, contentDescription = label, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}
