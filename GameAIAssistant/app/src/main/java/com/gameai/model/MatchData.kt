// MatchData.kt - 对局数据模型
package com.gameai.model

import com.gameai.common.constants.GameConstants

data class MatchData(
    val matchId: String = generateMatchId(),
    val gameName: String = "",
    val startTime: Long = System.currentTimeMillis(),
    var endTime: Long = 0L,
    var phase: GameConstants.MatchPhase = GameConstants.MatchPhase.LOBBY,
    var heroName: String = "",
    var position: GameConstants.GamePosition = GameConstants.GamePosition.MID,
    var currentScore: Int = 0,
    var currentGrade: GameConstants.ScoreGrade = GameConstants.ScoreGrade.B,
    var kdaData: KdaData = KdaData(),
    var economyData: EconomyData = EconomyData(),
    var visionData: VisionData = VisionData(),
    var teamfightData: TeamfightData = TeamfightData(),
    var objectiveData: ObjectiveData = ObjectiveData(),
    var movementData: MovementData = MovementData(),
    var finalResult: ScoreResult? = null,
    var isActive: Boolean = true,
    var gameTimeSec: Int = 0
) {
    data class KdaData(
        var kills: Int = 0,
        var deaths: Int = 0,
        var assists: Int = 0
    ) {
        val kda: Float get() = if (deaths == 0) (kills + assists).toFloat() else (kills + assists).toFloat() / deaths
    }

    data class EconomyData(
        var gold: Int = 0,
        var goldPerMin: Int = 0,
        var creepScore: Int = 0,
        var items: List<String> = emptyList()
    )

    data class VisionData(
        var wardsPlaced: Int = 0,
        var wardsDestroyed: Int = 0,
        var visionScore: Int = 0
    )

    data class TeamfightData(
        var participationRate: Float = 0f,
        var damageDealt: Int = 0,
        var damageTaken: Int = 0,
        var healing: Int = 0,
        var ccScore: Int = 0
    )

    data class ObjectiveData(
        var towers: Int = 0,
        var dragons: Int = 0,
        var barons: Int = 0,
        var heralds: Int = 0
    )

    data class MovementData(
        var distanceTraveled: Int = 0,
        var averageSpeed: Float = 0f,
        var roams: Int = 0,
        var successfulRoams: Int = 0
    )

    companion object {
        fun generateMatchId(): String = "M${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}
