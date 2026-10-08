package com.example.myfirstapp.data.user

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 个人资料（头像 / 简介）+ 关注关系：本机持久化，SharedPreferences，不依赖服务器。
 *
 * 用户标识统一用带前缀的 key，避免本机账号与服务器账号的 id 撞车：
 * - `local:<accountId>`  本机注册的账号（[UserRepository]）
 * - `cloud:<userId>`     服务器账号（[CloudUserApi]）
 *
 * 关注关系双向记账：关注 A→B 时同时写 following[A] 与 followers[B]，
 * 这样「粉丝查询」是一次 O(1) 读取，不必反扫整张关系表。
 *
 * UI 侧的刷新方式：订阅 [revision]（任何写操作都会 +1），再用 remember(revision, key)
 * 调本对象的读取方法重新取值。
 */
object UserProfileStore {

    private const val PREFS = "user_profiles"
    private const val PREFIX_AVATAR = "avatar_"
    private const val PREFIX_BIO = "bio_"
    private const val PREFIX_NAME = "name_"
    private const val PREFIX_FOLLOWING = "following_"
    private const val PREFIX_FOLLOWERS = "followers_"

    private val avatars = HashMap<String, String>()
    private val bios = HashMap<String, String>()
    private val names = HashMap<String, String>()
    private val following = HashMap<String, MutableSet<String>>()
    private val followers = HashMap<String, MutableSet<String>>()

    private var loaded = false

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 载入全部资料（幂等，页面进入时调用即可） */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val all = prefs(context).all
        all.forEach { (k, v) ->
            when {
                k.startsWith(PREFIX_AVATAR) && v is String ->
                    avatars[k.removePrefix(PREFIX_AVATAR)] = v
                k.startsWith(PREFIX_BIO) && v is String ->
                    bios[k.removePrefix(PREFIX_BIO)] = v
                k.startsWith(PREFIX_NAME) && v is String ->
                    names[k.removePrefix(PREFIX_NAME)] = v
                k.startsWith(PREFIX_FOLLOWING) && v is Set<*> ->
                    following[k.removePrefix(PREFIX_FOLLOWING)] = toStringSet(v)
                k.startsWith(PREFIX_FOLLOWERS) && v is Set<*> ->
                    followers[k.removePrefix(PREFIX_FOLLOWERS)] = toStringSet(v)
            }
        }
        _revision.value++
    }

    @Suppress("UNCHECKED_CAST")
    private fun toStringSet(raw: Set<*>): MutableSet<String> =
        raw.mapNotNull { it as? String }.toMutableSet()

    // ---------- 头像 / 简介 / 昵称缓存 ----------

    fun avatarOf(key: String): String? = avatars[key]?.takeIf { it.isNotBlank() }

    fun setAvatar(context: Context, key: String, uri: String?) {
        if (uri.isNullOrBlank()) {
            avatars.remove(key)
            prefs(context).edit().remove(PREFIX_AVATAR + key).apply()
        } else {
            avatars[key] = uri
            prefs(context).edit().putString(PREFIX_AVATAR + key, uri).apply()
        }
        _revision.value++
    }

    fun bioOf(key: String): String = bios[key].orEmpty()

    fun setBio(context: Context, key: String, text: String) {
        val v = text.trim()
        if (v.isEmpty()) {
            bios.remove(key)
            prefs(context).edit().remove(PREFIX_BIO + key).apply()
        } else {
            bios[key] = v
            prefs(context).edit().putString(PREFIX_BIO + key, v).apply()
        }
        _revision.value++
    }

    /** 缓存某个 key 的展示名（粉丝列表要显示名字，云端用户离线时也能解析） */
    fun cacheName(context: Context, key: String, name: String) {
        if (key.isBlank() || name.isBlank() || names[key] == name) return
        names[key] = name
        prefs(context).edit().putString(PREFIX_NAME + key, name).apply()
        _revision.value++
    }

    fun cachedNameOf(key: String): String? = names[key]?.takeIf { it.isNotBlank() }

    // ---------- 关注关系 ----------

    fun followingOf(key: String): Set<String> = following[key]?.toSet() ?: emptySet()

    fun followersOf(key: String): Set<String> = followers[key]?.toSet() ?: emptySet()

    fun isFollowing(from: String, to: String): Boolean =
        following[from]?.contains(to) == true

    /**
     * 关注 / 取关。自己关注自己直接忽略（返回 false），避免出现"我是自己的粉丝"。
     * @return 操作后的关注状态（true=已关注）
     */
    fun setFollow(context: Context, from: String, to: String, follow: Boolean): Boolean {
        if (from.isBlank() || to.isBlank() || from == to) return false
        val fs = following.getOrPut(from) { mutableSetOf() }
        val rs = followers.getOrPut(to) { mutableSetOf() }
        if (follow) {
            fs.add(to)
            rs.add(from)
        } else {
            fs.remove(to)
            rs.remove(from)
        }
        val editor = prefs(context).edit()
            .putStringSet(PREFIX_FOLLOWING + from, fs)
            .putStringSet(PREFIX_FOLLOWERS + to, rs)
        editor.apply()
        _revision.value++
        return follow
    }

    /** 关注状态取反，返回操作后的状态 */
    fun toggleFollow(context: Context, from: String, to: String): Boolean =
        setFollow(context, from, to, !isFollowing(from, to))

    // ---------- key 工具 ----------

    fun localKey(accountId: String): String = "local:$accountId"

    fun cloudKey(userId: String): String = "cloud:$userId"
}
