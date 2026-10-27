package dev.podhod.training.data

import dev.podhod.core.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class CloudFailure(val status: Int, val code: String) : IOException("Сервер вернул ошибку $status ($code).")
class CloudAPI(val baseURL: String, private val token: suspend () -> String, private val isCurrent: () -> Boolean) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS).build()
    init { requireHttps(baseURL) }
    suspend fun request(path: String, body: JsonObject? = null): JsonObject {
        check(isCurrent()) { "Аккаунт изменился." }
        val bearer = token()
        return withContext(Dispatchers.IO) {
            check(isCurrent())
            val builder = Request.Builder().url(baseURL.trimEnd('/') + path).header("Accept", "application/json").header("Authorization", "Bearer $bearer")
            if (body != null) builder.put(body.toString().toRequestBody("application/json".toMediaType()))
            http.newCall(builder.build()).execute().use { response ->
                check(isCurrent()) { "Аккаунт изменился." }
                val text = response.body?.string() ?: throw IOException("Пустой ответ сервера.")
                if (!response.isSuccessful) {
                    val code = runCatching { archiveJson.parseToJsonElement(text).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content }.getOrDefault("http_error")
                    throw CloudFailure(response.code, code)
                }
                archiveJson.parseToJsonElement(text).jsonObject
            }
        }
    }
    suspend inline fun <reified T> read(path: String): T = archiveJson.decodeFromJsonElement(request(path))
    suspend inline fun <reified T> write(path: String, expectedRevision: Long?, content: JsonObject): T {
        val body = buildJsonObject {
            expectedRevision?.let { put("expectedRevision", it) }
            put("requestID", UUID.randomUUID().toString())
            content.forEach { (key, value) -> put(key, value) }
        }
        return archiveJson.decodeFromJsonElement(request(path, body))
    }
}
