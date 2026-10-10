package com.example.myfirstapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myfirstapp.map.MapEngine
import com.example.myfirstapp.map.MapSurface
import com.example.myfirstapp.map.MapUiSettings
import com.example.myfirstapp.map.rememberMapSurfaceState
import com.example.myfirstapp.mapsources.MapSource
import com.example.myfirstapp.mapsources.MapSourceStore
import com.example.myfirstapp.offline.ElevationStore
import com.example.myfirstapp.offline.OfflineDownloadService
import com.example.myfirstapp.offline.OfflineRegion
import com.example.myfirstapp.offline.OfflineRegionStatus
import com.example.myfirstapp.offline.OfflineRegionStore
import com.example.myfirstapp.offline.OfflineStorage
import com.example.myfirstapp.offline.TileGrid
import com.example.myfirstapp.offline.VendorCity
import com.example.myfirstapp.offline.VendorOffline
import com.example.myfirstapp.offline.VendorStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.pow

/**
 * 离线地图管理页。
 *
 * 三块内容对应三条离线通道：
 * 1. **离线区域**：自建瓦片下载（天地图 / OpenTopoMap / 自定义 XYZ），按当前视口圈选；
 * 2. **官方城市包**：高德 / 百度官方离线包（这两家底图由 SDK 渲染，瓦片抓不到，只能用官方通道）；
 * 3. **高程**：terrarium 地形栅格，下载后可以无网查任意点的海拔。
 *
 * 顶部还有全局的「离线模式」开关：打开后地图只读本地数据，一次网络请求都不发，
 * 用来验证"这块区域到底下全了没有"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tabIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        OfflineRegionStore.ensureLoaded(context)
        VendorOffline.ensureLoaded(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("离线地图", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 全局离线模式：地图只读本地存档
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("离线模式", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = OfflineRegionStore.offlineOnly,
                            onCheckedChange = { OfflineRegionStore.offlineOnly = it }
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tabIndex) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("离线区域") })
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("官方城市包") })
                Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("高程") })
            }
            when (tabIndex) {
                0 -> RegionsTab(context)
                1 -> VendorTab()
                2 -> ElevationTab(context)
            }
        }
    }
}

// ==================== Tab 1：自建离线区域 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionsTab(context: android.content.Context) {
    val mapState = rememberMapSurfaceState("offline")
    // 可离线下载的图源 = 有 URL 模板的瓦片源（厂商原生底图抓不到瓦片，走官方城市包）
    val sources = remember(MapSourceStore.revision) {
        (MapSource.BUILTINS + MapSourceStore.customSources).filter { it.isTileSource }
    }
    var selected by remember { mutableStateOf(sources.firstOrNull()) }
    if (selected == null && sources.isNotEmpty()) selected = sources.first()

    var minZoom by remember { mutableFloatStateOf(10f) }
    var maxZoom by remember { mutableFloatStateOf(15f) }
    var includeElevation by remember { mutableStateOf(true) }
    // 视口 bbox：地图每次停下来（手势结束 / 首次定位）都会更新
    var bounds by remember { mutableStateOf<DoubleArray?>(null) }
    var boundsError by remember { mutableStateOf<String?>(null) }

    // 引擎就绪 + 用户手势后重算视口（onUserGesture 在拖动/缩放结束时回调）
    LaunchedEffect(mapState.engine) {
        recomputeBounds(mapState.engine) { b, err ->
            bounds = b
            boundsError = err
        }
    }

    val source = selected
    val layerCount = if (source != null && source.layers.isNotEmpty()) source.layers.size else 1
    val tileCount = if (source != null && bounds != null) {
        TileGrid.countTiles(
            bounds!![0], bounds!![1], bounds!![2], bounds!![3],
            minZoom.toInt(), maxZoom.toInt(), layerCount
        )
    } else 0L

    Column(Modifier.fillMaxSize()) {
        // ---- 圈选用地图（256dp）+ 中央提示 ----
        Box(Modifier.fillMaxWidth().height(256.dp)) {
            MapSurface(
                state = mapState,
                pageKey = "offline",
                modifier = Modifier.fillMaxSize(),
                uiSettings = MapUiSettings(zoomControls = true, myLocationButton = true),
                onUserGesture = {
                    recomputeBounds(mapState.engine) { b, err ->
                        bounds = b
                        boundsError = err
                    }
                },
                overlay = {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 6.dp)
                            .background(Color(0x99000000), MaterialTheme.shapes.small)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "拖动/缩放地图，把要下载的范围放进屏幕",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---- 图源选择 ----
            Text("下载哪个图源", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sources.forEach { s ->
                    FilterChip(
                        selected = s.id == source?.id,
                        onClick = { selected = s },
                        label = { Text(s.name, fontSize = 11.sp, maxLines = 1) }
                    )
                }
            }
            if (source == null) {
                Text("没有可离线下载的瓦片图源", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            // 天地图需要 Key：没配就说清楚，否则下下来全是鉴权失败图
            if (source?.needsKey == true && MapSourceStore.tiandituKey.isBlank()) {
                Text(
                    "⚠ ${source.name} 需要天地图 Key，请先在「图源管理」里填写",
                    color = MaterialTheme.colorScheme.error, fontSize = 12.sp
                )
            }

            // ---- 级别范围 ----
            Text("缩放级别：z${minZoom.toInt()} — z${maxZoom.toInt()}", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最小", fontSize = 11.sp, modifier = Modifier.width(28.dp))
                Slider(
                    value = minZoom,
                    onValueChange = { minZoom = it.coerceAtMost(maxZoom) },
                    valueRange = 3f..18f,
                    steps = 14,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最大", fontSize = 11.sp, modifier = Modifier.width(28.dp))
                Slider(
                    value = maxZoom,
                    onValueChange = { maxZoom = it.coerceAtLeast(minZoom) },
                    valueRange = 3f..18f,
                    steps = 14,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "级别每 +1，瓦片数 ×4。z15 一个屏幕约 ${"%,d".format(tilesInOneScreen(15))} 张，建议先用 z12 试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- 高程附带下载 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = includeElevation, onCheckedChange = { includeElevation = it })
                Spacer(Modifier.width(8.dp))
                Text("同时下载高程（可无网查海拔）", fontSize = 13.sp)
            }

            // ---- 预估与下载按钮 ----
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    if (bounds == null) {
                        Text(boundsError ?: "正在获取地图范围…", fontSize = 12.sp)
                    } else {
                        Text("范围：%.4f, %.4f → %.4f, %.4f".format(
                            bounds!![0], bounds!![1], bounds!![2], bounds!![3]
                        ), fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "预估 ${"%,d".format(tileCount)} 张 · 约 ${formatBytes(estimateBytes(tileCount, source))}",
                            fontSize = 13.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val b = bounds
                            val s = source
                            if (b == null || s == null) return@Button
                            val region = OfflineRegion(
                                id = "",
                                name = OfflineRegionStore.suggestName(s.name),
                                sourceId = s.id,
                                sourceName = s.name,
                                minLat = b[0], minLon = b[1], maxLat = b[2], maxLon = b[3],
                                minZoom = minZoom.toInt(),
                                maxZoom = maxZoom.toInt(),
                                includeElevation = includeElevation,
                                status = OfflineRegionStatus.QUEUED,
                                totalTiles = tileCount.toInt()
                            )
                            val id = OfflineRegionStore.add(region)
                            OfflineDownloadService.start(context, id)
                            Toast.makeText(context, "已开始下载，可在通知栏查看进度", Toast.LENGTH_SHORT).show()
                        },
                        enabled = bounds != null && source != null && tileCount > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("下载这块区域") }
                }
            }

            // ---- 已有区域列表 ----
            Text("已下载区域（${OfflineRegionStore.regions.size}）", style = MaterialTheme.typography.titleSmall)
            if (OfflineRegionStore.regions.isEmpty()) {
                Text(
                    "还没有离线区域。无网时地图会空白 —— 出发前先把要去的地方下下来。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OfflineRegionStore.regions.forEach { r ->
                RegionRow(r, onResume = { OfflineDownloadService.start(context, r.id) })
            }

            // ---- 磁盘占用 ----
            Spacer(Modifier.height(8.dp))
            Text(
                "离线数据占用：${formatBytes(OfflineStorage.usedBytes())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RegionRow(region: OfflineRegion, onResume: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (region.includeElevation) Icons.Default.Terrain else Icons.Default.Layers,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(region.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(region.status.label, fontSize = 11.sp, color = statusColor(region.status))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${region.sourceName} · z${region.minZoom}-${region.maxZoom}" +
                    if (region.includeElevation) " · 含高程" else "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (region.totalTiles > 0) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = region.progress,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${region.doneTiles} / ${region.totalTiles} 张 · ${formatBytes(region.bytes)}",
                    fontSize = 11.sp
                )
            }
            region.error?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (region.status == OfflineRegionStatus.PAUSED ||
                    region.status == OfflineRegionStatus.FAILED
                ) {
                    OutlinedButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("继续", fontSize = 12.sp)
                    }
                }
                TextButton(onClick = { OfflineRegionStore.remove(region.id) }) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除", fontSize = 12.sp)
                }
            }
        }
    }
}

// ==================== Tab 2：厂商官方城市包 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VendorTab() {
    val scope = rememberCoroutineScope()
    var vendor by remember { mutableStateOf(VendorOffline.VENDOR_AMAP) }
    var query by remember { mutableStateOf("") }

    val cities = if (vendor == VendorOffline.VENDOR_AMAP) VendorOffline.amapCities
    else VendorOffline.baiduCities
    val filtered = if (query.isBlank()) cities
    else cities.filter { it.name.contains(query.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "高德/百度底图由厂商 SDK 渲染，瓦片无法自行下载 —— 用官方离线包。下载后 SDK 自动优先用离线数据，无需额外设置。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = vendor == VendorOffline.VENDOR_AMAP,
                onClick = { vendor = VendorOffline.VENDOR_AMAP },
                label = { Text("高德") },
                enabled = VendorOffline.amapReady
            )
            FilterChip(
                selected = vendor == VendorOffline.VENDOR_BAIDU,
                onClick = { vendor = VendorOffline.VENDOR_BAIDU },
                label = { Text("百度") },
                enabled = VendorOffline.baiduReady
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { scope.launch { VendorOffline.refreshAll() } }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索城市", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )

        VendorOffline.error?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }

        if (filtered.isEmpty()) {
            Text(
                if (vendor == VendorOffline.VENDOR_AMAP && !VendorOffline.amapReady) "高德离线地图不可用（检查 SDK Key）"
                else if (vendor == VendorOffline.VENDOR_BAIDU && !VendorOffline.baiduReady) "百度离线地图不可用（检查 SDK Key 与初始化）"
                else "暂无城市数据，点右上角刷新",
                fontSize = 12.sp
            )
        } else {
            // 只展示"已下载/下载中"的 + 前 80 个可下载的，避免几百个城市一次性渲染
            // 已下载/下载中的排前面（用户关心的是这几个），其余按名字排，最多渲染 80 个避免卡顿
            val visible = filtered
                .sortedWith(
                    compareByDescending<VendorCity> { it.status != VendorStatus.AVAILABLE }
                        .thenBy { it.name }
                )
                .take(80)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visible, key = { it.vendor + "_" + it.id }) { c ->
                    VendorCityRow(c)
                }
            }
        }
    }
}

@Composable
private fun VendorCityRow(city: VendorCity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(city.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${city.vendorLabel} · ${city.status.label}" +
                        if (city.sizeBytes > 0) " · ${formatBytes(city.sizeBytes)}" else "",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (city.status == VendorStatus.DOWNLOADING && city.progress > 0) {
                    LinearProgressIndicator(
                        progress = (city.progress / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
            when (city.status) {
                VendorStatus.AVAILABLE,
                VendorStatus.ERROR -> IconButton(onClick = { VendorOffline.download(city) }) {
                    Icon(Icons.Default.Download, contentDescription = "下载")
                }
                VendorStatus.DOWNLOADING,
                VendorStatus.WAITING -> IconButton(onClick = { VendorOffline.pause(city) }) {
                    Icon(Icons.Default.Pause, contentDescription = "暂停")
                }
                VendorStatus.PAUSED -> IconButton(onClick = { VendorOffline.download(city) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "继续")
                }
                VendorStatus.DONE -> IconButton(onClick = { VendorOffline.remove(city) }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

// ==================== Tab 3：高程 ====================

@Composable
private fun ElevationTab(context: android.content.Context) {
    val scope = rememberCoroutineScope()
    val mapState = rememberMapSurfaceState("offlineElev")
    var bounds by remember { mutableStateOf<DoubleArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf(0L to 0L) }   // 瓦片数 / 字节
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            stats = ElevationStore.count() to ElevationStore.sizeBytes()
        }
    }
    LaunchedEffect(mapState.engine) {
        recomputeBounds(mapState.engine) { b, _ -> bounds = b }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "高程数据用来无网查海拔。数据源为公开的 terrarium 地形栅格（SRTM），" +
                "下载后在地图页长按任意点即可看到该点海拔。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                Text("已缓存高程瓦片：${stats.first} 张 · ${formatBytes(stats.second)}", fontSize = 13.sp)
                if (stats.first == 0L) {
                    Text("还没有高程数据，无网时查不到海拔", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val b = bounds
                        if (b == null) {
                            message = "地图还没就绪"
                            return@Button
                        }
                        busy = true
                        message = "正在下载高程…"
                        scope.launch {
                            val n = withContext(Dispatchers.IO) {
                                ElevationStore.download(b[0], b[1], b[2], b[3], 8, ElevationStore.MAX_ZOOM)
                            }
                            stats = withContext(Dispatchers.IO) {
                                ElevationStore.count() to ElevationStore.sizeBytes()
                            }
                            busy = false
                            message = "已下载 $n 张高程瓦片"
                        }
                    },
                    enabled = !busy && bounds != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "下载中…" else "下载当前视口的高程") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        ElevationStore.clear()
                        stats = 0L to 0L
                        message = "已清空高程数据"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("清空高程数据") }
            }
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(12.dp))
        // 复用一张小地图来圈选范围
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            MapSurface(
                state = mapState,
                pageKey = "offlineElev",
                modifier = Modifier.fillMaxSize(),
                onUserGesture = { recomputeBounds(mapState.engine) { b, _ -> bounds = b } }
            )
        }
        Text(
            "拖动上面的地图选择要下载高程的范围（固定下载 z8—z${ElevationStore.MAX_ZOOM}）",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
    }
}

// ==================== 工具（文件顶层：Composable 内的局部函数不能加 private） ====================

/**
 * 从引擎当前镜头 + View 尺寸反算可见的经纬度矩形。
 *
 * ★ 为什么不直接用各家 SDK 的 visibleRegion：四家 API 名字与坐标系都不一样
 *   （百度尤其受 setCoordType 影响），统一用「中心 + 缩放 + 像素尺寸」纯数学推算是
 *   唯一对所有引擎一致、且不需要新增 SDK 依赖的做法。
 *
 * @return 回调 [minLat, minLon, maxLat, maxLon]（GCJ-02），失败时给 null + 原因
 */
