// MatchStateMachine.kt - 对局状态机
package com.gameai.engine

import com.gameai.common.constants.GameConstants
import com.gameai.model.MatchData

class MatchStateMachine {
    private var currentPhase = GameConstants.MatchPhase.LOBBY
    private var currentMatch: MatchData? = null
    private val phaseChangeListeners = mutableListOf<(GameConstants.MatchPhase, GameConstants.MatchPhase) -> Unit>()
    private val matchEventListeners = mutableListOf<(String) -> Unit>()

    fun onPhaseChange(listener: (GameConstants.MatchPhase, GameConstants.MatchPhase) -> Unit) {
        phaseChangeListeners.add(listener)
    }

    fun onMatchEvent(listener: (String) -> Unit) {
        matchEventListeners.add(listener)
    }

    fun transitionTo(newPhase: GameConstants.MatchPhase) {
        if (newPhase == currentPhase) return

        val oldPhase = currentPhase
        currentPhase = newPhase
        phaseChangeListeners.forEach { it(oldPhase, newPhase) }

        // 对局相关事件
        when (newPhase) {
            GameConstants.MatchPhase.IN_GAME -> {
                if (currentMatch == null || !currentMatch!!.isActive) {
                    currentMatch = MatchData(gameName = "王者荣耀")
                    matchEventListeners.forEach { it("match_started") }
                }
            }
            GameConstants.MatchPhase.RESULT -> {
                currentMatch?.let {
                    it.isActive = false
                    it.endTime = System.currentTimeMillis()
                    matchEventListeners.forEach { _ -> "match_ended" }
                }
            }
            GameConstants.MatchPhase.LOBBY -> {
                currentMatch = null
            }
            else -> {}
        }
    }

    fun getCurrentPhase(): GameConstants.MatchPhase = currentPhase
    fun getCurrentMatch(): MatchData? = currentMatch

    fun updateMatchData(updater: (MatchData) -> Unit) {
        currentMatch?.let { match ->
            if (match.isActive) {
                updater(match)
                matchEventListeners.forEach { it("match_updated") }
            }
        }
    }

    fun endMatch(): MatchData? {
        val match = currentMatch
        match?.let {
            it.isActive = false
            it.endTime = System.currentTimeMillis()
        }
        currentMatch = null
        return match
    }
}
