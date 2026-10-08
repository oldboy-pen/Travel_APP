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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
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
import com.example.myfirstapp.track.TrackRepository
import com.example.myfirstapp.ui.components.FootprintEmptyHint
import com.example.myfirstapp.ui.components.PhotoTile
import com.example.myfirstapp.ui.components.collectPhotos
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 足迹 → 照片（独立页，带返回键）
 *
 * 汇总所有轨迹上的图片与视频打点，按日期分组展示（同一步路的"照片墙"）。
 * 点任意一张进该轨迹的「轨迹查看」页，从那里可以再看地图详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FootprintPhotosScreen(
    onBack: () -> Unit,
    onOpenTrackSource: (String) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { TrackRepository.get(context) }
    var tracks by remember { mutableStateOf(repo.list()) }

    // 从查看页返回时刷新（那边可能删了轨迹）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tracks = repo.list()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val photos = remember(tracks) { collectPhotos(tracks) }
    // 按日期分组：key 是 yyyy-MM-dd，保持照片本身的时间倒序
    val groups = remember(photos) {
        photos.groupBy { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(java.util.Date(it.time)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("照片") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (photos.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                FootprintEmptyHint("还没有照片\n在记录页给轨迹打点拍照，就会汇总到这里")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    OverviewCard(
                        photoCount = photos.count { !it.isVideo },
                        videoCount = photos.count { it.isVideo },
                        trackCount = photos.map { it.trackId }.distinct().size,
                        dayCount = groups.size
                    )
                }
                groups.forEach { (day, list) ->
                    item(key = "day_$day") {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    day,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    "${list.size} 张",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    // 三列一行（同 LazyColumn 内不能再嵌懒列表，用 chunked + Row）
                    list.chunked(3).forEachIndexed { index, row ->
                        item(key = "row_${day}_$index") {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                row.forEach { photo ->
                                    PhotoTile(
                                        photo = photo,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onOpenTrackSource(photo.trackId) }
                                    )
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

/** 顶部概览：照片数 / 视频数 / 涉及轨迹数 / 天数 */
@Composable
private fun OverviewCard(photoCount: Int, videoCount: Int, trackCount: Int, dayCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.size(12.dp))
            Row(modifier = Modifier.weight(1f)) {
                MiniStat("照片", photoCount.toString(), Modifier.weight(1f))
                MiniStat("视频", videoCount.toString(), Modifier.weight(1f))
                MiniStat("轨迹", trackCount.toString(), Modifier.weight(1f))
                MiniStat("天数", dayCount.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
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
