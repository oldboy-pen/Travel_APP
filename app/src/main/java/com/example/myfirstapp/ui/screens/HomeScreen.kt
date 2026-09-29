package com.example.myfirstapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.track.ActivityType
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.Track
import com.example.myfirstapp.track.TrackRepository
import com.example.myfirstapp.ui.home.DailyQuote
import com.example.myfirstapp.ui.home.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class PopularRoute(
    val id: String,
    val name: String,
    val distance: String,
    val difficulty: String,
    val tag: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenTrack: (String) -> Unit = {},
    vm: HomeViewModel = viewModel()
) {
    val context = LocalContext.current
    val repo = remember { TrackRepository.get(context) }
    var query by remember { mutableStateOf("") }

    val allTracks = remember(repo) { repo.list() }
    val filteredTracks = allTracks.filter { track ->
        val q = query.trim()
        if (q.isEmpty()) return@filter true
        val keyword = q.lowercase(Locale.getDefault())
        track.name.lowercase(Locale.getDefault()).contains(keyword) ||
            track.points.any { point ->
                val dateText = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(point.time))
                dateText.contains(keyword)
            } ||
            track.waypoints.any { waypoint ->
                waypoint.name.lowercase(Locale.getDefault()).contains(keyword)
            }
    }

    // 服务器端"热门线路"：真实存在的轨迹，而非写死的占位名称
    val serverRoutes by vm.routes.collectAsStateWithLifecycle()
    val routesLoading by vm.loading.collectAsStateWithLifecycle()
    val routesError by vm.error.collectAsStateWithLifecycle()
    val routeOpening by vm.opening.collectAsStateWithLifecycle()
    val openedTrackId by vm.openedTrackId.collectAsStateWithLifecycle()
    val openError by vm.openError.collectAsStateWithLifecycle()

    // 热门线路下载完成 → 跳详情页；失败 → 提示一句
    LaunchedEffect(openedTrackId) {
        openedTrackId?.let { id ->
            onOpenTrack(id)
            vm.consumeOpenedTrack()
        }
    }
    LaunchedEffect(openError) {
        openError?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val popularRoutes = serverRoutes.map { st ->
        PopularRoute(
            id = st.id,
            name = st.name,
            distance = GeoUtils.formatDistance(st.distanceMeters),
            difficulty = difficultyOf(st.climbMeters),
            tag = activityLabel(st.activityType)
        )
    }

    val heroTitle = if (allTracks.isNotEmpty()) allTracks.first().name else "探索新路线"
    val totalDistance = allTracks.sumOf { it.distanceMeters }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("首页") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                TravelHeroCard(
                    title = heroTitle,
                    subtitle = "今日值得出发",
                    distance = GeoUtils.formatDistance(totalDistance),
                    routeCount = allTracks.size,
                    query = query
                )
            }

            item {
                DailyQuoteCard(quote = DailyQuote.today())
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
                    placeholder = { Text("搜索路线、日期或目的地") },
                    shape = RoundedCornerShape(18.dp)
                )
            }

            item {
                DestinationOverview(
                    destination = if (allTracks.isNotEmpty()) allTracks.first().name else "待规划",
                    nextStop = if (allTracks.isNotEmpty()) "最近更新：${formatDate(allTracks.first().startTime)}" else "添加第一条轨迹",
                    query = query
                )
            }

            item {
                SectionHeader(title = "最近轨迹", count = filteredTracks.size.toString())
            }

            if (filteredTracks.isEmpty()) {
                item {
                    EmptySearchState()
                }
            } else {
                itemsIndexed(filteredTracks.take(5), key = { index, _ -> "track_$index" }) { _, track ->
                    HomeTrackCard(track = track, query = query, onClick = { onOpenTrack(track.id) })
                }
            }

            item {
                SectionHeader(
                    title = "热门线路",
                    count = if (routesLoading) "加载中" else popularRoutes.size.toString()
                )
            }

            when {
                routesLoading -> item { HotRoutesLoading() }
                routesError != null -> item {
                    HotRoutesError(message = routesError!!, onRetry = vm::loadRoutes)
                }
                popularRoutes.isEmpty() -> item { HotRoutesEmpty() }
                else -> itemsIndexed(serverRoutes, key = { index, _ -> "route_$index" }) { _, st ->
                    PopularRouteCard(
                        route = PopularRoute(
                            id = st.id,
                            name = st.name,
                            distance = GeoUtils.formatDistance(st.distanceMeters),
                            difficulty = difficultyOf(st.climbMeters),
                            tag = activityLabel(st.activityType)
                        ),
                        onClick = { if (!routeOpening) vm.openRoute(st) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TravelHeroCard(
    title: String,
    subtitle: String,
    distance: String,
    routeCount: Int,
    query: String
) {
    val highlightTitle = highlightText(title, query)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = subtitle,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "旅行",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = highlightTitle,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TravelStat(label = "累计里程", value = distance)
                    TravelStat(label = "路线数", value = "$routeCount")
                    TravelStat(label = "状态", value = "在线")
                }
            }
        }
    }
}

