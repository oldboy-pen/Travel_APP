package com.example.myfirstapp.ui.screens

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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.example.myfirstapp.track.GeoUtils

/**
 * 配速换算工具（「我的 → 工具 → 配速换算」）
 *
 * 三种用法，覆盖运动里最常见的换算需求：
 * 1. 距离 + 用时 → 配速 / 均速（跑完了算配速）
 * 2. 配速 + 距离 → 预计用时（赛前算完赛时间）
 * 3. 速度 ↔ 配速 互转（骑行码表 km/h 与跑步配速对不上时用）
 *
 * 纯本地计算，不涉及定位与网络。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaceConverterScreen(onBack: () -> Unit) {
    var mode by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配速换算") },
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("用时算配速", mode == 0) { mode = 0 }
                ModeChip("配速算用时", mode == 1) { mode = 1 }
                ModeChip("速度↔配速", mode == 2) { mode = 2 }
            }

            when (mode) {
                0 -> DistanceTimeToPace()
                1 -> PaceToFinishTime()
                else -> SpeedPaceSwap()
            }
        }
    }
}

/** 模式一：距离 + 用时 → 配速、均速、常见距离预估 */
@Composable
private fun DistanceTimeToPace() {
    var distance by remember { mutableStateOf("10") }
    var hour by remember { mutableStateOf("0") }
    var minute by remember { mutableStateOf("52") }
    var second by remember { mutableStateOf("0") }

    val km = distance.toDoubleOrNull() ?: 0.0
    val totalSec = (hour.toLongOrNull() ?: 0L) * 3600 +
        (minute.toLongOrNull() ?: 0L) * 60 +
        (second.toLongOrNull() ?: 0L)
    val pace = if (km > 0 && totalSec > 0) totalSec / km else null   // 秒/公里

    InputCard(title = "距离", icon = { Icon(Icons.Default.Straighten, null) }) {
        OutlinedTextField(
            value = distance,
            onValueChange = { distance = sanitizeNumber(it) },
            label = { Text("公里") },
            suffix = { Text("km") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
    }

    InputCard(title = "用时", icon = { Icon(Icons.Default.Timer, null) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField("时", hour, { hour = it }, Modifier.weight(1f))
            TimeField("分", minute, { minute = it }, Modifier.weight(1f))
            TimeField("秒", second, { second = it }, Modifier.weight(1f))
        }
    }

    if (pace == null) {
        HintCard("填入距离与用时后自动计算")
    } else {
        ResultCard(
            items = listOf(
                "配速" to "${formatPace(pace)}/km",
                "均速" to "%.2f km/h".format(3600.0 / pace),
                "每 400 米" to GeoUtils.formatDuration((pace * 0.4).toLong() * 1000),
                "半马预估" to GeoUtils.formatDuration((pace * 21.0975).toLong() * 1000),
                "全马预估" to GeoUtils.formatDuration((pace * 42.195).toLong() * 1000)
            )
        )
    }
}

/** 模式二：配速 + 距离 → 预计用时 */
@Composable
private fun PaceToFinishTime() {
    var paceMin by remember { mutableStateOf("5") }
    var paceSec by remember { mutableStateOf("30") }
    var distance by remember { mutableStateOf("10") }

    val pace = (paceMin.toLongOrNull() ?: 0L) * 60 + (paceSec.toLongOrNull() ?: 0L)
    val km = distance.toDoubleOrNull() ?: 0.0

    InputCard(title = "配速", icon = { Icon(Icons.Default.Speed, null) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField("分", paceMin, { paceMin = it }, Modifier.weight(1f))
            TimeField("秒", paceSec, { paceSec = it }, Modifier.weight(1f))
        }
    }

    InputCard(title = "目标距离", icon = { Icon(Icons.Default.Straighten, null) }) {
        OutlinedTextField(
            value = distance,
            onValueChange = { distance = sanitizeNumber(it) },
            label = { Text("公里") },
            suffix = { Text("km") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5.0, 10.0, 21.0975, 42.195).forEach { d ->
                androidx.compose.material3.OutlinedButton(
                    onClick = { distance = d.toString() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (d == 21.0975) "半马" else if (d == 42.195) "全马" else "${d.toInt()}k")
                }
            }
        }
    }

    if (pace <= 0 || km <= 0) {
        HintCard("填入配速与距离后自动计算")
    } else {
        val totalSec = (pace * km).toLong()
        ResultCard(
            items = listOf(
                "预计用时" to GeoUtils.formatDuration(totalSec * 1000),
                "均速" to "%.2f km/h".format(3600.0 / pace),
                "5 公里分段" to GeoUtils.formatDuration((pace * 5).toLong() * 1000),
                "10 公里分段" to GeoUtils.formatDuration((pace * 10).toLong() * 1000),
                "每公里用时" to formatPace(pace.toDouble())
            )
        )
    }
}

/** 模式三：速度 ↔ 配速 互转（两个方向各自独立输入，互不联动） */
@Composable
private fun SpeedPaceSwap() {
    var speed by remember { mutableStateOf("12") }
    var paceMin by remember { mutableStateOf("6") }
    var paceSec by remember { mutableStateOf("0") }

    val kmh = speed.toDoubleOrNull() ?: 0.0
    val pace = (paceMin.toLongOrNull() ?: 0L) * 60 + (paceSec.toLongOrNull() ?: 0L)

    InputCard(title = "速度 → 配速", icon = { Icon(Icons.Default.Speed, null) }) {
        OutlinedTextField(
            value = speed,
            onValueChange = { speed = sanitizeNumber(it) },
            label = { Text("公里/小时") },
            suffix = { Text("km/h") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        if (kmh > 0) {
            ResultCard(
                items = listOf(
                    "配速" to "${formatPace(3600.0 / kmh)}/km",
                    "米/秒" to "%.2f m/s".format(kmh / 3.6),
                    "10 公里用时" to GeoUtils.formatDuration((3600.0 / kmh * 10).toLong() * 1000)
                )
            )
        }
    }

    InputCard(title = "配速 → 速度", icon = { Icon(Icons.Default.Timer, null) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField("分", paceMin, { paceMin = it }, Modifier.weight(1f))
            TimeField("秒", paceSec, { paceSec = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        if (pace > 0) {
            ResultCard(
                items = listOf(
                    "速度" to "%.2f km/h".format(3600.0 / pace),
                    "米/秒" to "%.2f m/s".format(3600.0 / pace / 3.6),
                    "配速" to "${formatPace(pace.toDouble())}/km"
                )
            )
        }
    }
}

// ---------- 通用小组件 ----------

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
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
private fun InputCard(
    title: String,
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
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
                    icon()
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun TimeField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun ResultCard(items: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            items.forEach { (k, v) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        k,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        v,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
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

/** 秒/公里 → 5'30" */
private fun formatPace(secPerKm: Double): String {
    if (secPerKm <= 0 || secPerKm.isNaN() || secPerKm.isInfinite()) return "--"
    val total = secPerKm.toLong()
    return "%d'%02d\"".format(total / 60, total % 60)
}

/** 只保留数字与一个小数点，避免 "1.2.3"、中文输入等把计算搞崩 */
private fun sanitizeNumber(input: String): String {
    val filtered = input.filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    if (firstDot < 0) return filtered
    return filtered.substring(0, firstDot + 1) +
        filtered.substring(firstDot + 1).replace(".", "")
}
