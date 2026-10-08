package com.example.myfirstapp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myfirstapp.map.GeoPoint
import com.example.myfirstapp.map.MapEngine
import com.example.myfirstapp.map.MapSurface
import com.example.myfirstapp.map.MapUiSettings
import com.example.myfirstapp.map.rememberMapSurfaceState
import com.example.myfirstapp.track.DeviationSide
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.NavAlertSettings
import com.example.myfirstapp.track.NavigationState
import com.example.myfirstapp.track.NavigationStatus
import com.example.myfirstapp.track.Track
import com.example.myfirstapp.track.TrackNavigationService
import com.example.myfirstapp.track.TrackNavigator
import com.example.myfirstapp.track.TrackRepository
import com.example.myfirstapp.track.TrackColorStore
import com.example.myfirstapp.track.VoiceAnnouncer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 轨迹导航页：沿一条已保存的轨迹行进，实时显示剩余里程/进度/偏离距离，
 * 偏离超过 [NavAlertSettings.alertMeters]（默认 20 米）开始语音预警，
 * 超过 [NavAlertSettings.severeMeters]（默认 50 米）播报实际偏离距离并加密补播；
 * 四项阈值均可在底部面板的「预警设置」里改。
 *
 * 分工（与记录页同构）：
 * - [TrackNavigator]：定位 + 投影计算 + 播报判定（单例，页面不在前台也活着）
 * - [TrackNavigationService]：前台服务保活，息屏后仍持续定位与预警
 * - 本页：地图渲染 + HUD + 开关（语音、跟随）与结束导航
 *
 * 退出约定：返回键与「结束导航」都会真正结束导航（停定位、停服务），
 * 不留僵尸通知；息屏或按 Home 键退到后台则继续导航。
 */
