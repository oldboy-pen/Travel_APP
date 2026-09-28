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

/**
 * 账号 ViewModel：把耗时的 PBKDF2 计算挪到后台线程，回调回到主线程更新 UI。
 *
 * 注册/登录成功后账号会写进 UserRepository.currentUser，
 * 监听它的页面（「我的」页）会自动刷新，无需手动回传。
 */
class UserViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = UserRepository.get(app)

    val currentUser: StateFlow<UserAccount?> = repo.currentUser

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun register(username: String, password: String, nickname: String, onResult: (AuthResult) -> Unit) {
        runAuth(onResult) { repo.register(username, password, nickname) }
    }

    fun login(username: String, password: String, onResult: (AuthResult) -> Unit) {
        runAuth(onResult) { repo.login(username, password) }
    }

    fun logout() = repo.logout()

    /** 登录态检查：需要登录才能操作的入口先问它 */
    fun isLoggedIn(): Boolean = repo.currentUser.value != null

    private fun runAuth(onResult: (AuthResult) -> Unit, block: () -> AuthResult) {
        viewModelScope.launch {
            _busy.value = true
            // PBKDF2 是纯 CPU 活儿，放 Default 线程池；withContext 结束后自动回到主线程
            val result = withContext(Dispatchers.Default) { block() }
            _busy.value = false
            onResult(result)
        }
    }
}
