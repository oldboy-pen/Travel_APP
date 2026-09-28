package com.example.myfirstapp.data.user

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 认证结果：UI 据此决定跳转还是显示错误 */
sealed interface AuthResult {
    data class Success(val user: UserAccount) : AuthResult
    data class Failure(val message: String) : AuthResult
}

/**
 * 账号仓库：本地持久化（与轨迹库一个套路，org.json 手写序列化，不引第三方库）
 *
 * - 账号表：filesDir/users.json
 * - 登录态：SharedPreferences 只存当前账号 id，账号本体从文件读，避免两份数据不一致
 */
class UserRepository private constructor(context: Context) {

    private val file = File(context.filesDir, "users.json")
    private val prefs = context.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE)
    private val lock = Any()

    private val _currentUser = MutableStateFlow<UserAccount?>(restoreSession())
    val currentUser: StateFlow<UserAccount?> = _currentUser.asStateFlow()

    /** 注册：用户名唯一（忽略大小写），昵称留空则用用户名兜底 */
    fun register(username: String, password: String, nickname: String): AuthResult {
        val users = readAll().toMutableList()
        if (users.any { it.username.equals(username, ignoreCase = true) }) {
            return AuthResult.Failure("该用户名已被注册")
        }
        val salt = PasswordHasher.newSalt()
        val account = UserAccount(
            id = "u_${System.currentTimeMillis()}",
            username = username,
            nickname = nickname.trim().ifEmpty { username },
            passwordHash = PasswordHasher.hash(password, salt),
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            createdAt = System.currentTimeMillis()
        )
        users.add(account)
        writeAll(users)
        signIn(account)
        return AuthResult.Success(account)
    }

    /** 登录：用户名不存在和密码错误返回同一句提示，不暴露账号是否注册过 */
    fun login(username: String, password: String): AuthResult {
        val user = readAll().firstOrNull { it.username.equals(username, ignoreCase = true) }
            ?: return AuthResult.Failure("用户名或密码错误")
        if (!PasswordHasher.verify(password, user.salt, user.passwordHash)) {
            return AuthResult.Failure("用户名或密码错误")
        }
        signIn(user)
        return AuthResult.Success(user)
    }

    fun logout() {
        prefs.edit().remove(KEY_USER_ID).apply()
        _currentUser.value = null
    }

    // ---------- 持久化 ----------

    private fun signIn(user: UserAccount) {
        prefs.edit().putString(KEY_USER_ID, user.id).apply()
        _currentUser.value = user
    }

    /** 冷启动恢复登录态：账号可能已被清掉，读不到就当未登录 */
    private fun restoreSession(): UserAccount? {
        val id = prefs.getString(KEY_USER_ID, null) ?: return null
        return readAll().firstOrNull { it.id == id }
    }

    private fun readAll(): List<UserAccount> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        runCatching {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        UserAccount(
                            id = o.getString("id"),
                            username = o.getString("username"),
                            nickname = o.optString("nickname", o.getString("username")),
                            passwordHash = o.getString("pwdHash"),
                            salt = o.getString("salt"),
                            createdAt = o.optLong("createdAt", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(users: List<UserAccount>) = synchronized(lock) {
        val arr = JSONArray()
        users.forEach { u ->
            arr.put(JSONObject().apply {
                put("id", u.id)
                put("username", u.username)
                put("nickname", u.nickname)
                put("pwdHash", u.passwordHash)
                put("salt", u.salt)
                put("createdAt", u.createdAt)
            })
        }
        file.writeText(arr.toString())
    }

    companion object {
        private const val PREFS_SESSION = "user_session"
        private const val KEY_USER_ID = "current_user_id"

        @Volatile private var instance: UserRepository? = null

        fun get(context: Context): UserRepository =
            instance ?: synchronized(this) {
                instance ?: UserRepository(context.applicationContext).also { instance = it }
            }
    }
}
