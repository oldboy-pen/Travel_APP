package com.example.myfirstapp.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
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
    var pendingType by remember { mutableStateOf<com.example.myfirstapp.track.WaypointType?>(null) }
    // 拍照 / 相册选择弹窗 + 相机输出 Uri
    var showPhotoSourceDialog by remember { mutableStateOf(false) }
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }

    /** 统一的媒体标记入库逻辑（相册、拍照、录像、录音共用），explicitType 优先于 pendingType */
    fun addMediaWaypoint(uri: Uri, explicitType: com.example.myfirstapp.track.WaypointType? = null) {
        val type = explicitType ?: pendingType ?: com.example.myfirstapp.track.WaypointType.PHOTO
        TrackRecorder.addWaypoint(
            type = type,
            text = noteText.ifBlank { "${type.name.lowercase()} 标记 ${data.waypoints.size + 1}" },
            mediaUri = uri.toString()
        )
        noteText = ""
        pendingType = null
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) addMediaWaypoint(uri)
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraOutputUri?.let { addMediaWaypoint(it, com.example.myfirstapp.track.WaypointType.PHOTO) }
        cameraOutputUri = null
    }

    /** 拉起系统相机，照片存到 filesDir/camera/（持久保存） */
    fun launchCamera() {
        val dir = java.io.File(context.filesDir, "camera").apply { mkdirs() }
        val file = java.io.File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        cameraOutputUri = uri
        takePicture.launch(uri)
    }

    // ---- 录像：系统相机 CaptureVideo，输出到 filesDir/video/ ----
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success) cameraOutputUri?.let { addMediaWaypoint(it, com.example.myfirstapp.track.WaypointType.VIDEO) }
        cameraOutputUri = null
    }

    /** 拉起系统相机录像，视频存到 filesDir/video/ */
    fun launchVideoCamera() {
        val dir = java.io.File(context.filesDir, "video").apply { mkdirs() }
        val file = java.io.File(dir, "VID_${System.currentTimeMillis()}.mp4")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        cameraOutputUri = uri
        takeVideo.launch(uri)
    }

    // ---- 语音标记：应用内 MediaRecorder 直接录音（不再走相册选择器） ----
    var isRecordingVoice by remember { mutableStateOf(false) }
    var voiceFile by remember { mutableStateOf<java.io.File?>(null) }
    var voiceRecorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var voiceStartAt by remember { mutableStateOf(0L) }
    var voiceElapsedSec by remember { mutableStateOf(0) }

    // 录音计时刷新
    LaunchedEffect(isRecordingVoice) {
        while (isRecordingVoice) {
            voiceElapsedSec = ((System.currentTimeMillis() - voiceStartAt) / 1000).toInt()
            kotlinx.coroutines.delay(500)
        }
    }

    // 离开页面时释放录音器
    DisposableEffect(Unit) {
        onDispose {
            runCatching { voiceRecorder?.stop() }
            runCatching { voiceRecorder?.release() }
            voiceRecorder = null
        }
    }

    fun startVoiceRecording() {
        runCatching {
            @Suppress("DEPRECATION") // minSdk 24，旧构造器兼容 Android 7
            val rec = android.media.MediaRecorder()
            val dir = java.io.File(context.filesDir, "voice").apply { mkdirs() }
            val file = java.io.File(dir, "REC_${System.currentTimeMillis()}.m4a")
            rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(96_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            voiceRecorder = rec
            voiceFile = file
            voiceStartAt = System.currentTimeMillis()
            voiceElapsedSec = 0
            isRecordingVoice = true
        }.onFailure {
            Toast.makeText(context, "录音启动失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopVoiceRecording() {
        val rec = voiceRecorder ?: return
        val file = voiceFile
        runCatching { rec.stop() }
        runCatching { rec.release() }
        voiceRecorder = null
        isRecordingVoice = false
        if (file != null && file.exists() && file.length() > 0) {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            addMediaWaypoint(uri, com.example.myfirstapp.track.WaypointType.VOICE)
        } else {
            Toast.makeText(context, "录音太短，已丢弃", Toast.LENGTH_SHORT).show()
        }
        voiceFile = null
    }

    // 录音权限
    val voicePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceRecording()
        else Toast.makeText(context, "没有麦克风权限无法录制语音", Toast.LENGTH_SHORT).show()
    }

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
                        data.state == RecorderState.RECORDING && data.locationError != null ->
                            data.locationError.orEmpty()
                        data.state == RecorderState.RECORDING && data.fixCount < 5 -> "GPS 信号弱，请在开阔处等待…"
                        data.state == RecorderState.RECORDING -> "正在记录 · 当前 %.1f km/h".format(data.currentSpeed * 3.6)
                        data.state == RecorderState.PAUSED -> "已暂停"
                        else -> "准备就绪"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (data.state == RecorderState.RECORDING &&
                        (data.locationError != null || data.fixCount < 5))
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
                                    if (noteText.isBlank()) {
                                        showWaypointSettings = true
                                    } else {
                                        TrackRecorder.addWaypoint(
                                            type = com.example.myfirstapp.track.WaypointType.TEXT,
                                            text = noteText,
                                            name = "文字标记 ${data.waypoints.size + 1}"
                                        )
                                        noteText = ""
                                    }
                                }
                                RecordQuickActionButton(
                                    label = "拍摄",
                                    icon = Icons.Default.CameraAlt,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    pendingType = com.example.myfirstapp.track.WaypointType.PHOTO
                                    showPhotoSourceDialog = true
                                }
                                RecordQuickActionButton(
                                    label = if (isRecordingVoice) "停止 ${voiceElapsedSec}s"
                                    else "语音",
                                    icon = Icons.Default.Mic,
                                    modifier = Modifier.weight(1f),
                                    highlight = isRecordingVoice
                                ) {
                                    if (isRecordingVoice) {
                                        stopVoiceRecording()
                                    } else if (ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        pendingType = com.example.myfirstapp.track.WaypointType.VOICE
                                        startVoiceRecording()
                                    } else {
                                        pendingType = com.example.myfirstapp.track.WaypointType.VOICE
                                        voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
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
                                    placeholder = { Text("为当前打点添加文字、说明或备注…") },
                                    minLines = 2,
                                    maxLines = 3,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = {
                                            if (noteText.isNotBlank()) {
                                                TrackRecorder.addWaypoint(
                                                    type = com.example.myfirstapp.track.WaypointType.TEXT,
                                                    text = noteText,
                                                    name = "文字标记 ${data.waypoints.size + 1}"
                                                )
                                                noteText = ""
                                            }
                                            showWaypointSettings = false
                                        }
                                    ) {
                                        Text("保存备注")
                                    }
                                }
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
        // ---- 照片来源选择：拍照 / 相册 ----
        if (showPhotoSourceDialog) {
            AlertDialog(
                onDismissRequest = { showPhotoSourceDialog = false },
                title = { Text("拍摄照片 / 视频") },
                text = {
                    Column {
                        TextButton(
                            onClick = {
                                showPhotoSourceDialog = false
                                launchCamera()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CameraAlt, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("拍照")
                        }
                        TextButton(
                            onClick = {
                                showPhotoSourceDialog = false
                                pendingType = com.example.myfirstapp.track.WaypointType.VIDEO
                                launchVideoCamera()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Videocam, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("录像")
                        }
                        TextButton(
                            onClick = {
                                showPhotoSourceDialog = false
                                photoPicker.launch("image/*")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PhotoLibrary, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("从相册选择")
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showPhotoSourceDialog = false }) { Text("取消") }
                }
            )
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

/** 地图：跟随模式蓝点 + 实时轨迹线 + 图层切换 */
@Composable
private fun TrackingMapView(data: com.example.myfirstapp.track.RecordingData) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    val aMap = remember { mapView.map }

    // ---- 图层切换状态 ----
    var layerMode by remember { mutableStateOf(com.example.myfirstapp.ui.components.MapLayerMode.NORMAL) }
    val terrainOverlay = remember { mutableStateOf<com.amap.api.maps.model.TileOverlay?>(null) }

    // ---- 跟随状态：点定位按钮开启，用户手动拖动地图自动退出 ----
    val followMode = remember { mutableStateOf(false) }
    // 地图 SDK 最新定位（蓝点位置缓存；aMap.myLocation 实测取不到，用监听器自己存）
    val blueDotLatLng = remember { mutableStateOf<LatLng?>(null) }
    // 首次拿到定位 → 自动回中一次
    val hasAutoCentered = remember { mutableStateOf(false) }
    val locationStyle = remember {
        MyLocationStyle().apply {
            // 纯定位点：只显示蓝点不自动转镜头；跟随模式用 LOCATION_TYPE_LOCATION_ROTATE
            myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
            interval(2000)
        }
    }
    val followStyle = remember {
        MyLocationStyle().apply {
            // 跟随模式：定位 + 旋转
            myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
            interval(2000)
        }
    }
    /** 退出跟随模式：恢复纯定位点样式（不自动转镜头） */
    fun exitFollowMode() {
        if (followMode.value) {
            followMode.value = false
            aMap.myLocationStyle = locationStyle
        }
    }

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
        aMap.uiSettings.isMyLocationButtonEnabled = false // 关掉右下角内置按钮（会被底部面板遮挡），改为自绘 FAB
        aMap.isMyLocationEnabled = true
        aMap.myLocationStyle = locationStyle

        // 蓝点位置变化 → 缓存最新位置；首次定位自动回中一次
        aMap.setOnMyLocationChangeListener { location ->
            location ?: return@setOnMyLocationChangeListener
            blueDotLatLng.value = LatLng(location.latitude, location.longitude)
            if (!hasAutoCentered.value) {
                hasAutoCentered.value = true
                aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(blueDotLatLng.value!!, 17f))
            }
        }

        // 手势直接判定：用户拖动/双击/甩动地图 → 退出跟随（比镜头监听可靠，不会误杀跟随动画）
        aMap.setAMapGestureListener(object : com.amap.api.maps.model.AMapGestureListener {
            override fun onScroll(dx: Float, dy: Float) { exitFollowMode() }
            override fun onFling(dx: Float, dy: Float) { exitFollowMode() }
            override fun onDoubleTap(x: Float, y: Float) { exitFollowMode() }
            override fun onSingleTap(x: Float, y: Float) {}
            override fun onLongPress(x: Float, y: Float) {}
            override fun onDown(x: Float, y: Float) {}
            override fun onUp(x: Float, y: Float) {}
            override fun onMapStable() {}
        })

        onDispose {
            owner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // ---- 自绘"回到我的位置"按钮：屏幕右侧垂直居中（内置按钮在右下角会被底部面板遮挡） ----
        SmallFloatingActionButton(
            onClick = {
                // 用监听器缓存的蓝点位置；无则回退到最新轨迹点
                val target = blueDotLatLng.value
                    ?: data.points.lastOrNull()?.let { LatLng(it.latitude, it.longitude) }
                if (target != null) {
                    // 开启跟随模式：蓝点居中+旋转，手动拖图自动退出
                    followMode.value = true
                    aMap.myLocationStyle = followStyle
                    aMap.animateCamera(CameraUpdateFactory.changeLatLng(target))
                } else {
                    Toast.makeText(context, "尚未获取到定位", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            containerColor = Color.White
        ) {
            Icon(
                Icons.Default.MyLocation,
                contentDescription = "回到我的位置",
                tint = Color(0xFF2E7D32)
            )
        }

        // ---- 图层切换：右上角（避开顶部数据面板） ----
        com.example.myfirstapp.ui.components.MapLayerSwitcher(
            current = layerMode,
            onSelect = { mode ->
                layerMode = mode
                com.example.myfirstapp.ui.components.applyMapLayer(aMap, mode, terrainOverlay)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 130.dp, end = 12.dp)
        )
    }

    // 轨迹点变化 → 只重画轨迹线（镜头不再自动移动，由定位按钮手动回中）
    LaunchedEffect(data.points.size) {
        if (data.points.isEmpty()) {
            aMap.clear()
            return@LaunchedEffect
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
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = if (highlight)
            ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.error
            )
        else ButtonDefaults.outlinedButtonColors()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 6.dp)
        ) {
            Icon(icon, contentDescription = label, Modifier.size(22.dp))
            Spacer(Modifier.height(3.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
