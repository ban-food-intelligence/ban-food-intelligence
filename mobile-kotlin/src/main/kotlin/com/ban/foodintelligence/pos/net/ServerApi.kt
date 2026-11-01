package com.ban.foodintelligence.pos.net

import android.content.Context
import com.ban.foodintelligence.pos.security.DeviceIdentity
import com.ban.foodintelligence.pos.ui.AppLocale
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The API's error body ({"code","message","details"}): [message] is already in the app language
 * (Accept-Language); [fieldErrors] holds a 422's per-field details ("lines[0].lot_no" -> text).
 */
class ApiError(val status: Int, val code: String, override val message: String, val fieldErrors: Map<String, String> = emptyMap()) :
    Exception(message)

/**
 * Back-office calls from the phone, as the dashboard makes them: POST /auth/session with the
 * supervisor PIN gives a 30-minute bearer token, kept in memory only (never stored), and every
 * write carries it. The audit trail names the supervisor, not the device.
 */
object ServerApi {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(100, TimeUnit.SECONDS) // reading an invoice can take a minute on the server
            .build()
    }

    private var token: String? = null
    private var expiresAt: Instant = Instant.EPOCH

    suspend fun get(context: Context, path: String): String = withContext(Dispatchers.IO) {
        execute(context, Request.Builder().url(ApiConfig.baseUrl(context) + path).get())
    }

    /** A supervisor write: signs in with [pin] first when there is no valid session. */
    suspend fun post(context: Context, path: String, json: String, pin: String): String = withContext(Dispatchers.IO) {
        val first = runCatching { postWithSession(context, path, json, pin) }
        val e = first.exceptionOrNull()
        if (e is ApiError && e.code == "AUTH_REQUIRED") {
            token = null // the API restarted: sign in again once
            postWithSession(context, path, json, pin)
        } else {
            first.getOrThrow()
        }
    }

    private fun postWithSession(context: Context, path: String, json: String, pin: String): String {
        val bearer = session(context, pin)
        return execute(
            context,
            Request.Builder().url(ApiConfig.baseUrl(context) + path)
                .header("Authorization", "Bearer $bearer")
                .post(json.toRequestBody(JSON))
        )
    }

    private fun session(context: Context, pin: String): String {
        val current = token
        if (current != null && Instant.now().plusSeconds(30).isBefore(expiresAt)) return current
        val body = Gson().toJson(mapOf("pin" to pin, "device_id" to DeviceIdentity.id(context)))
        val answer = JsonParser.parseString(
            execute(context, Request.Builder().url(ApiConfig.baseUrl(context) + "/auth/session").post(body.toRequestBody(JSON)))
        ).asJsonObject
        val fresh = answer.get("token").asString
        token = fresh
        expiresAt = runCatching { Instant.parse(answer.get("expires_at").asString) }.getOrDefault(Instant.now().plusSeconds(600))
        return fresh
    }

    private fun execute(context: Context, builder: Request.Builder): String {
        val request = builder.header("Accept", "application/json").header("Accept-Language", AppLocale.current(context)).build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiError(0, "NETWORK", e.message ?: "network error")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.isSuccessful) return text
            throw parseError(it.code, text)
        }
    }

    /** Reads the API error body; anything else becomes an HTTP_<status> error. */
    fun parseError(status: Int, text: String): ApiError {
        val obj: JsonObject? = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        val code = obj?.get("code")?.takeIf { it.isJsonPrimitive }?.asString ?: "HTTP_$status"
        val message = obj?.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "HTTP $status"
        val details = obj?.get("details")
        val fields = if (details != null && details.isJsonObject) {
            details.asJsonObject.entrySet().associate { (k, v) -> k to (if (v.isJsonPrimitive) v.asString else v.toString()) }
        } else emptyMap()
        val detailText = details?.takeIf { it.isJsonPrimitive }?.asString
        return ApiError(status, code, if (detailText != null && fields.isEmpty()) "$message ($detailText)" else message, fields)
    }
}