@Composable
fun TrackNavigationScreen(trackId: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { TrackRepository.get(context) }
    val track = remember(trackId) { repo.load(trackId) }
    val navState by TrackNavigator.state.collectAsStateWithLifecycle()
    val mapState = rememberMapSurfaceState("navigation")
    val arrowBitmap = remember { createArrowBitmap() }

    var followLocation by remember { mutableStateOf(true) }
    var voiceOn by remember { mutableStateOf(VoiceAnnouncer.isEnabled(context)) }
    var confirmExit by remember { mutableStateOf(false) }
    // 偏离预警阈值/补播间隔（可点底部「预警设置」改，保存后立即生效）
    var alertSettings by remember {
        NavAlertSettings.ensureLoaded(context)
        mutableStateOf(NavAlertSettings.current())
    }
    var showAlertSettings by remember { mutableStateOf(false) }

    if (track == null) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, "轨迹不存在", Toast.LENGTH_SHORT).show()
            onExit()
        }
        return
    }

    /**
     * 结束导航并退出页面：取出实际行走轨迹存档 + 停定位 + 停前台服务 + 返回。
     * 实际轨迹点数足够（≥2 且通过降噪过滤）时保存为新轨迹并提示。
     */
    fun stopAndExit() {
        val actual = TrackNavigator.takeActualTrack()
        TrackNavigator.stop()
        TrackNavigationService.stop(context)
        if (actual != null) {
            repo.save(actual)
            Toast.makeText(context, "已生成实际轨迹：${actual.name}", Toast.LENGTH_LONG).show()
        }
        onExit()
    }

    // ---- 权限：定位（必需）+ 通知（Android 13+，前台服务通知要显示出来）----
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocationPermission =
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!hasLocationPermission) {
            Toast.makeText(context, "没有定位权限无法导航", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            val need = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
            permissionLauncher.launch(need.toTypedArray())
        }
    }

    // ---- 权限就绪 → 启动导航（同一条轨迹重复进入不重复启动）----
    LaunchedEffect(hasLocationPermission, track.id) {
        if (!hasLocationPermission) return@LaunchedEffect
        val s = TrackNavigator.state.value
        if (s.status != NavigationStatus.NAVIGATING || s.trackId != track.id) {
            TrackNavigator.start(context, track)
        }
        TrackNavigationService.start(context)
    }

    // 系统返回键 = 结束导航
    BackHandler { confirmExit = true }

    // ---- 地图：蓝点 + 跟随镜头 ----
    LaunchedEffect(mapState.engine) {
        mapState.engine?.setMyLocationEnabled(true, follow = false)
    }
    LaunchedEffect(mapState.engine, navState.latitude, navState.longitude, followLocation) {
        val engine = mapState.engine ?: return@LaunchedEffect
        if (!navState.located) return@LaunchedEffect
        val p = GeoPoint(navState.latitude, navState.longitude)
        engine.updateDeviceLocation(p, navState.accuracyMeters, navState.bearing)
        if (followLocation) engine.animateCamera(p)
    }

    // ---- 覆盖物：每 2 秒重绘一次（clearOverlays 会清掉全部，所以整幅重画）----
    LaunchedEffect(mapState.engine, track.id) {
        val engine = mapState.engine ?: return@LaunchedEffect
        TrackColorStore.ensureLoaded(context)
        var firstDraw = true
        while (isActive) {
            val s = TrackNavigator.state.value
            drawNavigation(engine, track, s, arrowBitmap)
            // 首次定位（或刚进页面还没定位）把镜头带到当前位置/轨迹起点
            if (firstDraw) {
                firstDraw = false
                if (s.located) engine.moveCamera(GeoPoint(s.latitude, s.longitude), 17f)
                else engine.fitBounds(
                    track.points.map { GeoPoint(it.latitude, it.longitude) }, 64
                )
            }
            delay(2000)
        }
    }

    Box(Modifier.fillMaxSize()) {
        MapSurface(
            state = mapState,
            pageKey = "navigation",
            modifier = Modifier.fillMaxSize(),
            uiSettings = MapUiSettings(),
            onUserGesture = { followLocation = false },
            overlay = {
                com.example.myfirstapp.ui.components.PhoneCompass(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 10.dp, top = 10.dp)
                )
                com.example.myfirstapp.ui.components.MapLayerSwitcher(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 10.dp)
                )
            }
        )

        // ---- 顶部：返回 + 轨迹名 ----
        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 62.dp)
                .fillMaxWidth(0.92f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { confirmExit = true }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "结束导航")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "导航：${track.name}",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        "用时 ${GeoUtils.formatDuration(navState.elapsedMillis)} · " +
                                "偏离预警 ${intOf(alertSettings.alertMeters)}" +
                                "/${intOf(alertSettings.severeMeters)} 米",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---- 偏离状态条：正常显示绿/橙，超阈值变红 ----
        if (navState.located) {
            DeviationBanner(
                state = navState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 128.dp)
            )
        }

        // ---- 底部数据面板 ----
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            if (navState.status == NavigationStatus.ARRIVED) "已到达终点"
                            else "剩余 ${GeoUtils.formatDistance(navState.remainingMeters)}",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "已走 ${GeoUtils.formatDistance(navState.coveredMeters)} / " +
                                    "全长 ${GeoUtils.formatDistance(navState.totalMeters)} · " +
                                    "实走 ${GeoUtils.formatDistance(navState.actualDistanceMeters)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "${(navState.progress * 100).toInt()}%",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { navState.progress },
                    modifier = Modifier.fillMaxWidth()
                )

                if (navState.nextWaypointName != null && navState.status == NavigationStatus.NAVIGATING) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "下一个途经点：${navState.nextWaypointName}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        Text(
                            GeoUtils.formatDistance(navState.nextWaypointDistance),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // 语音播报开关（与记录页共用同一开关）
                    OutlinedButton(
                        onClick = {
                            val next = !voiceOn
                            VoiceAnnouncer.setEnabled(context, next)
                            voiceOn = next
                            if (next) {
                                VoiceAnnouncer.announce(context, "偏离语音预警已开启")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (voiceOn) "语音预警：开" else "语音预警：关") }

                    Spacer(Modifier.width(8.dp))

                    // 镜头跟随开关（拖过地图就自动关闭，这里手动恢复）
                    OutlinedButton(
                        onClick = {
                            followLocation = true
                            if (navState.located) {
                                mapState.engine?.animateCamera(
                                    GeoPoint(navState.latitude, navState.longitude)
                                )
                            }
                        },
                        enabled = !followLocation,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (followLocation) "跟随中" else "回到我的位置") }

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = { confirmExit = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("结束") }
                }

                // ---- 偏离预警设置：显示当前阈值/补播间隔，点开可改，保存后立即生效 ----
                TextButton(
                    onClick = { showAlertSettings = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "预警设置：偏离 ${intOf(alertSettings.alertMeters)} 米提醒 · " +
                                "${intOf(alertSettings.severeMeters)} 米起报距离 · " +
                                "补播 ${intOf(alertSettings.reAlertMildMeters)} / " +
                                "${intOf(alertSettings.reAlertSevereMeters)} 米",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2
                    )
                }
            }
        }

        // ---- 右下角：跟随状态提示 ----
        if (!followLocation) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 200.dp, end = 16.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.LocationOn, null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("已停止跟随", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    // ---- 偏离预警设置 ----
    if (showAlertSettings) {
        NavAlertSettingsDialog(
            current = alertSettings,
            onDismiss = { showAlertSettings = false },
            onSave = { a, s, m, sev ->
                alertSettings = NavAlertSettings.save(context, a, s, m, sev)
                showAlertSettings = false
                Toast.makeText(context, "预警设置已生效", Toast.LENGTH_SHORT).show()
            },
            onReset = {
                alertSettings = NavAlertSettings.reset(context)
                showAlertSettings = false
                Toast.makeText(context, "已恢复默认设置", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ---- 结束导航确认 ----
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("结束导航") },
            text = { Text("结束本次轨迹导航？语音预警与后台定位将一并停止。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    stopAndExit()
                }) { Text("结束导航", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续导航") } }
        )
    }

    // ---- 到达终点 ----
    if (navState.status == NavigationStatus.ARRIVED) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("已到达终点") },
            text = {
                Text(
                    "「${track.name}」导航完成：\n" +
                            "用时 ${GeoUtils.formatDuration(navState.elapsedMillis)}，" +
                            "沿轨迹 ${GeoUtils.formatDistance(navState.coveredMeters)}，" +
                            "偏离预警 ${navState.alertCount} 次。\n" +
                            "实际行走轨迹（${GeoUtils.formatDistance(navState.actualDistanceMeters)}）" +
                            "点「完成」后保存到轨迹历史。"
                )
            },
            confirmButton = {
                TextButton(onClick = { stopAndExit() }) { Text("完成") }
            }
        )
    }
}

