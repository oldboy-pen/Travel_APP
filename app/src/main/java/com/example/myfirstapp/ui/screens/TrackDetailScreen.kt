package com.example.myfirstapp.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.Track
import com.example.myfirstapp.track.TrackRepository

/**
 * 轨迹详情页（回放）：地图绘制完整轨迹 + 途经点，
 * 展示里程/时长/均速/爬升，支持 GPX 分享导出、删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(trackId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { TrackRepository.get(context) }
    var track by remember { mutableStateOf(repo.load(trackId)) }
    var deleted by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (deleted) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val t = track
    if (t == null) {
        // 轨迹不存在（已删除）
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // 分享 GPX
                    IconButton(onClick = {
                        val file = repo.exportGpx(t)
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file
                        )
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "application/gpx+xml"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                "分享轨迹 GPX"
                            )
                        )
                    }) { Icon(Icons.Default.IosShare, "分享GPX") }
                    // 删除
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, "删除",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ---- 地图：完整轨迹 + 途经点 ----
            Box(Modifier.fillMaxWidth().height(340.dp)) {
                TrackPlaybackMapView(t)
            }

            // ---- 数据统计 ----
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DetailStat(GeoUtils.formatDistance(t.distanceMeters), "总里程")
                DetailStat(GeoUtils.formatDuration(t.durationMillis), "总时长")
                DetailStat(
                    "%.1f km/h".format(
                        if (t.durationMillis > 0)
                            t.distanceMeters / (t.durationMillis / 1000.0) * 3.6 else 0.0
                    ), "平均速度"
                )
                DetailStat("%.0f 米".format(t.climbMeters), "累计爬升")
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))

            // ---- 途经点列表 ----
            if (t.waypoints.isNotEmpty()) {
                Text(
                    "途经点（${t.waypoints.size}）",
                    Modifier.padding(16.dp),
                    fontWeight = FontWeight.Bold
                )
                t.waypoints.forEach { w ->
                    WaypointDetailCard(w = w, context = context, trackStart = t.points.firstOrNull())
                }
            } else {
                Text(
                    "本条轨迹没有打途经点",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ---- 删除确认 ----
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除轨迹") },
            text = { Text("删除「${t.name}」后无法恢复，确定吗？") },
            confirmButton = {
                TextButton(onClick = {
                    repo.delete(t.id)
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                    deleted = true
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}

/** 只读地图：画整条轨迹（起绿终红）+ 途经点标记，镜头框住全程 */
@Composable
private fun TrackPlaybackMapView(t: Track) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val aMap = remember { mapView.map }

    // ---- 图层切换状态 ----
    var layerMode by remember { mutableStateOf(com.example.myfirstapp.ui.components.MapLayerMode.NORMAL) }
    val terrainOverlay = remember { mutableStateOf<com.amap.api.maps.model.TileOverlay?>(null) }

    DisposableEffect(Unit) {
        mapView.onCreate(null)
        aMap.uiSettings.isZoomControlsEnabled = false
        onDispose {
            terrainOverlay.value?.remove()
            mapView.onDestroy()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // 图层切换：地图右上角
        com.example.myfirstapp.ui.components.MapLayerSwitcher(
            current = layerMode,
            onSelect = { mode ->
                layerMode = mode
                com.example.myfirstapp.ui.components.applyMapLayer(aMap, mode, terrainOverlay)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp)
        )
    }

    LaunchedEffect(t.id) {
        val latLngs = t.points.map { LatLng(it.latitude, it.longitude) }
        if (latLngs.size >= 2) {
            aMap.addPolyline(
                PolylineOptions().addAll(latLngs).width(12f).color(0xFF2E7D32.toInt())
            )
            // 起点/终点 Marker
            aMap.addMarker(MarkerOptions().position(latLngs.first()).title("起点"))
            aMap.addMarker(MarkerOptions().position(latLngs.last()).title("终点"))
        }
        t.waypoints.forEach { w ->
            aMap.addMarker(MarkerOptions().position(LatLng(w.latitude, w.longitude)).title(w.name))
        }
        if (latLngs.isNotEmpty()) {
            val bounds = LatLngBounds.builder().apply { latLngs.forEach { include(it) } }.build()
            aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 64))
        }
    }
}

