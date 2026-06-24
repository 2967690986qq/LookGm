// MatchDao.kt - 对局数据访问对象
package com.gameai.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MatchDao {
    @Query("SELECT * FROM matches ORDER BY startTime DESC")
    fun getAllMatches(): Flow<List<MatchEntity>>

    @Query("SELECT * FROM matches WHERE matchId = :matchId")
    suspend fun getMatchById(matchId: String): MatchEntity?

    @Query("SELECT * FROM matches WHERE gameName = :gameName ORDER BY startTime DESC")
    fun getMatchesByGame(gameName: String): Flow<List<MatchEntity>>

    @Query("SELECT * FROM matches ORDER BY totalScore DESC LIMIT :limit")
    fun getTopMatches(limit: Int = 10): Flow<List<MatchEntity>>

    @Query("SELECT AVG(totalScore) FROM matches WHERE gameName = :gameName")
    suspend fun getAverageScore(gameName: String): Float?

    @Query("SELECT COUNT(*) FROM matches")
    suspend fun getTotalMatchCount(): Int

    @Query("SELECT COUNT(*) FROM matches WHERE gameName = :gameName")
    suspend fun getMatchCountByGame(gameName: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatch(match: MatchEntity)

    @Update
    suspend fun updateMatch(match: MatchEntity)

    @Delete
    suspend fun deleteMatch(match: MatchEntity)

    @Query("DELETE FROM matches")
    suspend fun deleteAll()

    @Query("DELETE FROM matches WHERE startTime < :beforeTime")
    suspend fun deleteOldMatches(beforeTime: Long)
}
