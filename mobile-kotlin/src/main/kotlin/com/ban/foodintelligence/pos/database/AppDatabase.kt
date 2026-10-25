package com.ban.foodintelligence.pos.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.ban.foodintelligence.pos.dao.OutboxDao
import com.ban.foodintelligence.pos.model.OutboxItem
import com.ban.foodintelligence.pos.model.SyncOutboxQueue
import com.ban.foodintelligence.pos.security.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 * BAN Food Intelligence - Offline-First Room Database (`AppDatabase`)
 * Author: Nuri (Principal Android Security Architect)
 * ============================================================================
 *
 * MIGRATION POLICY: `fallbackToDestructiveMigration()` has been REMOVED. The
 * outbox holds signed transactions that have not reached the server yet, and
 * silently wiping them on a schema bump is data loss. Room now throws on a
 * version mismatch with no migration, so a missing migration fails loudly in
 * testing instead of erasing records in the field.
 *
 * Every future `version` bump MUST ship a tested [Migration] added to
 * [MIGRATIONS].
 *
 * Known caveat: a dev device still on schema v1 (the old generic outbox) has no
 * migration path to the v2 yield schema and must be reinstalled. No production
 * install exists on v1.
 *
 * Column note: `hmac_signature` keeps its legacy name to avoid a schema change,
 * but it now stores a Base64 ASN.1 ECDSA-P256 signature, not an HMAC.
 */
@Database(
    entities = [SyncOutboxQueue::class, OutboxItem::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun outboxDao(): OutboxDao

    /**
     * Signs an offline yield prediction with the hardware-backed ECDSA key and
     * enqueues it with a fresh UUIDv7. Runs on `Dispatchers.IO`.
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

        Log.i(TAG, "🔏 Signing yield record Batch=$batchId (UUIDv7=$uuidV7Timestamp) with ECDSA P-256...")
        val signature = KeystoreManager.signPayload(canonicalPayload)

        val record = SyncOutboxQueue(
            timestamp = uuidV7Timestamp,
            batchId = batchId,
            predictedYield = predictedYield,
            hmacSignature = signature,
            tenantId = tenantId,
            deviceId = deviceId,
            actorRole = actorRole,
            status = SyncOutboxQueue.STATUS_PENDING,
            retryCount = 0
        )

        outboxDao().enqueue(record)
        Log.i(TAG, "📥 Enqueued offline record -> Batch=$batchId Yield=$predictedYield% UUIDv7=$uuidV7Timestamp")
        record
    }

    companion object {
        private const val TAG = "AppDatabase"
        private const val DATABASE_NAME = "ban_avatar_pos_offline_outbox.db"

        /** Register every schema migration here. Intentionally empty at v2. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(*MIGRATIONS)
                    .build()
                    .also { db ->
                        INSTANCE = db
                        Log.i(TAG, "🗄️ Offline outbox opened ($DATABASE_NAME, WAL, no destructive fallback).")
                    }
            }
        }
    }
}
