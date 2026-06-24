// GameStateManager.kt - 游戏状态统一管理器（串联状态机 + 评分引擎 + 悬浮窗）
package com.gameai.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gameai.common.constants.GameConstants
import com.gameai.model.MatchData
import com.gameai.service.ScreenCaptureService
import com.gameai.service.FloatingWindowService
import com.gameai.ai.ScreenAnalysisEngine

/**
 * 单例管理器：
 * 1. 接收 ScreenCaptureService 发出的游戏状态广播
 * 2. 驱动 MatchStateMachine 状态转换
 * 3. 定时调用 ScoringEngine 计算评分
 * 4. 通过 LocalBroadcast 把评分发送给 FloatingWindowService
 */
object GameStateManager {

    private var appContext: Context? = null
    private val stateMachine = MatchStateMachine()
    private val scoringEngine = ScoringEngine()
    private var currentMatch: MatchData? = null
    private var isScoringActive = false

    // Dashboard 数据变化回调（通知 MainViewModel 刷新 UI）
    var onMatchDataChanged: ((MatchData?) -> Unit)? = null

    // 广播接收：游戏状态变化
    private val gameStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenCaptureService.ACTION_GAME_STATE_CHANGED) {
                val phaseName = intent.getStringExtra(ScreenCaptureService.EXTRA_GAME_PHASE)
                val phase = try {
                    GameConstants.MatchPhase.valueOf(phaseName ?: "LOBBY")
                } catch (e: Exception) {
                    android.util.Log.w("GameStateManager", "phase parse error", e)
                    GameConstants.MatchPhase.LOBBY
                }
                onGamePhaseChanged(phase)
            }
        }
    }

    // 广播接收：AI 分析结果（来自 ScreenAnalysisEngine）
    private val aiAnalysisReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenAnalysisEngine.ACTION_AI_ANALYSIS) {
                val text = intent?.getStringExtra(ScreenAnalysisEngine.EXTRA_ANALYSIS_TEXT) ?: return
                onAiAnalysisReceived(text)
            }
        }
    }

    // 广播接收：实时评分更新（来自PC端AI分析结果）
    private val scoreReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenCaptureService.ACTION_SCORE_UPDATE) {
                val score = intent.getIntExtra(ScreenCaptureService.EXTRA_SCORE, 0)
                val grade = intent.getStringExtra(ScreenCaptureService.EXTRA_GRADE) ?: "B"
                onScoreReceived(score, grade)
            }
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        val lbm = LocalBroadcastManager.getInstance(context)
        lbm.registerReceiver(gameStateReceiver, IntentFilter(ScreenCaptureService.ACTION_GAME_STATE_CHANGED))
        lbm.registerReceiver(scoreReceiver, IntentFilter(ScreenCaptureService.ACTION_SCORE_UPDATE))
        lbm.registerReceiver(aiAnalysisReceiver, IntentFilter(ScreenAnalysisEngine.ACTION_AI_ANALYSIS))

        // 监听 MatchStateMachine 阶段变化
        stateMachine.onPhaseChange { oldPhase, newPhase ->
            handlePhaseTransition(oldPhase, newPhase)
        }
    }

    fun release() {
        val lbm = appContext?.let { LocalBroadcastManager.getInstance(it) }
        lbm?.unregisterReceiver(gameStateReceiver)
        lbm?.unregisterReceiver(scoreReceiver)
        lbm?.unregisterReceiver(aiAnalysisReceiver)
        appContext = null
    }

    // ============================================================
    //  游戏阶段变化处理
    // ============================================================
    private fun onGamePhaseChanged(phase: GameConstants.MatchPhase) {
        stateMachine.transitionTo(phase)

        // 用 LocalBroadcast 通知所有UI组件
        val intent = Intent(FloatingWindowService.ACTION_UPDATE_LANE).apply {
            putExtra(FloatingWindowService.EXTRA_LANE, "")
            putExtra("phase", phase.name)
        }
        appContext?.let { LocalBroadcastManager.getInstance(it).sendBroadcast(intent) }
    }

    private fun handlePhaseTransition(oldPhase: GameConstants.MatchPhase, newPhase: GameConstants.MatchPhase) {
        when (newPhase) {
            GameConstants.MatchPhase.IN_GAME -> {
                // 对局开始：初始化 MatchData，启动评分
                if (currentMatch == null || !currentMatch!!.isActive) {
                    currentMatch = MatchData(gameName = "王者荣耀")
                    isScoringActive = true
                    // 通知悬浮窗显示评分
                    broadcastScoreToFloatingWindow()
                }
            }
            GameConstants.MatchPhase.RESULT -> {
                // 对局结束：最终评分，停止实时评分
                isScoringActive = false
                currentMatch?.let { match ->
                    match.isActive = false
                    match.endTime = System.currentTimeMillis()
                    // 用评分引擎做最终计算
                    val result = scoringEngine.calculateScore(match)
                    broadcastFinalScore(result.totalScore, result.grade.name)
                }
                currentMatch = null
            }
            GameConstants.MatchPhase.LOBBY -> {
                isScoringActive = false
                currentMatch = null
            }
            else -> {}
        }
    }

    // ============================================================
    //  评分相关
    // ============================================================
    private fun onScoreReceived(score: Int, grade: String) {
        // 把PC端AI返回的评分同步到悬浮窗
        broadcastScoreToFloatingWindow(score, grade)
    }

    /** AI 分析结果：转发给悬浮窗展示 */
    private fun onAiAnalysisReceived(analysis: String) {
        val intent = Intent(FloatingWindowService.ACTION_UPDATE_ANALYSIS).apply {
            putExtra(FloatingWindowService.EXTRA_ANALYSIS, analysis)
        }
        appContext?.let { LocalBroadcastManager.getInstance(it).sendBroadcast(intent) }
    }

    /** 外部调用：更新当前对局的KDA数据（OCR或AI分析后调用）*/
    fun updateMatchKda(kills: Int, deaths: Int, assists: Int) {
        currentMatch?.let { match ->
            if (match.isActive) {
                match.kdaData.kills = kills
                match.kdaData.deaths = deaths
                match.kdaData.assists = assists
                recalculateAndBroadcastScore()
            }
        }
    }

    /** 外部调用：更新经济数据（传入总金币，自动计算每分钟金币）*/
    fun updateMatchEconomy(totalGold: Int, gameTimeSec: Int = -1) {
        currentMatch?.let { match ->
            if (match.isActive) {
                // 如果提供了游戏时间（>1分钟），计算每分钟金币
                val goldPerMin = if (gameTimeSec > 60) {
                    totalGold * 60 / gameTimeSec
                } else {
                    totalGold
                }
                match.economyData.gold = totalGold
                match.economyData.goldPerMin = goldPerMin
                recalculateAndBroadcastScore()
            }
        }
    }

    /** 外部调用：更新对局时间（秒）*/
    fun updateMatchTime(gameTimeSec: Int) {
        currentMatch?.let { match ->
            if (match.isActive) {
                match.gameTimeSec = gameTimeSec
            }
        }
    }

    /** 触发重新评分并广播 */
    private fun recalculateAndBroadcastScore() {
        currentMatch?.let { match ->
            if (!match.isActive) return
            val result = scoringEngine.calculateScore(match)
            match.currentScore = result.totalScore
            match.currentGrade = result.grade
            broadcastScoreToFloatingWindow(result.totalScore, result.grade.name)

            // 通知 UI 刷新
            onMatchDataChanged?.invoke(match)
        }
    }

    /** 发送评分广播给 FloatingWindowService */
    private fun broadcastScoreToFloatingWindow(score: Int? = null, grade: String? = null) {
        val s = score ?: currentMatch?.currentScore ?: 0
        val g = grade ?: currentMatch?.currentGrade?.name ?: "B"
        // 调用非空参数版本，避免递归
        broadcastScoreToFloatingWindowImpl(s, g)
    }

    private fun broadcastScoreToFloatingWindowImpl(score: Int, grade: String) {
        val intent = Intent(FloatingWindowService.ACTION_UPDATE_SCORE).apply {
            putExtra(FloatingWindowService.EXTRA_SCORE, score)
            putExtra(FloatingWindowService.EXTRA_GRADE, grade)
        }
        appContext?.let { LocalBroadcastManager.getInstance(it).sendBroadcast(intent) }
    }

    private fun broadcastFinalScore(score: Int, grade: String) {
        val intent = Intent("com.gameai.MATCH_ENDED").apply {
            putExtra("score", score)
            putExtra("grade", grade)
        }
        appContext?.let { LocalBroadcastManager.getInstance(it).sendBroadcast(intent) }
    }

    /** 获取当前对局数据（供UI展示）*/
    fun getCurrentMatch(): MatchData? = currentMatch

    /** 获取当前评分（供UI展示）*/
    fun getCurrentScore(): Pair<Int, String> {
        val match = currentMatch
        return if (match != null && match.isActive) {
            Pair(match.currentScore, match.currentGrade.name)
        } else {
            Pair(0, "B")
        }
    }
}
