package com.example.myfirstapp.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.data.user.CloudTrackViewModel
import com.example.myfirstapp.track.Track
import com.example.myfirstapp.track.TrackFileFormat
import com.example.myfirstapp.track.TrackRepository
import com.example.myfirstapp.track.TrackSource
import com.example.myfirstapp.track.TrackSourceResolver
import com.example.myfirstapp.ui.components.FootprintEmptyHint
import com.example.myfirstapp.ui.components.SourceFilterRow
import com.example.myfirstapp.ui.components.TrackRow
import com.example.myfirstapp.ui.components.TrackExportDialog
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 足迹 → 轨迹（独立页，带返回键）
 *
 * 原「我的」页里那块轨迹列表整块搬到这里，并补全了：
 * 导入 / 导出全部 / 同步云端，外加按来源（本地·云端·他人·导入）筛选。
 * 点任一条进「轨迹查看」页（trackView/{id}），在那里再看来源详情或进地图详情页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FootprintTracksScreen(
    onBack: () -> Unit,
    onOpenTrackSource: (String) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { TrackRepository.get(context) }
    val cloudVm: CloudTrackViewModel = viewModel()
    val syncedIds by cloudVm.syncedIds.collectAsStateWithLifecycle()
    val cloudBusy by cloudVm.busy.collectAsStateWithLifecycle()
    val cloudMessage by cloudVm.message.collectAsStateWithLifecycle()

    var tracks by remember { mutableStateOf(repo.list()) }
    var message by remember { mutableStateOf<String?>(null) }
    var exportingTrack by remember { mutableStateOf<Track?>(null) }
    var saveAsFormat by remember { mutableStateOf(TrackFileFormat.GPX) }
    var sourceFilter by remember { mutableStateOf<TrackSource?>(null) }

    /** 每条轨迹的来源（依赖 syncedIds，同步完立刻变"云端"） */
    val sourceOf = remember(tracks, syncedIds) {
        TrackSourceResolver.ofAll(context, tracks.map { it.id }, syncedIds)
    }
    val visibleTracks = remember(tracks, sourceOf, sourceFilter) {
        val f = sourceFilter ?: return@remember tracks
        tracks.filter { sourceOf[it.id] == f }
    }

    LaunchedEffect(Unit) { cloudVm.refreshSynced() }
    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            message = null
        }
    }
    LaunchedEffect(cloudMessage) {
        cloudMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            cloudVm.clearMessage()
        }
    }
    // 从轨迹查看页返回时（那边可能刚删了轨迹）重新读一次
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tracks = repo.list()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    fun syncToCloud() {
        if (tracks.isEmpty()) {
            message = "没有可同步的轨迹"
            return
        }
        cloudVm.sync(tracks.map { repo.toJson(it) })
    }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("轨迹") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.IosShare, null, Modifier.size(18.dp))
                                Text("  导入")
                            }
                            OutlinedButton(
                                onClick = ::exportAllAsZip,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Archive, null, Modifier.size(18.dp))
                                Text("  导出全部")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = ::syncToCloud,
                                enabled = !cloudBusy && tracks.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                                Text(if (cloudBusy) "  同步中…" else "  同步到云端")
                            }
                            if (syncedIds.isNotEmpty()) {
                                Text(
                                    "已同步 ${syncedIds.size} 条",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (cloudBusy) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (tracks.isEmpty()) {
                item { FootprintEmptyHint("还没有轨迹记录\n去「运动」页开始第一条，或导入轨迹") }
            } else {
                item {
                    SourceFilterRow(
                        counts = TrackSource.values().associateWith { s ->
                            sourceOf.count { it.value == s }
                        },
                        total = tracks.size,
                        selected = sourceFilter,
                        onSelect = { sourceFilter = if (sourceFilter == it) null else it }
                    )
                }
                if (visibleTracks.isEmpty()) {
                    item { FootprintEmptyHint("没有「${sourceFilter?.label}」\n换个来源看看") }
                } else {
                    items(visibleTracks, key = { "tk_" + it.id }) { track ->
                        TrackRow(
                            track = track,
                            source = sourceOf[track.id] ?: TrackSource.LOCAL,
                            synced = syncedIds.contains(track.id),
                            onClick = { onOpenTrackSource(track.id) },
                            onExport = { exportingTrack = track }
                        )
                    }
                }
                item {
                    Text(
                        "共 ${tracks.size} 条${sourceFilter?.let { "，当前筛选「${it.label}」${visibleTracks.size} 条" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}
