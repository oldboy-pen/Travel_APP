package com.example.myfirstapp.globe

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myfirstapp.mapsources.MapSourceStore
import kotlin.math.abs

/**
 * 3D 地球：自由旋转 / 缩放看全球地形的一屏（OpenGL ES 自绘，不依赖任何地图 SDK）。
 *
 * - 贴图用现有「瓦片图源」体系：默认高德卫星影像（GCJ-02 瓦片号自动重映射、国内直连稳定），
 *   另有高德卫星路网/矢量、天地图地形/卫星/矢量（需 Key）、OpenTopoMap 等高线（境外服务器，
 *   国内大概率超时）、以及用户导入的自定义 XYZ 图源都能直接贴到球面上。
 * - 「山体阴影」开关叠一份高程图做实时渲染的山体立体感（高程源不可达时自动关闭）。
 * - 从运动页进来时会把当前记录的轨迹、已加载的参考轨迹和当前位置一起画到球上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = rememberGlobeView()
    val renderer = remember(view) { view.renderer }

    MapSourceStore.ensureLoaded(context)
    val sources = remember { GlobeSources.candidates(context) }
    var selected by remember { mutableStateOf(GlobeSources.preferred(context)) }
    var hillshade by remember { mutableStateOf(false) }
    var center by remember { mutableStateOf<LonLat?>(null) }
    var altitudeKm by remember { mutableStateOf(0.0) }
    var homeMarker by remember { mutableStateOf<LonLat?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    DisposableEffect(view) {
        renderer.onCameraChanged = { ll, km ->
            center = ll
            altitudeKm = km
        }
        renderer.onDemUnavailable = {
            hillshade = false
            Toast.makeText(
                context,
                "高程数据拉不动，已关闭山体阴影（不影响地图贴图）",
                Toast.LENGTH_LONG
            ).show()
        }
        onDispose {
            renderer.onCameraChanged = null
            renderer.onDemUnavailable = null
        }
    }

    // 首帧：挂上运动页带过来的数据，并把镜头落到目标上
    LaunchedEffect(view) {
        val (lines, marker) = PendingGlobeData.take()
        homeMarker = marker
        renderer.setOverlay(lines, marker)
        val target = marker ?: lines.firstOrNull()?.points?.firstOrNull()
        if (target != null) renderer.lookAt(target.lon, target.lat, 1.35f)
    }

    LaunchedEffect(selected) { renderer.setSource(selected) }
    LaunchedEffect(hillshade) { renderer.setHillshade(hillshade) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("3D 地球") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {

            GlobeMap(view = view, modifier = Modifier.fillMaxSize())

            // ---- 左上：视角信息 + 当前图源 ----
            Card(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC0D1520.toInt()))
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        "视角中心  ${selected.name}",
                        color = Color(0xCCFFFFFF.toInt()),
                        fontSize = 11.sp
                    )
                    Text(
                        fmtLonLat(center),
                        color = Color.White,
                        fontSize = 13.sp,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "相机高度 %.0f km".format(altitudeKm),
                        color = Color(0x99FFFFFF.toInt()),
                        fontSize = 11.sp
                    )
                }
            }

            // ---- 右上：图层切换 / 山体阴影 / 全景 / 回到我的位置 ----
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End
            ) {
                Box {
                    SmallFloatingActionButton(
                        onClick = { menuExpanded = true },
                        containerColor = Color.White
                    ) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = "切换图源",
                            tint = Color(0xFF2E7D32.toInt())
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        sources.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.name) },
                                onClick = {
                                    selected = s
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }

                SmallFloatingActionButton(
                    onClick = { hillshade = !hillshade },
                    containerColor = if (hillshade) Color(0xFF8D6E63.toInt()) else Color.White
                ) {
                    Icon(
                        Icons.Default.Terrain,
                        contentDescription = "山体阴影",
                        tint = if (hillshade) Color.White else Color(0xFF2E7D32.toInt())
                    )
                }

                SmallFloatingActionButton(
                    onClick = {
                        val c = center
                        if (c != null) renderer.lookAt(c.lon, c.lat, 3.2f)
                    },
                    containerColor = Color.White
                ) {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = "回到全景",
                        tint = Color(0xFF2E7D32.toInt())
                    )
                }

                SmallFloatingActionButton(
                    onClick = {
                        val m = homeMarker
                        if (m != null) renderer.lookAt(m.lon, m.lat, 1.35f)
                        else Toast.makeText(context, "还没有定位信息", Toast.LENGTH_SHORT).show()
                    },
                    containerColor = Color.White
                ) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = "回到我的位置",
                        tint = Color(0xFF2E7D32.toInt())
                    )
                }
            }

            // ---- 底部：图源署名 + 手势说明 ----
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC0D1520.toInt()))
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(
                        selected.attribution.ifBlank { "拖动旋转 · 双指缩放 · 双击贴近" },
                        color = Color(0xCCFFFFFF.toInt()),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

private fun fmtLonLat(ll: LonLat?): String {
    if (ll == null) return "转动中…"
    val ns = if (ll.lat >= 0) "N" else "S"
    val ew = if (ll.lon >= 0) "E" else "W"
    return "%.4f°%s  %.4f°%s".format(abs(ll.lat), ns, abs(ll.lon), ew)
}
