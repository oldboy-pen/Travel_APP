package com.example.myfirstapp.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.Track
import com.example.myfirstapp.track.TrackRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class PopularRoute(
    val name: String,
    val distance: String,
    val difficulty: String,
    val tag: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenTrack: (String) -> Unit = {}) {
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

    val heroTitle = if (allTracks.isNotEmpty()) allTracks.first().name else "探索新路线"
    val totalDistance = allTracks.sumOf { it.distanceMeters }
    val popularRoutes = listOf(
        PopularRoute("青城山环线", "18.4 km", "中等", "热门"),
        PopularRoute("山海步道", "12.7 km", "轻松", "新开"),
        PopularRoute("湖边漫游", "8.3 km", "轻松", "周末"),
        PopularRoute("古镇巡游", "6.9 km", "适中", "精选")
    )

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
                items(filteredTracks.take(5), key = { it.id }) { track ->
                    HomeTrackCard(track = track, query = query, onClick = { onOpenTrack(track.id) })
                }
            }

            item {
                SectionHeader(title = "热门路线", count = "4")
            }

            items(popularRoutes) { route ->
                PopularRouteCard(route = route)
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
private fun PopularRouteCard(route: PopularRoute) {
    Card(
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
