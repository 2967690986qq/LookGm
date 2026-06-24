// MainViewModel.kt - 主界面ViewModel (完整实现)
package com.gameai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.gameai.ai.ScreenAnalysisEngine
import com.gameai.common.constants.GameConstants
import com.gameai.db.AppDatabase
import com.gameai.db.MatchEntity
import com.gameai.engine.GameStateManager
import com.gameai.engine.ScoringEngine
import com.gameai.engine.MatchStateMachine
import com.gameai.model.*
import com.gameai.service.ScreenCaptureService
import com.gameai.service.WebSocketService
import com.gameai.service.VoiceService
import com.gameai.utils.PreferencesManager
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // 数据库
    private val db = AppDatabase.getInstance(application)
    private val matchDao = db.matchDao()

    // 引擎
    private val scoringEngine = ScoringEngine()
    private val stateMachine = MatchStateMachine()

    // 配置
    private val prefs = PreferencesManager.getInstance(application)

    // LiveData
    private val _isCapturing = MutableLiveData(false)
    val isCapturing: LiveData<Boolean> = _isCapturing

    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    private val _currentScore = MutableLiveData(0)
    val currentScore: LiveData<Int> = _currentScore

    private val _currentGrade = MutableLiveData("B")
    val currentGrade: LiveData<String> = _currentGrade

    private val _matchPhase = MutableLiveData(GameConstants.MatchPhase.LOBBY)
    val matchPhase: LiveData<GameConstants.MatchPhase> = _matchPhase

    private val _statusText = MutableLiveData("就绪")
    val statusText: LiveData<String> = _statusText

    private val _fps = MutableLiveData(0)
    val fps: LiveData<Int> = _fps

    private val _latestAnalysis = MutableLiveData("")
    val latestAnalysis: LiveData<String> = _latestAnalysis

    private val _matchHistory = MutableLiveData<List<MatchEntity>>(emptyList())
    val matchHistory: LiveData<List<MatchEntity>> = _matchHistory

    // 评分详情
    private val _scoreBreakdown = MutableLiveData<Map<String, ScoreResult.CategoryScore>>(emptyMap())
    val scoreBreakdown: LiveData<Map<String, ScoreResult.CategoryScore>> = _scoreBreakdown

    // 当前对局实时数据（来自 GameStateManager/OCR，供 UI 观察）
    private val _matchDataLive = MutableLiveData<MatchData?>()
    val matchData: LiveData<MatchData?> = _matchDataLive

    // 当前对局（模拟用，保留兼容）
    private var currentMatch: MatchData? = null

    init {
        // 初始化语音
        VoiceService.init(application)

        // 监听WebSocket回调
        setupWebSocketCallbacks()

        // 监听 GameStateManager 实时数据变化（OCR 识别后回调）
        GameStateManager.onMatchDataChanged = { match ->
            _matchDataLive.postValue(match)
            if (match != null && match.isActive) {
                _currentScore.postValue(match.currentScore)
                _currentGrade.postValue(match.currentGrade.name)
            }
        }

        // 监听 AI 屏幕分析结果（事件驱动）
        ScreenAnalysisEngine.onAnalysisResult = { analysis ->
            _latestAnalysis.postValue(analysis)
        }

        // 监听对局状态
        stateMachine.onPhaseChange { old, new ->
            _matchPhase.postValue(new)
            when (new) {
                GameConstants.MatchPhase.IN_GAME -> {
                    _statusText.postValue("对局中")
                    currentMatch = stateMachine.getCurrentMatch()
                }
                GameConstants.MatchPhase.RESULT -> {
                    _statusText.postValue("结算中...")
                    onMatchEnd()
                }
                GameConstants.MatchPhase.LOADING -> _statusText.postValue("加载中...")
                GameConstants.MatchPhase.HERO_SELECT -> _statusText.postValue("选英雄中")
                GameConstants.MatchPhase.LOBBY -> _statusText.postValue("大厅")
                GameConstants.MatchPhase.MATCHING -> _statusText.postValue("匹配中...")
                GameConstants.MatchPhase.RANK_LOBBY -> _statusText.postValue("排位大厅")
            }
        }

        // 加载历史
        loadMatchHistory()
    }

    private fun setupWebSocketCallbacks() {
        WebSocketService.onConnectionChange = { connected ->
            _isConnected.postValue(connected)
            if (connected) {
                _statusText.postValue("已连接到服务器")
            } else {
                _statusText.postValue("未连接")
            }
        }

        WebSocketService.onScoreUpdate = { result ->
            _currentScore.postValue(result.totalScore)
            _currentGrade.postValue(result.grade.label)
            _latestAnalysis.postValue(result.aiAnalysis)
            _scoreBreakdown.postValue(result.categories)

            // 播报评分
            val ctx = getApplication<Application>()
            if (prefs.isVoiceEnabled()) {
                VoiceService.speakScore(ctx, result.totalScore, result.grade.label)
            }

            // 更新悬浮窗
            if (ScreenCaptureService.isRunning) {
                val intent = android.content.Intent(ctx, com.gameai.service.FloatingWindowService::class.java).apply {
                    action = com.gameai.service.FloatingWindowService.ACTION_UPDATE_SCORE
                    putExtra(com.gameai.service.FloatingWindowService.EXTRA_SCORE, result.totalScore)
                    putExtra(com.gameai.service.FloatingWindowService.EXTRA_GRADE, result.grade.label)
                }
                ctx.startService(intent)
            }
        }

        WebSocketService.onMatchStatus = { status, detail ->
            when (status) {
                "in_game" -> stateMachine.transitionTo(GameConstants.MatchPhase.IN_GAME)
                "result" -> stateMachine.transitionTo(GameConstants.MatchPhase.RESULT)
                "lobby" -> stateMachine.transitionTo(GameConstants.MatchPhase.LOBBY)
                "loading" -> stateMachine.transitionTo(GameConstants.MatchPhase.LOADING)
            }
        }

        WebSocketService.onTtsText = { text ->
            if (prefs.isVoiceEnabled()) {
                VoiceService.speak(getApplication(), text)
            }
        }
    }

    private fun onMatchEnd() {
        val match = stateMachine.endMatch() ?: return

        // 本地计算最终评分
        val result = scoringEngine.calculateScore(match)
        match.finalResult = result

        _currentScore.postValue(result.totalScore)
        _currentGrade.postValue(result.grade.label)
        _latestAnalysis.postValue(result.aiAnalysis)
        _scoreBreakdown.postValue(result.categories)

        // 保存到数据库
        viewModelScope.launch {
            val entity = MatchEntity.fromMatchDataAndResult(
                matchId = match.matchId,
                gameName = match.gameName,
                heroName = match.heroName,
                position = match.position.label,
                startTime = match.startTime,
                endTime = match.endTime,
                totalScore = result.totalScore,
                grade = result.grade.label,
                kills = match.kdaData.kills,
                deaths = match.kdaData.deaths,
                assists = match.kdaData.assists,
                gpm = match.economyData.goldPerMin,
                damage = match.teamfightData.damageDealt,
                result = result
            )
            matchDao.insertMatch(entity)
            loadMatchHistory()
        }

        // 语音总结
        val ctx = getApplication<Application>()
        VoiceService.speak(ctx, "对局结束！最终评分${result.totalScore}分，等级${result.grade.label}")
    }

    fun startCapture(resultCode: Int, data: android.content.Intent) {
        val config = prefs.loadConfig()
        val intent = android.content.Intent(getApplication(), ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_GAME_NAME, config.gameName)
            putExtra(ScreenCaptureService.EXTRA_WS_HOST, config.serverHost)
            putExtra(ScreenCaptureService.EXTRA_WS_PORT, config.serverPort)
            putExtra("capture_fps", config.captureFps)
            putExtra("jpeg_quality", config.jpegQuality)
        }
        getApplication<Application>().startService(intent)
        _isCapturing.postValue(true)
        _statusText.postValue("开始采集...")

        // 自动启动悬浮窗
        if (config.enableFloatingBall) {
            getApplication<Application>().startService(
                android.content.Intent(getApplication(), com.gameai.service.FloatingWindowService::class.java)
            )
        }

        // 如果启用语音
        if (config.enableVoice) {
            VoiceService.speak(getApplication(), "游戏AI助手已启动")
        }
    }

    fun stopCapture() {
        val intent = android.content.Intent(getApplication(), ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
        _isCapturing.postValue(false)
        _isConnected.postValue(false)
        _statusText.postValue("已停止")
        _fps.postValue(0)
        _currentScore.postValue(0)
        _currentGrade.postValue("B")
    }

    fun requestScreenCapture(activity: androidx.fragment.app.FragmentActivity) {
        ScreenCaptureService.requestPermission(activity, 1001)
    }

    // 本地模拟评分 (不依赖PC端时使用)
    fun simulateScoreUpdate() {
        viewModelScope.launch {
            val match = currentMatch ?: MatchData(gameName = "王者荣耀").also { currentMatch = it }

            // 随机更新KDA
            match.kdaData = match.kdaData.copy(
                kills = match.kdaData.kills + (0..3).random(),
                deaths = match.kdaData.deaths + (0..1).random(),
                assists = match.kdaData.assists + (0..4).random()
            )
            match.economyData = match.economyData.copy(
                goldPerMin = (400..900).random(),
                creepScore = match.economyData.creepScore + (5..20).random()
            )
            match.teamfightData = match.teamfightData.copy(
                participationRate = kotlin.random.Random.nextFloat() * 0.65f + 0.3f,
                damageDealt = (5000..35000).random()
            )
            match.visionData = match.visionData.copy(
                visionScore = (5..100).random()
            )

            val result = scoringEngine.calculateScore(match)
            _currentScore.postValue(result.totalScore)
            _currentGrade.postValue(result.grade.label)
            _latestAnalysis.postValue(result.aiAnalysis)
            _scoreBreakdown.postValue(result.categories)
        }
    }

    // 模拟对局结束
    fun simulateMatchEnd() {
        currentMatch?.let { match ->
            match.isActive = false
            match.endTime = System.currentTimeMillis()
            val result = scoringEngine.calculateScore(match)
            match.finalResult = result

            _currentScore.postValue(result.totalScore)
            _currentGrade.postValue(result.grade.label)
            _latestAnalysis.postValue(result.aiAnalysis)
            _scoreBreakdown.postValue(result.categories)

            viewModelScope.launch {
                val entity = MatchEntity.fromMatchDataAndResult(
                    matchId = match.matchId,
                    gameName = match.gameName,
                    heroName = match.heroName,
                    position = match.position.label,
                    startTime = match.startTime,
                    endTime = match.endTime,
                    totalScore = result.totalScore,
                    grade = result.grade.label,
                    kills = match.kdaData.kills,
                    deaths = match.kdaData.deaths,
                    assists = match.kdaData.assists,
                    gpm = match.economyData.goldPerMin,
                    damage = match.teamfightData.damageDealt,
                    result = result
                )
                matchDao.insertMatch(entity)
                loadMatchHistory()
            }

            VoiceService.speak(getApplication(), "对局结束！最终评分${result.totalScore}分，等级${result.grade.label}")
            currentMatch = null
        }
    }

    private fun loadMatchHistory() {
        viewModelScope.launch {
            matchDao.getAllMatches().collect { matches ->
                _matchHistory.postValue(matches)
            }
        }
    }

    fun refreshMatchHistory() {
        loadMatchHistory()
    }

    fun clearHistory() {
        viewModelScope.launch {
            matchDao.deleteAll()
        }
    }

    fun getConfig(): GameConfig = prefs.loadConfig()
    fun saveConfig(config: GameConfig) = prefs.saveConfig(config)
}
