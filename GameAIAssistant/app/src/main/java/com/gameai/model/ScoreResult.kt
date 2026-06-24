// ScoreResult.kt - 评分结果模型
package com.gameai.model

import com.gameai.common.constants.GameConstants

data class ScoreResult(
    val matchId: String = "",
    val playerName: String = "",
    val position: GameConstants.GamePosition = GameConstants.GamePosition.MID,
    val grade: GameConstants.ScoreGrade = GameConstants.ScoreGrade.B,
    val totalScore: Int = 0,
    val categories: Map<String, CategoryScore> = emptyMap(),
    val aiAnalysis: String = "",
    val aiAdvice: String = "",
    val keyMoments: List<KeyMoment> = emptyList(),
    val timeline: List<TimelinePoint> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    data class CategoryScore(
        val name: String,
        val score: Int,
        val maxScore: Int,
        val rating: String,
        val detail: String = ""
    )

    data class KeyMoment(
        val time: String,
        val event: String,
        val type: MomentType = MomentType.NORMAL
    )

    enum class MomentType {
        POSITIVE, NEGATIVE, NORMAL, CRITICAL
    }

    data class TimelinePoint(
        val gameTime: String,
        val score: Int,
        val event: String = ""
    )
}
