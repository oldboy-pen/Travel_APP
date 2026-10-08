package com.example.myfirstapp.data.user

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用户目录：给「关注 / 粉丝」提供"人"的来源与名字解析。
 *
 * 现阶段没有服务器端的关注接口，所以可关注的人来自两处（都失败也只是列表为空，
 * 不会打断页面）：
 * - 本机注册的其他账号（换一个账号登录即可互相关注，粉丝列表立刻能看到）；
 * - 服务器 /api/users（服务器在线时），用于真机多用户场景。
 */
object SocialDirectory {

    /** 一个可被关注的用户 */
    data class Person(
        val key: String,      // UserProfileStore 的统一 key
        val name: String,     // 展示名
        val desc: String      // 副标题（来源说明）
    )

    private const val PREFS_CLOUD = "cloud_server"
    private const val KEY_URL = "server_url"
    private const val DEFAULT_URL = "http://10.0.2.2:8000"

    /**
     * 发现用户（排除自己）。网络部分失败静默降级为"只有本机账号"。
     * 必须在 IO 线程调用（内部已切 [Dispatchers.IO]）。
     */
    suspend fun discover(context: Context, selfKey: String): List<Person> =
        withContext(Dispatchers.IO) {
            val local = UserRepository.get(context).allUsers()
                .filter { UserProfileStore.localKey(it.id) != selfKey }
                .map {
                    Person(
                        key = UserProfileStore.localKey(it.id),
                        name = it.nickname,
                        desc = "@${it.username} · 本机账号"
                    )
                }

            val cloud = runCatching {
                val url = context.getSharedPreferences(PREFS_CLOUD, Context.MODE_PRIVATE)
                    .getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
                CloudUserApi(url).listUsers().map {
                    Person(
                        key = UserProfileStore.cloudKey(it.id),
                        name = it.nickname,
                        desc = "@${it.username} · 服务器"
                    )
                }
            }.getOrDefault(emptyList())

            (local + cloud).distinctBy { it.key }
        }

    /**
     * 解析某个 key 的展示名：本机账号实时查账号表，其余走 [UserProfileStore] 的缓存
     * （关注/拉取目录时会写入缓存），都没解析出就返回一个可读的兜底串。
     */
    fun nameOf(context: Context, key: String): String {
        if (key.startsWith("local:")) {
            val id = key.removePrefix("local:")
            UserRepository.get(context).allUsers()
                .firstOrNull { it.id == id }?.nickname?.let { return it }
        }
        UserProfileStore.cachedNameOf(key)?.let { return it }
        return when {
            key.startsWith("cloud:") -> "服务器用户 ${key.removePrefix("cloud:").take(6)}"
            key.isBlank() -> "未知用户"
            else -> key.substringAfter(':')
        }
    }

    /** 副标题：说明这个 key 来自本机还是服务器 */
    fun descOf(key: String): String = when {
        key.startsWith("local:") -> "本机账号"
        key.startsWith("cloud:") -> "服务器账号"
        else -> ""
    }
}
