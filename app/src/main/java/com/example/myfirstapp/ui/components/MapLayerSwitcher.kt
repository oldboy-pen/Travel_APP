package com.example.myfirstapp.ui.components

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
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
import com.example.myfirstapp.R
import com.example.myfirstapp.map.MapEngineKeys
import com.example.myfirstapp.mapsources.MapSource
import com.example.myfirstapp.mapsources.MapSourceStore
import com.example.myfirstapp.offline.OfflineRegionStore
import com.example.myfirstapp.track.TrackColorStore

/** 内置图源 → 预览缩略图（真实瓦片，成都/四姑娘山一带 z12） */
@DrawableRes
fun previewOf(source: MapSource): Int? = when (source.id) {
    "amap.normal", "tdt.vec", "tdt.cva" -> R.drawable.pv_amap_vector
    "amap.satellite", "tdt.img" -> R.drawable.pv_amap_sat
    "amap.sat_road" -> R.drawable.pv_amap_sat
    "amap.night" -> R.drawable.pv_amap_night
    "tencent.street" -> R.drawable.pv_tencent_street
    "tencent.satellite" -> R.drawable.pv_tencent_sat
    // tencent.dark 暂未准备预览图，走下面的图标占位（想补的话按同一规则
    // 截一张 z12 缩略图放到 res/drawable-nodpi/ 再加一行映射即可）
    "baidu.street" -> R.drawable.pv_baidu_street
    "baidu.satellite" -> R.drawable.pv_baidu_sat
    "tdt.terrain", "opentopomap" -> R.drawable.pv_hillshade
    else -> null // 自定义图源：图标占位
}

/**
 * 图层切换控件：右上角小按钮展开「缩略图+名称」卡片网格（图上文下），
 * 分底图/叠加层两组，底部图源管理入口。
 * 选择状态存 MapSourceStore（全局共享、持久化，三个地图页一致）。
 *
 * 选中某张图后，地图页面会自动切到它对应的原生 SDK 渲染（见 map 包下的引擎抽象）：
 *   amap.* → AMapEngine，tencent.* → TencentMapEngine，baidu.* → BaiduMapEngine，
 *   天地图/自定义瓦片 → 仍然用 AMapEngine + CustomTileProvider 叠加渲染。
 *
 * 选择时有一层 Key 保护：该厂商 Key 没配置就提示并要求先配置，避免用户只看到白屏。
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

    // 首次进入也确保 store 已加载（页面未调用 ensureLoaded 时兜底）
    LaunchedEffect(Unit) {
        MapSourceStore.ensureLoaded(context)
        // ★ 离线区域清单：底图列表里那几张「离线 · …」是从区域现算出来的，
        //   清单没加载就一张都不会出现（也选不中，trySelectBase 会静默失败）
        OfflineRegionStore.ensureLoaded(context)
        MapEngineKeys.init(context)
        TrackColorStore.ensureLoaded(context)
    }

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
}

/**
 * 选中的底图对应的厂商没配 Key 时拒绝切换并提示。
 * （否则地图区会是一片空白 + 各家「未鉴权」水印，用户不知道发生了什么）
 */
private fun trySelectBase(context: android.content.Context, id: String?) {
    id ?: return
    val source = MapSourceStore.findSource(id) ?: return
    // 天地图走瓦片 API（tdt.*，needsKey=true），Key 在图源管理面板而非 manifest：
    // 没填 Key 时瓦片请求全部 403，地图会白屏，这里直接拦下并引导配置。
    if (source.needsKey && MapSourceStore.tiandituKey.isBlank()) {
        Toast.makeText(
            context,
            "尚未配置天地图 Key：请到「管理图源…」面板填写（免费，lbs.tianditu.gov.cn 申请）",
            Toast.LENGTH_LONG
        ).show()
        return
    }
    if (!MapEngineKeys.isConfigured(source.engineKind)) {
        Toast.makeText(context, MapEngineKeys.missingHint(source.engineKind), Toast.LENGTH_LONG)
            .show()
        return
    }
    MapSourceStore.selectBase(id)
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
    val context = LocalContext.current
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
            ) { id -> trySelectBase(context, id) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("叠加层")
            SourceGrid(
                sources = listOf<MapSource?>(null) + MapSourceStore.allOverlays(),
                selectedId = MapSourceStore.activeOverlayId
            ) { MapSourceStore.selectOverlay(it) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("轨迹颜色")
            TrackColorSection()
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

/**
 * 「轨迹颜色」区块：两行色块入口，点击弹出完整取色器。
 * - 导航参考轨迹：轨迹导航页要走的路线（已走/未走用透明度区分）
 * - 记录/生成轨迹：记录页实时轨迹、详情页历史轨迹、导航中实际走过的轨迹
 * 颜色存 TrackColorStore，各地图页订阅其 StateFlow，改完即时重绘。
 */
@Composable
private fun TrackColorSection() {
    val context = LocalContext.current
    val navColor by TrackColorStore.navColor.collectAsState()
    val liveColor by TrackColorStore.liveColor.collectAsState()
    var pickNav by remember { mutableStateOf(false) }
    var pickLive by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ColorRow(
            label = "导航参考轨迹",
            color = navColor,
            onClick = { pickNav = true }
        )
        ColorRow(
            label = "记录 / 生成轨迹",
            color = liveColor,
            onClick = { pickLive = true }
        )
    }

    if (pickNav) {
        ColorPickerDialog(
            title = "导航参考轨迹颜色",
            initialColor = navColor,
            defaultColor = TrackColorStore.DEFAULT_NAV,
            minAlpha = TrackColorStore.MIN_LINE_ALPHA / 255f,
            onConfirm = {
                TrackColorStore.setNavColor(context, it)
                pickNav = false
            },
            onDismiss = { pickNav = false }
        )
    }
    if (pickLive) {
        ColorPickerDialog(
            title = "记录 / 生成轨迹颜色",
            initialColor = liveColor,
            defaultColor = TrackColorStore.DEFAULT_LIVE,
            minAlpha = TrackColorStore.MIN_LINE_ALPHA / 255f,
            onConfirm = {
                TrackColorStore.setLiveColor(context, it)
                pickLive = false
            },
            onDismiss = { pickLive = false }
        )
    }
}

/** 颜色行：色块 + 名称 + 当前色值，整行可点 */
@Composable
private fun ColorRow(label: String, color: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(color))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            "#%06X".format(color and 0x00FFFFFF),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
                        when {
                            // 没有预览缩略图时的图标占位：离线区域用"下载"图标，一眼能认出
                            source == null -> Icons.Default.Clear
                            source.offlineRegionId != null -> Icons.Default.DownloadForOffline
                            else -> Icons.Default.Map
                        },
                        contentDescription = null,
                        tint = if (source == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            // 需 Key 提示角标：天地图没填 Key，或这张图对应的厂商 Key 还是占位符
            if (source?.needsKey == true && MapSourceStore.tiandituKey.isBlank() ||
                source?.let { !MapEngineKeys.isConfigured(it.engineKind) } == true
            ) {
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
