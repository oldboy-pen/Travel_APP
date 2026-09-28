package com.example.myfirstapp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
// 注意：var xxx by remember { mutableStateOf(...) } 依赖下面这两个委托扩展，
// 删掉会报 "Property delegate must have a getValue/setValue method"
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myfirstapp.mapsources.MapSourceStore

/**
 * 当前图源署名标签。
 *
 * 背景：地图引擎始终是高德 SDK（第三方瓦片只是叠在高德矢量底图之上），
 * 左下角那枚「高德地图」logo 由 SDK 绘制 —— 遮挡/隐藏它违反高德合规要求，
 * 而且叠加了腾讯/百度/OSM 瓦片后只看到"高德地图"会误导用户。
 *
 * 解决：在左下角（或底部面板内）追加这枚标签，实时显示真正的底图 + 叠加层归属，
 * 点击还能直接打开图层选择面板。
 *
 * @param floating true=悬浮在地图上的胶囊（半透明白底）；false=嵌在底部面板里（无背景）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapAttribution(
    modifier: Modifier = Modifier,
    floating: Boolean = true
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var showManager by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { MapSourceStore.ensureLoaded(context) }
    // revision 变化时重组（切图源 / 增删自定义源）
    MapSourceStore.revision

    val base = MapSourceStore.activeBase()
    val overlay = MapSourceStore.activeOverlay()
    val attribution = base.attribution.ifBlank { "第三方图源" }
    val overlayText = overlay?.let { " + ${it.name}" }.orEmpty()

    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Layers,
                contentDescription = null,
                tint = if (floating) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "${base.name}$overlayText · $attribution",
                fontSize = if (floating) 10.sp else 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (floating) Color(0xFF424242) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (floating) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.White.copy(alpha = 0.88f),
            modifier = modifier
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { expanded = true }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) { content() }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .clickable { expanded = true }
                .padding(vertical = 2.dp)
        ) { content() }
    }

    // 点击标签 → 打开图层面板（与右上角按钮一致的呈现方式）
    if (expanded) {
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
