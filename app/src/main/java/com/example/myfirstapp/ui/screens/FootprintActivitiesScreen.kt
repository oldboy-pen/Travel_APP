package com.example.myfirstapp.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.myfirstapp.track.ActivityType
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.TrackRepository
import com.example.myfirstapp.track.TrackSource
import com.example.myfirstapp.track.TrackSourceResolver
import com.example.myfirstapp.ui.components.ActivityStatCard
import com.example.myfirstapp.ui.components.FootprintEmptyHint
import com.example.myfirstapp.ui.components.TrackRow
import com.example.myfirstapp.ui.components.summarizeActivities

/**
 * 足迹 → 活动（独立页，带返回键）
 *
 * 顶部一行总览（总次数 / 总里程 / 总时长 / 覆盖运动类型），下面按运动方式
 * 列出汇总卡，点某类展开该类下的轨迹，点轨迹进「轨迹查看」页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FootprintActivitiesScreen(
    onBack: () -> Unit,
    onOpenTrackSource: (String) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { TrackRepository.get(context) }
    var tracks by remember { mutableStateOf(repo.list()) }
    var expandedType by remember { mutableStateOf<ActivityType?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tracks = repo.list()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val stats = remember(tracks) { summarizeActivities(tracks) }
    val sourceOf = remember(tracks) {
        TrackSourceResolver.ofAll(context, tracks.map { it.id })
    }
    val totalDistance = remember(tracks) { tracks.sumOf { it.distanceMeters } }
    val totalDuration = remember(tracks) { tracks.sumOf { it.durationMillis } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("活动") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (stats.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                FootprintEmptyHint("还没有活动记录\n完成一次轨迹记录就会出现在这里")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    TotalCard(
                        count = tracks.size,
                        distanceMeters = totalDistance,
                        durationMillis = totalDuration,
                        typeCount = stats.size
                    )
                }
                stats.forEach { stat ->
                    item(key = "act_" + stat.type.name) {
                        ActivityStatCard(
                            stat = stat,
                            expanded = expandedType == stat.type,
                            onClick = {
                                expandedType =
                                    if (expandedType == stat.type) null else stat.type
                            }
                        )
                    }
                    if (expandedType == stat.type) {
                        items(
                            tracks.filter { it.activityType == stat.type },
                            key = { "act_tk_" + it.id }
                        ) { track ->
                            TrackRow(
                                track = track,
                                source = sourceOf[track.id] ?: TrackSource.LOCAL,
                                synced = false,
                                onClick = { onOpenTrackSource(track.id) }
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

/** 总览卡：总次数 / 总里程 / 总时长 / 覆盖类型 */
@Composable
private fun TotalCard(count: Int, distanceMeters: Double, durationMillis: Long, typeCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.EmojiEvents,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "全部活动",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "覆盖 $typeCount 种运动",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                ColumnStat("次数", "$count 次", Modifier.weight(1f))
                ColumnStat("总里程", GeoUtils.formatDistance(distanceMeters), Modifier.weight(1f))
                ColumnStat("总时长", GeoUtils.formatDuration(durationMillis), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ColumnStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
