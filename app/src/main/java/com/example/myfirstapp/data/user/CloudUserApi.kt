package com.example.myfirstapp.data.user

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 云端接口错误：message 可直接展示给用户（含服务器不可达、业务报错） */
class CloudApiException(message: String) : Exception(message)

/**
 * 临时云端账号 API 客户端（本机 FastAPI 服务器，验证用）
 *
 * 不引 OkHttp/Retrofit，用 HttpURLConnection + org.json，跟项目"少依赖"的调性一致。
 * 全部是阻塞方法，调用方要放在 Dispatchers.IO 上，否则主线程会抛 NetworkOnMainThreadException。
 *
 * @param baseUrl 形如 http://192.168.1.23:8000（真机填电脑局域网 IP，模拟器填 http://10.0.2.2:8000）
 */
class CloudUserApi(private val baseUrl: String) {

    data class CloudUser(
        val id: String,
        val username: String,
        val nickname: String,
        val createdAt: Long
    )

    /** 健康检查：服务器在不在、地址对不对 */
    fun health(): Boolean = runCatching {
        get("/health").optBoolean("ok", false)
    }.getOrDefault(false)

    fun register(username: String, password: String, nickname: String): CloudUser =
        parseUser(
            post("/api/register", JSONObject().apply {
                put("username", username)
                put("password", password)
                put("nickname", nickname)
            })
        )

    fun login(username: String, password: String): CloudUser =
        parseUser(
            post("/api/login", JSONObject().apply {
                put("username", username)
                put("password", password)
            })
        )

    /** 拉取服务器已保存的用户，用来确认"数据真的落库了" */
    fun listUsers(): List<CloudUser> {
        val obj = get("/api/users")
        val arr = obj.optJSONArray("users") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    CloudUser(
                        id = o.optString("id"),
                        username = o.optString("username"),
                        nickname = o.optString("nickname"),
                        createdAt = o.optLong("createdAt")
                    )
                )
            }
        }
    }

    // ---------- 内部实现 ----------

    private fun parseUser(obj: JSONObject): CloudUser {
        val u = obj.optJSONObject("user")
            ?: throw CloudApiException("响应格式异常：缺少 user 字段")
        return CloudUser(
            id = u.optString("id"),
            username = u.optString("username"),
            nickname = u.optString("nickname"),
            createdAt = u.optLong("createdAt")
        )
    }

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
            readTimeout = 8000
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
