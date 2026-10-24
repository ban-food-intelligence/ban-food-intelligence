package com.ban.foodintelligence.pos.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ban.foodintelligence.pos.dao.OutboxDao
import com.ban.foodintelligence.pos.model.SyncOutboxQueue
import com.ban.foodintelligence.pos.security.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * BAN Food Intelligence - Offline-First Room Database (`AppDatabase`)
 * Author: Nuri (Principal Android Security Engineer & Lead POS Architect)
 * ============================================================================
 *
 * Persists [SyncOutboxQueue] entities containing:
 * - `batch_id`        : Retail lot identifier
 * - `predicted_yield` : ONNX Edge AI predicted retention yield (%)
 * - `timestamp`       : Monotonic RFC 9562 UUIDv7 identifier
 * - `hmac_signature`  : Hardware-backed 256-bit HMAC-SHA256 signature
 */
@Database(
    entities = [SyncOutboxQueue::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun outboxDao(): OutboxDao

    /**
     * Atomically signs an offline Edge AI yield prediction using the Android
     * Hardware-Backed Keystore (`KeystoreManager.generateHMACSignature`) and
     * enqueues it in `sync_outbox_queue` with a fresh UUIDv7 `timestamp`.
     *
     * Protects against MITM tampering and binds BFLA/IDOR identity claims
     * (`tenantId`, `deviceId`, `ROLE_POS_DEVICE`) into the cryptographic digest.
     */
    suspend fun enqueueSignedYieldPrediction(
        batchId: String,
        predictedYield: Float,
        tenantId: String = "MARKET-TIER-A-IST-01",
        deviceId: String = "POS-TERM-NURI-001",
        actorRole: String = "ROLE_POS_DEVICE"
    ): SyncOutboxQueue = withContext(Dispatchers.IO) {
        val uuidV7Timestamp = SyncOutboxQueue.generateUuidV7()

        val canonicalPayload = KeystoreManager.buildCanonicalYieldPayload(
            tenantId = tenantId,
            deviceId = deviceId,
            actorRole = actorRole,
            batchId = batchId,
            predictedYield = predictedYield,
            timestampUuidV7 = uuidV7Timestamp
        )

        println("🔒 [OUTBOX-SIGN] Generating Hardware HMAC-SHA256 for Batch=$batchId (UUIDv7=$uuidV7Timestamp)...")
        val hmacSignature = KeystoreManager.generateHMACSignature(canonicalPayload)

        val record = SyncOutboxQueue(
            timestamp = uuidV7Timestamp,
            batchId = batchId,
            predictedYield = predictedYield,
            hmacSignature = hmacSignature,
            tenantId = tenantId,
            deviceId = deviceId,
            actorRole = actorRole,
            status = SyncOutboxQueue.STATUS_PENDING,
            retryCount = 0
        )

        outboxDao().enqueue(record)
        println("📥 [OUTBOX-DB] Enqueued offline record -> Batch: $batchId | Yield: $predictedYield% | UUIDv7: $uuidV7Timestamp")
        record
    }

    companion object {
        private const val DATABASE_NAME = "ban_avatar_pos_offline_outbox.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Returns the process-wide singleton [AppDatabase] configured with
         * Write-Ahead Logging (WAL) for crash-resilient offline POS storage.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { db ->
                        INSTANCE = db
                        println("🗄️ [ROOM-DB] Initialized Offline SQLite Outbox ($DATABASE_NAME) in WAL mode.")
                    }
            }
        }
    }
}
