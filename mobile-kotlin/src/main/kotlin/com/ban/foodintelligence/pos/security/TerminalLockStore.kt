package com.ban.foodintelligence.pos.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Snapshot of the incident that locked the terminal. Persisted so the lock
 * screen can re-render the same details after a process kill or reboot.
 */
data class LockIncident(
    val recallCode: String,
    val affectedLot: String,
    val rootLot: String,
    val oodMetric: String,
    val oodValue: Float,
    val lockedAtEpochMs: Long
)

/**
 * ============================================================================
 * Persistent terminal-lock flag (`IS_TERMINAL_LOCKED`)
 * Author: Nuri (Principal Android Security Architect)
 * ============================================================================
 *
 * Backed by `SharedPreferences` written with `commit()` (synchronous), NOT
 * `apply()`. `apply()` flushes asynchronously, so a process kill right after
 * locking could lose the flag. The file lives in app-private storage and
 * survives process death and reboot.
 *
 * Honest limit: a root attacker (or anyone who can clear app data) can delete
 * this file. Local state cannot defend against that. A device-owner/MDM policy
 * and server-side terminal status are the controls for it.
 */
object TerminalLockStore {

    private const val TAG = "TerminalLockStore"
    private const val PREFS_NAME = "ban_pos_terminal_lock_state"

    const val KEY_IS_TERMINAL_LOCKED = "IS_TERMINAL_LOCKED"
    private const val KEY_RECALL_CODE = "incident_recall_code"
    private const val KEY_AFFECTED_LOT = "incident_affected_lot"
    private const val KEY_ROOT_LOT = "incident_root_lot"
    private const val KEY_OOD_METRIC = "incident_ood_metric"
    private const val KEY_OOD_VALUE = "incident_ood_value"
    private const val KEY_LOCKED_AT = "incident_locked_at"

    internal fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isLocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_TERMINAL_LOCKED, false)

    /** Persists the lock + incident synchronously. @return true if the write was durable. */
    fun lock(context: Context, incident: LockIncident): Boolean {
        val ok = prefs(context).edit()
            .putBoolean(KEY_IS_TERMINAL_LOCKED, true)
            .putString(KEY_RECALL_CODE, incident.recallCode)
            .putString(KEY_AFFECTED_LOT, incident.affectedLot)
            .putString(KEY_ROOT_LOT, incident.rootLot)
            .putString(KEY_OOD_METRIC, incident.oodMetric)
            .putFloat(KEY_OOD_VALUE, incident.oodValue)
            .putLong(KEY_LOCKED_AT, incident.lockedAtEpochMs)
            .commit()
        if (ok) {
            Log.w(TAG, "🔒 IS_TERMINAL_LOCKED=true persisted (code=${incident.recallCode}).")
        } else {
            Log.e(TAG, "💀 FAILED to persist terminal lock flag (commit returned false)!")
        }
        return ok
    }

    /** Clears the lock flag synchronously. @return true if the clear was durable. */
    fun unlock(context: Context): Boolean {
        val ok = prefs(context).edit()
            .putBoolean(KEY_IS_TERMINAL_LOCKED, false)
            .commit()
        Log.w(TAG, if (ok) "🔓 IS_TERMINAL_LOCKED cleared." else "💀 FAILED to clear lock flag!")
        return ok
    }

    fun readIncident(context: Context): LockIncident {
        val p = prefs(context)
        return LockIncident(
            recallCode = p.getString(KEY_RECALL_CODE, null).orEmpty(),
            affectedLot = p.getString(KEY_AFFECTED_LOT, null).orEmpty(),
            rootLot = p.getString(KEY_ROOT_LOT, null).orEmpty(),
            oodMetric = p.getString(KEY_OOD_METRIC, null).orEmpty(),
            oodValue = p.getFloat(KEY_OOD_VALUE, Float.NaN),
            lockedAtEpochMs = p.getLong(KEY_LOCKED_AT, 0L)
        )
    }
}
