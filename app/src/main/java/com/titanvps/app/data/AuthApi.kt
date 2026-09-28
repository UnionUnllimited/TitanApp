package com.titanvps.app.data

import com.titanvps.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Client for the TitanVPS auth server (see /server in the repo). */
class AuthApi(private val baseUrl: String = BuildConfig.AUTH_URL.trimEnd('/')) {

    class AuthException(message: String) : Exception(message)

    data class TelegramLogin(val token: String, val url: String)

    sealed interface PollResult {
        data object Pending : PollResult
        data object NotFound : PollResult
        data object Expired : PollResult
        data class Ok(val subscriptionUrl: String) : PollResult
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun startTelegram(): TelegramLogin {
        val o = call(Request.Builder().url("$baseUrl/api/tg/start").post(EMPTY).build())
        return TelegramLogin(o.getString("token"), o.getString("url"))
    }

    suspend fun pollTelegram(token: String): PollResult {
        val o = call(Request.Builder().url("$baseUrl/api/tg/poll?token=$token").build())
        return when (o.optString("status")) {
            "ok" -> PollResult.Ok(o.getString("subscriptionUrl"))
            "not_found" -> PollResult.NotFound
            "expired" -> PollResult.Expired
            else -> PollResult.Pending
        }
    }

    suspend fun requestEmailCode(email: String) {
        call(post("/api/email/start", JSONObject().put("email", email)))
    }

    suspend fun verifyEmailCode(email: String, code: String): String =
        call(post("/api/email/verify", JSONObject().put("email", email).put("code", code)))
            .getString("subscriptionUrl")

    private fun post(path: String, body: JSONObject) = Request.Builder()
        .url(baseUrl + path)
        .post(body.toString().toRequestBody(JSON))
        .build()

    private suspend fun call(request: Request): JSONObject = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body.string()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (!resp.isSuccessful) {
                    throw AuthException(json?.optString("detail")?.takeIf { it.isNotBlank() } ?: "Ошибка сервера (${resp.code})")
                }
                json ?: throw AuthException("Некорректный ответ сервера")
            }
        } catch (e: IOException) {
            throw AuthException("Нет соединения с сервером")
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val EMPTY = ByteArray(0).toRequestBody(null)
    }
}
