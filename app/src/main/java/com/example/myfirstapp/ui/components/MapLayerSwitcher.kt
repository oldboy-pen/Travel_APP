package com.example.myfirstapp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amap.api.maps.AMap
import com.example.myfirstapp.R
import com.example.myfirstapp.mapsources.CustomTileProvider
import com.example.myfirstapp.mapsources.MapSource
import com.example.myfirstapp.mapsources.MapSourceStore

/**
 * 持有一张地图当前的全部瓦片图层，切换图源时整体替换。
 */
class MapOverlaysHolder {
    var baseOverlay: com.amap.api.maps.model.TileOverlay? = null
    var roadOverlay: com.amap.api.maps.model.TileOverlay? = null
    var extraOverlay: com.amap.api.maps.model.TileOverlay? = null

    fun clearAll() {
        baseOverlay?.remove(); baseOverlay = null
        roadOverlay?.remove(); roadOverlay = null
        extraOverlay?.remove(); extraOverlay = null
    }
}

/**
 * 应用当前图源配置（底图 + 叠加层）到高德地图实例。
 * 幂等：每次先移除全部旧瓦片图层再重建，各地图页在图源变化或页面重入时调用。
 *
 * 底图策略：
 * - amap.* 走高德 SDK 原生底图（矢量/卫星/夜景/卫星路网）；
 * - 其他图源（腾讯/百度/天地图/自定义）：高德矢量打底做兜底（瓦片加载间隙不露白），
 *   不透明第三方瓦片覆盖其上；WGS84/BD09 图源由 CustomTileProvider 逐像素纠偏。
 */
fun applyMapSources(aMap: AMap, holder: MapOverlaysHolder) {
    holder.clearAll()
    val base = MapSourceStore.activeBase()
    when (base.id) {
        "amap.satellite" -> aMap.mapType = AMap.MAP_TYPE_SATELLITE
        "amap.night" -> aMap.mapType = AMap.MAP_TYPE_NIGHT
        "amap.sat_road" -> {
            // 卫星底图 + 高德官方路网透明层（style=7：白线道路+地名标注，无坐标系偏差）
            aMap.mapType = AMap.MAP_TYPE_SATELLITE
            holder.roadOverlay = aMap.addTileOverlay(
                com.amap.api.maps.model.TileOverlayOptions()
                    .tileProvider(object : com.amap.api.maps.model.UrlTileProvider(256, 256) {
                        override fun getTileUrl(x: Int, y: Int, zoom: Int): java.net.URL? =
                            runCatching {
                                java.net.URL("https://wprd0$((x + y) % 4).is.autonavi.com/appmaptile?style=7&x=$x&y=$y&z=$zoom")
                            }.getOrNull()
                    })
                    .zIndex(0f)
                    .diskCacheEnabled(true)
                    .memCacheSize(10 * 1024 * 1024)
            )
        }
        else -> {
            // 高德矢量 / 第三方瓦片底图
            aMap.mapType = AMap.MAP_TYPE_NORMAL
            if (base.urlTemplate.isNotBlank()) {
                holder.baseOverlay = aMap.addTileOverlay(
                    com.amap.api.maps.model.TileOverlayOptions()
                        .tileProvider(CustomTileProvider(base) { MapSourceStore.tiandituKey })
                        .zIndex(0f)
                        .diskCacheEnabled(true)
                        .memCacheSize(20 * 1024 * 1024)
                )
            }
        }
    }

    // 叠加层（半透明标注/等高线等）
    MapSourceStore.activeOverlay()?.let { ov ->
        if (ov.urlTemplate.isNotBlank()) {
            holder.extraOverlay = aMap.addTileOverlay(
                com.amap.api.maps.model.TileOverlayOptions()
                    .tileProvider(CustomTileProvider(ov) { MapSourceStore.tiandituKey })
                    .zIndex(1f)
                    .diskCacheEnabled(true)
                    .memCacheSize(10 * 1024 * 1024)
            )
        }
    }
}

/** 内置图源 → 预览缩略图（真实瓦片，成都/四姑娘山一带 z12） */
@DrawableRes
fun previewOf(source: MapSource): Int? = when (source.id) {
    "amap.normal", "tdt.vec", "tdt.cva" -> R.drawable.pv_amap_vector
    "amap.satellite", "tdt.img" -> R.drawable.pv_amap_sat
    "amap.sat_road" -> R.drawable.pv_amap_sat
    "amap.night" -> R.drawable.pv_amap_night
    "tencent.street" -> R.drawable.pv_tencent_street
    "tencent.satellite" -> R.drawable.pv_tencent_sat
    "baidu.street" -> R.drawable.pv_baidu_street
    "baidu.satellite" -> R.drawable.pv_baidu_sat
    "tdt.ter", "opentopomap" -> R.drawable.pv_hillshade
    else -> null // 自定义图源：图标占位
}

