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
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.example.myfirstapp.mapsources.MapSourceImporter

/** 把「Key: Value」多行文本解析为请求头 Map（空行/无冒号跳过） */
private fun parseHeaders(text: String): Map<String, String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() && it.contains(":") }
        .mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()

/** 把 BitMatrix 画成位图：黑条白底 */
private fun renderBits(bits: BitMatrix): Bitmap {
    val w = bits.width
    val h = bits.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            pixels[y * w + x] = if (bits[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.RGB_565)
}

/** Code128 在 zxing 里的硬限制：内容最长 80 字符 */
private const val CODE128_MAX_LEN = 80

/**
 * 生成图源分享码位图：优先条形码（Code128，窄条、易扫）；
 * URL 过长（超过 Code128 容量）时自动回退方形二维码。
 */
fun generateShareCodeBitmap(source: MapSource, size: Int = 640): Bitmap? = runCatching {
    val payload = MapSourceQr.toBarcodePayload(source)
    if (payload.length <= CODE128_MAX_LEN) {
        // 宽度随内容增长，保证每条 ≥2px，屏幕上更好扫
        val width = (payload.length * 24).coerceIn(1000, 2400)
        renderBits(MultiFormatWriter().encode(payload, BarcodeFormat.CODE_128, width, 200))
    } else {
        renderBits(QRCodeWriter().encode(MapSourceQr.toJson(source), BarcodeFormat.QR_CODE, size, size))
    }
}.getOrNull()

/**
 * 图源管理面板：天地图 Key、自定义图源增删改、扫码/手动添加、条码分享。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSourceManagerSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current

    // 编辑对话框（null=关闭）；initial.id 空=新增
    var editTarget by remember { mutableStateOf<MapSource?>(null) }
    // 分享码对话框（优先条码，过长回退二维码）
    var shareSource by remember { mutableStateOf<MapSource?>(null) }
    // 删除确认
    var deleteTarget by remember { mutableStateOf<MapSource?>(null) }

    // ---- 导入（文件 / 粘贴） ----
    var pendingImport by remember { mutableStateOf<List<MapSource>?>(null) }
    var pasteOpen by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            android.widget.Toast.makeText(context, "读取文件失败", android.widget.Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val list = MapSourceImporter.importText(text)
        if (list.isEmpty()) {
            android.widget.Toast.makeText(context, "无法识别该文件中的图源", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            pendingImport = list
        }
    }

    // ---- 扫码 ----
    val scanLauncher = rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        val content = result.contents
        if (content.isNullOrBlank()) return@rememberLauncherForActivityResult
        val parsed = MapSourceQr.parse(content)
        if (parsed == null) {
            android.widget.Toast.makeText(context, "扫码内容不是有效图源", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            if (parsed.urlTemplate.isBlank()) {
                // 两步路分享码：u 参数被加密，名称/坐标系/层级已预填，缺瓦片地址
                android.widget.Toast.makeText(
                    context, "已识别两步路分享码，瓦片地址被其加密，请在下方粘贴瓦片地址",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            editTarget = parsed // 打开确认对话框，允许修改后保存
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanLauncher.launch(
                com.journeyapps.barcodescanner.ScanOptions().apply {
                    // 同时识别条码和二维码（URL 过长时分享端回退二维码）
                    setDesiredBarcodeFormats(
                        com.journeyapps.barcodescanner.ScanOptions.ONE_D_CODE_TYPES +
                            com.journeyapps.barcodescanner.ScanOptions.QR_CODE
                    )
                    setCaptureActivity(ScanCaptureActivity::class.java)
                    setPrompt("对准图源条码 / 二维码")
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
                    // 同时识别条码和二维码（URL 过长时分享端回退二维码）
                    setDesiredBarcodeFormats(
                        com.journeyapps.barcodescanner.ScanOptions.ONE_D_CODE_TYPES +
                            com.journeyapps.barcodescanner.ScanOptions.QR_CODE
                    )
                    setCaptureActivity(ScanCaptureActivity::class.java)
                    setPrompt("对准图源条码 / 二维码")
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
                    Icon(Icons.Default.QrCodeScanner, "扫条码/二维码添加", tint = MaterialTheme.colorScheme.primary)
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
                    Icon(Icons.Default.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("扫条码/二维码添加")
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { editTarget = MapSource(id = "", name = "", urlTemplate = "") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("手动添加")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("导入文件")
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = { pasteOpen = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("粘贴图源")
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "支持两步路(.xms/.xml)、奥维(.xml/.ovmap)、通用 XYZ 文本。导入后可在列表中编辑坐标系 / 防盗链头。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

    // ---- 分享码（条码 / 二维码） ----
    shareSource?.let { src ->
        val qr = remember(src) { generateShareCodeBitmap(src) }
        AlertDialog(
            onDismissRequest = { shareSource = null },
            title = { Text("分享「${src.name}」") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (qr != null) {
                        Image(
                            qr.asImageBitmap(),
                            null,
                            Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("分享码生成失败")
                    }
                    Text(
                        if (qr != null && qr.width > qr.height) {
                            "用另一台设备的「撒野」扫描此条码即可添加图源"
                        } else {
                            "用另一台设备的「撒野」扫描此二维码即可添加图源"
                        },
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

    // ---- 导入确认（批量） ----
    pendingImport?.let { list ->
        var crs by remember { mutableStateOf(TileCrs.GCJ02) }
        var menuOpen by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("确认导入 ${list.size} 个图源") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    list.forEach { src ->
                        Text("· ${src.name}  (z${src.minZoom}-${src.maxZoom})", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box {
                        OutlinedButton(onClick = { menuOpen = true }) {
                            Text("坐标系：${crs.name}", fontSize = 13.sp)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            TileCrs.entries.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.label, fontSize = 13.sp) },
                                    onClick = { crs = c; menuOpen = false }
                                )
                            }
                        }
                    }
                    Text(
                        "坐标系选错会偏移几百米：高德/腾讯/天地图国内选 GCJ-02，OSM/国际源选 WGS-84，百度瓦片选 BD-09",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    list.forEach { MapSourceStore.upsertCustom(it.copy(crs = crs)) }
                    android.widget.Toast.makeText(
                        context, "已导入 ${list.size} 个图源", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    pendingImport = null
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } }
        )
    }

    // ---- 粘贴图源文本 ----
    if (pasteOpen) {
        var txt by remember(pasteOpen) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasteOpen = false },
            title = { Text("粘贴图源代码 / 文本") },
            text = {
                OutlinedTextField(
                    value = txt, onValueChange = { txt = it },
                    label = { Text("两步路 XML / 奥维 XML 或 ovmap / XYZ 地址") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    singleLine = false
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val list = MapSourceImporter.importText(txt)
                    pasteOpen = false
                    if (list.isEmpty()) {
                        android.widget.Toast.makeText(
                            context, "无法识别文本中的图源", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        pendingImport = list
                    }
                }) { Text("解析") }
            },
            dismissButton = { TextButton(onClick = { pasteOpen = false }) { Text("取消") } }
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
    var headers by remember {
        mutableStateOf(initial.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }
    var overlay by remember { mutableStateOf(initial.isOverlay) }
    var crsMenuOpen by remember { mutableStateOf(false) }

    // 归一化两步路/奥维风格占位符（{$x}→{x}、{host}→{s}、&amp;→&），校验与保存都用归一化结果
    val normalizedUrl = MapSourceImporter.normalizeUrl(url.trim())
    val urlOk = normalizedUrl.contains("{z}") && normalizedUrl.contains("{x}") && normalizedUrl.contains("{y}") &&
            (normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://"))
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
                            if (urlOk) "示例：https://example.com/{z}/{x}/{y}.png"
                            else "需以 http(s):// 开头且包含 {z} {x} {y}（兼容两步路写法 {\$x}/{\$y}/{\$z}）",
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
                OutlinedTextField(
                    value = headers, onValueChange = { headers = it },
                    label = { Text("自定义请求头（防盗链，可空）") },
                    supportingText = {
                        Text("每行一个，格式「Key: Value」，如 Referer: https://x.com", fontSize = 11.sp)
                    },
                    singleLine = false,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    modifier = Modifier.height(96.dp)
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
                            urlTemplate = normalizedUrl,
                            crs = crs,
                            minZoom = minZ ?: 3,
                            maxZoom = maxZ ?: 18,
                            subdomains = subdomains.trim(),
                            headers = parseHeaders(headers),
                            isOverlay = overlay
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
