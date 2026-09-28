package com.example.myfirstapp.data.user

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 云端账号验证页的 ViewModel
 *
 * 服务器地址记在 SharedPreferences 里，下次进来不用重填。
 * 所有网络调用走 Dispatchers.IO，结果只回传一句可展示的文案。
 */
class CloudUserViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _users = MutableStateFlow<List<CloudUserApi.CloudUser>>(emptyList())
    val users: StateFlow<List<CloudUserApi.CloudUser>> = _users.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setServerUrl(url: String) {
        val trimmed = url.trim()
        prefs.edit().putString(KEY_URL, trimmed).apply()
        _serverUrl.value = trimmed
    }

    fun register(username: String, password: String, nickname: String) = runApi {
        val user = it.register(username.trim(), password, nickname.trim())
        fetchUsers(it)
        "注册成功：${user.nickname} 已存入服务器（id=${user.id}）"
    }

    fun login(username: String, password: String) = runApi {
        val user = it.login(username.trim(), password)
        "登录成功：${user.nickname}（id=${user.id}）"
    }

    /** 拉取服务器用户列表，验证数据确实落库 */
    fun refreshUsers() = runApi {
        fetchUsers(it)
        "已拉取服务器用户列表"
    }

    fun clearMessage() { _message.value = null }

    // ---------- 内部 ----------

    private fun fetchUsers(api: CloudUserApi) {
        _users.value = api.listUsers()
    }

    /** block 在 IO 线程执行，返回成功文案；异常统一转成「失败：xxx」 */
    private fun runApi(block: (CloudUserApi) -> String) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            val text = withContext(Dispatchers.IO) {
                runCatching { block(CloudUserApi(_serverUrl.value)) }
                    .getOrElse { e -> "失败：${e.message ?: e::class.java.simpleName}" }
            }
            _busy.value = false
            _message.value = text
        }
    }

    companion object {
        private const val PREFS = "cloud_server"
        private const val KEY_URL = "server_url"
        // Android 模拟器访问宿主机的固定地址；真机要改成电脑的局域网 IP
        const val DEFAULT_URL = "http://10.0.2.2:8000"
    }
}
