package com.example.myfirstapp.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.mapsources.GeoTransform
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 经纬度转换工具（「我的 → 工具 → 经纬度转换」）
 *
 * 两件事：
 * 1. 格式转换：小数度 ↔ 度分秒（116.397428 ⇄ E 116°23'50.74"），
 *    输入支持 "39.9087"、"39°54'31.3""、"39 54 31.3"、"N39.9087" 等常见写法；
 * 2. 坐标系转换：WGS84（GPS 原始）/ GCJ02（高德·腾讯·微信）/ BD09（百度）三者互转，
 *    一次输入同时给出三个坐标系的结果，方便对照偏差。
 *
 * 转换算法复用 [GeoTransform]（与地图叠加图源用的是同一套）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinateConverterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var lng by remember { mutableStateOf("116.397428") }
    var lat by remember { mutableStateOf("39.908722") }
    var source by remember { mutableIntStateOf(1) }   // 0=WGS84 1=GCJ02 2=BD09，默认 GCJ02（国内地图通用）

    val parsedLng = remember(lng) { parseCoordinate(lng) }
    val parsedLat = remember(lat) { parseCoordinate(lat) }

    // 先统一换算到 WGS84，再由 WGS84 推 GCJ02 / BD09（与地图内部口径一致）
    val wgs = remember(parsedLng, parsedLat, source) {
        val lng0 = parsedLng ?: return@remember null
        val lat0 = parsedLat ?: return@remember null
        when (source) {
            0 -> doubleArrayOf(lng0, lat0)
            1 -> GeoTransform.gcj02ToWgs84(lng0, lat0)
            else -> {
                val gcj02 = bd09ToGcj02(lng0, lat0)
                GeoTransform.gcj02ToWgs84(gcj02[0], gcj02[1])
            }
        }
    }
    val gcj = remember(wgs) { wgs?.let { GeoTransform.wgs84ToGcj02(it[0], it[1]) } }
    val bd = remember(gcj) { gcj?.let { GeoTransform.gcj02ToBd09(it[0], it[1]) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("经纬度转换") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("输入坐标", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = lng,
                        onValueChange = { raw ->
                            // 粘贴 "116.3974,39.9087" 这种整串时自动拆成两格
                            val parts = raw.split(",", "，")
                            if (parts.size >= 2) {
                                lng = parts[0]
                                lat = parts[1]
                            } else {
                                lng = raw
                            }
                        },
                        label = { Text("经度") },
                        placeholder = { Text("116.397428 或 E 116°23'50.7\"") },
                        singleLine = true,
                        isError = parsedLng == null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = lat,
                        onValueChange = { lat = it },
                        label = { Text("纬度") },
                        placeholder = { Text("39.908722 或 N 39°54'31.4\"") },
                        singleLine = true,
                        isError = parsedLat == null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "输入坐标系",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CoordChip("WGS84", source == 0) { source = 0 }
                        CoordChip("GCJ02", source == 1) { source = 1 }
                        CoordChip("BD09", source == 2) { source = 2 }
                    }
                }
            }

            if (wgs == null || gcj == null || bd == null) {
                HintCard("坐标格式无法识别，试试 39.9087 或 39°54'31.4\"")
            } else {
                CoordResultCard(
                    title = "WGS84（GPS 原始 / 国际通用）",
                    lng = wgs[0], lat = wgs[1],
                    onCopy = { copyText(context, "%.6f,%.6f".format(wgs[0], wgs[1])) }
                )
                CoordResultCard(
                    title = "GCJ02（高德 / 腾讯 / 微信）",
                    lng = gcj[0], lat = gcj[1],
                    onCopy = { copyText(context, "%.6f,%.6f".format(gcj[0], gcj[1])) }
                )
                CoordResultCard(
                    title = "BD09（百度地图）",
                    lng = bd[0], lat = bd[1],
                    onCopy = { copyText(context, "%.6f,%.6f".format(bd[0], bd[1])) }
                )
                if (GeoTransform.outOfChina(wgs[0], wgs[1])) {
                    HintCard("该坐标在中国境外，按规则不做 GCJ02 偏移，三个坐标系结果相同")
                }
            }
        }
    }
}