@Composable
private fun DestinationOverview(destination: String, nextStop: String, query: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "目的地概览",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = highlightText(destination, query),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = nextStop,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = count,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun HomeTrackCard(track: Track, query: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Route,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = highlightText(track.name, query),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(GeoUtils.formatDistance(track.distanceMeters))
                        append(" · ")
                        append(GeoUtils.formatDuration(track.durationMillis))
                        if (track.startTime > 0) {
                            append(" · ")
                            append(SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(track.startTime)))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.AutoMirrored.Filled.ArrowRightAlt,
                contentDescription = "查看轨迹",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun PopularRouteCard(route: PopularRoute, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${route.distance} · ${route.difficulty}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = route.tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ---------- 每日名言 + 热门线路状态卡 ----------

@Composable
private fun DailyQuoteCard(quote: DailyQuote.Quote) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.FormatQuote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = quote.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Medium,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.3f
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "—— ${quote.author}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun HotRoutesLoading() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "正在从服务器获取热门线路…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HotRoutesError(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "热门线路加载失败",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("重试")
            }
        }
    }
}

@Composable
private fun HotRoutesEmpty() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Route,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(42.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "服务器还没有线路，去记录并同步一条吧",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TravelStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun EmptySearchState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(42.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "暂无匹配轨迹，换个关键词试试",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatDate(time: Long): String =
    if (time > 0) {
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(time))
    } else {
        "暂未记录"
    }

/** 根据累计爬升粗略推断线路难度（与徒步/登山的体感一致） */
private fun difficultyOf(climbMeters: Double): String = when {
    climbMeters >= 800 -> "困难"
    climbMeters >= 300 -> "中等"
    else -> "轻松"
}

/** 把服务器返回的 activityType 枚举名（如 HIKING）转成中文标签，未知则回退"线路" */
private fun activityLabel(activityType: String): String =
    runCatching { ActivityType.valueOf(activityType).label }
        .getOrElse { "线路" }

@Composable
private fun highlightText(value: String, query: String): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(value)

    val lowerValue = value.lowercase(Locale.getDefault())
    val lowerQuery = q.lowercase(Locale.getDefault())
    val highlights = mutableListOf<Pair<Int, Int>>()
    var index = lowerValue.indexOf(lowerQuery)
    while (index >= 0) {
        highlights.add(index to index + q.length)
        index = lowerValue.indexOf(lowerQuery, index + 1)
    }
    if (highlights.isEmpty()) return AnnotatedString(value)

    return buildAnnotatedString {
        var last = 0
        highlights.forEach { (start, end) ->
            if (start > last) {
                append(value.substring(last, start))
            }
            withStyle(
                SpanStyle(
                    background = MaterialTheme.colorScheme.primaryContainer,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            ) {
                append(value.substring(start, end))
            }
            last = end
        }
        if (last < value.length) {
            append(value.substring(last))
        }
    }
}