/** 米数取整后转字符串（配置值都是整数米，避免显示 20.0 这种） */
private fun intOf(meters: Double): String = meters.toInt().toString()

/**
 * 偏离预警设置弹窗：四个数值——一级提醒阈值、二级报距离阈值、各自的补播间隔（米）。
 * 补播按**走过的里程**而非时间：走得快报得勤，站着不动不会被反复提醒。
 * 非法输入（空/非数字）在保存时回退到原值；大小关系非法由 [NavAlertSettings.save] 收敛。
 */
@Composable
private fun NavAlertSettingsDialog(
    current: NavAlertSettings.Result,
    onDismiss: () -> Unit,
    onSave: (Double, Double, Double, Double) -> Unit,
    onReset: () -> Unit
) {
    var alert by remember { mutableStateOf(intOf(current.alertMeters)) }
    var severe by remember { mutableStateOf(intOf(current.severeMeters)) }
    var mild by remember { mutableStateOf(intOf(current.reAlertMildMeters)) }
    var severeIv by remember { mutableStateOf(intOf(current.reAlertSevereMeters)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("偏离预警设置") },
        text = {
            Column {
                Text(
                    "超过「提醒阈值」开始预警；超过「报距离阈值」播报实际偏离距离。" +
                            "补播按走过的里程计算，不是按时间。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                NumberField("提醒阈值（米）", alert) { alert = it }
                Spacer(Modifier.height(6.dp))
                NumberField("报距离阈值（米）", severe) { severe = it }
                Spacer(Modifier.height(6.dp))
                NumberField("轻度偏离补播间隔（米）", mild) { mild = it }
                Spacer(Modifier.height(6.dp))
                NumberField("严重偏离补播间隔（米）", severeIv) { severeIv = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    alert.toDoubleOrNull() ?: current.alertMeters,
                    severe.toDoubleOrNull() ?: current.severeMeters,
                    mild.toDoubleOrNull() ?: current.reAlertMildMeters,
                    severeIv.toDoubleOrNull() ?: current.reAlertSevereMeters
                )
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/** 纯数字输入框（米），空字符串允许中间态，保存时才由调用方兜底 */
@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 偏离状态条：正常（绿）/ 轻偏离（橙）/ 严重偏离（红，带方向提示）三套样式 */
@Composable
private fun DeviationBanner(state: NavigationState, modifier: Modifier = Modifier) {
    val off = state.offTrack
    val severe = state.severeOffTrack
    val sideText = when (state.deviationSide) {
        DeviationSide.LEFT -> "在轨迹左侧"
        DeviationSide.RIGHT -> "在轨迹右侧"
        DeviationSide.NONE -> ""
    }
    val container = when {
        severe -> MaterialTheme.colorScheme.errorContainer
        off -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when {
        severe -> MaterialTheme.colorScheme.onErrorContainer
        off -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(0.92f),
        shape = RoundedCornerShape(14.dp),
        color = container,
        contentColor = content
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when {
                    severe -> "⚠ 严重偏离轨迹"
                    off -> "⚠ 已偏离轨迹"
                    else -> "偏离"
                },
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "%.0f 米".format(state.deviationMeters),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            if (sideText.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(sideText, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.weight(1f))
            if (off) {
                Text(
                    when (state.deviationSide) {
                        DeviationSide.LEFT -> "请向左返回"
                        DeviationSide.RIGHT -> "请向右返回"
                        DeviationSide.NONE -> "请返回轨迹"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ==================== 地图绘制 ====================

/** 重绘上限：轨迹点过多时抽稀，避免每 2 秒重建上万点的 Polyline */
private const val MAX_POLYLINE_POINTS = 1200

/**
 * 画导航图层（颜色可自定义，见 TrackColorStore / 图层面板「轨迹颜色」）：
 * - 导航参考轨迹（navColor）：未走过=半透明、已走过=不透明，同色系区分
 * - 实际行走轨迹（liveColor）：导航中同步记录的线，结束时保存为新轨迹
 * - 蓝色箭头：当前位置（按航向角旋转）
 * - 红色连线：偏离时从当前位置指向轨迹上的回归点
 */
private fun drawNavigation(
    engine: MapEngine,
    t: Track,
    s: NavigationState,
    arrow: Bitmap
) {
    runCatching {
        engine.clearOverlays()
        val navColor = TrackColorStore.navColor.value
        val liveColor = TrackColorStore.liveColor.value
        val all = t.points.map { GeoPoint(it.latitude, it.longitude) }
        if (all.size < 2) return@runCatching
        val step = if (all.size > MAX_POLYLINE_POINTS) all.size / MAX_POLYLINE_POINTS + 1 else 1
        val full = if (step == 1) all
        else all.filterIndexed { i, _ -> i % step == 0 || i == all.lastIndex }

        // 参考轨迹——未走过的：同色半透明
        engine.addPolyline(
            full, widthPx = 12f,
            colorArgb = TrackColorStore.withAlpha(navColor, 0x66)
        )
        // 参考轨迹——已走过的：不透明加粗
        val doneEnd = s.matchedIndex.coerceIn(0, all.lastIndex)
        if (doneEnd > 0) {
            val done = if (step == 1) all.subList(0, doneEnd + 1)
            else (0..doneEnd).filter { it % step == 0 || it == doneEnd }.map { all[it] }
            engine.addPolyline(done, widthPx = 14f, colorArgb = navColor)
        }

        // 实际行走轨迹（本页生成的轨迹线，与参考轨迹颜色区分）
        if (s.actualPoints.size >= 2) {
            engine.addPolyline(
                s.actualPoints.map { GeoPoint(it.latitude, it.longitude) },
                widthPx = 12f, colorArgb = liveColor
            )
        }

        engine.addMarker(all.first(), title = "起点")
        engine.addMarker(all.last(), title = "终点")
        t.waypoints.forEach { w ->
            engine.addMarker(GeoPoint(w.latitude, w.longitude), title = w.name)
        }

        if (s.located) {
            val me = GeoPoint(s.latitude, s.longitude)
            // 偏离：画一条回到轨迹的引导线
            if (s.offTrack && s.rejoinLatitude != 0.0) {
                val rejoin = GeoPoint(s.rejoinLatitude, s.rejoinLongitude)
                engine.addPolyline(
                    listOf(me, rejoin), widthPx = 8f, colorArgb = 0xFFE53935.toInt()
                )
                engine.addCircle(
                    center = rejoin, radiusMeters = 8.0,
                    fillColor = 0x55E53935.toInt(), strokeColor = 0xFFE53935.toInt(),
                    strokeWidthPx = 2f
                )
            }
            engine.addMarker(
                point = me,
                bitmap = arrow,
                title = "我的位置",
                zIndex = 10f,
                rotateDeg = s.bearing
            )
        }
    }
}

/**
 * 当前位置箭头图标（自绘，避免依赖任何一家 SDK 的默认图标）：
 * 尖端朝上 = 未旋转时指向正北，配合 rotateDeg=bearing 即指向行进方向。
 */
private fun createArrowBitmap(): Bitmap {
    val size = 72
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val path = Path().apply {
        moveTo(size / 2f, 4f)
        lineTo(size - 6f, size - 10f)
        lineTo(size / 2f, size - 22f)
        lineTo(6f, size - 10f)
        close()
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#FF1E88E5")
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = android.graphics.Color.WHITE
    }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, stroke)
    return bmp
}
