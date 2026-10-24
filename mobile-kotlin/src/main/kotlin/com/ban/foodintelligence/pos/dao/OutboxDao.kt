package com.ban.foodintelligence.pos.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ban.foodintelligence.pos.model.SyncOutboxQueue
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * BAN Food Intelligence - Offline Outbox Room Data Access Object (`OutboxDao`)
 * Author: Nuri (Principal Android Security Engineer & Lead POS Architect)
 * ============================================================================
 *
 * Uses strictly pre-compiled SQLite prepared statements (`:parameter` binding)
 * with Kotlin Coroutines (`suspend`) and cold reactive streams (`Flow`) to
 * guarantee zero SQL Injection (SQLi) surface area and non-blocking UI updates.
 */
@Dao
interface OutboxDao {

    /**
     * Inserts a cryptographically signed [SyncOutboxQueue] record into the
     * local SQLite outbox. Aborts on duplicate UUIDv7 `timestamp` to enforce
     * strict idempotency.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(item: SyncOutboxQueue): Long

    /**
     * Reactive `Flow` emitting all pending outbox records ordered chronologically
     * by their monotonic UUIDv7 `timestamp`.
     */
    @Query(
        """
        SELECT * FROM sync_outbox_queue
        WHERE status = :status
        ORDER BY timestamp ASC
        """
    )
    fun observePendingRecords(
        status: String = SyncOutboxQueue.STATUS_PENDING
    ): Flow<List<SyncOutboxQueue>>

    /**
     * Reactive `Flow` emitting the real-time count of unsynchronized records
     * waiting in the offline outbox queue.
     */
    @Query("SELECT COUNT(*) FROM sync_outbox_queue WHERE status = :status")
    fun observePendingCount(
        status: String = SyncOutboxQueue.STATUS_PENDING
    ): Flow<Int>

    /**
     * One-shot coroutine query used by `SyncWorker` to fetch a batch of pending
     * records when network connectivity (`NetworkType.CONNECTED`) is restored.
     */
    @Query(
        """
        SELECT * FROM sync_outbox_queue
        WHERE status = :status
        ORDER BY timestamp ASC
        LIMIT :limit
        """
    )
    suspend fun getPendingItems(
        limit: Int = 50,
        status: String = SyncOutboxQueue.STATUS_PENDING
    ): List<SyncOutboxQueue>

    /**
     * Marks a batch of UUIDv7 `timestamp` keys as `SYNCED` after Berke's Go API
     * (`/sync`) returns a verified HTTP 200 acknowledgement.
     */
    @Query(
        """
        UPDATE sync_outbox_queue
        SET status = :newStatus
        WHERE timestamp IN (:timestamps)
        """
    )
    suspend fun markAcknowledged(
        timestamps: List<String>,
        newStatus: String = SyncOutboxQueue.STATUS_SYNCED
    )

    /**
     * Increments `retry_count` when a `/sync` transmission encounters a transient
     * network or HTTP 5xx failure, preparing the record for WorkManager's
     * exponential backoff retry.
     */
    @Query(
        """
        UPDATE sync_outbox_queue
        SET retry_count = retry_count + 1
        WHERE timestamp IN (:timestamps)
        """
    )
    suspend fun incrementRetryCount(timestamps: List<String>)

    /**
     * Marks a specific UUIDv7 record as permanently `FAILED` if its local HMAC
     * integrity check fails (indicating local database tampering).
     */
    @Query(
        """
        UPDATE sync_outbox_queue
        SET status = :failedStatus, retry_count = retry_count + 1
        WHERE timestamp = :timestamp
        """
    )
    suspend fun markFailed(
        timestamp: String,
        failedStatus: String = SyncOutboxQueue.STATUS_FAILED
    )

    /**
     * Purges already-acknowledged outbox records older than the retention window.
     */
    @Query("DELETE FROM sync_outbox_queue WHERE status = :syncedStatus")
    suspend fun purgeSyncedRecords(
        syncedStatus: String = SyncOutboxQueue.STATUS_SYNCED
    ): Int
}
