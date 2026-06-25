// SessionMemoryDao.kt — 会话记忆数据访问层
package com.gameai.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionMemoryDao {

    @Query("SELECT * FROM session_memories WHERE sessionId = :sessionId ORDER BY updatedAt DESC")
    fun getBySession(sessionId: String): Flow<List<SessionMemoryEntity>>

    @Query("SELECT * FROM session_memories WHERE category = :category ORDER BY updatedAt DESC")
    fun getByCategory(category: String): Flow<List<SessionMemoryEntity>>

    @Query("SELECT * FROM session_memories WHERE category = 'pref' ORDER BY updatedAt DESC")
    suspend fun getUserPreferences(): List<SessionMemoryEntity>

    @Query("SELECT * FROM session_memories WHERE category = 'pref' ORDER BY updatedAt DESC")
    fun getUserPreferencesFlow(): Flow<List<SessionMemoryEntity>>

    @Query("SELECT * FROM session_memories WHERE category = 'insight' ORDER BY updatedAt DESC")
    suspend fun getInsights(): List<SessionMemoryEntity>

    @Query("SELECT * FROM session_memories WHERE `key` = :key ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getByKey(key: String): SessionMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: SessionMemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(memories: List<SessionMemoryEntity>)

    @Update
    suspend fun update(memory: SessionMemoryEntity)

    @Query("UPDATE session_memories SET accessCount = accessCount + 1, lastAccessedAt = :now WHERE memoryId = :memoryId")
    suspend fun recordAccess(memoryId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE session_memories SET importance = :importance WHERE memoryId = :memoryId")
    suspend fun setImportance(memoryId: String, importance: Float)

    @Delete
    suspend fun delete(memory: SessionMemoryEntity)

    @Query("DELETE FROM session_memories WHERE memoryId = :memoryId")
    suspend fun deleteById(memoryId: String)

    @Query("DELETE FROM session_memories WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT * FROM session_memories ORDER BY (importance * 0.7 + CASE WHEN accessCount > 2 THEN 0.3 ELSE 0 END) DESC")
    suspend fun getAllByRelevance(): List<SessionMemoryEntity>

    @Query("SELECT COUNT(*) FROM session_memories")
    suspend fun count(): Int

    @Query("DELETE FROM session_memories WHERE memoryId NOT IN (SELECT memoryId FROM session_memories ORDER BY (importance * 0.7 + CASE WHEN accessCount > 2 THEN 0.3 ELSE 0 END) DESC LIMIT :keep)")
    suspend fun compact(keep: Int = 50)
}