@Composable
private fun CoordResultCard(
    title: String,
    lng: Double,
    lat: Double,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制小数度",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            CoordLine("经度", "%.6f".format(lng), toDms(lng, false))
            Spacer(Modifier.height(6.dp))
            CoordLine("纬度", "%.6f".format(lat), toDms(lat, true))
        }
    }
}

@Composable
private fun CoordLine(label: String, decimal: String, dms: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.width(36.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(decimal, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                dms,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CoordChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Composable
private fun HintCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("coordinate", text))
    Toast.makeText(context, "已复制：$text", Toast.LENGTH_SHORT).show()
}

/**
 * 解析经纬度字符串，支持：
 * - 小数度：39.9087 / -39.9087 / N39.9087 / 39.9087N
 * - 度分秒：39°54'31.4" / 39 54 31.4 / 39,54,31.4（分隔符任意混合）
 * 解析不出返回 null（UI 用它标红输入框）。
 */
private fun parseCoordinate(raw: String): Double? {
    var s = raw.trim()
    if (s.isEmpty()) return null

    var sign = 1.0
    if (s.startsWith("-")) {
        sign = -1.0
        s = s.substring(1).trim()
    }
    // 去掉方位字母（N/S/E/W，前后都可能出现；S/W 表示南/西 → 负号）
    fun isHemisphere(c: Char?): Boolean =
        c == 'N' || c == 'S' || c == 'E' || c == 'W'
    fun negative(c: Char?): Boolean = c == 'S' || c == 'W'

    if (isHemisphere(s.firstOrNull()?.uppercaseChar())) {
        if (negative(s.first().uppercaseChar())) sign = -1.0
        s = s.substring(1)
    }
    if (isHemisphere(s.lastOrNull()?.uppercaseChar())) {
        if (negative(s.last().uppercaseChar())) sign = -1.0
        s = s.dropLast(1)
    }

    // 度分秒符号统一成空格后切分
    val normalized = s
        .replace('°', ' ')
        .replace('\'', ' ')
        .replace('"', ' ')
        .replace('′', ' ')
        .replace('″', ' ')
        .replace('’', ' ')
        .replace('“', ' ')
        .replace('”', ' ')
        .replace(',', ' ')
        .trim()

    val parts = normalized.split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
    if (parts.isEmpty()) return null
    val deg = kotlin.math.abs(parts[0])
    val min = parts.getOrNull(1) ?: 0.0
    val sec = parts.getOrNull(2) ?: 0.0
    if (min >= 60 || sec >= 60) return null
    return sign * (deg + min / 60.0 + sec / 3600.0)
}

/** 小数度 → 度分秒字符串（isLat 决定用 N/S 还是 E/W） */
private fun toDms(value: Double, isLat: Boolean): String {
    val hemisphere = if (isLat) {
        if (value >= 0) "N" else "S"
    } else {
        if (value >= 0) "E" else "W"
    }
    val abs = kotlin.math.abs(value)
    val d = abs.toInt()
    val minFull = (abs - d) * 60
    val m = minFull.toInt()
    val s = (minFull - m) * 60
    return "%s %d°%02d'%05.2f\"".format(hemisphere, d, m, s)
}

/** BD09 → GCJ02（GeoTransform 只给了反向的封闭公式，这里是它的标准逆运算） */
private fun bd09ToGcj02(lng: Double, lat: Double): DoubleArray {
    val x = lng - 0.0065
    val y = lat - 0.006
    val z = sqrt(x * x + y * y) - 0.00002 * sin(y * GeoTransform.X_PI)
    val theta = atan2(y, x) - 0.000003 * cos(x * GeoTransform.X_PI)
    return doubleArrayOf(z * cos(theta), z * sin(theta))
}
