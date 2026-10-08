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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myfirstapp.map.GeoPoint
import com.example.myfirstapp.map.MapEngine
import com.example.myfirstapp.map.MapSurface
import com.example.myfirstapp.map.MapSurfaceState
import com.example.myfirstapp.map.MapUiSettings
import com.example.myfirstapp.map.rememberMapSurfaceState
import com.example.myfirstapp.mapsources.MapSourceStore
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.RecorderState
import com.example.myfirstapp.track.StepSensorStatus
import com.example.myfirstapp.track.Track
import com.example.myfirstapp.track.TrackColorStore
import com.example.myfirstapp.track.TrackRecorder
import com.example.myfirstapp.track.TrackRecordingService
import com.example.myfirstapp.track.TrackRepository
import com.example.myfirstapp.track.TrackSourceResolver

/**
 * 运动记录页（两步路核心功能）：
 * - 大按钮 开始/暂停/继续/结束
 * - 实时数据面板：距离、时长、均速、当前速度、累计爬升、GPS信号
 * - 地图实时绘制轨迹线
 * - 记录中可打"途经点"
 * - 空闲态可选择一条已保存轨迹进入导航（沿轨迹行进）
 * - 结束后保存到轨迹库并跳转详情
 */
@Composable
fun RecordScreen(
    onTrackSaved: (String) -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val data by TrackRecorder.data.collectAsStateWithLifecycle()
    var noteText by remember { mutableStateOf("") }
    var showWaypointSettings by remember { mutableStateOf(false) }
    var pendingType by remember { mutableStateOf<com.example.myfirstapp.track.WaypointType?>(null) }
    var showActivitySelector by remember { mutableStateOf(false) }
    var selectedActivityType by remember { mutableStateOf(com.example.myfirstapp.track.ActivityType.DEFAULT) }
    // 后台保活引导：未加入电池白名单时，开始新记录前提示一次
    var showKeepAliveDialog by remember { mutableStateOf(false) }
    // 拍照 / 相册选择弹窗 + 相机输出 Uri
    var showPhotoSourceDialog by remember { mutableStateOf(false) }
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    // ---- 轨迹导航：弹窗列出已保存轨迹，选一条开始导航（记录中需先结束）----
    var showNavTrackPicker by remember { mutableStateOf(false) }
    var navTracks by remember { mutableStateOf<List<com.example.myfirstapp.track.Track>>(emptyList()) }

    // ---- 加载轨迹：本地 / 网络 / 已下载 三栏，勾选即叠加到地图作参考 ----
    var showTrackLoadSheet by remember { mutableStateOf(false) }
    var loadedTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    // 单独查看：非空时地图上只显示这一条叠加轨迹（其余临时隐藏），镜头框到它
    var focusTrackId by remember { mutableStateOf<String?>(null) }
    // 本地列表版本号：导入/删除后 +1，让加载面板重新读一次轨迹库
    var localVersion by remember { mutableStateOf(0) }

    /** 单独查看某条：未叠加的先自动叠上，再切到只看它 */
    fun focusTrack(track: Track) {
        if (loadedTracks.none { it.id == track.id }) {
            loadedTracks = loadedTracks + track
        }
        focusTrackId = if (focusTrackId == track.id) null else track.id
        showTrackLoadSheet = false
    }

    /** 勾选/取消勾选：叠加到地图或从地图上撤掉（不动本地文件） */
    fun toggleLoaded(track: Track) {
        loadedTracks = if (loadedTracks.any { it.id == track.id }) {
            loadedTracks.filterNot { it.id == track.id }
                .also { if (focusTrackId == track.id) focusTrackId = null }
        } else loadedTracks + track
    }

    /** 删除本地轨迹：连带从地图撤掉，并清掉来源标记（下载/导入）与自定义线色 */
    fun deleteTrack(track: Track) {
        TrackRepository.get(context).delete(track.id)
        TrackSourceResolver.clearMarks(context, track.id)
        loadedTracks = loadedTracks.filterNot { it.id == track.id }
        if (focusTrackId == track.id) focusTrackId = null
        localVersion++
        Toast.makeText(context, "已删除：${track.name}", Toast.LENGTH_SHORT).show()
    }

    // 从文件导入轨迹（GPX / KML / KMZ 自动识别）→ 直接入库
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val track = TrackRepository.get(context).importTrack(context, uri)
            if (track != null) {
                localVersion++
                Toast.makeText(context, "导入成功：${track.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "导入失败：不是有效的轨迹文件（支持 GPX / KML / KMZ）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 每次打开选择器重新读一次轨迹库（可能刚记录完新轨迹）
    LaunchedEffect(showNavTrackPicker) {
        if (!showNavTrackPicker) return@LaunchedEffect
        navTracks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            TrackRepository.get(context).list().filter { it.points.size >= 2 }
        }
    }

    fun openNavigationPicker() {
        if (data.state == RecorderState.IDLE) {
            showNavTrackPicker = true
        } else {
            Toast.makeText(context, "请先结束当前记录，再进行轨迹导航", Toast.LENGTH_SHORT).show()
        }
    }

    // 底部控制面板：进入运动页时默认折叠到屏幕边缘，点按抓手展开
    var bottomPanelCollapsed by remember { mutableStateOf(true) }
    // 一旦开始/暂停记录，自动展开控制面板，避免丢失「暂停 / 结束」等按钮
    LaunchedEffect(data.state) {
        if (data.state != RecorderState.IDLE) bottomPanelCollapsed = false
    }

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

    // 身体活动权限补申请：授予后立即重启计步传感器（无需重开记录）
    val activityRecognitionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            TrackRecorder.startMotionSensors()
            Toast.makeText(context, "计步已开启", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "无「身体活动」权限，步数将无法统计", Toast.LENGTH_LONG).show()
        }
    }

    fun startTrackingWithSelectedMode(activityType: com.example.myfirstapp.track.ActivityType) {
        selectedActivityType = activityType
        if (!permissionsGranted) {
            Toast.makeText(context, "请先授予定位权限", Toast.LENGTH_SHORT).show()
            return
        }
        // 新轨迹开始时：不在电池优化白名单则提示一次（不影响记录继续进行）
        if (data.state == RecorderState.IDLE &&
            !TrackRecordingService.isIgnoringBatteryOptimizations(context)
        ) {
            showKeepAliveDialog = true
        }
        TrackRecorder.start(context, activityType)
        TrackRecordingService.start(context)
        // Android 10+ 计步需要「身体活动」权限；被拒过的话在开始记录时补一次申请
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
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
        // Android 10+ 计步传感器需要：用于确认"确实在走"，防止小位移被降噪误杀
        if (Build.VERSION.SDK_INT >= 29) need += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(need.toTypedArray())
    }

    Box(Modifier.fillMaxSize()) {
        // ---- 地图：跟随蓝点 + 实时轨迹线 ----
        TrackingMapView(
            data = data,
            bottomPanelCollapsed = bottomPanelCollapsed,
            overlayTracks = loadedTracks,
            focusTrackId = focusTrackId,
            onOpenNavigation = { openNavigationPicker() },
            onOpenTrackLoad = { showTrackLoadSheet = true },
            onFocus = { track -> focusTrack(track) },
            onRemove = { track -> toggleLoaded(track) },
            onClearOverlay = { loadedTracks = emptyList(); focusTrackId = null },
            onExitFocus = { focusTrackId = null }
        )

        // ---- 数据条：记录中/暂停时显示在屏幕最底部（空闲态隐藏）----
        AnimatedVisibility(
            visible = data.state != RecorderState.IDLE,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
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
                    StatCell("${data.stepCount}", "步数")
                }
            }
        }

        // ---- 折叠态：空闲时抓手即「开始记录」按钮，点按直接拉起运动方式选择；
        //      记录中若被收起，抓手用于展开面板 ----
        AnimatedVisibility(
            visible = bottomPanelCollapsed,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            val isIdle = data.state == RecorderState.IDLE
            Surface(
                onClick = {
                    if (isIdle) showActivitySelector = true
                    else bottomPanelCollapsed = false
                },
                modifier = Modifier
                    .padding(bottom = if (isIdle) 12.dp else 76.dp)
                    .height(52.dp)
                    .widthIn(min = 160.dp, max = 220.dp),
                shape = RoundedCornerShape(26.dp),
                color = if (isIdle) Color(0xFF2E7D32) else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isIdle) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("开始记录", fontWeight = FontWeight.Bold, color = Color.White)
                    } else {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("展开控制", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // ---- 展开态：完整控制面板 ----
        AnimatedVisibility(
            visible = !bottomPanelCollapsed,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 76.dp)
            ) {
                // 收起抓手：点按折叠面板至屏幕边缘
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { bottomPanelCollapsed = true }
                        .padding(top = 4.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(2.dp)
                            )
                    )
                }
                Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 当前图源署名（可点开图层面板）：高德 SDK 的 logo 永远显示「高德地图」，
                // 叠加腾迅/百度/OSM 瓦片后必须让用户知道真正的数据归属
                com.example.myfirstapp.ui.components.MapAttribution(
                    floating = false,
                    modifier = Modifier
                        .align(Alignment.Start) // Column 内的水平对齐：Alignment.Horizontal
                        .padding(bottom = 8.dp)
                )
                Text(
                    when {
                        data.state == RecorderState.RECORDING && data.locationError != null ->
                            data.locationError.orEmpty()
                        data.state == RecorderState.RECORDING &&
                                data.stepSensorStatus == StepSensorStatus.NO_PERMISSION ->
                            "正在记录 · 计步不可用：请授予「身体活动」权限（结束并重开记录生效）"
                        data.state == RecorderState.RECORDING &&
                                data.stepSensorStatus == StepSensorStatus.NO_SENSOR ->
                            "正在记录 · 计步不可用：本机无计步传感器"
                        data.state == RecorderState.RECORDING && data.fixCount < 5 -> "GPS 信号弱，请在开阔处等待…"
                        data.state == RecorderState.RECORDING -> "正在记录 · ${data.activityType.label} · 当前 %.1f km/h".format(data.currentSpeed * 3.6)
                        data.state == RecorderState.PAUSED -> "已暂停 · ${data.activityType.label}"
                        else -> "准备就绪 · ${data.activityType.label}"
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
                        RecorderState.IDLE -> Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { showActivitySelector = true },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                ) {
                                    Icon(Icons.Default.PlayArrow, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("开始记录", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                // 沿已保存的轨迹行进（导航）
                                OutlinedButton(
                                    onClick = { openNavigationPicker() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.Navigation, null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("轨迹导航", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            // 加载参考轨迹：本地 / 网络 / 已下载 三栏勾选叠加到地图
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showTrackLoadSheet = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.Route, null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (loadedTracks.isEmpty()) "加载轨迹（本地 / 网络 / 已下载）"
                                    else "已加载 ${loadedTracks.size} 条轨迹 · 继续添加",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        RecorderState.RECORDING -> Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RecordQuickActionButton(
                                    label = "文字",
                                    icon = Icons.Default.Image,
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
                                    label = "备注",
                                    icon = Icons.Default.NoteAdd,
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
        }

        // ---- 后台保活引导：建议加入电池优化白名单（长距离记录防杀后台） ----
        if (showKeepAliveDialog) {
            AlertDialog(
                onDismissRequest = { showKeepAliveDialog = false },
                title = { Text("建议开启后台保活") },
                text = {
                    Text(
                        "长时间息屏记录（尤其徒步一整天）时，系统省电策略可能冻结或杀掉" +
                                "本应用，导致轨迹中断。\n\n" +
                                "建议在接下来的系统弹窗中选择「允许」（不受限制）。\n" +
                                "小米/华为等机型还建议在 设置→应用管理 中开启「自启动」权限。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showKeepAliveDialog = false
                        TrackRecordingService.requestIgnoreBatteryOptimizations(context)
                    }) { Text("去设置") }
                },
                dismissButton = {
                    TextButton(onClick = { showKeepAliveDialog = false }) { Text("暂不") }
                }
            )
        }

        // ---- 轨迹导航：选择要沿哪条已保存轨迹行进 ----
        if (showNavTrackPicker) {
            NavigationTrackPickerDialog(
                tracks = navTracks,
                onDismiss = { showNavTrackPicker = false },
                onPick = { id ->
                    showNavTrackPicker = false
                    onNavigate(id)
                }
            )
        }

        // ---- 加载轨迹：本地 / 网络 / 已下载 ----
        if (showTrackLoadSheet) {
            com.example.myfirstapp.ui.components.TrackLoadSheet(
                loadedIds = loadedTracks.map { it.id }.toSet(),
                focusId = focusTrackId,
                localVersion = localVersion,
                onDismiss = { showTrackLoadSheet = false },
                onToggle = { track ->
                    toggleLoaded(track)
                    // 记录中加载完自动收起面板，让出地图
                    if (data.state != RecorderState.IDLE) showTrackLoadSheet = false
                },
                onFocus = { track -> focusTrack(track) },
                onNavigate = { id ->
                    showTrackLoadSheet = false
                    if (data.state == RecorderState.IDLE) onNavigate(id)
                    else Toast.makeText(context, "请先结束当前记录，再进行轨迹导航", Toast.LENGTH_SHORT).show()
                },
                onImportFile = { importLauncher.launch(arrayOf("*/*")) },
                onDelete = { track -> deleteTrack(track) }
            )
        }

        if (showActivitySelector) {
            AlertDialog(
                onDismissRequest = { showActivitySelector = false },
                title = { Text("选择运动方式") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.example.myfirstapp.track.ActivityType.values().forEach { type ->
                            TextButton(
                                onClick = {
                                    showActivitySelector = false
                                    startTrackingWithSelectedMode(type)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(type.label, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showActivitySelector = false }) { Text("取消") }
                }
            )
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

/**
 * 导航轨迹选择器：列出轨迹库里可导航的轨迹（≥2 个点），点一条即进入导航页。
 * 列表可能很长，故限制高度并可滚动。
 */
@Composable
private fun NavigationTrackPickerDialog(
    tracks: List<com.example.myfirstapp.track.Track>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val dateFmt = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择导航轨迹") },
        text = {
            if (tracks.isEmpty()) {
                Text(
                    "暂无可导航的轨迹（轨迹至少需要 2 个定位点）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    tracks.forEach { t ->
                        Surface(
                            onClick = { onPick(t.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(t.name, fontWeight = FontWeight.Bold, maxLines = 1)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${GeoUtils.formatDistance(t.distanceMeters)} · " +
                                            "${dateFmt.format(java.util.Date(t.startTime))} · " +
                                            "${t.points.size} 个点",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
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

/**
 * 地图：跟随模式蓝点 + 实时轨迹线 + 图层切换。
 *
 * 这一屏跟设备的哪个 MapView 打交道由 [MapSurface] 决定（当前图源选了哪家就用
 * 哪家的原生 SDK），本函数只认识 [MapSurfaceState.engine] 这个抽象，不再直接
 * import 任何一家地图 SDK。
 */
@Composable
private fun TrackingMapView(
    data: com.example.myfirstapp.track.RecordingData,
    bottomPanelCollapsed: Boolean,
    overlayTracks: List<Track>,
    focusTrackId: String?,
    onOpenNavigation: () -> Unit,
    onOpenTrackLoad: () -> Unit,
    onFocus: (Track) -> Unit,
    onRemove: (Track) -> Unit,
    onClearOverlay: () -> Unit,
    onExitFocus: () -> Unit
) {
    val context = LocalContext.current
    val mapState = rememberMapSurfaceState("record")

    // ---- 跟随状态：点定位按钮开启，用户手动拖动地图自动退出 ----
    val followMode = remember { mutableStateOf(false) }
    // 最新定位：记录中来自 TrackRecorder 的过滤定位流；非记录中来自地图 SDK
    // 的蓝点回调（百度没有该回调，退化成用轨迹末点）
    val latestPoint = remember { mutableStateOf<GeoPoint?>(null) }
    val hasAutoCentered = remember { mutableStateOf(false) }
    val lastGestureAt = remember { mutableStateOf(0L) }

    // 自绘"行进方向箭头"位图：尖头朝正上（= 正北 0°），显示时按 GPS bearing
    // 顺时针旋转（MapEngine.addMarker 的 rotateDeg）。向量图作 Marker 图标在
    // 部分机型不显示，因此一律用 Canvas 画 Bitmap。
    val arrowBitmap: android.graphics.Bitmap = remember {
        val density = context.resources.displayMetrics.density
        val size = (34 * density).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bmp)
        // 箭头形状：上尖下宽的导航箭头（底部中间内凹，更像"指向"而非三角警示牌）
        val path = android.graphics.Path().apply {
            moveTo(size * 0.50f, size * 0.06f)   // 尖端（正北）
            lineTo(size * 0.86f, size * 0.88f)   // 右下
            lineTo(size * 0.50f, size * 0.66f)   // 底部内凹点
            lineTo(size * 0.14f, size * 0.88f)   // 左下
            close()
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // 先画一圈白边（底层描边），再填蓝色主体，保证在任何底图上都有轮廓
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = 0xFF1E88E5.toInt()
        canvas.drawPath(path, paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.strokeJoin = android.graphics.Paint.Join.ROUND
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(path, paint)
        bmp
    }

    // 单独查看时只画那一条，其余临时隐藏
    val visibleOverlays =
        focusTrackId?.let { id -> overlayTracks.filter { it.id == id } } ?: overlayTracks

    // ---- 运动页：进入自动叠加等高线图层（仅本页；退出还原全局叠加层）----
    val contourApplied = remember { mutableStateOf(false) }
    val prevOverlayId = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mapState.engine) {
        val engine = mapState.engine ?: return@LaunchedEffect
        if (contourApplied.value) return@LaunchedEffect
        MapSourceStore.ensureLoaded(context)
        prevOverlayId.value = MapSourceStore.activeOverlayId
        if (MapSourceStore.autoContourEnabled) {
            val cs = MapSourceStore.contourSource()
            // 仅在本页尚未是等高线时切换，避免无意义刷新；退还会还原到进入前的叠加层
            if (prevOverlayId.value != cs.id) {
                MapSourceStore.selectOverlay(cs.id)
                Toast.makeText(context, "已自动叠加等高线：${cs.name}", Toast.LENGTH_SHORT).show()
            }
        }
        contourApplied.value = true
    }
    DisposableEffect(Unit) {
        onDispose {
            if (contourApplied.value) {
                MapSourceStore.selectOverlay(prevOverlayId.value)
                contourApplied.value = false
            }
        }
    }

    // 右侧「已加载轨迹」侧栏展开状态 + 逐条改色的取色器
    var sideTabExpanded by remember { mutableStateOf(false) }
    var colorPicking by remember { mutableStateOf<Track?>(null) }

    /** 整体重画：先清掉本抽象层画过的东西（不含底图瓦片层），再按最新数据画一遍 */
    fun redraw(engine: MapEngine) {
        engine.clearOverlays()
        // 先画叠加的参考轨迹（加载的本地/网络/下载轨迹），再画实时记录线，
        // 保证当前正在走的这条压在最上面
        visibleOverlays.forEach { t ->
            if (t.points.size < 2) return@forEach
            engine.addPolyline(
                t.points.map { GeoPoint(it.latitude, it.longitude) },
                widthPx = 10f,
                // 每条叠加轨迹可单独改色（存在 TrackColorStore，按 id 查），
                // 没改过的一律用默认橙红
                colorArgb = TrackColorStore.overlayColorOf(t.id)
            )
            // 起点打一个默认图标，方便判断该从哪头上路
            val first = t.points.first()
            engine.addMarker(GeoPoint(first.latitude, first.longitude), title = "${t.name} · 起点")
        }
        if (data.points.size >= 2) {
            engine.addPolyline(
                data.points.map { GeoPoint(it.latitude, it.longitude) },
                widthPx = 12f,
                colorArgb = TrackColorStore.liveColor.value
            )
        }
        val lat = data.lastLatitude
        val lng = data.lastLongitude
        if (data.state == RecorderState.RECORDING && lat != null && lng != null) {
            val p = GeoPoint(lat, lng)
            latestPoint.value = p
            if (data.lastAccuracy > 0f) {
                engine.addCircle(
                    p, data.lastAccuracy.toDouble(),
                    fillColor = 0x141E88E5, strokeColor = 0x661E88E5, strokeWidthPx = 2f
                )
            }
            // 箭头方向 = GPS 航向角（bearing：正北 0°，顺时针）
            engine.addMarker(p, bitmap = arrowBitmap, zIndex = 10f, rotateDeg = data.lastBearing)
        }
    }

    MapSurface(
        state = mapState,
        pageKey = "record",
        modifier = Modifier.fillMaxSize(),
        uiSettings = MapUiSettings(),   // 不用 SDK 自带控件（指北针用自绘 PhoneCompass，缩放/定位按钮会被底部面板遮挡）
        onUserGesture = {
            followMode.value = false
            lastGestureAt.value = System.currentTimeMillis()
        },
        onLocationChange = { point ->
            latestPoint.value = point
            if (!hasAutoCentered.value) {
                hasAutoCentered.value = true
                mapState.engine?.moveCamera(point, 17f)
            }
        },
        overlay = {
            // ---- 物理指北针：左上角，跟随手机转动，始终指向真实北方 ----
            com.example.myfirstapp.ui.components.PhoneCompass(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 16.dp)
            )

            // ---- 自绘"回到我的位置"按钮：屏幕右侧垂直居中 ----
            SmallFloatingActionButton(
                onClick = {
                    val target = latestPoint.value
                        ?: data.points.lastOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
                    if (target != null) {
                        followMode.value = true
                        mapState.engine?.animateCamera(target)
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

            // ---- 语音播报开关：左上角、指北针下方。每 1 公里播报 均速/爬升/耗时 ----
            var voiceEnabled by remember { mutableStateOf(com.example.myfirstapp.track.VoiceAnnouncer.isEnabled(context)) }
            SmallFloatingActionButton(
                onClick = {
                    voiceEnabled = !voiceEnabled
                    com.example.myfirstapp.track.VoiceAnnouncer.setEnabled(context, voiceEnabled)
                    Toast.makeText(
                        context,
                        if (voiceEnabled) "语音播报已开启（每 1 公里）" else "语音播报已关闭",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 88.dp), // 指北针（top16+56）下方留 16dp 间距
                containerColor = Color.White
            ) {
                Icon(
                    if (voiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    contentDescription = "语音播报开关",
                    tint = if (voiceEnabled) Color(0xFF2E7D32) else Color.Gray
                )
            }

            // ---- 轨迹导航入口：左侧一列第三个（指北针 → 语音 → 导航）----
            SmallFloatingActionButton(
                onClick = onOpenNavigation,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 136.dp), // 语音按钮（top88+40）下方留 8dp
                containerColor = Color.White
            ) {
                Icon(
                    Icons.Default.Navigation,
                    contentDescription = "轨迹导航",
                    tint = Color(0xFF2E7D32)
                )
            }

            // ---- 加载轨迹入口：左侧一列第四个（本地 / 网络 / 已下载）----
            SmallFloatingActionButton(
                onClick = onOpenTrackLoad,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 184.dp), // 导航按钮（top136+40）下方留 8dp
                containerColor = if (overlayTracks.isEmpty()) Color.White else Color(0xFFFF6D00)
            ) {
                Icon(
                    Icons.Default.Route,
                    contentDescription = "加载轨迹",
                    tint = if (overlayTracks.isEmpty()) Color(0xFF2E7D32) else Color.White
                )
            }

            // ---- 顶部状态条：单独查看中 → 显示该条 + 退出；否则显示已加载条数 + 清除 ----
            val focusTrack = overlayTracks.firstOrNull { it.id == focusTrackId }
            if (focusTrack != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = Color(TrackColorStore.overlayColorOf(focusTrack.id)),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "只看：${focusTrack.name}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp)
                        )
                        IconButton(onClick = onExitFocus, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "退出单独查看",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            } else if (overlayTracks.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xFFFF6D00),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "已加载 ${overlayTracks.size} 条参考轨迹",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge
                        )
                        IconButton(onClick = onClearOverlay, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "清除叠加轨迹",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // ---- 当前图源署名：贴在 SDK 自带 logo 右侧 ----
            // 底部面板折叠时上移，避免被折叠抓手遮挡
            com.example.myfirstapp.ui.components.MapAttribution(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 84.dp, bottom = if (bottomPanelCollapsed) 72.dp else 4.dp)
            )

            // ---- 图层切换：固定在右上角（数据条已移至底部，无需让位）----
            com.example.myfirstapp.ui.components.MapLayerSwitcher(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 12.dp),
                asSheet = true
            )

            // ---- 已加载轨迹侧栏：贴右边，折叠时是一个竖标签，点开是列表 ----
            if (overlayTracks.isNotEmpty()) {
                LoadedTracksSideTab(
                    tracks = overlayTracks,
                    focusTrackId = focusTrackId,
                    expanded = sideTabExpanded,
                    onToggleExpand = { sideTabExpanded = !sideTabExpanded },
                    onPickColor = { colorPicking = it },
                    onFocus = onFocus,
                    onRemove = onRemove,
                    onAdd = onOpenTrackLoad,
                    onClear = onClearOverlay,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(bottom = 48.dp)   // 上移一点，避开底部数据面板
                )
            }
        }
    )

    // ---- 蓝点来源切换：记录中=自绘（GPS 优先的过滤定位），其余=SDK 蓝点做位置预览 ----
    LaunchedEffect(mapState.engine, data.state) {
        val engine = mapState.engine ?: return@LaunchedEffect
        if (data.state == RecorderState.RECORDING) {
            // 关掉 SDK 蓝点：其内置定位客户端不可配置，GPS 弱时偏 20~50 米
            engine.setMyLocationEnabled(false)
        } else {
            engine.setMyLocationEnabled(true, follow = false)
        }
        redraw(engine)
    }

    // ---- 记录中：每次显示定位更新 → 重画蓝点 + 跟随镜头 / 首次自动回中 ----
    LaunchedEffect(mapState.engine, data.lastFixTime) {
        val engine = mapState.engine ?: return@LaunchedEffect
        if (data.state != RecorderState.RECORDING) return@LaunchedEffect
        redraw(engine)
        val target = latestPoint.value ?: return@LaunchedEffect
        if (followMode.value) {
            engine.animateCamera(target)
        } else if (!hasAutoCentered.value) {
            hasAutoCentered.value = true
            engine.moveCamera(target, 17f)
        }
    }

    // ---- 轨迹点变化 / 叠加轨迹变化 / 叠加线改色 → 重画（镜头只在「新增叠加轨迹」时调整）----
    val overlayColors by TrackColorStore.overlayColors.collectAsState()
    var lastOverlayCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(mapState.engine, data.points.size, visibleOverlays, overlayColors) {
        val engine = mapState.engine ?: return@LaunchedEffect
        redraw(engine)
        // 新叠加一条 → 把镜头框到这条轨迹；撤掉时不动画镜头，避免视野乱跳。
        // 判据用 overlayTracks.size（已加载总数），这样进出"单独查看"不会误触发镜头动画
        if (overlayTracks.size > lastOverlayCount) {
            visibleOverlays.lastOrNull()?.takeIf { it.points.size >= 2 }?.let { t ->
                engine.fitBounds(t.points.map { GeoPoint(it.latitude, it.longitude) }, 64, animate = true)
            }
        }
        lastOverlayCount = overlayTracks.size
    }

    // ---- 单独查看：镜头框到这一条（切换/退出时都会触发一次）----
    LaunchedEffect(mapState.engine, focusTrackId) {
        val engine = mapState.engine ?: return@LaunchedEffect
        val t = visibleOverlays.firstOrNull { it.id == focusTrackId } ?: return@LaunchedEffect
        if (t.points.size >= 2) {
            engine.fitBounds(t.points.map { GeoPoint(it.latitude, it.longitude) }, 64, animate = true)
        }
    }

    // ---- 轨迹颜色自定义变化 → 立即重画轨迹线 ----
    val trackColor by TrackColorStore.liveColor.collectAsState()
    LaunchedEffect(mapState.engine, trackColor) {
        val engine = mapState.engine ?: return@LaunchedEffect
        TrackColorStore.ensureLoaded(context)
        redraw(engine)
    }

    // ---- 60 秒无操作 → 自动回到当前定位点（并开启跟随）----
    LaunchedEffect(mapState.engine) {
        val engine = mapState.engine ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(5_000) // 每 5 秒检查一次
            val last = lastGestureAt.value
            // 从未操作过 / 已在跟随中 / 无定位 → 跳过
            if (last == 0L || followMode.value) continue
            if (System.currentTimeMillis() - last >= 30_000) {
                val target = latestPoint.value
                    ?: data.points.lastOrNull()?.let { GeoPoint(it.latitude, it.longitude) }
                if (target != null) {
                    followMode.value = true
                    engine.animateCamera(target)
                }
            }
        }
    }

    // ---- 侧栏里点色点 → 改这条轨迹自己的线色 ----
    colorPicking?.let { t ->
        com.example.myfirstapp.ui.components.ColorPickerDialog(
            title = "轨迹颜色：${t.name}",
            initialColor = TrackColorStore.overlayColorOf(t.id),
            defaultColor = TrackColorStore.DEFAULT_OVERLAY,
            onConfirm = { argb ->
                TrackColorStore.setOverlayColor(context, t.id, argb)
                colorPicking = null
            },
            onDismiss = { colorPicking = null }
        )
    }
}

/**
 * 已加载轨迹侧栏（贴在地图右边）：
 * - 折叠态：一条竖排小标签，显示已加载条数，点一下展开；
 * - 展开态：右侧卡片列出每条已加载轨迹，可单独查看 / 改线色 / 移除，
 *   底部给「添加」和「清除全部」。
 *
 * 只在有叠加轨迹时出现，避免空标签占地方。
 */
@Composable
private fun LoadedTracksSideTab(
    tracks: List<Track>,
    focusTrackId: String?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onPickColor: (Track) -> Unit,
    onFocus: (Track) -> Unit,
    onRemove: (Track) -> Unit,
    onAdd: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!expanded) {
        // ---- 折叠：竖排标签（每字一行）----
        Surface(
            onClick = onToggleExpand,
            modifier = modifier.width(30.dp),
            shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ("已加载 ${tracks.size} 条").forEach { ch ->
                    Text(
                        ch.toString(),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        return
    }

    // ---- 展开：右侧列表卡片 ----
    Surface(
        modifier = modifier
            .width(236.dp)
            .heightIn(max = 360.dp),
        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "已加载 ${tracks.size} 条",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "收起",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(tracks, key = { it.id }) { t ->
                    val focused = focusTrackId == t.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 线色小圆点：点一下改这条的颜色
                        Surface(
                            onClick = { onPickColor(t) },
                            shape = CircleShape,
                            color = Color(TrackColorStore.overlayColorOf(t.id)),
                            modifier = Modifier
                                .size(22.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        ) {}
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
                                color = if (focused) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                GeoUtils.formatDistance(t.distanceMeters),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 单独查看（再点一次取消）
                        IconButton(onClick = { onFocus(t) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                if (focused) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (focused) "取消单独查看" else "单独查看",
                                tint = if (focused) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        // 从地图上撤掉这一条（不删本地文件）
                        IconButton(onClick = { onRemove(t) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "移除这条",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onAdd, modifier = Modifier.weight(1f)) {
                    Text("添加", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                    Text("清除全部", style = MaterialTheme.typography.labelLarge)
                }
            }
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
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        colors = if (highlight)
            ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.error
            )
        else ButtonDefaults.outlinedButtonColors()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(icon, contentDescription = label, Modifier.size(20.dp))
            Spacer(Modifier.height(3.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    }
}
