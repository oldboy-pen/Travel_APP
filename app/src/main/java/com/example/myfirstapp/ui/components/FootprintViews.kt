package com.example.myfirstapp.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.track.ActivityType
import com.example.myfirstapp.track.GeoUtils
import com.example.myfirstapp.track.Track
import com.example.myfirstapp.track.TrackSource
import com.example.myfirstapp.track.WaypointType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 足迹三页（轨迹 / 照片 / 活动）共用的视图组件与派生数据。
 *
 * 抽出来的原因：三个页面都要显示"轨迹行""来源标签""照片缩略图""活动统计卡"，
 * 各写一份必然走样，改样式也要改三处。
 */

// ==================== 派生数据 ====================

/** 足迹里的一个媒体打点（图片或视频） */
data class FootprintPhoto(
    val trackId: String,
    val trackName: String,
    val uri: String,
    val title: String,
    val time: Long,
    val isVideo: Boolean
)

/** 某类运动的汇总 */
data class ActivityStat(
    val type: ActivityType,
    val count: Int,
    val distanceMeters: Double,
    val durationMillis: Long,
    val lastTime: Long
)

/** 汇总所有轨迹上的图片与视频打点，按时间倒序 */
fun collectPhotos(tracks: List<Track>): List<FootprintPhoto> =
    tracks.flatMap { t ->
        t.waypoints
            .filter {
                (it.type == WaypointType.PHOTO || it.type == WaypointType.VIDEO) &&
                    !it.mediaUri.isNullOrBlank()
            }
            .map { w ->
                FootprintPhoto(
                    trackId = t.id,
                    trackName = t.name,
                    uri = w.mediaUri!!,
                    title = w.name,
                    time = w.time,
                    isVideo = w.type == WaypointType.VIDEO
                )
            }
    }.sortedByDescending { it.time }

/** 按运动方式汇总：次数 / 里程 / 时长 / 最后一次时间 */
fun summarizeActivities(tracks: List<Track>): List<ActivityStat> =
    tracks.groupBy { it.activityType }.map { (type, list) ->
        ActivityStat(
            type = type,
            count = list.size,
            distanceMeters = list.sumOf { it.distanceMeters },
            durationMillis = list.sumOf { it.durationMillis },
            lastTime = list.maxOf { it.startTime }
        )
    }.sortedByDescending { it.count }

// ==================== 组件 ====================

/** 来源小标签：列表里一眼看出这条轨迹是哪来的 */
@Composable
fun TrackSourceTag(source: TrackSource) {
    val (container, content) = sourceColors(source)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(source.short, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

/** 四类来源各自的配色（容器色 / 内容色） */
@Composable
fun sourceColors(source: TrackSource): Pair<Color, Color> = when (source) {
    TrackSource.LOCAL -> MaterialTheme.colorScheme.primaryContainer to
        MaterialTheme.colorScheme.onPrimaryContainer
    TrackSource.CLOUD -> MaterialTheme.colorScheme.tertiaryContainer to
        MaterialTheme.colorScheme.onTertiaryContainer
    TrackSource.DOWNLOADED -> MaterialTheme.colorScheme.secondaryContainer to
        MaterialTheme.colorScheme.onSecondaryContainer
    TrackSource.IMPORTED -> MaterialTheme.colorScheme.surfaceVariant to
        MaterialTheme.colorScheme.onSurfaceVariant
}

/** 来源筛选行：全部 + 四类来源（带条数），点已选项取消筛选 */
@Composable
fun SourceFilterRow(
    counts: Map<TrackSource, Int>,
    total: Int,
    selected: TrackSource?,
    onSelect: (TrackSource?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterTextChip("全部 $total", selected == null) { onSelect(null) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TrackSource.values().forEach { s ->
                FilterTextChip(
                    text = "${s.short} ${counts[s] ?: 0}",
                    selected = selected == s,
                    modifier = Modifier.weight(1f)
                ) { onSelect(s) }
            }
        }
    }
}

@Composable
private fun FilterTextChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

/** 轨迹行：名称 + 来源标签 + 里程/时长/日期，右侧同步标记与导出按钮 */
@Composable
fun TrackRow(
    track: Track,
    source: TrackSource,
    synced: Boolean,
    onClick: () -> Unit,
    onExport: (() -> Unit)? = null   // 传 null 就不显示导出按钮（活动页只做展示）
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(track.name, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    TrackSourceTag(source)
                }
            },
            supportingContent = {
                Text(
                    buildString {
                        append(GeoUtils.formatDistance(track.distanceMeters))
                        append(" · ").append(GeoUtils.formatDuration(track.durationMillis))
                        if (track.startTime > 0) {
                            append(" · ").append(formatDate(track.startTime))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            },
            leadingContent = {
                Icon(Icons.Default.Route, null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (synced) {
                        Icon(
                            Icons.Default.CloudDone,
                            contentDescription = "已同步到云端",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (track.waypoints.isNotEmpty()) {
                        Text(
                            "${track.waypoints.size}个点",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (onExport != null) {
                        IconButton(onClick = onExport) {
                            Icon(
                                Icons.Default.IosShare,
                                contentDescription = "导出轨迹",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        )
    }
}

/** 一张照片/视频缩略图（视频没有缩略图，用播放角标占位） */
@Composable
fun PhotoTile(photo: FootprintPhoto, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(photo.uri) {
        if (photo.isVideo) null else decodeSampledBitmap(context, photo.uri, 300)
    }
    Card(
        onClick = onClick,
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = photo.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    if (photo.isVideo) Icons.Default.PlayArrow else Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/** 活动统计卡：某类运动做了几次、累计里程与时长，点击展开该类轨迹 */
@Composable
fun ActivityStatCard(stat: ActivityStat, expanded: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
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
                    stat.type.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${stat.count} 次 · ${GeoUtils.formatDistance(stat.distanceMeters)} · ${GeoUtils.formatDuration(stat.durationMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (stat.lastTime > 0) {
                    Text(
                        formatDate(stat.lastTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 空态提示卡 */
@Composable
fun FootprintEmptyHint(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Route, null, Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 带色圆底图标（页面里的分区标题用） */
@Composable
fun RoundIconBadge(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

// ==================== 工具 ====================

/** 按目标边长降采样解码，避免整张大图直接进内存 */
fun decodeSampledBitmap(context: Context, uri: String, targetPx: Int): Bitmap? = runCatching {
    val parsed = Uri.parse(uri)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(parsed)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    var sample = 1
    while (bounds.outWidth / sample > targetPx && bounds.outHeight / sample > targetPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(parsed)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    }
}.getOrNull()

fun formatDate(time: Long): String =
    if (time > 0) SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(time)) else "未知"

fun formatDateTime(time: Long): String =
    if (time > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(time)) else "未知"
