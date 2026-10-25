package com.ban.foodintelligence.pos.security

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * ============================================================================
 * Supervisor emergency-unlock PIN verification
 * Author: Nuri (Principal Android Security Architect)
 * ============================================================================
 *
 * The PIN is hardcoded as requested, but only as a salted PBKDF2-HMAC-SHA256
 * hash ([PIN_HASH_HEX]); the PIN itself is not in the source. Verification is
 * constant-time and attempt-throttled with persisted counters, so killing the
 * process does not reset the brute-force penalty.
 *
 * LIMITS, stated plainly:
 * - A 6-digit PIN has only 1,000,000 values. Anyone who extracts this hash and
 *   salt from the APK can brute-force it offline quickly, PBKDF2 or not.
 * - One PIN shared by every terminal means one leak unlocks all of them.
 * - This is a stopgap. The durable design is challenge-response: the terminal
 *   shows a nonce, a supervisor signs it with a key held by the backend/HSM,
 *   and the device verifies that signature with an embedded public key.
 *
 * Throttle timing uses `SystemClock.elapsedRealtime()` (not settable by the user).
 * It resets on reboot; that is detected (`now < start`) and the penalty restarts.
 */
object SupervisorAuth {

    private const val TAG = "SupervisorAuth"

    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_HEX = "b7e2a91c04d35f68a1c97e20d4b86f13"
    private const val PIN_HASH_HEX = "76790db407f3b0b4514ab816f6b20fc21ee9f566fc9578bf8fe5bb83204c22b5"

    private const val MIN_PIN_LENGTH = 4
    private const val MAX_PIN_LENGTH = 12

    private const val FREE_ATTEMPTS = 3
    private const val BASE_LOCKOUT_MS = 30_000L
    private const val MAX_LOCKOUT_MS = 15 * 60_000L

    private const val KEY_FAILURES = "supervisor_pin_failures"
    private const val KEY_LOCKOUT_START = "supervisor_lockout_start_elapsed"
    private const val KEY_LOCKOUT_DURATION = "supervisor_lockout_duration_ms"

    sealed class Result {
        object Success : Result()
        data class WrongPin(val freeAttemptsLeft: Int) : Result()
        data class LockedOut(val remainingMs: Long) : Result()
    }

    /** CPU-heavy (PBKDF2). Call from a background dispatcher, not the main thread. */
    fun verifyPin(context: Context, pin: String): Result {
        val prefs = TerminalLockStore.prefs(context)

        val remaining = remainingLockoutMs(context)
        if (remaining > 0) {
            Log.w(TAG, "⛔ PIN entry locked out for ${remaining / 1000}s.")
            return Result.LockedOut(remaining)
        }

        val matches = pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() } && pinMatches(pin)

        if (matches) {
            prefs.edit()
                .putInt(KEY_FAILURES, 0)
                .putLong(KEY_LOCKOUT_START, 0L)
                .putLong(KEY_LOCKOUT_DURATION, 0L)
                .commit()
            Log.w(TAG, "✅ Supervisor PIN accepted.")
            return Result.Success
        }

        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILURES, failures)
        if (failures >= FREE_ATTEMPTS) {
            val steps = (failures - FREE_ATTEMPTS).coerceAtMost(10)
            val duration = (BASE_LOCKOUT_MS shl steps).coerceAtMost(MAX_LOCKOUT_MS)
            editor.putLong(KEY_LOCKOUT_START, SystemClock.elapsedRealtime())
            editor.putLong(KEY_LOCKOUT_DURATION, duration)
            Log.e(TAG, "🚫 Wrong PIN ($failures consecutive). Locked out for ${duration / 1000}s.")
        } else {
            Log.e(TAG, "🚫 Wrong PIN ($failures consecutive).")
        }
        editor.commit()
        return Result.WrongPin(freeAttemptsLeft = (FREE_ATTEMPTS - failures).coerceAtLeast(0))
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

    private fun pinMatches(pin: String): Boolean {
        val spec = PBEKeySpec(pin.toCharArray(), hexToBytes(SALT_HEX), PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return try {
            val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            MessageDigest.isEqual(derived, hexToBytes(PIN_HASH_HEX))
        } finally {
            spec.clearPassword()
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
