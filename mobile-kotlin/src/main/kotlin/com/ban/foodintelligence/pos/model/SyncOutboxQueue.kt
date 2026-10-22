package com.ban.foodintelligence.pos.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Offline Outbox Queue Room Entity.
 * Implements Nuri's UUIDv7 Idempotency and HMAC Tamper-proofing.
 */
@Entity(
    tableName = "sync_outbox_queue",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["status", "created_at"])
    ]
)
data class SyncOutboxQueue(
    @PrimaryKey
    @ColumnInfo(name = "outbox_id")
    val outboxId: String, // UUIDv7 time-sortable identifier

    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "action_type")
    val actionType: String, // "RETAIL_SALE", "QUARANTINE_LOG", "WASTE_ADJUSTMENT"

    @ColumnInfo(name = "payload_json")
    val payloadJson: String,

    @ColumnInfo(name = "signature_hmac")
    val signatureHmac: String, // Android Keystore hardware HMAC-SHA256

    @ColumnInfo(name = "status")
    val status: String = "PENDING", // PENDING, SYNCING, ACKNOWLEDGED, FAILED

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
