// UsageDao.kt — 用量数据访问层
package com.gameai.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageDao {

    @Query("SELECT * FROM usage_records ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentRecords(limit: Int = 50): Flow<List<UsageEntity>>

    @Query("SELECT * FROM usage_records WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun getRecordsSince(since: Long): Flow<List<UsageEntity>>

    @Insert
    suspend fun insertRecord(record: UsageEntity)

    @Query("SELECT SUM(totalTokens) FROM usage_records WHERE timestamp >= :since")
    suspend fun getTotalTokensSince(since: Long): Int?

    @Query("SELECT SUM(promptTokens) AS promptTokens, SUM(completionTokens) AS completionTokens, SUM(totalTokens) AS totalTokens FROM usage_records WHERE timestamp >= :since")
    suspend fun getTokenSummarySince(since: Long): UsageSummary?

    @Query("SELECT providerName, modelName, purpose, SUM(totalTokens) as totalTokens FROM usage_records WHERE timestamp >= :since GROUP BY providerName, modelName, purpose ORDER BY totalTokens DESC")
    suspend fun getUsageByModelSince(since: Long): List<ModelUsageSummary>

    @Query("DELETE FROM usage_records WHERE timestamp < :before")
    suspend fun deleteOldRecords(before: Long)

    data class UsageSummary(
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int
    )

    data class ModelUsageSummary(
        val providerName: String,
        val modelName: String,
        val purpose: String,
        val totalTokens: Int
    )
}
