package com.example.myfirstapp.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.myfirstapp.mapsources.MapSource
import com.example.myfirstapp.mapsources.MapSourceQr
import com.example.myfirstapp.mapsources.MapSourceStore
import com.example.myfirstapp.mapsources.TileCrs
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** 生成二维码位图（内容较长时自动放大尺寸保证可扫） */
fun generateQrBitmap(content: String, size: Int = 640): Bitmap? = runCatching {
    val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (bits[x, y]) Color.BLACK else Color.WHITE
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}.getOrNull()

/**
 * 图源管理面板：天地图 Key、自定义图源增删改、扫码/手动添加、二维码分享。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSourceManagerSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current

    // 编辑对话框（null=关闭）；initial.id 空=新增
    var editTarget by remember { mutableStateOf<MapSource?>(null) }
    // 二维码分享对话框
    var shareSource by remember { mutableStateOf<MapSource?>(null) }
    // 删除确认
    var deleteTarget by remember { mutableStateOf<MapSource?>(null) }

    // ---- 扫码 ----
    val scanLauncher = rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        val content = result.contents
        if (content.isNullOrBlank()) return@rememberLauncherForActivityResult
        val parsed = MapSourceQr.parse(content)
        if (parsed == null) {
            android.widget.Toast.makeText(context, "二维码内容不是有效图源", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            editTarget = parsed // 打开确认对话框，允许修改后保存
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanLauncher.launch(
                com.journeyapps.barcodescanner.ScanOptions().apply {
                    setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                    setPrompt("对准图源二维码")
                    setBeepEnabled(false)
                    setOrientationLocked(true)
                }
            )
        } else {
            android.widget.Toast.makeText(context, "需要相机权限才能扫码", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    fun startScan() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            scanLauncher.launch(
                com.journeyapps.barcodescanner.ScanOptions().apply {
                    setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                    setPrompt("对准图源二维码")
                    setBeepEnabled(false)
                    setOrientationLocked(true)
                }
            )
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Text("图源管理", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            // ---- 天地图 Key ----
            TiandituKeySection()

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // ---- 自定义图源 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自定义图源", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { startScan() }) {
                    Icon(Icons.Default.QrCodeScanner, "扫码添加", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { editTarget = MapSource(id = "", name = "", urlTemplate = "") }) {
                    Icon(Icons.Default.Edit, "手动添加", tint = MaterialTheme.colorScheme.primary)
                }
            }
            val custom = MapSourceStore.customSources
            if (custom.isEmpty()) {
                Text(
                    "暂无自定义图源。点右上角扫码 / 手动添加，或在下方按钮添加。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                custom.forEach { src ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(src.name, fontSize = 15.sp)
                            Text(
                                buildString {
                                    append(src.crs.name)
                                    if (src.isOverlay) append(" · 叠加层")
                                    append(" · z${src.minZoom}-${src.maxZoom}")
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = { shareSource = src }) {
                            Icon(Icons.Default.QrCode2, "分享二维码", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { editTarget = src }) {
                            Icon(Icons.Default.Edit, "编辑")
                        }
                        IconButton(onClick = { deleteTarget = src }) {
                            Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row {
                OutlinedButton(onClick = { startScan() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("扫码添加")
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { editTarget = MapSource(id = "", name = "", urlTemplate = "") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("手动添加")
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "URL 模板占位符：{z} 级别 {x} 列 {y} 行 {-y} 反转行(腾讯) {s} 子域 {tk} 天地图Key",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // ---- 编辑/新增对话框 ----
    editTarget?.let { initial ->
        MapSourceEditDialog(
            initial = initial,
            onSave = { src ->
                MapSourceStore.upsertCustom(src)
                editTarget = null
                android.widget.Toast.makeText(context, "已保存「${src.name}」", android.widget.Toast.LENGTH_SHORT).show()
            },
            onDismiss = { editTarget = null }
        )
    }

    // ---- 分享二维码 ----
    shareSource?.let { src ->
        val qr = remember(src.id) { generateQrBitmap(MapSourceQr.toJson(src)) }
        AlertDialog(
            onDismissRequest = { shareSource = null },
            title = { Text("分享「${src.name}」") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (qr != null) {
                        Image(qr.asImageBitmap(), null, Modifier.size(260.dp))
                    } else {
                        Text("二维码生成失败")
                    }
                    Text(
                        "用另一台设备的「撒野」扫码即可添加此图源",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { shareSource = null }) { Text("关闭") } }
        )
    }

    // ---- 删除确认 ----
    deleteTarget?.let { src ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除图源") },
            text = { Text("确定删除「${src.name}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    MapSourceStore.removeCustom(src.id)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun TiandituKeySection() {
    var key by remember { mutableStateOf(MapSourceStore.tiandituKey) }
    Column {
        Text("天地图 Key", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "天地图矢量/卫星/地形/标注需免费 Key。到 lbs.tianditu.gov.cn → 控制台 → 创建应用（类型选「Android端」或「浏览器端」均可）复制 Key 填到这里。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("粘贴天地图 Key") },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { MapSourceStore.updateTiandituKey(key) }) { Text("保存") }
        }
    }
}

/**
 * 图源编辑对话框：新增/编辑共用。保存时校验名称与 URL 模板。
 */
