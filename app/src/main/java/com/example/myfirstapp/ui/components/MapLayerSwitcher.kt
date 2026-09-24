package com.example.myfirstapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amap.api.maps.AMap

/**
 * 地图图层枚举：
 * - NORMAL  矢量图（默认）
 * - SATELLITE 卫星图（SDK 内置）
 * - NIGHT   夜景图（SDK 内置）
 * - SATELLITE_TERRAIN 卫星图+路网（登山户外常用：影像底图 + 道路地名标注）
 * - TERRAIN 等高线地形图（OpenTopoMap 瓦片叠加；⚠ 国内网络目前不可达，瓦片加载不出，
 *           代码保留待后期接入可达瓦片源，如天地图地形层）
 */
enum class MapLayerMode(val label: String) {
    NORMAL("矢量"),
    SATELLITE("卫星"),
    NIGHT("夜景"),
    SATELLITE_TERRAIN("卫星路网"),
    TERRAIN("等高线(预留)")
}

/**
 * 应用图层模式到高德地图实例。
 * @param tileOverlayHolder 上一次创建的瓦片图层持有者（外部 remember { mutableStateOf(null) }），
 *        用于切换模式时移除叠加层。
 */
fun applyMapLayer(
    aMap: AMap,
    mode: MapLayerMode,
    tileOverlayHolder: MutableState<com.amap.api.maps.model.TileOverlay?>
) {
    // 先移除旧的叠加层
    tileOverlayHolder.value?.remove()
    tileOverlayHolder.value = null

    when (mode) {
        MapLayerMode.NORMAL -> aMap.mapType = AMap.MAP_TYPE_NORMAL
        MapLayerMode.SATELLITE -> aMap.mapType = AMap.MAP_TYPE_SATELLITE
        MapLayerMode.NIGHT -> aMap.mapType = AMap.MAP_TYPE_NIGHT
        MapLayerMode.SATELLITE_TERRAIN -> {
            // 卫星底图 + 路网地名透明层叠加（高德官方瓦片服务，含中文路名/地名，无坐标系偏差）
            aMap.mapType = AMap.MAP_TYPE_SATELLITE
            val overlay = aMap.addTileOverlay(
                com.amap.api.maps.model.TileOverlayOptions()
                    .tileProvider(object : com.amap.api.maps.model.UrlTileProvider(256, 256) {
                        // style=7 是高德路网透明层（白线道路+地名标注），叠加在卫星图上
                        override fun getTileUrl(x: Int, y: Int, zoom: Int): java.net.URL? =
                            runCatching {
                                java.net.URL("https://wprd0$((x + y) % 4).is.autonavi.com/appmaptile?style=7&x=$x&y=$y&z=$zoom")
                            }.getOrNull()
                    })
                    .zIndex(0f)
                    .diskCacheEnabled(true)
                    .memCacheSize(10 * 1024 * 1024)
            )
            tileOverlayHolder.value = overlay
        }
        MapLayerMode.TERRAIN -> {
            // 等高线：OpenTopoMap 瓦片叠加（当前国内不可达，仅保留实现，后期换可达瓦片源）
            aMap.mapType = AMap.MAP_TYPE_NORMAL
            val overlay = aMap.addTileOverlay(
                com.amap.api.maps.model.TileOverlayOptions()
                    .tileProvider(object : com.amap.api.maps.model.UrlTileProvider(256, 256) {
                        override fun getTileUrl(x: Int, y: Int, zoom: Int): java.net.URL? =
                            runCatching {
                                java.net.URL("https://tile.opentopomap.org/$zoom/$x/$y.png")
                            }.getOrNull()
                    })
                    .zIndex(-1f)
                    .diskCacheEnabled(true)
                    .memCacheSize(10 * 1024 * 1024)
            )
            tileOverlayHolder.value = overlay
        }
    }
}

/**
 * 图层切换控件：右侧小按钮展开/收起图层选择列表。
 * 放在地图右上角（避开顶部数据面板和右侧定位按钮）。
 */
@Composable
fun MapLayerSwitcher(
    current: MapLayerMode,
    onSelect: (MapLayerMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End
    ) {
        if (expanded) {
            // 图层选项列表
            Column(
                modifier = Modifier
                    .widthIn(min = 88.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.End
            ) {
                MapLayerMode.entries.forEach { mode ->
                    TextButton(
                        onClick = {
                            onSelect(mode)
                            expanded = false
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)
                    ) {
                        if (mode == current) {
                            Text(
                                mode.label, fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        } else {
                            Text(mode.label, fontSize = 14.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // 图层按钮
        SmallFloatingActionButton(
            onClick = { expanded = !expanded },
            containerColor = Color.White
        ) {
            Icon(
                if (expanded) Icons.Default.Clear else Icons.Default.Layers,
                contentDescription = "切换图层",
                tint = Color(0xFF2E7D32)
            )
        }
    }
}