@Composable
private fun DetailStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WaypointDetailCard(
    w: com.example.myfirstapp.track.Waypoint,
    context: Context,
    trackStart: com.example.myfirstapp.track.TrackPoint? = null
) {
    val typeIcon = when (w.type) {
        com.example.myfirstapp.track.WaypointType.TEXT -> Icons.Default.TextFields
        com.example.myfirstapp.track.WaypointType.PHOTO -> Icons.Default.CameraAlt
        com.example.myfirstapp.track.WaypointType.VIDEO -> Icons.Default.Videocam
        com.example.myfirstapp.track.WaypointType.VOICE -> Icons.Default.Mic
    }

    // 距起点直线距离（无起点则不显示）
    val distFromStart = trackStart?.let {
        GeoUtils.distance(it.latitude, it.longitude, w.latitude, w.longitude)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(typeIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(w.name, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(8.dp))
            // 精简信息：坐标 + 距起点距离（其余类型/时间等不再展示）
            Row {
                Text(
                    "坐标：%.5f, %.5f".format(w.latitude, w.longitude),
                    style = MaterialTheme.typography.bodySmall
                )
                if (distFromStart != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "距起点 ${GeoUtils.formatDistance(distFromStart)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (!w.text.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = w.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!w.mediaUri.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                when (w.type) {
                    com.example.myfirstapp.track.WaypointType.PHOTO -> {
                        val uri = Uri.parse(w.mediaUri)
                        val stream = try {
                            context.contentResolver.openInputStream(uri)
                        } catch (_: Exception) { null }
                        val bitmap = stream?.use { BitmapFactory.decodeStream(it) }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = w.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                        }
                    }                    com.example.myfirstapp.track.WaypointType.VIDEO -> {
                        val uri = Uri.parse(w.mediaUri)
                        InAppVideoPlayer(uri)
                    }
                    com.example.myfirstapp.track.WaypointType.VOICE -> {
                        val uri = Uri.parse(w.mediaUri)
                        InAppVoicePlayer(uri)
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** ============ 应用内媒体播放组件 ============ */

/**
 * 应用内视频播放器：VideoView 包装成 Compose。
 * 自带 MediaController（进度条、播放/暂停），点屏幕呼出。
 */
@Composable
private fun InAppVideoPlayer(uri: Uri) {
    val context = LocalContext.current
    var videoHeight by remember { mutableStateOf(220.dp) }

    Column(modifier = Modifier.fillMaxWidth()) {
        AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    setVideoURI(uri)
                    setMediaController(android.widget.MediaController(ctx))
                    setOnPreparedListener { mp ->
                        // 按视频宽高比调整显示高度
                        val w = mp.videoWidth
                        val h = mp.videoHeight
                        if (w > 0 && h > 0) {
                            videoHeight = (220.dp * h / w).coerceIn(140.dp, 360.dp)
                        }
                    }
                    setOnErrorListener { _, what, extra ->
                        Toast.makeText(ctx, "视频播放失败（$what/$extra）", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(videoHeight)
        )
        TextButton(
            onClick = {
                // 备用：用外部播放器打开（兼容个别设备硬解码不支持的格式）
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                }.onFailure {
                    Toast.makeText(context, "没有可用的视频播放器", Toast.LENGTH_SHORT).show()
                }
            }
        ) { Text("用其他应用打开", style = MaterialTheme.typography.labelSmall) }
    }
}

/**
 * 应用内语音播放器：MediaPlayer + 播放/暂停按钮 + 进度条。
 */
@Composable
private fun InAppVoicePlayer(uri: Uri) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }   // 0..1
    var totalMs by remember { mutableStateOf(0) }

    // 初始化播放器
    LaunchedEffect(uri) {
        runCatching {
            android.media.MediaPlayer().apply {
                setDataSource(context, uri)
                setOnPreparedListener {
                    prepared = true
                    totalMs = it.duration
                }
                setOnCompletionListener {
                    playing = false
                    progress = 0f
                    it.seekTo(0)
                }
                prepareAsync()
            }
        }.onSuccess { player = it }
            .onFailure { Toast.makeText(context, "语音加载失败：${it.message}", Toast.LENGTH_SHORT).show() }
    }

    // 播放中轮询进度
    LaunchedEffect(playing) {
        while (playing) {
            player?.let { p ->
                if (p.duration > 0) progress = p.currentPosition.toFloat() / p.duration
            }
            kotlinx.coroutines.delay(200)
        }
    }

    // 离开时释放
    DisposableEffect(uri) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }

    if (prepared) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = {
                        player?.let { p ->
                            if (p.isPlaying) {
                                p.pause(); playing = false
                            } else {
                                p.start(); playing = true
                            }
                        }
                    }
                ) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null, Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (playing) "暂停" else "播放")
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "%02d:%02d".format((totalMs / 1000) / 60, (totalMs / 1000) % 60),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
