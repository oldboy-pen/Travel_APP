package com.example.myfirstapp.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.data.user.CloudTrackViewModel
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.Track
import com.example.myfirstapp.track.TrackFileFormat
import com.example.myfirstapp.track.TrackPoint
import com.example.myfirstapp.track.TrackRepository
import com.example.myfirstapp.track.TrackSource
import com.example.myfirstapp.track.TrackSourceResolver
import com.example.myfirstapp.ui.components.TrackExportDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轨迹查看页（「我的 → 足迹 → 轨迹 → 点某条」弹出）
 *
 * 与轨迹详情页（track/{id}，带地图的那页）分工不同：这页先回答"这条轨迹是哪来的"，
 * 用四色徽章区分 本地录制 / 云端已同步 / 下载的他人轨迹 / 外部文件导入，
 * 再给出关键统计与操作（查看详情、导出分享、删除）。要看地图和逐点信息再点「查看详情」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSourceViewScreen(
    trackId: String,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { TrackRepository.get(context) }
    val cloudVm: CloudTrackViewModel = viewModel()
    val syncedIds by cloudVm.syncedIds.collectAsStateWithLifecycle()

    var track by remember { mutableStateOf<Track?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 文件读取放 IO 线程（轨迹点位多，主线程读会卡一帧）
    LaunchedEffect(trackId) {
        loaded = false
        val t = withContext(Dispatchers.IO) { repo.load(trackId) }
        track = t
        loaded = true
    }
    LaunchedEffect(Unit) { cloudVm.refreshSynced() }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            message = null
        }
    }

    val source = remember(track, syncedIds) {
        track?.let { TrackSourceResolver.of(context, it.id, syncedIds) }
    }
    val owner = remember(track) { track?.let { TrackSourceResolver.ownerOf(context, it.id) } }
    val synced = track?.let { syncedIds.contains(it.id) } ?: false

    // 另存为：系统文件选择器
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val t = track
        if (uri != null && t != null) {
            runCatching {
                val file = repo.exportTrack(t, TrackFileFormat.GPX)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                message = "已保存：${t.name}.gpx"
            }.onFailure { message = "保存失败：${it.message}" }
        }
    }

    fun shareTrack(format: TrackFileFormat) {
        val t = track ?: return
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
        }.onFailure { message = "导出失败：${it.message}" }
    }

    if (exporting && track != null) {
        TrackExportDialog(
            trackName = track!!.name,
            onDismiss = { exporting = false },
            onShare = { format ->
                exporting = false
                shareTrack(format)
            },
            onSaveAs = {
                exporting = false
                saveAsLauncher.launch("${track!!.name}.${it.extension}")
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条轨迹？") },
            text = { Text("${track?.name.orEmpty()}\n删除后本机的这条轨迹无法恢复（已同步到服务器的副本不受影响）。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    track?.let { t ->
                        repo.delete(t.id)
                        TrackSourceResolver.clearMarks(context, t.id)
                        message = "已删除：${t.name}"
                    }
                    onBack()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("轨迹查看") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        when {
            !loaded -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            track == null -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("这条轨迹不存在或已被删除", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> {
                val t = track!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SourceBadgeCard(source = source ?: TrackSource.LOCAL, owner = owner, synced = synced)
                    PreviewCard(points = t.points)
                    InfoCard(track = t)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onOpenDetail(t.id) },
                            modifier = Modifier.weight(1f)
                        ) { Text("查看详情") }
                        OutlinedButton(
                            onClick = { exporting = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.IosShare, null, Modifier.size(18.dp))
                            Text("  导出")
                        }
                    }
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Delete, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text("  删除这条轨迹", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** 来源徽章卡：四类来源各有图标与配色，另附作者 / 同步状态 */
@Composable
private fun SourceBadgeCard(source: TrackSource, owner: String?, synced: Boolean) {
    val (icon, container, content) = when (source) {
        TrackSource.LOCAL -> Triple(
            Icons.Default.Route,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        TrackSource.CLOUD -> Triple(
            Icons.Default.CloudDone,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        TrackSource.DOWNLOADED -> Triple(
            Icons.Default.CloudDownload,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        TrackSource.IMPORTED -> Triple(
            Icons.Default.FileOpen,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(content.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = content)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        source.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = content
                    )
                    if (synced) {
                        Spacer(Modifier.width(8.dp))
                        SyncChip()
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        source == TrackSource.DOWNLOADED && !owner.isNullOrBlank() -> "来自 $owner 的分享"
                        else -> source.desc
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun SyncChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            "已同步",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 轨迹形状缩略图：把点位等比投影到画布，抽稀到 ~300 点后画折线（自绘，不依赖地图 SDK） */
@Composable
private fun PreviewCard(points: List<TrackPoint>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val startColor = MaterialTheme.colorScheme.tertiary
    val endColor = MaterialTheme.colorScheme.error

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = bg)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "轨迹形状",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (points.size < 2) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "这条轨迹没有可用的轨迹点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val sampled = remember(points) { samplePoints(points, 300) }
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    val minLat = sampled.minOf { it.latitude }
                    val maxLat = sampled.maxOf { it.latitude }
                    val minLng = sampled.minOf { it.longitude }
                    val maxLng = sampled.maxOf { it.longitude }
                    val pad = 16f
                    val w = size.width - pad * 2
                    val h = size.height - pad * 2
                    val spanLat = (maxLat - minLat).coerceAtLeast(1e-7)
                    val spanLng = (maxLng - minLng).coerceAtLeast(1e-7)
                    val scale = minOf(w / spanLng.toFloat(), h / spanLat.toFloat())
                    val offX = pad + (w - spanLng.toFloat() * scale) / 2f
                    val offY = pad + (h - spanLat.toFloat() * scale) / 2f

                    fun toOffset(p: TrackPoint): Offset = Offset(
                        x = offX + ((p.longitude - minLng).toFloat() * scale),
                        // 纬度越大越靠上，画布 y 轴向下，取反
                        y = offY + ((maxLat - p.latitude).toFloat() * scale)
                    )

                    val path = Path().apply {
                        val first = toOffset(sampled.first())
                        moveTo(first.x, first.y)
                        for (i in 1 until sampled.size) {
                            val o = toOffset(sampled[i])
                            lineTo(o.x, o.y)
                        }
                    }
                    drawPath(path, color = lineColor, style = Stroke(width = 4f))

                    val s = toOffset(sampled.first())
                    val e = toOffset(sampled.last())
                    drawCircle(color = startColor, radius = 6f, center = s)
                    drawCircle(color = endColor, radius = 6f, center = e)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    LegendDot(startColor, "起点")
                    LegendDot(endColor, "终点")
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 关键统计：距离 / 时长 / 爬升 / 均速 / 点数 / 途经点 / 类型 / 起止时间 */
@Composable
private fun InfoCard(track: Track) {
    val avgSpeed = if (track.durationMillis > 0) {
        "%.1f km/h".format(track.distanceMeters / (track.durationMillis / 1000.0) * 3.6)
    } else "—"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                track.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                StatItem("距离", GeoUtils.formatDistance(track.distanceMeters), Modifier.weight(1f))
                StatItem("用时", GeoUtils.formatDuration(track.durationMillis), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                StatItem("累计爬升", "%.0f 米".format(track.climbMeters), Modifier.weight(1f))
                StatItem("平均速度", avgSpeed, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                StatItem("轨迹点", "${track.points.size} 个", Modifier.weight(1f))
                StatItem("途经点", "${track.waypoints.size} 个", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Route, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    track.activityType.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (track.startTime > 0) formatDateTime(track.startTime) else "时间未知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 均匀抽稀，保证缩略图折线形状不变但点数可控 */
private fun samplePoints(points: List<TrackPoint>, max: Int): List<TrackPoint> {
    if (points.size <= max) return points
    val step = points.size.toDouble() / max
    return buildList(max) {
        for (i in 0 until max) add(points[(i * step).toInt().coerceAtMost(points.size - 1)])
        add(points.last())       // 终点必须保留
    }
}

private fun formatDateTime(time: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(time))
