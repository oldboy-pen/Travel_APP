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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
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
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.RecorderState
import com.example.myfirstapp.track.StepSensorStatus
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
    var showActivitySelector by remember { mutableStateOf(false) }
    var selectedActivityType by remember { mutableStateOf(com.example.myfirstapp.track.ActivityType.DEFAULT) }
    // 后台保活引导：未加入电池白名单时，开始新记录前提示一次
    var showKeepAliveDialog by remember { mutableStateOf(false) }
    // 拍照 / 相册选择弹窗 + 相机输出 Uri
    var showPhotoSourceDialog by remember { mutableStateOf(false) }
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }

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
        TrackingMapView(data, bottomPanelCollapsed)

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
                        RecorderState.IDLE -> Button(
                            onClick = { showActivitySelector = true },
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
    bottomPanelCollapsed: Boolean
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

    // 自绘蓝点位图：Canvas 画"白边蓝点"（向量图作 Marker 图标在部分机型不显示）
    val blueDotBitmap: android.graphics.Bitmap = remember {
        val density = context.resources.displayMetrics.density
        val size = (30 * density).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = 0xFF1E88E5.toInt()
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3 * density, paint)
        bmp
    }

    /** 整体重画：先清掉本抽象层画过的东西（不含底图瓦片层），再按最新数据画一遍 */
    fun redraw(engine: MapEngine) {
        engine.clearOverlays()
        if (data.points.size >= 2) {
            engine.addPolyline(
                data.points.map { GeoPoint(it.latitude, it.longitude) },
                widthPx = 12f,
                colorArgb = 0xFF2E7D32.toInt()
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
            engine.addMarker(p, bitmap = blueDotBitmap, zIndex = 10f)
        }
    }

    MapSurface(
        state = mapState,
        pageKey = "record",
        modifier = Modifier.fillMaxSize(),
        uiSettings = MapUiSettings(),   // 不用 SDK 自带的缩放/定位按钮（会被底部面板遮挡）
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

    // ---- 轨迹点变化 → 只重画轨迹线（镜头不再自动移动）----
    LaunchedEffect(mapState.engine, data.points.size) {
        val engine = mapState.engine ?: return@LaunchedEffect
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
