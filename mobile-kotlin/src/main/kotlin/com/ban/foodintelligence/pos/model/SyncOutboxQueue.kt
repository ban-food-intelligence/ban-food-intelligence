package com.ban.foodintelligence.pos.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.SecureRandom

/**
 * ============================================================================
 * BAN Food Intelligence - Offline Outbox Queue Room Entity (`SyncOutboxQueue`)
 * Author: Nuri (Principal Android Security Engineer & Lead POS Architect)
 * ============================================================================
 *
 * Stores cryptographically signed Edge AI retail yield predictions while the
 * POS terminal operates offline.
 *
 * Schema Columns:
 * - `timestamp`       : Time-ordered RFC 9562 UUIDv7 primary key & idempotency token
 * - `batch_id`        : Retail lot identifier (e.g., "BATCH-GM-00042" or "SUC-2026-045")
 * - `predicted_yield` : ONNX Edge AI predicted retention yield percentage
 * - `hmac_signature`  : Base64 ASN.1 ECDSA-P256 signature from the Keystore private key
 *                       (legacy column name kept to avoid a schema migration)
 * - `tenant_id`       : Store/Market tenant scope for anti-IDOR verification
 * - `device_id`       : Bound POS terminal hardware ID for anti-BFLA/IDOR validation
 * - `actor_role`      : Least-privilege RBAC role (`ROLE_POS_DEVICE`)
 * - `status`          : Synchronization lifecycle state (`PENDING`, `SYNCED`, `FAILED`)
 * - `retry_count`     : Exponential backoff attempt counter
 */
@Entity(
    tableName = "sync_outbox_queue",
    indices = [
        Index(value = ["batch_id"]),
        Index(value = ["status", "timestamp"])
    ]
)
data class SyncOutboxQueue(
    @PrimaryKey
    @ColumnInfo(name = "timestamp")
    val timestamp: String = generateUuidV7(), // RFC 9562 time-ordered UUIDv7 format

    @ColumnInfo(name = "batch_id")
    val batchId: String,

    @ColumnInfo(name = "predicted_yield")
    val predictedYield: Float,

    @ColumnInfo(name = "hmac_signature")
    val hmacSignature: String,

    @ColumnInfo(name = "tenant_id")
    val tenantId: String = "MARKET-TIER-A-IST-01",

    @ColumnInfo(name = "device_id")
    val deviceId: String = "POS-TERM-NURI-001",

    @ColumnInfo(name = "actor_role")
    val actorRole: String = "ROLE_POS_DEVICE",

    @ColumnInfo(name = "status")
    val status: String = STATUS_PENDING,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SYNCED = "SYNCED"
        const val STATUS_FAILED = "FAILED"

        private val secureRandom = SecureRandom()

        /**
         * Generates an RFC 9562 compliant 128-bit UUIDv7 string encoding a
         * 48-bit big-endian UNIX epoch millisecond timestamp + 74 bits of
         * CSPRNG entropy for monotonic B-Tree indexing and distributed idempotency.
         */
        fun generateUuidV7(epochMillis: Long = System.currentTimeMillis()): String {
            val randBytes = ByteArray(10)
            secureRandom.nextBytes(randBytes)

            val timeHigh = (epochMillis ushr 16) and 0xFFFFFFFFL
            val timeLow = epochMillis and 0xFFFFL
            val verAndRandA = 0x7000L or (((randBytes[0].toLong() and 0xFFL) shl 8 or (randBytes[1].toLong() and 0xFFL)) and 0x0FFFL)

            var randB = 0L
            for (i in 2 until 10) {
                randB = (randB shl 8) or (randBytes[i].toLong() and 0xFFL)
            }
            val varAndRandB = (randB and 0x3FFFFFFFFFFFFFFFL) or (0x8000000000000000UL).toLong()

            return "%08x-%04x-%04x-%04x-%012x".format(
                timeHigh,
                timeLow,
                verAndRandA,
                (varAndRandB ushr 48) and 0xFFFFL,
                varAndRandB and 0xFFFFFFFFFFFFL
            )
        }
    }
}
