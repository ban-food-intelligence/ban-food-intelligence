package com.ban.foodintelligence.pos.dao

import androidx.room.*
import com.ban.foodintelligence.pos.model.SyncOutboxQueue

@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(item: SyncOutboxQueue): Long

    @Query("SELECT * FROM sync_outbox_queue WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit")
    suspend fun getPendingItems(limit: Int = 50): List<SyncOutboxQueue>

    @Query("UPDATE sync_outbox_queue SET status = 'ACKNOWLEDGED' WHERE outbox_id IN (:ids)")
    suspend fun markAcknowledged(ids: List<String>)

    @Query("UPDATE sync_outbox_queue SET status = 'FAILED', retry_count = retry_count + 1 WHERE outbox_id = :id")
    suspend fun markFailed(id: String)
}
