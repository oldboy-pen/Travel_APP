package com.example.myfirstapp.track

import android.content.Context

/**
 * 「已下载」标记表：记录哪些本地轨迹是从云端服务器下载下来的。
 *
 * 之所以要单独记一份：从服务器下载的轨迹落盘后跟本地记录/导入的轨迹混在
 * filesDir/tracks 里，文件格式完全相同，无法从文件本身区分来源。运动页
 * 「加载轨迹 → 已下载」这一栏要按来源过滤，所以用 SharedPreferences 存一份
 * id 集合即可（几十条轨迹，规模很小，不需要上数据库）。
 *
 * 一致性：任何"从服务器拉轨迹存本地"的入口（运动页加载面板、首页热门线路）
 * 保存成功后都要调 [mark]，否则该栏会漏。
 */
class TrackDownloadStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 已下载轨迹的本地 id 集合 */
    fun ids(): Set<String> =
        // getStringSet 返回的是内部可变引用，复制一份避免外部改动影响持久化数据
        prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun isDownloaded(id: String): Boolean = ids().contains(id)

    /** 标记某条轨迹为"已下载"，owner 是服务器上的作者昵称（查看页要显示"来自谁"） */
    fun mark(id: String, owner: String? = null) {
        prefs.edit()
            .putStringSet(KEY, ids() + id)
            .apply { owner?.takeIf { it.isNotBlank() }?.let { putString(KEY_OWNER_PREFIX + id, it) } }
            .apply()
    }

    /** 取消标记（本地文件被删除 / 用户主动移除下载时使用） */
    fun unmark(id: String) {
        prefs.edit()
            .putStringSet(KEY, ids() - id)
            .remove(KEY_OWNER_PREFIX + id)
            .apply()
    }

    /** 这条下载轨迹的原作者昵称；没有记录返回 null */
    fun ownerOf(id: String): String? =
        prefs.getString(KEY_OWNER_PREFIX + id, null)?.takeIf { it.isNotBlank() }

    companion object {
        private const val PREFS = "downloaded_tracks"
        private const val KEY = "ids"
        private const val KEY_OWNER_PREFIX = "owner_"

        @Volatile private var instance: TrackDownloadStore? = null

        fun get(context: Context): TrackDownloadStore =
            instance ?: synchronized(this) {
                instance ?: TrackDownloadStore(context).also { instance = it }
            }
    }
}
