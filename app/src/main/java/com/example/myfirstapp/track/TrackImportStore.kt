package com.example.myfirstapp.track

import android.content.Context

/**
 * 「外部导入」标记表：记录哪些本地轨迹是从 GPX / KML / KMZ 文件导入的。
 *
 * 与 [TrackDownloadStore] 同一套路——导入的轨迹落盘后格式和本地录制的完全一致，
 * 光看 filesDir/tracks 下的 json 文件分不出来，「我的 → 足迹」要按来源分组就得单独记一份。
 *
 * 标记动作放在 TrackRepository.importTrack 内部完成，任何调用导入的地方
 * （我的页、运动页加载面板）都自动生效，不用各自补一行。
 */
class TrackImportStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ids(): Set<String> =
        prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun isImported(id: String): Boolean = ids().contains(id)

    fun mark(id: String) {
        prefs.edit().putStringSet(KEY, ids() + id).apply()
    }

    fun unmark(id: String) {
        prefs.edit().putStringSet(KEY, ids() - id).apply()
    }

    companion object {
        private const val PREFS = "imported_tracks"
        private const val KEY = "ids"

        @Volatile private var instance: TrackImportStore? = null

        fun get(context: Context): TrackImportStore =
            instance ?: synchronized(this) {
                instance ?: TrackImportStore(context).also { instance = it }
            }
    }
}