/**
 * 图层切换控件：右上角小按钮展开「缩略图+名称」卡片网格（图上文下），
 * 分底图/叠加层两组，底部图源管理入口。
 * 选择状态存 MapSourceStore（全局共享、持久化，三个地图页一致）。
 *
 * @param panelMaxHeight 面板内容区最大高度。内容超过即在内部滚动，
 *                       避免整块面板把"关闭"按钮顶出屏幕（格子多了原本会超出一屏）。
 * @param asSheet true=用底部弹层展开（运动页用：底部控制区很高，右侧悬浮面板会被遮挡/挤走，
 *                弹层可下拉、点外部、返回键关闭，永远有关闭入口）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapLayerSwitcher(
    modifier: Modifier = Modifier,
    panelMaxHeight: Dp = 340.dp,
    asSheet: Boolean = false
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var showManager by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End
    ) {
        if (expanded && !asSheet) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.width(272.dp)
            ) {
                LayerPanelContent(
                    maxBodyHeight = panelMaxHeight,
                    onManage = { expanded = false; showManager = true },
                    onClose = { expanded = false }
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        SmallFloatingActionButton(
            onClick = { expanded = !expanded },
            containerColor = Color.White
        ) {
            Icon(
                if (expanded && !asSheet) Icons.Default.Close else Icons.Default.Layers,
                contentDescription = if (expanded && !asSheet) "关闭图层面板" else "切换图层",
                tint = Color(0xFF2E7D32)
            )
        }
    }

    // 底部弹层模式（运动页）
    if (asSheet && expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false }) {
            LayerPanelContent(
                maxBodyHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp,
                onManage = { expanded = false; showManager = true },
                onClose = { expanded = false },
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .padding(bottom = 12.dp)
            )
        }
    }

    if (showManager) {
        MapSourceManagerSheet(onDismiss = { showManager = false })
    }

    // 首次进入也确保 store 已加载（页面未调用 ensureLoaded 时兜底）
    LaunchedEffect(Unit) { MapSourceStore.ensureLoaded(context) }
}

/**
 * 面板内容：顶部固定标题栏（含关闭按钮）+ 可滚动的内容区 + 底部操作栏。
 * 标题栏不参与滚动 → 无论内容滚到哪里，关闭按钮始终可见。
 */
@Composable
fun LayerPanelContent(
    maxBodyHeight: Dp,
    onManage: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // ---- 标题栏：标题 + 关闭（固定，不随内容滚动）----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Layers,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "地图图层",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭图层面板",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider()

        // ---- 内容区：高度封顶 + 内部滚动 ----
        Column(
            modifier = Modifier
                .heightIn(max = maxBodyHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            SectionLabel("底图")
            SourceGrid(
                sources = MapSourceStore.allBases(),
                selectedId = MapSourceStore.activeBaseId
            ) { id -> id?.let { MapSourceStore.selectBase(it) } }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("叠加层")
            SourceGrid(
                sources = listOf<MapSource?>(null) + MapSourceStore.allOverlays(),
                selectedId = MapSourceStore.activeOverlayId
            ) { MapSourceStore.selectOverlay(it) }
        }

        // ---- 底部操作栏 ----
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onManage) { Text("管理图源…", fontSize = 14.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Text("关闭", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp)
    )
}

/** 图源卡片网格：3 列，图上文下。null 元素表示叠加层"无"。 */
@Composable
private fun SourceGrid(
    sources: List<MapSource?>,
    selectedId: String?,
    onSelect: (String?) -> Unit
) {
    // 简单网格（数量少，不用 LazyGrid 的滚动）
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { src ->
                    SourceCard(
                        source = src,
                        // null（"无"）与 selectedId==null 互选中
                        selected = (src?.id ?: "") == (selectedId ?: ""),
                        modifier = Modifier.weight(1f)
                    ) { onSelect(src?.id) }
                }
                // 补齐空位保持等宽
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SourceCard(
    source: MapSource?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(5.dp)
    ) {
        // 缩略图（上）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val preview = source?.let { previewOf(it) }
            if (preview != null) {
                Image(
                    painter = painterResource(preview),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // "无" / 自定义图源：图标占位
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        if (source == null) Icons.Default.Clear else Icons.Default.Map,
                        contentDescription = null,
                        tint = if (source == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            // 需 Key 提示角标
            if (source?.needsKey == true && MapSourceStore.tiandituKey.isBlank()) {
                Text(
                    "需Key",
                    fontSize = 9.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .background(
                            MaterialTheme.colorScheme.error,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // 名称（下）
        Text(
            source?.name ?: "无",
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
