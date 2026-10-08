package com.example.myfirstapp.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myfirstapp.data.user.CloudTrackApi
import com.example.myfirstapp.data.user.CloudTrackApi.ServerTrack
import com.example.myfirstapp.data.user.CloudTrackViewModel
import com.example.myfirstapp.track.TrackDownloadStore
import com.example.myfirstapp.track.TrackRepository
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

    // ---------- 点击热门线路：拉完整轨迹 → 落本地 → 通知 UI 打开详情 ----------

    private val _opening = MutableStateFlow(false)
    val opening: StateFlow<Boolean> = _opening.asStateFlow()

    private val _openedTrackId = MutableStateFlow<String?>(null)
    val openedTrackId: StateFlow<String?> = _openedTrackId.asStateFlow()

    private val _openError = MutableStateFlow<String?>(null)
    val openError: StateFlow<String?> = _openError.asStateFlow()

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

    /**
     * 点击一条热门线路：从服务器拉完整轨迹 → 存一份到本地 → 通知 UI 按 id 打开详情页。
     * 之所以落本地：详情页（TrackDetailScreen）是按 id 从 TrackRepository 读的，
     * 存一份就能直接复用现成的地图渲染与统计展示，不必另做一套云端详情页。
     */
    fun openRoute(route: ServerTrack) {
        if (_opening.value) return          // 下载中忽略重复点击
        viewModelScope.launch {
            _opening.value = true
            _openError.value = null
            val (id, err) = withContext(Dispatchers.IO) {
                runCatching {
                    val repo = TrackRepository.get(getApplication())
                    // 记账：任何"从服务器拉下来的轨迹"都打上已下载标记，
                    // 运动页「加载轨迹 → 已下载」栏靠它过滤
                    val store = TrackDownloadStore.get(getApplication())
                    // 本地已有（比如自己同步过的那条）就直接打开，省一次网络请求
                    val local = repo.load(route.id)
                    if (local != null) {
                        store.mark(local.id, route.ownerNickname)
                        local.id
                    } else {
                        val json = CloudTrackApi(serverUrl).downloadTrack(route.userId, route.id)
                            ?: error("服务器上没有这条线路的轨迹数据")
                        val track = repo.parseTrack(json)
                        repo.save(track)
                        store.mark(track.id, route.ownerNickname)
                        track.id
                    }
                }.fold(
                    onSuccess = { it to (null as String?) },
                    onFailure = { (null as String?) to (it.message ?: "打开这条线路失败") }
                )
            }
            _opening.value = false
            _openError.value = err
            if (id != null) _openedTrackId.value = id
        }
    }

    /** 详情页已打开，清掉这个一次性事件，便于下次点击同类线路再触发 */
    fun consumeOpenedTrack() {
        _openedTrackId.value = null
    }

    companion object {
        private const val MAX_ROUTES = 6
    }
}
