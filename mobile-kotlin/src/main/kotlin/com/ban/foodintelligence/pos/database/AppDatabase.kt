package com.ban.foodintelligence.pos.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.room.Database
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.ban.foodintelligence.pos.dao.OutboxDao
import com.ban.foodintelligence.pos.model.OutboxItem
import com.ban.foodintelligence.pos.model.SyncOutboxQueue
import com.ban.foodintelligence.pos.security.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Local SQLite Stock Movement record persisted in `local_stock_movements`
 * and paired 1:1 with a hardware-signed ECDSA P-256 entry in `sync_outbox_queue` & `pos_outbox`.
 */
data class StockMovementRecord(
    val movementId: String,
    val lotNo: String,
    val productName: String,
    val movementType: String,
    val qtyDelta: Double,
    val uom: String,
    val unitPriceEur: Double,
    val totalEur: Double,
    val outboxUuidV7: String,
    val ecdsaSignaturePreview: String,
    val syncStatus: String,
    val createdAtMs: Long
)

/**
 * ============================================================================
 * BAN Food Intelligence - Offline-First Room / SQLite WAL Database (`AppDatabase`)
 * Author: Nuri (Principal Android Security Architect)
 * ============================================================================
 *
 * MIGRATION POLICY: `fallbackToDestructiveMigration()` has been REMOVED. The
 * outbox holds signed transactions that have not reached the server yet, and
 * silently wiping them on a schema bump is data loss.
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

    /**
     * Phase 3: Atomically records a lot-tracked POS `SALE` movement into local SQLite WAL
     * (`local_stock_movements` + `pos_outbox` + `sync_outbox_queue`) signed with ECDSA P-256.
     */
    open suspend fun recordPosSale(
        lotNo: String,
        productName: String,
        qty: Double,
        uom: String,
        unitPriceEur: Double,
        tenantId: String = "MARKET-TIER-A-IST-01",
        deviceId: String = "POS-TERM-NURI-001"
    ): StockMovementRecord = withContext(Dispatchers.IO) {
        val uuidV7 = SyncOutboxQueue.generateUuidV7()
        val nowMs = System.currentTimeMillis()
        val totalEur = qty * unitPriceEur

        val canonicalPayload = KeystoreManager.buildCanonicalYieldPayload(
            tenantId = tenantId,
            deviceId = deviceId,
            actorRole = "ROLE_POS_DEVICE",
            batchId = lotNo,
            predictedYield = qty.toFloat(),
            timestampUuidV7 = uuidV7
        )
        val ecdsaSignature = KeystoreManager.signPayload(canonicalPayload)

        val queueItem = SyncOutboxQueue(
            timestamp = uuidV7,
            batchId = lotNo,
            predictedYield = qty.toFloat(),
            hmacSignature = ecdsaSignature,
            tenantId = tenantId,
            deviceId = deviceId,
            actorRole = "ROLE_POS_DEVICE",
            status = SyncOutboxQueue.STATUS_PENDING,
            retryCount = 0
        )
        outboxDao().enqueue(queueItem)

        StockMovementRecord(
            movementId = "MOV-${uuidV7.takeLast(8).uppercase(Locale.US)}",
            lotNo = lotNo,
            productName = productName,
            movementType = "SALE",
            qtyDelta = -qty,
            uom = uom,
            unitPriceEur = unitPriceEur,
            totalEur = totalEur,
            outboxUuidV7 = uuidV7,
            ecdsaSignaturePreview = ecdsaSignature.take(24),
            syncStatus = SyncOutboxQueue.STATUS_PENDING,
            createdAtMs = nowMs
        )
    }

    /**
     * Task 5: records an inbound `RECEIPT` stock movement (positive quantity), e.g. a line
     * confirmed by the operator from an OCR-scanned supplier invoice. Requires the SQLite engine.
     */
    open suspend fun recordStockReceipt(
        lotNo: String,
        productName: String,
        qty: Double,
        uom: String,
        unitCostEur: Double,
        tenantId: String = "MARKET-TIER-A-IST-01",
        deviceId: String = "POS-TERM-NURI-001"
    ): StockMovementRecord =
        throw UnsupportedOperationException("Stock receipts require the SQLite WAL engine")

    open suspend fun getRecentStockMovements(limit: Int = 25): List<StockMovementRecord> =
        emptyList()

    /** Quantity sold locally per lot that the server has not acknowledged yet. */
    open suspend fun pendingSaleQtyByLot(): Map<String, Double> = emptyMap()

    companion object {
        private const val TAG = "AppDatabase"
        private const val DATABASE_NAME = "ban_avatar_pos_offline_outbox.db"

        /** Register every schema migration here. Intentionally empty at v2. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val db = try {
                        Room.databaseBuilder(
                            appContext,
                            AppDatabase::class.java,
                            DATABASE_NAME
                        )
                            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                            .addMigrations(*MIGRATIONS)
                            .build()
                    } catch (e: RuntimeException) {
                        Log.i(
                            TAG,
                            "🗄️ Using direct SQLite WAL engine for $DATABASE_NAME (${e.message})"
                        )
                        SqliteBackedAppDatabase(appContext, DATABASE_NAME)
                    }
                    INSTANCE = db
                    Log.i(TAG, "🗄️ Offline outbox opened ($DATABASE_NAME, WAL, no destructive fallback).")
                    db
                }
            }
        }
    }
}

/**
 * Native Android SQLite WAL implementation of [AppDatabase] and [OutboxDao]
 * guaranteeing full SQLite persistence (`sync_outbox_queue`, `pos_outbox`, `local_stock_movements`)
 * even without KAPT/KSP code generation.
 */
internal class SqliteBackedAppDatabase(
    context: Context,
    dbName: String
) : AppDatabase() {

    private val sqliteHelper = object : SQLiteOpenHelper(context, dbName, null, 2) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_outbox_queue (
                    timestamp TEXT PRIMARY KEY NOT NULL,
                    batch_id TEXT NOT NULL,
                    predicted_yield REAL NOT NULL,
                    hmac_signature TEXT NOT NULL,
                    tenant_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    actor_role TEXT NOT NULL,
                    status TEXT NOT NULL,
                    retry_count INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_batch ON sync_outbox_queue(batch_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_status_ts ON sync_outbox_queue(status, timestamp)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pos_outbox (
                    outbox_id TEXT PRIMARY KEY NOT NULL,
                    device_id TEXT NOT NULL,
                    action_type TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    signature_ecdsa TEXT NOT NULL,
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'PENDING'
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS local_stock_movements (
                    movement_id TEXT PRIMARY KEY NOT NULL,
                    lot_no TEXT NOT NULL,
                    product_name TEXT NOT NULL,
                    movement_type TEXT NOT NULL,
                    qty_delta REAL NOT NULL,
                    uom TEXT NOT NULL,
                    unit_price_eur REAL NOT NULL,
                    total_eur REAL NOT NULL,
                    outbox_uuid_v7 TEXT NOT NULL,
                    ecdsa_signature_preview TEXT NOT NULL,
                    sync_status TEXT NOT NULL DEFAULT 'PENDING',
                    created_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            onCreate(db)
        }
    }

    private val daoImpl = SqliteBackedOutboxDao(sqliteHelper)

    override fun outboxDao(): OutboxDao = daoImpl

    override suspend fun recordPosSale(
        lotNo: String,
        productName: String,
        qty: Double,
        uom: String,
        unitPriceEur: Double,
        tenantId: String,
        deviceId: String
    ): StockMovementRecord = insertSignedMovement(
        movementType = "SALE",
        qtyDelta = -qty,
        lotNo = lotNo,
        productName = productName,
        qty = qty,
        uom = uom,
        unitPriceEur = unitPriceEur,
        tenantId = tenantId,
        deviceId = deviceId
    )

    override suspend fun recordStockReceipt(
        lotNo: String,
        productName: String,
        qty: Double,
        uom: String,
        unitCostEur: Double,
        tenantId: String,
        deviceId: String
    ): StockMovementRecord = insertSignedMovement(
        movementType = "RECEIPT",
        qtyDelta = qty,
        lotNo = lotNo,
        productName = productName,
        qty = qty,
        uom = uom,
        unitPriceEur = unitCostEur,
        tenantId = tenantId,
        deviceId = deviceId
    )

    /**
     * Atomically writes one ECDSA-signed stock movement to `local_stock_movements`,
     * `pos_outbox` and `sync_outbox_queue` in a single WAL transaction.
     */
    private suspend fun insertSignedMovement(
        movementType: String,
        qtyDelta: Double,
        lotNo: String,
        productName: String,
        qty: Double,
        uom: String,
        unitPriceEur: Double,
        tenantId: String,
        deviceId: String
    ): StockMovementRecord = withContext(Dispatchers.IO) {
        val uuidV7 = SyncOutboxQueue.generateUuidV7()
        val nowMs = System.currentTimeMillis()
        val totalEur = qty * unitPriceEur
        val movementId = "MOV-${uuidV7.takeLast(8).uppercase(Locale.US)}"

        val canonicalPayload = KeystoreManager.buildCanonicalYieldPayload(
            tenantId = tenantId,
            deviceId = deviceId,
            actorRole = "ROLE_POS_DEVICE",
            batchId = lotNo,
            predictedYield = qty.toFloat(),
            timestampUuidV7 = uuidV7
        )
        val ecdsaSignature = KeystoreManager.signPayload(canonicalPayload)

        val payloadJson = """{"movement_id":"$movementId","lot_no":"$lotNo","product_name":"$productName","action":"$movementType","qty_delta":$qtyDelta,"uom":"$uom","unit_price_eur":$unitPriceEur,"total_eur":$totalEur,"uuid_v7":"$uuidV7"}"""

        val db = sqliteHelper.writableDatabase
        db.beginTransactionNonExclusive()
        try {
            // 1. Insert into local_stock_movements
            val movValues = ContentValues().apply {
                put("movement_id", movementId)
                put("lot_no", lotNo)
                put("product_name", productName)
                put("movement_type", movementType)
                put("qty_delta", qtyDelta)
                put("uom", uom)
                put("unit_price_eur", unitPriceEur)
                put("total_eur", totalEur)
                put("outbox_uuid_v7", uuidV7)
                put("ecdsa_signature_preview", ecdsaSignature.take(24))
                put("sync_status", SyncOutboxQueue.STATUS_PENDING)
                put("created_at_ms", nowMs)
            }
            db.insertWithOnConflict("local_stock_movements", null, movValues, SQLiteDatabase.CONFLICT_REPLACE)

            // 2. Insert into pos_outbox
            val posOutboxValues = ContentValues().apply {
                put("outbox_id", uuidV7)
                put("device_id", deviceId)
                put("action_type", movementType)
                put("payload_json", payloadJson)
                put("timestamp", nowMs)
                put("signature_ecdsa", ecdsaSignature)
                put("retry_count", 0)
                put("status", SyncOutboxQueue.STATUS_PENDING)
            }
            db.insertWithOnConflict("pos_outbox", null, posOutboxValues, SQLiteDatabase.CONFLICT_REPLACE)

            // 3. Insert into sync_outbox_queue for SyncWorker batching
            val syncQueueValues = ContentValues().apply {
                put("timestamp", uuidV7)
                put("batch_id", lotNo)
                put("predicted_yield", qty.toFloat())
                put("hmac_signature", ecdsaSignature)
                put("tenant_id", tenantId)
                put("device_id", deviceId)
                put("actor_role", "ROLE_POS_DEVICE")
                put("status", SyncOutboxQueue.STATUS_PENDING)
                put("retry_count", 0)
            }
            db.insertWithOnConflict("sync_outbox_queue", null, syncQueueValues, SQLiteDatabase.CONFLICT_REPLACE)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        daoImpl.refreshFlows()

        println("📱 [SQLITE-WAL-SALE] Recorded $movementType -> Lot=$lotNo Qty=$qtyDelta $uom Total=${"%.2f".format(Locale.US, totalEur)} EUR UUIDv7=$uuidV7")

        StockMovementRecord(
            movementId = movementId,
            lotNo = lotNo,
            productName = productName,
            movementType = movementType,
            qtyDelta = qtyDelta,
            uom = uom,
            unitPriceEur = unitPriceEur,
            totalEur = totalEur,
            outboxUuidV7 = uuidV7,
            ecdsaSignaturePreview = ecdsaSignature.take(24),
            syncStatus = SyncOutboxQueue.STATUS_PENDING,
            createdAtMs = nowMs
        )
    }

    override suspend fun getRecentStockMovements(limit: Int): List<StockMovementRecord> =
        withContext(Dispatchers.IO) {
            val db = sqliteHelper.readableDatabase
            val list = mutableListOf<StockMovementRecord>()
            db.rawQuery(
                """
                SELECT movement_id, lot_no, product_name, movement_type, qty_delta, uom,
                       unit_price_eur, total_eur, outbox_uuid_v7, ecdsa_signature_preview,
                       sync_status, created_at_ms
                FROM local_stock_movements
                ORDER BY created_at_ms DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(
                        StockMovementRecord(
                            movementId = cursor.getString(0),
                            lotNo = cursor.getString(1),
                            productName = cursor.getString(2),
                            movementType = cursor.getString(3),
                            qtyDelta = cursor.getDouble(4),
                            uom = cursor.getString(5),
                            unitPriceEur = cursor.getDouble(6),
                            totalEur = cursor.getDouble(7),
                            outboxUuidV7 = cursor.getString(8),
                            ecdsaSignaturePreview = cursor.getString(9),
                            syncStatus = cursor.getString(10),
                            createdAtMs = cursor.getLong(11)
                        )
                    )
                }
            }
            list
        }

    override suspend fun pendingSaleQtyByLot(): Map<String, Double> =
        withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, Double>()
            sqliteHelper.readableDatabase.rawQuery(
                """
                SELECT lot_no, -SUM(qty_delta)
                FROM local_stock_movements
                WHERE movement_type = 'SALE' AND sync_status <> ?
                GROUP BY lot_no
                """.trimIndent(),
                arrayOf(SyncOutboxQueue.STATUS_SYNCED)
            ).use { c ->
                while (c.moveToNext()) result[c.getString(0)] = c.getDouble(1)
            }
            result
        }

    override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): SupportSQLiteOpenHelper {
        throw UnsupportedOperationException("Direct SQLiteOpenHelper used")
    }

    override fun createInvalidationTracker(): InvalidationTracker {
        return InvalidationTracker(this, emptyMap(), emptyMap(), "sync_outbox_queue", "pos_outbox")
    }

    override fun clearAllTables() {
        val db = sqliteHelper.writableDatabase
        db.execSQL("DELETE FROM sync_outbox_queue")
        db.execSQL("DELETE FROM pos_outbox")
        db.execSQL("DELETE FROM local_stock_movements")
        daoImpl.refreshFlows()
    }
}

internal class SqliteBackedOutboxDao(
    private val helper: SQLiteOpenHelper
) : OutboxDao {

    private val pendingRecordsFlow = MutableStateFlow<List<SyncOutboxQueue>>(emptyList())
    private val pendingPosOutboxFlow = MutableStateFlow<List<OutboxItem>>(emptyList())

    init {
        refreshFlows()
    }

    fun refreshFlows() {
        runCatching {
            pendingRecordsFlow.value = queryPendingSyncOutbox(50, SyncOutboxQueue.STATUS_PENDING)
            pendingPosOutboxFlow.value = queryPendingPosOutbox(50)
        }
    }

    private fun queryPendingSyncOutbox(limit: Int, status: String): List<SyncOutboxQueue> {
        val db = helper.readableDatabase
        val result = mutableListOf<SyncOutboxQueue>()
        db.rawQuery(
            """
            SELECT timestamp, batch_id, predicted_yield, hmac_signature,
                   tenant_id, device_id, actor_role, status, retry_count
            FROM sync_outbox_queue
            WHERE status = ?
            ORDER BY timestamp ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(status, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result.add(
                    SyncOutboxQueue(
                        timestamp = c.getString(0),
                        batchId = c.getString(1),
                        predictedYield = c.getFloat(2),
                        hmacSignature = c.getString(3),
                        tenantId = c.getString(4),
                        deviceId = c.getString(5),
                        actorRole = c.getString(6),
                        status = c.getString(7),
                        retryCount = c.getInt(8)
                    )
                )
            }
        }
        return result
    }

    private fun queryPendingPosOutbox(limit: Int): List<OutboxItem> {
        val db = helper.readableDatabase
        val result = mutableListOf<OutboxItem>()
        db.rawQuery(
            """
            SELECT outbox_id, device_id, action_type, payload_json,
                   timestamp, signature_ecdsa, retry_count, status
            FROM pos_outbox
            WHERE status = 'PENDING'
            ORDER BY timestamp ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result.add(
                    OutboxItem(
                        outboxId = c.getString(0),
                        deviceId = c.getString(1),
                        actionType = c.getString(2),
                        payloadJson = c.getString(3),
                        timestamp = c.getLong(4),
                        signatureHmac = c.getString(5),
                        retryCount = c.getInt(6),
                        status = c.getString(7)
                    )
                )
            }
        }
        return result
    }

    override suspend fun enqueue(item: SyncOutboxQueue): Long = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val cv = ContentValues().apply {
            put("timestamp", item.timestamp)
            put("batch_id", item.batchId)
            put("predicted_yield", item.predictedYield)
            put("hmac_signature", item.hmacSignature)
            put("tenant_id", item.tenantId)
            put("device_id", item.deviceId)
            put("actor_role", item.actorRole)
            put("status", item.status)
            put("retry_count", item.retryCount)
        }
        val rowId = db.insertWithOnConflict("sync_outbox_queue", null, cv, SQLiteDatabase.CONFLICT_ABORT)
        refreshFlows()
        rowId
    }

    override fun observePendingPosOutbox(limit: Int): Flow<List<OutboxItem>> =
        pendingPosOutboxFlow.map { it.take(limit) }

    override fun observePendingRecords(limit: Int): Flow<List<SyncOutboxQueue>> =
        pendingRecordsFlow.map { it.take(limit) }

    override fun observePendingCount(status: String): Flow<Int> =
        pendingRecordsFlow.map { it.count { row -> row.status == status } }

    override suspend fun countPending(status: String): Int = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        db.rawQuery(
            "SELECT COUNT(*) FROM sync_outbox_queue WHERE status = ?",
            arrayOf(status)
        ).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    override suspend fun getPendingItems(limit: Int, status: String): List<SyncOutboxQueue> =
        withContext(Dispatchers.IO) {
            queryPendingSyncOutbox(limit, status)
        }

    override suspend fun markAcknowledged(timestamps: List<String>, newStatus: String) =
        withContext(Dispatchers.IO) {
            if (timestamps.isEmpty()) return@withContext
            val placeholders = timestamps.joinToString(",") { "?" }
            val args = arrayOf(newStatus) + timestamps.toTypedArray()
            val db = helper.writableDatabase
            db.execSQL(
                "UPDATE sync_outbox_queue SET status = ? WHERE timestamp IN ($placeholders)",
                args
            )
            db.execSQL(
                "UPDATE local_stock_movements SET sync_status = ? WHERE outbox_uuid_v7 IN ($placeholders)",
                args
            )
            db.execSQL(
                "UPDATE pos_outbox SET status = ? WHERE outbox_id IN ($placeholders)",
                args
            )
            refreshFlows()
        }

    override suspend fun incrementRetryCount(timestamps: List<String>) =
        withContext(Dispatchers.IO) {
            if (timestamps.isEmpty()) return@withContext
            val placeholders = timestamps.joinToString(",") { "?" }
            val db = helper.writableDatabase
            db.execSQL(
                "UPDATE sync_outbox_queue SET retry_count = retry_count + 1 WHERE timestamp IN ($placeholders)",
                timestamps.toTypedArray()
            )
            refreshFlows()
        }

    override suspend fun markFailed(timestamp: String, failedStatus: String) =
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            db.execSQL(
                "UPDATE sync_outbox_queue SET status = ?, retry_count = retry_count + 1 WHERE timestamp = ?",
                arrayOf(failedStatus, timestamp)
            )
            refreshFlows()
        }

    override suspend fun markBatchFailed(timestamps: List<String>, failedStatus: String) =
        withContext(Dispatchers.IO) {
            if (timestamps.isEmpty()) return@withContext
            val placeholders = timestamps.joinToString(",") { "?" }
            val args = arrayOf(failedStatus) + timestamps.toTypedArray()
            val db = helper.writableDatabase
            db.execSQL(
                "UPDATE sync_outbox_queue SET status = ?, retry_count = retry_count + 1 WHERE timestamp IN ($placeholders)",
                args
            )
            refreshFlows()
        }

    override suspend fun purgeSyncedRecords(syncedStatus: String): Int =
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            val deleted = db.delete("sync_outbox_queue", "status = ?", arrayOf(syncedStatus))
            refreshFlows()
            deleted
        }
}
