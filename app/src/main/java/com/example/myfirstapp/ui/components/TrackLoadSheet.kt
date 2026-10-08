package com.example.myfirstapp.ui.components

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.data.user.CloudTrackApi
import com.example.myfirstapp.data.user.CloudTrackApi.ServerTrack
import com.example.myfirstapp.data.user.CloudTrackViewModel
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.Track
import com.example.myfirstapp.track.TrackColorStore
import com.example.myfirstapp.track.TrackDownloadStore
import com.example.myfirstapp.track.TrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运动页「加载轨迹」底部弹层：把轨迹按来源分成三栏叠加到运动地图上。
 *
 * - 本地：filesDir/tracks 里的全部轨迹 + 从文件导入（GPX / KML / KMZ）
 * - 网络：服务器 /api/tracks/all 上的共享线路，点一条即下载到本地并叠加
 * - 已下载：本地中「从服务器下载过」的那部分（标记见 [TrackDownloadStore]）
 *
 * 勾选 = 叠加到地图（可多条），取消勾选 = 从地图上撤掉（不删文件）。
 * 每条都给「沿此轨迹导航」入口（记录中不许起导航，由调用方拦截）。
 * 每条可单独改线色（存在 [TrackColorStore] 里，按轨迹 id 区分），
 * 也可「单独查看」——地图上只留这一条，镜头自动框到它。
 *
 * @param loadedIds 当前已叠加的轨迹 id，用于渲染勾选态
 * @param focusId 当前正在"单独查看"的轨迹 id（null = 全部叠加轨迹一起显示）
 * @param localVersion 外部导入新文件后 +1，触发本地列表重新读取
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackLoadSheet(
    loadedIds: Set<String>,
    focusId: String?,
    localVersion: Int,
    onDismiss: () -> Unit,
    onToggle: (Track) -> Unit,
    onFocus: (Track) -> Unit,
    onNavigate: (String) -> Unit,
    onImportFile: () -> Unit,
    onDelete: (Track) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { TrackRepository.get(context) }
    val store = remember { TrackDownloadStore.get(context) }
    val scope = rememberCoroutineScope()

    // 订阅叠加轨迹的自定义颜色表：改色后立即重组，行内色块与地图线同步
    TrackColorStore.overlayColors.collectAsState().value

    var tab by remember { mutableIntStateOf(0) }
    var localTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var downloadedIds by remember { mutableStateOf(store.ids()) }

    // 颜色表首次载入（记录页进来时一般已载入过，这里兜底）
    LaunchedEffect(Unit) { TrackColorStore.ensureLoaded(context) }

    // 本地列表：打开弹层、切回本地栏、外部导入成功（localVersion 变化）时重读
    LaunchedEffect(localVersion, tab) {
        if (tab == 0 || tab == 2) {
            localTracks = withContext(Dispatchers.IO) { repo.list() }
            downloadedIds = store.ids()
        }
    }

    // ---------- 网络栏：服务器共享线路 ----------
    val serverUrl = remember {
        context.getSharedPreferences(CloudTrackViewModel.PREFS, android.content.Context.MODE_PRIVATE)
            .getString(CloudTrackViewModel.KEY_URL, CloudTrackViewModel.DEFAULT_URL)
            ?: CloudTrackViewModel.DEFAULT_URL
    }
    var netRoutes by remember { mutableStateOf<List<ServerTrack>>(emptyList()) }
    var netLoading by remember { mutableStateOf(false) }
    var netError by remember { mutableStateOf<String?>(null) }
    var downloadingId by remember { mutableStateOf<String?>(null) }

    fun loadNet() {
        scope.launch {
            netLoading = true
            netError = null
            val (list, err) = withContext(Dispatchers.IO) {
                runCatching { CloudTrackApi(serverUrl).listAllTracks() }
                    .fold(
                        onSuccess = { it to (null as String?) },
                        onFailure = { emptyList<ServerTrack>() to (it.message ?: "获取线路失败") }
                    )
            }
            netLoading = false
            netRoutes = list
            netError = err
        }
    }
    LaunchedEffect(tab) { if (tab == 1 && netRoutes.isEmpty() && netError == null) loadNet() }

    /** 下载一条服务器线路：本地已有就直接复用，否则拉取完整轨迹落本地并打「已下载」标记 */
    fun download(st: ServerTrack) {
        if (downloadingId != null) return
        downloadingId = st.id
        scope.launch {
            val (track, err) = withContext(Dispatchers.IO) {
                runCatching {
                    val local = repo.load(st.id)
                    if (local != null) {
                        store.mark(local.id, st.ownerNickname)
                        local
                    } else {
                        val json = CloudTrackApi(serverUrl).downloadTrack(st.userId, st.id)
                            ?: error("服务器上没有这条线路的轨迹数据")
                        val t = repo.parseTrack(json)
                        repo.save(t)
                        store.mark(t.id, st.ownerNickname)
                        t
                    }
                }.fold(
                    onSuccess = { it to (null as String?) },
                    onFailure = { (null as Track?) to (it.message ?: "下载失败") }
                )
            }
            downloadingId = null
            if (track != null) {
                localTracks = withContext(Dispatchers.IO) { repo.list() }
                downloadedIds = store.ids()
                onToggle(track)   // 下载完成即叠加到地图
                Toast.makeText(context, "已下载：${track.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, err ?: "下载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- 逐条改色 ----------
    var colorPicking by remember { mutableStateOf<Track?>(null) }
    colorPicking?.let { t ->
        ColorPickerDialog(
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

    // ---------- 删除确认 ----------
    var deleting by remember { mutableStateOf<Track?>(null) }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除轨迹") },
            text = { Text("确定删除「${t.name}」？该轨迹的本地文件会被移除，且不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(t)
                    localTracks = localTracks.filterNot { it.id == t.id }
                    downloadedIds = store.ids()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
        ) {
            TabRow(selectedTabIndex = tab) {
                listOf("本地", "网络", "已下载").forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            Spacer(Modifier.height(8.dp))

            val bodyHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = bodyHeight)
            ) {
                when (tab) {
                    0 -> LocalPane(
                        tracks = localTracks,
                        downloadedIds = downloadedIds,
                        loadedIds = loadedIds,
                        focusId = focusId,
                        onImport = onImportFile,
                        onToggle = onToggle,
                        onFocus = onFocus,
                        onPickColor = { colorPicking = it },
                        onNavigate = onNavigate,
                        onDelete = { deleting = it }
                    )

                    1 -> NetworkPane(
                        routes = netRoutes,
                        loading = netLoading,
                        error = netError,
                        serverUrl = serverUrl,
                        downloadedIds = downloadedIds,
                        downloadingId = downloadingId,
                        onRetry = ::loadNet,
                        onDownload = ::download
                    )

                    else -> DownloadedPane(
                        tracks = localTracks.filter { downloadedIds.contains(it.id) },
                        loadedIds = loadedIds,
                        focusId = focusId,
                        onToggle = onToggle,
                        onFocus = onFocus,
                        onPickColor = { colorPicking = it },
                        onNavigate = onNavigate,
                        onDelete = { deleting = it }
                    )
                }
            }
        }
    }
}

// ------------------------------ 本地栏 ------------------------------

@Composable
private fun LocalPane(
    tracks: List<Track>,
    downloadedIds: Set<String>,
    loadedIds: Set<String>,
    focusId: String?,
    onImport: () -> Unit,
    onToggle: (Track) -> Unit,
    onFocus: (Track) -> Unit,
    onPickColor: (Track) -> Unit,
    onNavigate: (String) -> Unit,
    onDelete: (Track) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.IosShare, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("从文件导入（GPX / KML / KMZ）")
        }
        Spacer(Modifier.height(8.dp))
        if (tracks.isEmpty()) {
            EmptyHint("本地还没有轨迹\n去记录一条，或从文件导入")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(tracks, key = { it.id }) { t ->
                    TrackRow(
                        track = t,
                        loaded = loadedIds.contains(t.id),
                        focused = focusId == t.id,
                        badge = if (downloadedIds.contains(t.id)) "云端" else null,
                        colorArgb = TrackColorStore.overlayColorOf(t.id),
                        onToggle = { onToggle(t) },
                        onFocus = { onFocus(t) },
                        onPickColor = { onPickColor(t) },
                        onNavigate = { onNavigate(t.id) },
                        onDelete = { onDelete(t) }
                    )
                }
            }
        }
    }
}

// ------------------------------ 网络栏 ------------------------------

@Composable
private fun NetworkPane(
    routes: List<ServerTrack>,
    loading: Boolean,
    error: String?,
    serverUrl: String,
    downloadedIds: Set<String>,
    downloadingId: String?,
    onRetry: () -> Unit,
    onDownload: (ServerTrack) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.CloudQueue, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                serverUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRetry) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "刷新",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            }
            error != null -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("获取线路失败", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(error, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRetry) { Text("重试") }
            }
            routes.isEmpty() -> EmptyHint("服务器上还没有共享线路\n先在「我的」页把轨迹同步到云端")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(routes, key = { it.id + it.userId }) { st ->
                    ServerRouteRow(
                        route = st,
                        downloaded = downloadedIds.contains(st.id),
                        downloading = downloadingId == st.id,
                        onDownload = { onDownload(st) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerRouteRow(
    route: ServerTrack,
    downloaded: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit
) {
    Surface(
        onClick = onDownload,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(route.name, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(GeoUtils.formatDistance(route.distanceMeters))
                        append(" · ${route.pointCount} 个点")
                        if (route.waypointCount > 0) append(" · ${route.waypointCount} 个打卡")
                        if (route.ownerNickname.isNotBlank()) append(" · ${route.ownerNickname}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            if (downloading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else if (downloaded) {
                Icon(
                    Icons.Default.CloudDone,
                    contentDescription = "已下载",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = "下载",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ------------------------------ 已下载栏 ------------------------------

@Composable
private fun DownloadedPane(
    tracks: List<Track>,
    loadedIds: Set<String>,
    focusId: String?,
    onToggle: (Track) -> Unit,
    onFocus: (Track) -> Unit,
    onPickColor: (Track) -> Unit,
    onNavigate: (String) -> Unit,
    onDelete: (Track) -> Unit
) {
    if (tracks.isEmpty()) {
        EmptyHint("还没有从云端下载过轨迹\n去「网络」栏挑一条线路下载")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(tracks, key = { it.id }) { t ->
                TrackRow(
                    track = t,
                    loaded = loadedIds.contains(t.id),
                    focused = focusId == t.id,
                    badge = "云端",
                    colorArgb = TrackColorStore.overlayColorOf(t.id),
                    onToggle = { onToggle(t) },
                    onFocus = { onFocus(t) },
                    onPickColor = { onPickColor(t) },
                    onNavigate = { onNavigate(t.id) },
                    onDelete = { onDelete(t) }
                )
            }
        }
    }
}

// ------------------------------ 公共小组件 ------------------------------

@Composable
private fun TrackRow(
    track: Track,
    loaded: Boolean,
    focused: Boolean,
    badge: String?,
    colorArgb: Int,
    onToggle: () -> Unit,
    onFocus: () -> Unit,
    onPickColor: () -> Unit,
    onNavigate: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFmt = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (loaded || focused) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = loaded, onCheckedChange = { onToggle() })

            // 线色小圆点：点一下弹出取色器，改的是这一条自己的颜色
            Surface(
                onClick = onPickColor,
                shape = CircleShape,
                color = Color(colorArgb),
                modifier = Modifier
                    .size(26.dp)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
            ) {}

            Spacer(Modifier.width(8.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(track.name, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (badge != null) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(GeoUtils.formatDistance(track.distanceMeters))
                        append(" · ${track.points.size} 个点")
                        if (track.startTime > 0) append(" · ${dateFmt.format(Date(track.startTime))}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            // 单独查看：地图上只留这一条（再点一次取消）
            IconButton(onClick = onFocus) {
                Icon(
                    if (focused) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (focused) "取消单独查看" else "单独查看",
                    modifier = Modifier.size(20.dp),
                    tint = if (focused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onNavigate) {
                Icon(
                    Icons.Default.Navigation,
                    contentDescription = "沿此轨迹导航",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除轨迹",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Route, null, Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
