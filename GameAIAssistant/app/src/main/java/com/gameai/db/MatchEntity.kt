// MatchEntity.kt - 对局数据库实体
package com.gameai.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.gameai.model.ScoreResult
import com.google.gson.Gson

@Entity(tableName = "matches")
data class MatchEntity(
    @PrimaryKey val matchId: String,
    val gameName: String,
    val heroName: String,
    val position: String,
    val startTime: Long,
    val endTime: Long,
    val totalScore: Int,
    val grade: String,
    val kdaKills: Int = 0,
    val kdaDeaths: Int = 0,
    val kdaAssists: Int = 0,
    val goldPerMin: Int = 0,
    val damageDealt: Int = 0,
    val scoreResultJson: String = "",
    val isVictory: Boolean = true
) {
    fun toScoreResult(): ScoreResult? {
        return try {
            Gson().fromJson(scoreResultJson, ScoreResult::class.java)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun fromMatchDataAndResult(
            matchId: String,
            gameName: String,
            heroName: String,
            position: String,
            startTime: Long,
            endTime: Long,
            totalScore: Int,
            grade: String,
            kills: Int,
            deaths: Int,
            assists: Int,
            gpm: Int,
            damage: Int,
            result: ScoreResult?
        ): MatchEntity {
            return MatchEntity(
                matchId = matchId,
                gameName = gameName,
                heroName = heroName,
                position = position,
                startTime = startTime,
                endTime = endTime,
                totalScore = totalScore,
                grade = grade,
                kdaKills = kills,
                kdaDeaths = deaths,
                kdaAssists = assists,
                goldPerMin = gpm,
                damageDealt = damage,
                scoreResultJson = Gson().toJson(result),
                isVictory = true
            )
        }
    }
}
