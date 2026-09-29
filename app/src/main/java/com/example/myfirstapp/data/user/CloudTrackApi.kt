package com.example.myfirstapp.data.user

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端轨迹同步 API 客户端（本机 FastAPI 服务器，验证用）
 *
 * 沿用 CloudUserApi 的套路：不引 OkHttp/Retrofit，用 HttpURLConnection +
 * org.json。全部阻塞方法，调用方务必放在 Dispatchers.IO 上。
 *
 * 与服务端约定：
 *   POST /api/tracks      {"user_id","tracks":[ JSONObject... ]} → {"synced":N}
 *   GET  /api/tracks?user_id= → {"count", "tracks":[ 摘要... ]}
 *
 * @param baseUrl 与 CloudUserApi 共用同一份服务器地址
 */
class CloudTrackApi(private val baseUrl: String) {

    /** 服务器返回的轨迹摘要（不含点位，用于标记已同步） */
    data class TrackSummary(
        val id: String,
        val name: String,
        val distance: Double,
        val startTime: Long,
        val pointCount: Int,
        val waypointCount: Int,
        val activityType: String,
        val updatedAt: Long
    )

    /** 批量上传轨迹（已序列化的 JSONObject 列表），返回成功落库条数 */
    fun uploadTracks(userId: String, tracks: List<JSONObject>): Int {
        val body = JSONObject().apply {
            put("user_id", userId)
            put("tracks", JSONArray(tracks))
        }
        return post("/api/tracks", body).optInt("synced", 0)
    }

    /** 拉取某用户在服务器上的轨迹摘要，用于标记哪些已同步 */
    fun listTracks(userId: String): List<TrackSummary> {
        val obj = get("/api/tracks?user_id=" + userId)
        val arr = obj.optJSONArray("tracks") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    TrackSummary(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        distance = o.optDouble("distance"),
                        startTime = o.optLong("startTime"),
                        pointCount = o.optInt("pointCount"),
                        waypointCount = o.optInt("waypointCount"),
                        activityType = o.optString("activityType"),
                        updatedAt = o.optLong("updated_at")
                    )
                )
            }
        }
    }

    /** 服务器上某条轨迹（跨用户总览），用于首页"热门线路推荐" */
    data class ServerTrack(
        val id: String,
        val name: String,
        val distanceMeters: Double,
        val climbMeters: Double,
        val activityType: String,   // 枚举名，如 HIKING / WALKING
        val pointCount: Int,
        val waypointCount: Int,
        val ownerNickname: String,
        val updatedAt: Long
    )

    /**
     * 拉取服务器上所有用户上传的轨迹（跨账号总览），用于首页"热门线路推荐"。
     * 走 GET /api/tracks/all，服务器没数据时返回空列表（不抛异常）。
     */
    fun listAllTracks(limit: Int = 200): List<ServerTrack> {
        val obj = get("/api/tracks/all?limit=$limit")
        val arr = obj.optJSONArray("tracks") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    ServerTrack(
                        id = o.optString("id"),
                        name = o.optString("name").takeIf { it.isNotBlank() } ?: "(未命名线路)",
                        distanceMeters = o.optDouble("distance"),
                        climbMeters = o.optDouble("climb"),
                        activityType = o.optString("activityType"),
                        pointCount = o.optInt("pointCount"),
                        waypointCount = o.optInt("waypointCount"),
                        ownerNickname = o.optString("nickname"),
                        updatedAt = o.optLong("updated_at")
                    )
                )
            }
        }
    }

    // ---------- 内部实现（与 CloudUserApi 同构） ----------

    private fun get(path: String): JSONObject = request(path, "GET", null)

    private fun post(path: String, body: JSONObject): JSONObject = request(path, "POST", body)

    private fun request(path: String, method: String, body: JSONObject?): JSONObject {
        val root = baseUrl.trim().trimEnd('/')
        if (!root.startsWith("http://") && !root.startsWith("https://")) {
            throw CloudApiException("服务器地址要以 http:// 或 https:// 开头")
        }
        val conn = (URL(root + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Accept", "application/json")
            connectTimeout = 5000
            readTimeout = 15000      // 轨迹点位多，上传/拉取给长一点的超时
            if (body != null) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw CloudApiException(readDetail(text, code))
            runCatching { JSONObject(text) }
                .getOrElse { throw CloudApiException("响应不是合法 JSON：$text") }
        } finally {
            conn.disconnect()
        }
    }

    /** FastAPI 的错误体统一是 {"detail": "..."} */
    private fun readDetail(text: String, code: Int): String {
        val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
        return when {
            !detail.isNullOrBlank() -> detail
            code == 0 || code >= 500 -> "连不上服务器（$code），检查地址和电脑防火墙"
            else -> "请求失败（$code）"
        }
    }
}
