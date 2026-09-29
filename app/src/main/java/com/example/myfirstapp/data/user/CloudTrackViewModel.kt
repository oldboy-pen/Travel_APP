package com.example.myfirstapp.data.user

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 云端轨迹同步 ViewModel
 *
 * 复用 CloudUserViewModel 的服务器地址偏好（cloud_server / server_url），
 * 并通过 CloudSession 读取当前云端用户 id，把本地轨迹关联到该账号。
 *
 * 同步是"全量 upsert"：把本地所有轨迹的 JSON 一次性提交，服务端按
 * (user_id, id) 覆盖写入，重复提交幂等，逻辑最简单也最稳。
 */
class CloudTrackViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 已同步到服务器的轨迹 id 集合（用于列表里打勾标记） */
    private val _syncedIds = MutableStateFlow<Set<String>>(emptySet())
    val syncedIds: StateFlow<Set<String>> = _syncedIds.asStateFlow()

    fun setServerUrl(url: String) {
        val trimmed = url.trim()
        prefs.edit().putString(KEY_URL, trimmed).apply()
        _serverUrl.value = trimmed
    }

    /** 全量同步本地轨迹到服务器；未登录云端时给出提示 */
    fun sync(tracksJson: List<JSONObject>) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val uid = CloudSession.getUserId(getApplication())
                        ?: return@runCatching "失败：请先在「云端账号（验证）」页登录后再同步"
                    val api = CloudTrackApi(_serverUrl.value)
                    val n = api.uploadTracks(uid, tracksJson)
                    _syncedIds.value = api.listTracks(uid).map { it.id }.toSet()
                    "成功：已同步 $n 条轨迹到服务器"
                }.getOrElse { e -> "失败：${e.message ?: e::class.java.simpleName}" }
            }
            _busy.value = false
            _message.value = text
        }
    }

    /** 拉取服务器端已同步的轨迹 id（进入页面时调用，用于标记哪些已上传） */
    fun refreshSynced() {
        viewModelScope.launch {
            val uid = CloudSession.getUserId(getApplication()) ?: return@launch
            val ids = withContext(Dispatchers.IO) {
                runCatching {
                    CloudTrackApi(_serverUrl.value).listTracks(uid).map { it.id }.toSet()
                }.getOrNull()
            }
            ids?.let { _syncedIds.value = it }
        }
    }

    fun clearMessage() { _message.value = null }

    companion object {
        internal const val PREFS = "cloud_server"
        internal const val KEY_URL = "server_url"
        const val DEFAULT_URL = "http://10.0.2.2:8000"
    }
}
