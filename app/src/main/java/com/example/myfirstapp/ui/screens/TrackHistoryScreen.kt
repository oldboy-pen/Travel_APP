package com.example.myfirstapp.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.Track
import com.example.myfirstapp.track.TrackRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 轨迹库页（两步路"我的轨迹"）：
 * - 本地历史轨迹列表，点击查看详情/回放
 * - 支持从文件导入 GPX
 * - 支持导出：单条（分享 / 另存为）+ 全部打包导出
 */
@Composable
fun TrackHistoryScreen(onOpenTrack: (String) -> Unit) {
    val context = LocalContext.current
    val repo = remember { TrackRepository.get(context) }
    var tracks by remember { mutableStateOf(repo.list()) }
    var message by remember { mutableStateOf<String?>(null) }

    // 当前正在导出的轨迹（弹窗用）
    var exportingTrack by remember { mutableStateOf<Track?>(null) }

    /** 分享一个 GPX 文件 */
    fun shareGpx(track: Track) {
        runCatching {
            val file = repo.exportGpx(track)
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
        }.onFailure { message = "导出失败：${it.message}" }
    }

    /** 全部轨迹导出为一个 zip（备份/迁移用） */
    fun exportAllAsZip() {
        runCatching {
            val zipFile = java.io.File(context.cacheDir, "gpx/tracks_backup.zip")
            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                tracks.forEach { t ->
                    val gpx = repo.exportGpx(t)
                    zos.putNextEntry(ZipEntry(gpx.name))
                    gpx.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", zipFile
            )
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "导出全部轨迹（${tracks.size} 条）"
                )
            )
        }.onFailure { message = "导出失败：${it.message}" }
    }

    // 另存为：系统文件选择器让用户指定保存位置
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        val track = exportingTrack
        if (uri != null && track != null) {
            runCatching {
                val gpx = repo.exportGpx(track)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    gpx.inputStream().use { it.copyTo(out) }
                }
                message = "已保存：${track.name}.gpx"
            }.onFailure { message = "保存失败：${it.message}" }
        }
        exportingTrack = null
    }

    // GPX 导入
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val track = repo.importGpx(context, uri)
            message = if (track != null) "导入成功：${track.name}" else "导入失败：文件不是有效的 GPX 轨迹"
            tracks = repo.list()
        }
    }

    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            message = null
        }
    }

    // 单条导出弹窗
    exportingTrack?.let { track ->
        AlertDialog(
            onDismissRequest = { exportingTrack = null },
            title = { Text("导出「${track.name}」") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            exportingTrack = null
                            shareGpx(track)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("分享 GPX 文件")
                    }
                    TextButton(
                        onClick = {
                            saveAsLauncher.launch("${track.name}.gpx")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.SaveAlt, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("另存到指定位置")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { exportingTrack = null }) { Text("取消") }
            }
        )
    }

    if (tracks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Route, null, Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text("还没有轨迹记录\n去「运动」页开始第一条，或导入 GPX",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = {
                    importLauncher.launch(arrayOf("*/*"))
                }) {
                    Icon(Icons.Default.IosShare, null, Modifier.size(18.dp))
                    Text("  导入 GPX")
                }
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.IosShare, null, Modifier.size(18.dp))
                        Text("  导入 GPX")
                    }
                    OutlinedButton(
                        onClick = { exportAllAsZip() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Archive, null, Modifier.size(18.dp))
                        Text("  导出全部")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            items(tracks, key = { it.id }) { track ->
                TrackCard(
                    track,
                    onClick = { onOpenTrack(track.id) },
                    onExport = { exportingTrack = track }
                )
            }
        }
    }
}

@Composable
private fun TrackCard(track: Track, onClick: () -> Unit, onExport: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(track.name) },
            supportingContent = {
                Text(
                    buildString {
                        append(GeoUtils.formatDistance(track.distanceMeters))
                        append(" · ").append(GeoUtils.formatDuration(track.durationMillis))
                        if (track.startTime > 0) {
                            append(" · ").append(
                                SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(track.startTime))
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            },
            leadingContent = {
                Icon(Icons.Default.Route, null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (track.waypoints.isNotEmpty()) {
                        Text(
                            "${track.waypoints.size}个点",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onExport) {
                        Icon(
                            Icons.Default.IosShare,
                            contentDescription = "导出轨迹",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        )
    }
}
