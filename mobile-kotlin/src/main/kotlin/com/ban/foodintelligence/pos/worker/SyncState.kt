package com.ban.foodintelligence.pos.worker

import android.content.Context

/** When the server last answered a sync request (shown on the Sync tab). */
object SyncState {
    private const val PREFS = "pos_sync_state"
    private const val KEY_LAST_CONTACT = "last_contact_ms"

    fun markContacted(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CONTACT, System.currentTimeMillis()).apply()
    }

    fun lastContactMs(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CONTACT, 0L)
}