private fun recomputeBounds(engine: MapEngine?, out: (DoubleArray?, String?) -> Unit) {
    val cam = engine?.getCamera()
    if (cam == null) {
        out(null, "当前底图不支持读取视野范围（请用高德/腾讯/osmdroid 底图）")
        return
    }
    val w = engine.view.width
    val h = engine.view.height
    if (w <= 0 || h <= 0) {
        out(null, "地图尚未布局完成")
        return
    }
    val lat = cam.center.latitude
    val latRad = Math.toRadians(lat)
    // Web 墨卡托下该纬度的分辨率（米/像素）
    val res = 156543.03392 * cos(latRad) / 2.0.pow(cam.zoom.toDouble())
    val halfLatDeg = (h / 2.0 * res) / 110574.0
    val metersPerLonDeg = 111320.0 * cos(latRad).coerceAtLeast(0.01)
    val halfLonDeg = (w / 2.0 * res) / metersPerLonDeg
    out(
        doubleArrayOf(
            (lat - halfLatDeg).coerceIn(-85.0, 85.0),
            cam.center.longitude - halfLonDeg,
            (lat + halfLatDeg).coerceIn(-85.0, 85.0),
            cam.center.longitude + halfLonDeg
        ), null
    )
}

/** 单个屏幕在指定级别下大致多少张瓦片（给用户的量级提示用） */
private fun tilesInOneScreen(zoom: Int): Int {
    val n = 1 shl zoom
    // 按 1080×1080 的可见区域、中纬度估算
    val res = 156543.03392 * cos(Math.toRadians(35.0)) / 2.0.pow(zoom.toDouble())
    val spanDeg = 1080 * res / (111320.0 * cos(Math.toRadians(35.0)))
    val tiles = (spanDeg / 360.0 * n).toInt().coerceAtLeast(1)
    return tiles * tiles
}

/** 粗估体积：矢量瓦片约 12 KB、卫星/地形约 35 KB */
private fun estimateBytes(tiles: Long, source: MapSource?): Long {
    val per = if (source?.name?.contains("卫星") == true || source?.name?.contains("地形") == true) 35_000L
    else 12_000L
    return tiles * per * (if (source != null && source.layers.isNotEmpty()) source.layers.size else 1)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val i = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(3)
    return "%.1f %s".format(bytes / 1024.0.pow(i.toDouble()), units[i])
}

@Composable
private fun statusColor(status: OfflineRegionStatus): Color = when (status) {
    OfflineRegionStatus.READY -> Color(0xFF2E7D32)
    OfflineRegionStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
    OfflineRegionStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
