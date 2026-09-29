package com.example.myfirstapp.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myfirstapp.data.user.CloudTrackApi
import com.example.myfirstapp.data.user.CloudTrackApi.ServerTrack
import com.example.myfirstapp.data.user.CloudTrackViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页 ViewModel：把服务器上已有的轨迹变成"热门线路推荐"。
 *
 * 复用 CloudTrackViewModel 的服务器地址偏好（cloud_server / server_url），
 * 进入页面时自动拉取一次 /api/tracks/all，并按"热度"挑出前 6 条展示。
 * 热度 = 轨迹点 + 打卡点（打卡点权重更高，更能反映一条线的内容量），
 * 同分时按更新时间倒序，保证最近上传的线路优先露出。
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(
        CloudTrackViewModel.PREFS, android.content.Context.MODE_PRIVATE
    )
    private val serverUrl: String
        get() = prefs.getString(CloudTrackViewModel.KEY_URL, CloudTrackViewModel.DEFAULT_URL)
            ?: CloudTrackViewModel.DEFAULT_URL

    private val _routes = MutableStateFlow<List<ServerTrack>>(emptyList())
    val routes: StateFlow<List<ServerTrack>> = _routes.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadRoutes()
    }

    /** 重新拉取服务器端热门线路（错误态下的"重试"按钮调用） */
    fun loadRoutes() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            val (list, err) = withContext(Dispatchers.IO) {
                runCatching {
                    CloudTrackApi(serverUrl).listAllTracks()
                        .sortedWith(
                            compareByDescending<ServerTrack> { it.pointCount + it.waypointCount * 3 }
                                .thenByDescending { it.updatedAt }
                        )
                        .take(MAX_ROUTES)
                }.fold(
                    onSuccess = { it to null as String? },
                    onFailure = { emptyList<ServerTrack>() to (it.message ?: "加载热门线路失败") }
                )
            }
            _loading.value = false
            _routes.value = list
            _error.value = err
        }
    }

    companion object {
        private const val MAX_ROUTES = 6
    }
}
