package com.santamota.reminder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Query("SELECT * FROM reminders WHERE status = 'ACTIVE' ORDER BY triggerEpochMs ASC")
    fun observeActive(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE status = 'ACTIVE'")
    suspend fun activeOnce(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: String): ReminderEntity?

    @Query("""
        SELECT * FROM reminders
        WHERE status = 'ACTIVE'
          AND (LOWER(title) LIKE '%' || LOWER(:q) || '%'
               OR LOWER(tags) LIKE '%' || LOWER(:q) || '%')
        ORDER BY triggerEpochMs ASC
    """)
    suspend fun search(q: String): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE parentId = :parentId")
    suspend fun childrenOf(parentId: String): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE groupId = :groupId")
    suspend fun byGroup(groupId: String): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReminderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ReminderEntity>)

    @Update
    suspend fun update(entity: ReminderEntity)

    @Query("UPDATE reminders SET status = :status, updatedEpochMs = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, now: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: String)

    @Transaction
    suspend fun replaceCluster(entities: List<ReminderEntity>) {
        upsertAll(entities)
    }
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages ORDER BY id DESC LIMIT :limit")
    fun observeLatest(limit: Int = 100): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages WHERE id IN (SELECT id FROM chat_messages ORDER BY id ASC LIMIT :n)")
    suspend fun trimOldest(n: Int)

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun count(): Int
}
