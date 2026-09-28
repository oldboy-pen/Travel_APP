package com.example.myfirstapp.data.user

/**
 * 本地账号模型
 *
 * 密码一律不落明文：只保存随机盐 + PBKDF2 哈希（见 PasswordHasher），
 * 换机/清数据后账号随之消失，这是纯本地账号方案的固有取舍。
 */
data class UserAccount(
    val id: String,          // 账号唯一 id
    val username: String,    // 登录名（唯一，比对时统一小写）
    val nickname: String,    // 展示昵称，注册时未填则回退为用户名
    val passwordHash: String,// PBKDF2 哈希（Base64）
    val salt: String,        // 随机盐（Base64）
    val createdAt: Long      // 注册时间（毫秒）
)

/** 注册/登录的校验规则：checkXxx 返回 null 表示通过，否则返回可直接展示的错误提示 */
object AccountRules {
    const val USERNAME_MIN = 3
    const val USERNAME_MAX = 20
    const val PASSWORD_MIN = 6
    const val PASSWORD_MAX = 32

    /** 只允许中英文、数字、下划线，避免空格和特殊字符带来的比对歧义 */
    private val USERNAME_PATTERN = Regex("^[a-zA-Z0-9_\\u4e00-\\u9fa5]+$")

    fun checkUsername(raw: String): String? = when {
        raw.isBlank() -> "请输入用户名"
        raw.length < USERNAME_MIN -> "用户名至少 $USERNAME_MIN 个字符"
        raw.length > USERNAME_MAX -> "用户名最多 $USERNAME_MAX 个字符"
        !USERNAME_PATTERN.matches(raw) -> "用户名只能包含中英文、数字和下划线"
        else -> null
    }

    fun checkPassword(raw: String): String? = when {
        raw.isEmpty() -> "请输入密码"
        raw.length < PASSWORD_MIN -> "密码至少 $PASSWORD_MIN 位"
        raw.length > PASSWORD_MAX -> "密码最多 $PASSWORD_MAX 位"
        else -> null
    }
}
