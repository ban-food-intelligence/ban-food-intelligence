package com.ban.foodintelligence.pos.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SQLite Entity representing an offline POS mutation queued in `pos_outbox`.
 * Signed using Nuri's Asymmetric ECDSA P-256 hardware key (`KeystoreManager.signPayload`).
 */
@Entity(tableName = "pos_outbox")
data class OutboxItem(
    @PrimaryKey
    @ColumnInfo(name = "outbox_id")
    val outboxId: String,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "action_type")
    val actionType: String,

    @ColumnInfo(name = "payload_json")
    val payloadJson: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "signature_ecdsa")
    val signatureHmac: String,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "status")
    val status: String = "PENDING"
)
