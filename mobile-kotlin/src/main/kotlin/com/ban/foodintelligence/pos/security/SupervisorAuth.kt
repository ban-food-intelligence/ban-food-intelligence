package com.ban.foodintelligence.pos.security

import android.content.Context
import android.os.SystemClock
import com.ban.foodintelligence.pos.BuildConfig
import com.ban.foodintelligence.pos.net.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Supervisor PIN check that releases a recall-locked terminal.
 *
 * 1. Online (preferred): POST /auth/supervisor/verify. The server compares against bcrypt
 *    hashes in MySQL, locks a device out after 5 wrong PINs and writes an audit record.
 * 2. Offline fallback: a salted PBKDF2-HMAC-SHA256 hash injected at build time from the
 *    gitignored local.properties (`SUPERVISOR_PIN_PBKDF2=iterations:saltHex:hashHex`).
 *    No PIN, hash or salt is in the source code. If it is not configured, offline unlock
 *    is unavailable and the cashier is told to reconnect.
 *
 * Both paths share a local throttle (5 attempts, then 5 minutes, doubling up to 30 min)
 * stored with `elapsedRealtime`, so killing the app does not reset it.
 */
object SupervisorAuth {

    private const val MAX_ATTEMPTS = 5
    private const val BASE_LOCKOUT_MS = 5 * 60_000L
    private const val MAX_LOCKOUT_MS = 30 * 60_000L

    private const val KEY_FAILURES = "supervisor_pin_failures"
    private const val KEY_LOCKOUT_START = "supervisor_lockout_start_elapsed"
    private const val KEY_LOCKOUT_DURATION = "supervisor_lockout_duration_ms"
    private const val KEY_AUDIT = "supervisor_unlock_audit"

    sealed interface Result {
        data class Success(val supervisor: String, val offline: Boolean) : Result
        data class WrongPin(val attemptsLeft: Int) : Result
        data class LockedOut(val remainingMs: Long) : Result
        /** Server unreachable and no offline hash configured. */
        data object OfflineUnavailable : Result
    }

    /** One unlock (or denied attempt) as shown in the local history. */
    data class AuditEntry(val atMs: Long, val supervisor: String, val lotNo: String, val recallCode: String, val offline: Boolean)

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }

    val isOfflineConfigured: Boolean
        get() = parseOfflineHash() != null

    suspend fun verify(
        context: Context,
        pin: String,
        deviceId: String,
        lotNo: String,
        recallCode: String
    ): Result = withContext(Dispatchers.IO) {
        remainingLockoutMs(context).let { if (it > 0) return@withContext Result.LockedOut(it) }

        val online = try {
            verifyOnline(context, pin, deviceId, lotNo, recallCode)
        } catch (e: IOException) {
            null
        }

        val result = online ?: run {
            val offline = parseOfflineHash() ?: return@withContext Result.OfflineUnavailable
            if (pbkdf2Matches(pin, offline)) Result.Success(supervisor = "", offline = true) else Result.WrongPin(0)
        }

        when (result) {
            is Result.Success -> {
                clearFailures(context)
                appendAudit(context, AuditEntry(System.currentTimeMillis(), result.supervisor, lotNo, recallCode, result.offline))
                result
            }
            is Result.WrongPin -> recordFailure(context)
            else -> result
        }
    }

    /** Returns null when the server cannot be reached (caller falls back to offline). */
    private fun verifyOnline(context: Context, pin: String, deviceId: String, lotNo: String, recallCode: String): Result? {
        val body = JSONObject()
            .put("pin", pin).put("device_id", deviceId).put("lot_no", lotNo).put("recall_code", recallCode)
            .toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(ApiConfig.baseUrl(context) + "/auth/supervisor/verify")
            .header("Accept-Language", Locale.getDefault().language)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrDefault(JSONObject())
            return when (response.code) {
                200 -> Result.Success(supervisor = json.optString("display_name"), offline = false)
                401 -> Result.WrongPin(json.optJSONObject("details")?.optInt("attempts_left") ?: 0)
                429 -> Result.LockedOut((json.optJSONObject("details")?.optLong("retry_after_seconds") ?: 300L) * 1000L)
                else -> null
            }
        }
    }

    /** Remaining throttle time in ms, or 0 if entry is allowed. */
    fun remainingLockoutMs(context: Context): Long {
        val prefs = TerminalLockStore.prefs(context)
        val duration = prefs.getLong(KEY_LOCKOUT_DURATION, 0L)
        if (duration <= 0L) return 0L
        val start = prefs.getLong(KEY_LOCKOUT_START, 0L)
        val now = SystemClock.elapsedRealtime()
        if (now < start) {
            // elapsedRealtime went backwards: the device rebooted. Restart the penalty.
            prefs.edit().putLong(KEY_LOCKOUT_START, now).commit()
            return duration
        }
        return (duration - (now - start)).coerceAtLeast(0L)
    }

    fun auditLog(context: Context): List<AuditEntry> {
        val raw = TerminalLockStore.prefs(context).getString(KEY_AUDIT, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AuditEntry(o.getLong("at"), o.optString("by"), o.optString("lot"), o.optString("recall"), o.optBoolean("offline"))
        }
    }

    private fun appendAudit(context: Context, e: AuditEntry) {
        val prefs = TerminalLockStore.prefs(context)
        val arr = runCatching { JSONArray(prefs.getString(KEY_AUDIT, "[]")) }.getOrDefault(JSONArray())
        val updated = JSONArray().put(
            JSONObject().put("at", e.atMs).put("by", e.supervisor).put("lot", e.lotNo).put("recall", e.recallCode).put("offline", e.offline)
        )
        for (i in 0 until minOf(arr.length(), 49)) updated.put(arr.get(i))
        prefs.edit().putString(KEY_AUDIT, updated.toString()).commit()
    }

    private fun recordFailure(context: Context): Result {
        val prefs = TerminalLockStore.prefs(context)
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILURES, failures)
        val left = MAX_ATTEMPTS - failures
        if (left <= 0) {
            val steps = (failures - MAX_ATTEMPTS).coerceIn(0, 3)
            val duration = (BASE_LOCKOUT_MS shl steps).coerceAtMost(MAX_LOCKOUT_MS)
            editor.putLong(KEY_LOCKOUT_START, SystemClock.elapsedRealtime()).putLong(KEY_LOCKOUT_DURATION, duration).commit()
            return Result.LockedOut(duration)
        }
        editor.commit()
        return Result.WrongPin(left)
    }

    private fun clearFailures(context: Context) {
        TerminalLockStore.prefs(context).edit()
            .putInt(KEY_FAILURES, 0).putLong(KEY_LOCKOUT_START, 0L).putLong(KEY_LOCKOUT_DURATION, 0L).commit()
    }

    private data class OfflineHash(val iterations: Int, val salt: ByteArray, val hash: ByteArray)

    private fun parseOfflineHash(): OfflineHash? {
        val parts = BuildConfig.SUPERVISOR_PIN_PBKDF2.split(':')
        if (parts.size != 3) return null
        val iterations = parts[0].toIntOrNull() ?: return null
        return runCatching { OfflineHash(iterations, hexToBytes(parts[1]), hexToBytes(parts[2])) }.getOrNull()
    }

    private fun pbkdf2Matches(pin: String, h: OfflineHash): Boolean {
        val spec = PBEKeySpec(pin.toCharArray(), h.salt, h.iterations, h.hash.size * 8)
        return try {
            val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            MessageDigest.isEqual(derived, h.hash)
        } finally {
            spec.clearPassword()
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
