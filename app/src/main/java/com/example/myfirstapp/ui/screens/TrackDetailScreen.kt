package com.example.myfirstapp.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import java.io.File
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
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.myfirstapp.map.AMapEngine
import com.example.myfirstapp.map.GeoPoint
import com.example.myfirstapp.map.MapEngine
import com.example.myfirstapp.map.MapSurface
import com.example.myfirstapp.map.MapSurfaceState
import com.example.myfirstapp.map.MapUiSettings
import com.example.myfirstapp.map.rememberMapSurfaceState
import com.example.myfirstapp.mapsources.MapSourceStore
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.Track
import com.example.myfirstapp.track.TrackFileFormat
import com.example.myfirstapp.track.TrackRepository
import com.example.myfirstapp.track.TrackVideoExporter
import com.example.myfirstapp.ui.components.TrackExportDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 轨迹详情页（回放）：地图绘制完整轨迹 + 途经点，
 * 展示里程/时长/均速/爬升，支持多格式导出（GPX / KML 轨迹 / KML 路径 / KMZ）、删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(trackId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { TrackRepository.get(context) }
    var track by remember { mutableStateOf(repo.load(trackId)) }
    var deleted by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var saveAsFormat by remember { mutableStateOf(TrackFileFormat.GPX) }

    // ---- 3D 运动视频导出状态 ----
    var videoProgress by remember { mutableStateOf<Float?>(null) }   // null=未在生成
    var videoFile by remember { mutableStateOf<File?>(null) }        // 生成完成待分享
    var videoJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // 地图实例提升到页面级：导出视频时需要直接驱动同一地图实例。
    // 具体是哪家厂商的地图由当前图源决定（见 map 包），这里只持有抽象句柄。
    val mapState = rememberMapSurfaceState("trackDetail")

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

    // 另存为：系统文件选择器指定保存位置
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            val fmt = saveAsFormat
            runCatching {
                val file = repo.exportTrack(t, fmt)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                Toast.makeText(context, "已保存：${t.name}.${fmt.extension}", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "保存失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
        showExport = false
    }

    // 3D 视频另存为：系统文件选择器
    val saveVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        if (uri != null) {
            val f = videoFile
            if (f != null && f.exists()) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        f.inputStream().use { it.copyTo(out) }
                    }
                    Toast.makeText(context, "视频已保存", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "保存失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        videoFile = null
    }

    /** 启动 3D 运动视频生成：地图页滚到顶 → 实时飞行回放 → 完成后弹分享/另存 */
    fun startVideoExport() {
        val tr = track ?: return
        if (tr.points.size < 2) {
            Toast.makeText(context, "轨迹点太少，无法生成视频", Toast.LENGTH_SHORT).show()
            return
        }
        // 视频导出是从高德地图控件的 TextureView 抓帧 + 驱动相机飞行，
        // 目前只有高德引擎能干这个活；腾讯/百度底图下先回高德再导出。
        val amapView = (mapState.engine as? AMapEngine)?.textureMapView
        if (amapView == null) {
            Toast.makeText(
                context,
                "生成3D视频需要高德底图：请先在右上角图层面板切回「高德矢量/卫星」",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (videoJob?.isActive == true) return
        runCatching {
            (context as? Activity)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        videoJob = scope.launch {
            try {
                videoProgress = 0f
                scrollState.animateScrollTo(0)
                val safe = tr.name.replace(Regex("[\\\\/:*?\"<>| ]"), "_")
                val outDir = File(context.cacheDir, "track_video").apply { mkdirs() }
                val outFile = File(outDir, "${safe}_3D.mp4")
                runCatching {
                    TrackVideoExporter(amapView, tr).export(
                        outFile,
                        onProgress = { videoProgress = it },
                        restore = {
                            mapState.engine?.let { drawTrackOnMap(it, tr, fitBounds = true) }
                        }
                    )
                }.onSuccess { videoFile = it }
                    .onFailure {
                        // 取消要向上传播，让协程正常结束
                        if (it is CancellationException) throw it
                        Toast.makeText(context, "视频生成失败：${it.message}", Toast.LENGTH_LONG).show()
                    }
            } finally {
                videoProgress = null
                runCatching {
                    (context as? Activity)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    // 导出弹窗：选格式 + 分享/另存
    if (showExport) {
        TrackExportDialog(
            trackName = t.name,
            onDismiss = { showExport = false },
            onShare = { format ->
                showExport = false
                runCatching {
                    val file = repo.exportTrack(t, format)
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = format.mimeType
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            "分享轨迹（${format.label}）"
                        )
                    )
                }
            },
            onSaveAs = { format ->
                saveAsFormat = format
                saveAsLauncher.launch("${t.name}.${format.extension}")
            }
        )
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
                    // 3D 运动视频：倾斜视角沿轨迹飞行 + 实时数据字幕，导出 MP4
                    IconButton(
                        onClick = { startVideoExport() },
                        enabled = videoProgress == null
                    ) {
                        Icon(Icons.Default.Movie, "生成3D运动视频")
                    }
                    // 导出（GPX / KML 轨迹 / KML 路径 / KMZ）
                    IconButton(onClick = { showExport = true }) {
                        Icon(Icons.Default.IosShare, "导出轨迹")
                    }
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
                .verticalScroll(scrollState)
        ) {
            // ---- 地图：完整轨迹 + 途经点 ----
            Box(Modifier.fillMaxWidth().height(340.dp)) {
                TrackPlaybackMapView(t, mapState)
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

    // ---- 3D 运动视频：生成进度弹窗（实时回放中，请勿切走页面）----
    videoProgress?.let { p ->
        AlertDialog(
            onDismissRequest = {},   // 生成中不可误触关闭
            title = { Text("生成 3D 运动视频") },
            text = {
                Column {
                    Text("正在以 3D 视角沿轨迹飞行录制，请保持本页面在前台…")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(p * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { videoJob?.cancel() }) { Text("取消") }
            }
        )
    }

    // ---- 3D 运动视频：生成完成，分享/另存 ----
    videoFile?.let { f ->
        AlertDialog(
            onDismissRequest = { videoFile = null },
            title = { Text("视频已生成") },
            text = {
                Text("「${t.name}_3D.mp4」（%.1f MB）已就绪，可分享或保存到相册/指定位置。"
                    .format(f.length() / 1048576.0))
            },
            confirmButton = {
                TextButton(onClick = {
                    val vf = videoFile
                    if (vf != null && vf.exists()) {
                        runCatching {
                            val uri = FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", vf
                            )
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    "分享 3D 运动视频"
                                )
                            )
                        }
                    }
                    videoFile = null
                }) { Text("分享") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        saveVideoLauncher.launch("${t.name}_3D.mp4")
                    }) { Text("另存到…") }
                    TextButton(onClick = { videoFile = null }) { Text("关闭") }
                }
            }
        )
    }
}

/** 在地图上绘制完整轨迹（轨迹线 + 起终点 + 途经点标记），fitBounds=true 时镜头框住全程 */
private fun drawTrackOnMap(engine: MapEngine, t: Track, fitBounds: Boolean) {
    runCatching {
        engine.clearOverlays()   // 只清抽象层画过的东西，底图瓦片层不动
        val points = t.points.map { GeoPoint(it.latitude, it.longitude) }
        if (points.size >= 2) {
            engine.addPolyline(points, widthPx = 12f, colorArgb = 0xFF2E7D32.toInt())
            engine.addMarker(points.first(), title = "起点")
            engine.addMarker(points.last(), title = "终点")
        }
        t.waypoints.forEach { w ->
            engine.addMarker(GeoPoint(w.latitude, w.longitude), title = w.name)
        }
        if (fitBounds && points.isNotEmpty()) {
            engine.fitBounds(points, paddingPx = 64)
        }
    }
}

/** 只读地图：画整条轨迹（含起终点、途经点），镜头框住全程。
 *  地图实例 [MapSurface] 负责（池化复用，进出详情页不销毁，各 SDK 都扛不住频繁销毁重建） */
@Composable
private fun TrackPlaybackMapView(
    t: Track,
    mapState: MapSurfaceState
) {
    val context = LocalContext.current

    MapSurface(
        state = mapState,
        pageKey = "trackDetail",
        modifier = Modifier.fillMaxSize(),
        uiSettings = MapUiSettings(),
        overlay = {
            // 图层切换：地图右上角
            com.example.myfirstapp.ui.components.MapLayerSwitcher(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 10.dp)
            )

            // 当前图源署名（左下角，紧贴 SDK 自带 logo 右侧）
            com.example.myfirstapp.ui.components.MapAttribution(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 84.dp, bottom = 6.dp)
            )
        }
    )

    // ---- 轨迹 / 图源变化 → 重绘 ----
    LaunchedEffect(mapState.engine, t.id) {
        val engine = mapState.engine ?: return@LaunchedEffect
        MapSourceStore.ensureLoaded(context)
        drawTrackOnMap(engine, t, fitBounds = true)
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
