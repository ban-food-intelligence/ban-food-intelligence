package com.ban.foodintelligence.pos.security

import android.content.Context
import java.util.Locale
import java.util.UUID

/**
 * The id this terminal uses towards the API: in the sync envelope, in every signed sale and in
 * supervisor unlocks. One random id per installation, kept with the app data, so it lives as long
 * as the Keystore signing key (both go when the app is uninstalled). The API pins the signing key
 * to this id on the first signed sync, so a second phone, or a reinstall with a new key, gets its
 * own id instead of being refused with KEY_MISMATCH.
 */
object DeviceIdentity {
    private const val PREFS = "pos_device"
    private const val KEY_ID = "device_id"

    @Volatile private var cached: String? = null

    fun id(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = prefs.getString(KEY_ID, null) ?: newId().also { prefs.edit().putString(KEY_ID, it).commit() }
            cached = id
            return id
        }
    }

    /** "POS-" + 12 hex digits (48 random bits), e.g. POS-3F9A0C12B7E4. */
    internal fun newId(uuid: UUID = UUID.randomUUID()): String =
        "POS-" + uuid.toString().replace("-", "").take(12).uppercase(Locale.ROOT)
}
