package com.example.myfirstapp.track

import android.content.Context

/**
 * 轨迹来源：本地库里躺着的所有轨迹，按"从哪来"分成四类。
 *
 * 四条来源靠两张标记表区分（文件格式完全相同，看不出来源）：
 * - [TrackDownloadStore]：从服务器拉下来的（别人的或自己上传后又拉回的）；
 * - [TrackImportStore]：从 GPX / KML / KMZ 文件导入的；
 * - 同步成功的 id 集合（CloudTrackViewModel.syncedIds）：本机上传过服务器的；
 * - 其余即本机录制的。
 *
 * 判定顺序：下载 > 导入 > 云端 > 本地。
 * 一条轨迹可以既是"导入的"又"已同步"，此时来源标签取导入，
 * 页面另外单独显示"已同步到云端"徽章，两个信息不冲突。
 */
enum class TrackSource(
    val label: String,     // 完整名（列表标签、页面徽章）
    val short: String,     // 两字简称（筛选行这类窄位置）
    val desc: String
) {
    LOCAL("本地轨迹", "本地", "在本机记录生成的轨迹"),
    CLOUD("云端轨迹", "云端", "已同步到服务器，换设备可拉回"),
    DOWNLOADED("他人轨迹", "他人", "从服务器下载的他人分享线路"),
    IMPORTED("导入轨迹", "导入", "从 GPX / KML / KMZ 文件导入")
}

object TrackSourceResolver {

    /**
     * 判定单条轨迹的来源。
     * @param syncedIds 服务器返回的"已同步"轨迹 id 集合，没拉取过传空集即可
     */
    fun of(context: Context, trackId: String, syncedIds: Set<String> = emptySet()): TrackSource =
        when {
            TrackDownloadStore.get(context).isDownloaded(trackId) -> TrackSource.DOWNLOADED
            TrackImportStore.get(context).isImported(trackId) -> TrackSource.IMPORTED
            syncedIds.contains(trackId) -> TrackSource.CLOUD
            else -> TrackSource.LOCAL
        }

    /** 一次算好整张表，列表渲染时直接用（避免每条都去读 SharedPreferences） */
    fun ofAll(
        context: Context,
        trackIds: Collection<String>,
        syncedIds: Set<String> = emptySet()
    ): Map<String, TrackSource> {
        val download = TrackDownloadStore.get(context)
        val imported = TrackImportStore.get(context)
        return trackIds.associateWith { id ->
            when {
                download.isDownloaded(id) -> TrackSource.DOWNLOADED
                imported.isImported(id) -> TrackSource.IMPORTED
                syncedIds.contains(id) -> TrackSource.CLOUD
                else -> TrackSource.LOCAL
            }
        }
    }

    /** 他人轨迹的原作者昵称（下载时记下的），非下载轨迹返回 null */
    fun ownerOf(context: Context, trackId: String): String? =
        TrackDownloadStore.get(context).ownerOf(trackId)

    /** 删除轨迹时把所有来源标记一起清掉，避免留下死条目 */
    fun clearMarks(context: Context, trackId: String) {
        TrackDownloadStore.get(context).unmark(trackId)
        TrackImportStore.get(context).unmark(trackId)
        TrackColorStore.clearOverlayColor(context, trackId)
    }
}
