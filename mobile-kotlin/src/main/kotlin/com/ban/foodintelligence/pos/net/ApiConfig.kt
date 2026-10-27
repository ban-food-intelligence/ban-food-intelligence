package com.ban.foodintelligence.pos.net

import android.content.Context
import com.ban.foodintelligence.pos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Result of a GET /health round trip. */
data class HealthCheck(val latencyMs: Long, val databaseOk: Boolean, val version: String)

/**
 * Where the Go API lives. Stored on the device so the same APK works on the emulator,
 * on a USB-connected phone (`adb reverse tcp:8080 tcp:8080`) and on a phone in the LAN.
 */
object ApiConfig {

    enum class Preset(val url: String?) {
        USB("http://127.0.0.1:8080"),
        EMULATOR("http://10.0.2.2:8080"),
        LAN(null)
    }

    private const val PREFS = "pos_api_config"
    private const val KEY_BASE_URL = "base_url"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    fun baseUrl(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, null) ?: BuildConfig.POS_API_BASE_URL

    fun setBaseUrl(context: Context, url: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BASE_URL, normalize(url)).apply()
    }

    fun presetFor(url: String): Preset =
        Preset.entries.firstOrNull { it.url != null && it.url == normalize(url) } ?: Preset.LAN

    /** Adds a scheme and default port, drops trailing slashes: "192.168.1.20" -> "http://192.168.1.20:8080". */
    fun normalize(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        val hostPart = url.substringAfter("://")
        if (!hostPart.contains(':')) url = "$url:8080"
        return url
    }

    fun isValid(raw: String): Boolean = raw.isNotBlank() && normalize(raw).toHttpUrlOrNull() != null

    suspend fun testConnection(url: String): Result<HealthCheck> = withContext(Dispatchers.IO) {
        runCatching {
            val started = System.nanoTime()
            val request = Request.Builder().url(normalize(url) + "/health").get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val json = JSONObject(body)
                HealthCheck(
                    latencyMs = (System.nanoTime() - started) / 1_000_000,
                    databaseOk = json.optString("mysql_status") == "CONNECTED",
                    version = json.optString("version", "?")
                )
            }
        }
    }
}