@Composable
fun MapSourceEditDialog(
    initial: MapSource,
    onSave: (MapSource) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.urlTemplate) }
    var crs by remember { mutableStateOf(initial.crs) }
    var minZoom by remember { mutableStateOf(initial.minZoom.toString()) }
    var maxZoom by remember { mutableStateOf(initial.maxZoom.toString()) }
    var subdomains by remember { mutableStateOf(initial.subdomains) }
    var overlay by remember { mutableStateOf(initial.isOverlay) }
    var crsMenuOpen by remember { mutableStateOf(false) }

    val urlOk = url.contains("{z}") && url.contains("{x}") && url.contains("{y}") &&
            (url.startsWith("http://") || url.startsWith("https://"))
    val nameOk = name.isNotBlank()
    val minZ = minZoom.toIntOrNull()?.coerceIn(1, 22)
    val maxZ = maxZoom.toIntOrNull()?.coerceIn(1, 22)
    val zoomOk = minZ != null && maxZ != null && minZ <= maxZ

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "添加图源" else "编辑图源") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true,
                    isError = !nameOk
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("URL 模板") },
                    supportingText = {
                        Text(
                            if (urlOk) "示例：https://example.com/{z}/{x}/{y}.png" else "需以 http(s):// 开头且包含 {z} {x} {y}",
                            fontSize = 11.sp
                        )
                    },
                    isError = !urlOk,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                Spacer(Modifier.height(8.dp))
                // 坐标系
                Box {
                    OutlinedButton(onClick = { crsMenuOpen = true }) {
                        Text("坐标系：${crs.name}", fontSize = 13.sp)
                    }
                    DropdownMenu(expanded = crsMenuOpen, onDismissRequest = { crsMenuOpen = false }) {
                        TileCrs.entries.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.label, fontSize = 13.sp) },
                                onClick = { crs = c; crsMenuOpen = false }
                            )
                        }
                    }
                }
                Text(
                    "选错坐标系会偏移几百米：高德/腾讯选 GCJ-02，天地图/OSM 选 WGS-84，百度瓦片选 BD-09",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minZoom, onValueChange = { minZoom = it.filter { ch -> ch.isDigit() } },
                        label = { Text("最小级别") }, singleLine = true,
                        modifier = Modifier.weight(1f), isError = minZ == null
                    )
                    OutlinedTextField(
                        value = maxZoom, onValueChange = { maxZoom = it.filter { ch -> ch.isDigit() } },
                        label = { Text("最大级别") }, singleLine = true,
                        modifier = Modifier.weight(1f), isError = maxZ == null
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = subdomains, onValueChange = { subdomains = it },
                    label = { Text("子域名 {s}（可空，如 0123）") }, singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = overlay, onCheckedChange = { overlay = it })
                    Spacer(Modifier.width(8.dp))
                    Text("作为叠加层（半透明标注层，叠在底图上）", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = urlOk && nameOk && zoomOk,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            urlTemplate = url.trim(),
                            crs = crs,
                            minZoom = minZ ?: 3,
                            maxZoom = maxZ ?: 18,
                            subdomains = subdomains.trim(),
                            isOverlay = overlay
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
