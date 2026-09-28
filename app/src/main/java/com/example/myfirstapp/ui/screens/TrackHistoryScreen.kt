package com.example.myfirstapp.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.data.user.UserAccount
import com.example.myfirstapp.data.user.UserViewModel
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.Track
import com.example.myfirstapp.track.TrackFileFormat
import com.example.myfirstapp.track.TrackRepository
import com.example.myfirstapp.ui.components.TrackExportDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.compose.foundation.background


/**
 * 轨迹库页（两步路"我的轨迹"）：
 * - 本地历史轨迹列表，点击查看详情/回放
 * - 支持从文件导入轨迹（GPX / KML / KMZ，自动识别格式）
 * - 支持导出：单条（GPX / KML 轨迹 / KML 路径 / KMZ，分享或另存）+ 全部打包导出
 * - 顶部账号卡片：未登录显示「登录/注册」入口，已登录显示昵称与退出
 */
@Composable
fun TrackHistoryScreen(
    onOpenTrack: (String) -> Unit,
    onOpenAuth: () -> Unit,
    onOpenCloudAuth: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { TrackRepository.get(context) }
    val userVm: UserViewModel = viewModel()
    val user by userVm.currentUser.collectAsState()
    var tracks by remember { mutableStateOf(repo.list()) }
    var message by remember { mutableStateOf<String?>(null) }

    // 当前正在导出的轨迹（弹窗用）+ 另存时使用的格式
    var exportingTrack by remember { mutableStateOf<Track?>(null) }
    var saveAsFormat by remember { mutableStateOf(TrackFileFormat.GPX) }

    /** 分享一个轨迹文件（格式由导出弹窗选择） */
    fun shareTrack(track: Track, format: TrackFileFormat) {
        runCatching {
            val file = repo.exportTrack(track, format)
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

    // 另存为：系统文件选择器让用户指定保存位置（格式可变，mime 用 */*）
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val track = exportingTrack
        if (uri != null && track != null) {
            val fmt = saveAsFormat
            runCatching {
                val file = repo.exportTrack(track, fmt)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                message = "已保存：${track.name}.${fmt.extension}"
            }.onFailure { message = "保存失败：${it.message}" }
        }
        exportingTrack = null
    }

    // 轨迹导入（GPX / KML / KMZ 自动识别）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val track = repo.importTrack(context, uri)
            message = if (track != null) "导入成功：${track.name}"
            else "导入失败：不是有效的轨迹文件（支持 GPX / KML / KMZ）"
            tracks = repo.list()
        }
    }

    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            message = null
        }
    }

    // 单条导出弹窗（选格式 + 分享/另存）
    exportingTrack?.let { track ->
        TrackExportDialog(
            trackName = track.name,
            onDismiss = { exportingTrack = null },
            onShare = { format ->
                exportingTrack = null
                shareTrack(track, format)
            },
            onSaveAs = { format ->
                saveAsFormat = format
                saveAsLauncher.launch("${track.name}.${format.extension}")
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        UserCard(
            user = user,
            onOpenAuth = onOpenAuth,
            onLogout = {
                userVm.logout()
                message = "已退出登录"
            }
        )

        // 云端账号验证入口（临时，验证完可连同 cloudAuth 路由一起删掉）
        TextButton(
            onClick = onOpenCloudAuth,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("云端账号验证（临时）", style = MaterialTheme.typography.bodySmall)
        }

        if (tracks.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Route, null, Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "还没有轨迹记录\n去「运动」页开始第一条，或导入轨迹",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = {
                        importLauncher.launch(arrayOf("*/*"))
                    }) {
                        Icon(Icons.Default.IosShare, null, Modifier.size(18.dp))
                        Text("  导入轨迹")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
                            Text("  导入轨迹")
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
}

/** 顶部账号卡片：未登录 → 登录/注册入口；已登录 → 昵称 + 退出登录 */
@Composable
private fun UserCard(user: UserAccount?, onOpenAuth: () -> Unit, onLogout: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                if (user == null) {
                    Icon(
                        Icons.Default.PersonOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        user.nickname.take(1),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user?.nickname ?: "未登录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (user == null) "点击登录或注册账号" else "@${user.username} · 注册于 ${formatDate(user.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (user == null) {
                Button(onClick = onOpenAuth, shape = RoundedCornerShape(12.dp)) {
                    Text("登录/注册")
                }
            } else {
                OutlinedButton(onClick = onLogout, shape = RoundedCornerShape(12.dp)) {
                    Text("退出")
                }
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

private fun formatDate(time: Long): String =
    if (time > 0) SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(time)) else "未知"
