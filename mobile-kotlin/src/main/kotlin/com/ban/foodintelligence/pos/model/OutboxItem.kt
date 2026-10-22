package com.ban.foodintelligence.pos.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SQLite Entity representing an offline POS mutation queued in the Outbox.
 * Written by Nuri for resilient retail offline-first operations.
 */
@Entity(tableName = "pos_outbox")
data class OutboxItem(
    @PrimaryKey
    val outboxId: String,
    val deviceId: String,
    val actionType: String, // SALE_TRANSACTION, LOT_CONSUMPTION, RETURN
    val payloadJson: String,
    val timestamp: Long = System.currentTimeMillis(),
    val signatureHmac: String, // Security integrity token
    val retryCount: Int = 0,
    val syncStatus: String = "PENDING" // PENDING, SYNCING, FAILED, ACKNOWLEDGED
)
