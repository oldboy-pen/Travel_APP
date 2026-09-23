package com.example.myfirstapp.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.Track
import com.example.myfirstapp.track.TrackRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轨迹库页（两步路"我的轨迹"）：
 * - 本地历史轨迹列表，点击查看详情/回放
 * - 支持从文件导入 GPX
 */
@Composable
fun TrackHistoryScreen(onOpenTrack: (String) -> Unit) {
    val context = LocalContext.current
    val repo = remember { TrackRepository.get(context) }
    var tracks by remember { mutableStateOf(repo.list()) }
    var message by remember { mutableStateOf<String?>(null) }

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
                    Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp))
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
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp))
                    Text("  导入 GPX 文件")
                }
                Spacer(Modifier.height(8.dp))
            }
            items(tracks, key = { it.id }) { track ->
                TrackCard(track, onClick = { onOpenTrack(track.id) })
            }
        }
    }
}

@Composable
private fun TrackCard(track: Track, onClick: () -> Unit) {
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
                Text(
                    if (track.waypoints.isNotEmpty()) "${track.waypoints.size}个点" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}
