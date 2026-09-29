package com.example.myfirstapp.data.user

import android.content.Context

/**
 * 云端会话：把「云端账号」返回的服务器用户 id 记到 SharedPreferences，
 * 供轨迹同步时把本地轨迹关联到正确的云端账号。
 *
 * 与本地账号（UserRepository）是两套独立体系——本地登录态走 user_session，
 * 云端登录态走 cloud_server（和服务器地址共用一份偏好文件）。
 */
object CloudSession {

    private const val PREFS = "cloud_server"
    private const val KEY_UID = "cloud_user_id"

    /** 当前已登录的云端用户 id；未登录返回 null */
    fun getUserId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_UID, null)
            ?.takeIf { it.isNotBlank() }

    /** 云端注册/登录成功后写入 */
    fun saveUserId(context: Context, userId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_UID, userId).apply()
    }

    /** 退出云端登录时清除（目前云账号页未提供退出，预留） */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_UID).apply()
    }
}
