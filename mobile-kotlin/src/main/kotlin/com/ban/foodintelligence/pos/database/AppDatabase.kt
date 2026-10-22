package com.ban.foodintelligence.pos.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ban.foodintelligence.pos.dao.OutboxDao
import com.ban.foodintelligence.pos.model.SyncOutboxQueue

@Database(
    entities = [SyncOutboxQueue::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
}
