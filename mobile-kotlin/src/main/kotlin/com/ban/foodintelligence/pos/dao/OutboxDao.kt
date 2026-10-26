package com.ban.foodintelligence.pos.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ban.foodintelligence.pos.model.OutboxItem
import com.ban.foodintelligence.pos.model.SyncOutboxQueue
import kotlinx.coroutines.flow.Flow

/**
 * ============================================================================
 * BAN Food Intelligence - Offline Outbox Room DAO (`OutboxDao`)
 * Author: Nuri (Principal Android Security Architect)
 * ============================================================================
 *
 * "Steel Vest" & "Quantum Shield" Remediation:
 * 1. Bounded SQL `LIMIT :limit` (default 50) directly inside SQLite so Room never
 *    materializes an unbounded offline backlog into the JVM heap.
 * 2. Batched `markBatchFailed(timestamps)` in a single SQLite WAL transaction to
 *    eliminate 50-row unbatched `fsync` amplification during HTTP 4xx rejection.
 * 3. `purgeSyncedRecords()` to reclaim SQLite pages after server acknowledgement
 *    and prevent `SYNCED` tombstone accumulation during a 20,000-record drain.
 */
@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(item: SyncOutboxQueue): Long

    /**
     * Bounded SQL `Flow` for `pos_outbox` (`LIMIT :limit`, default 50) to prevent OOM.
     */
    @Query("SELECT * FROM pos_outbox WHERE status = 'PENDING' LIMIT :limit")
    fun observePendingPosOutbox(limit: Int = 50): Flow<List<OutboxItem>>

    /**
     * Bounded SQL `Flow` for `sync_outbox_queue` (`LIMIT :limit`, default 50) to prevent OOM.
     */
    @Query("SELECT * FROM sync_outbox_queue WHERE status = 'PENDING' ORDER BY timestamp ASC LIMIT :limit")
    fun observePendingRecords(limit: Int = 50): Flow<List<SyncOutboxQueue>>

    /**
     * Reactive `Flow` emitting the scalar count of pending outbox records.
     */
    @Query("SELECT COUNT(*) FROM sync_outbox_queue WHERE status = :status")
    fun observePendingCount(
        status: String = SyncOutboxQueue.STATUS_PENDING
    ): Flow<Int>

    /**
     * One-shot count query used by `SyncWorker` to check if additional batches
     * of 50 remain in the backlog after a successful flush.
     */
    @Query("SELECT COUNT(*) FROM sync_outbox_queue WHERE status = :status")
    suspend fun countPending(
        status: String = SyncOutboxQueue.STATUS_PENDING
    ): Int

    /**
     * Bounded batch fetch (`LIMIT :limit`, default 50) ordered by monotonic UUIDv7.
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

    @Query(
        """
        UPDATE sync_outbox_queue
        SET retry_count = retry_count + 1
        WHERE timestamp IN (:timestamps)
        """
    )
    suspend fun incrementRetryCount(timestamps: List<String>)

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
     * Quantum Shield Fix: Batched `WHERE timestamp IN (:timestamps)` update so
     * permanent HTTP 4xx failures execute in 1 SQLite WAL transaction instead of 50.
     */
    @Query(
        """
        UPDATE sync_outbox_queue
        SET status = :failedStatus, retry_count = retry_count + 1
        WHERE timestamp IN (:timestamps)
        """
    )
    suspend fun markBatchFailed(
        timestamps: List<String>,
        failedStatus: String = SyncOutboxQueue.STATUS_FAILED
    )

    @Query("DELETE FROM sync_outbox_queue WHERE status = :syncedStatus")
    suspend fun purgeSyncedRecords(
        syncedStatus: String = SyncOutboxQueue.STATUS_SYNCED
    ): Int
}
