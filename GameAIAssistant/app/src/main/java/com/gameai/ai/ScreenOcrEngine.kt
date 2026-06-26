package com.gameai.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gameai.engine.GameStateManager
import com.gameai.engine.ScoringEngine
import com.gameai.model.MatchData
import com.gameai.model.ScoreResult
import com.gameai.common.constants.GameConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * 实时屏幕OCR分析引擎
 * 负责：定时/事件驱动OCR识别 → 结构化解析 → 评分计算 → 状态广播
 */
class ScreenOcrEngine(private val context: Context) {

    companion object {
        const val ACTION_OCR_STATUS = "com.gameai.OCR_STATUS"
        const val EXTRA_OCR_STATUS = "ocr_status"
        const val EXTRA_SCORE = "score"
        const val EXTRA_GRADE = "grade"
        const val EXTRA_KDA = "kda"

        const val STATUS_IDLE = "idle"
        const val STATUS_RUNNING = "running"
        const val STATUS_PAUSED = "paused"
        const val STATUS_ERROR = "error"

        @Volatile private var instance: ScreenOcrEngine? = null
        fun init(ctx: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = ScreenOcrEngine(ctx.applicationContext)
                    }
                }
            }
        }
        fun getInstance(): ScreenOcrEngine = instance
            ?: throw IllegalStateException("ScreenOcrEngine not initialized")
        fun getOrNull(): ScreenOcrEngine? = instance
    }

    // 状态流
    private val _status = MutableStateFlow(STATUS_IDLE)
    val status: StateFlow<String> = _status.asStateFlow()

    private val _currentScore = MutableStateFlow(0)
    val currentScore: StateFlow<Int> = _currentScore.asStateFlow()

    private val _currentGrade = MutableStateFlow(GameConstants.ScoreGrade.B)
    val currentGrade: StateFlow<GameConstants.ScoreGrade> = _currentGrade.asStateFlow()

    // 配置
    private var scope: CoroutineScope? = null
    private var ocrJob: Job? = null
    private var ocrIntervalMs: Long = 10000L // 默认10秒一次
    private var minOcrIntervalMs: Long = 3000L // 最小间隔3秒
    private var lastOcrTime: Long = 0L

    // 评分引擎
    private val scoringEngine = ScoringEngine()

    // 当前对局数据
    private var currentMatch: MatchData? = null
    private var lastResult: OcrStructuredResult? = null

    // 连续失败计数
    private var consecutiveFailures = 0
    private val maxConsecutiveFailures = 5

    // 降级模式：连续失败后自动降低频率
    private var isDegradedMode = false
    private val degradedIntervalMs: Long = 30000L

    /**
     * 启动OCR分析引擎
     */
    fun start(intervalMs: Long = 10000L) {
        if (_status.value == STATUS_RUNNING) {
            android.util.Log.d("ScreenOcr", "OCR引擎已在运行中")
            return
        }

        this.ocrIntervalMs = intervalMs
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        consecutiveFailures = 0
        isDegradedMode = false

        OcrStructuredAnalyzer.init(context)

        _status.value = STATUS_RUNNING
        android.util.Log.d("ScreenOcr", "🚀 OCR分析引擎启动，间隔: ${intervalMs}ms")
        broadcastStatus(STATUS_RUNNING)

        startOcrLoop()
    }

    /**
     * 停止OCR分析引擎
     */
    fun stop() {
        android.util.Log.d("ScreenOcr", "⏹️ 停止OCR分析引擎")
        ocrJob?.cancel()
        ocrJob = null
        scope?.cancel()
        scope = null
        lastResult = null
        _status.value = STATUS_IDLE
        broadcastStatus(STATUS_IDLE)
    }

    /**
     * 手动触发一次OCR识别（事件驱动）
     */
    fun triggerOcr(reason: String = "manual") {
        if (scope == null) return
        val now = System.currentTimeMillis()
        if (now - lastOcrTime < minOcrIntervalMs) {
            android.util.Log.d("ScreenOcr", "⏳ OCR触发过于频繁，跳过 (原因: $reason)")
            return
        }
        scope?.launch {
            performOcrOnce(reason)
        }
    }

    /**
     * OCR主循环
     */
    private fun startOcrLoop() {
        ocrJob = scope?.launch {
            while (isActive) {
                try {
                    val interval = if (isDegradedMode) degradedIntervalMs else ocrIntervalMs
                    delay(interval)
                    performOcrOnce("scheduled")
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    android.util.Log.e("ScreenOcr", "OCR循环异常", e)
                    delay(5000)
                }
            }
        }
    }

    /**
     * 执行一次OCR识别
     */
    private suspend fun performOcrOnce(reason: String) {
        try {
            lastOcrTime = System.currentTimeMillis()

            // 1. 获取最新截图
            val bitmap = VoiceConversationEngine.latestBitmap
            if (bitmap == null) {
                android.util.Log.w("ScreenOcr", "⚠️ 无可用截图，跳过OCR (原因: $reason)")
                return
            }

            android.util.Log.d("ScreenOcr", "🔍 开始OCR识别 (原因: $reason, 尺寸: ${bitmap.width}x${bitmap.height})")

            // 2. OCR识别 + 结构化解析
            val analyzer = OcrStructuredAnalyzer.getInstance()
            val result = analyzer.analyzeScreenshot(bitmap)

            if (result == null) {
                handleFailure("OCR识别结果为空")
                return
            }

            // 3. 成功处理
            handleSuccess(result)

        } catch (e: Exception) {
            android.util.Log.e("ScreenOcr", "❌ OCR识别失败", e)
            handleFailure(e.message ?: "未知错误")
        }
    }

    /**
     * 处理OCR成功
     */
    private fun handleSuccess(result: OcrStructuredResult) {
        consecutiveFailures = 0
        if (isDegradedMode) {
            isDegradedMode = false
            android.util.Log.i("ScreenOcr", "✅ 恢复正常模式")
        }

        lastResult = result

        android.util.Log.d("ScreenOcr", "✅ OCR成功: $result")

        // 初始化或更新对局数据
        val match = currentMatch ?: run {
            val newMatch = MatchData(
                gameName = "王者荣耀",
                phase = result.gamePhase
            )
            currentMatch = newMatch
            newMatch
        }

        // 应用OCR结果到对局数据
        result.applyToMatch(match)

        // 状态变化检测
        val oldPhase = match.phase
        if (oldPhase != result.gamePhase) {
            android.util.Log.d("ScreenOcr", "🎮 游戏状态变化: $oldPhase → ${result.gamePhase}")

            // 新对局开始
            if (result.gamePhase == GameConstants.MatchPhase.HERO_SELECT ||
                (result.gamePhase == GameConstants.MatchPhase.IN_GAME && oldPhase != GameConstants.MatchPhase.IN_GAME
                        && oldPhase != GameConstants.MatchPhase.HERO_SELECT)) {
                resetMatch()
            }

            // 对局结束
            if (result.gamePhase == GameConstants.MatchPhase.RESULT) {
                finalizeMatch()
            }
        }

        // 计算评分（仅在对局中）
        if (result.gamePhase == GameConstants.MatchPhase.IN_GAME ||
            result.gamePhase == GameConstants.MatchPhase.RESULT) {
            calculateAndBroadcastScore(match)
        }
    }

    /**
     * 处理OCR失败
     */
    private fun handleFailure(error: String) {
        consecutiveFailures++
        android.util.Log.w("ScreenOcr", "❌ OCR失败 (连续 $consecutiveFailures 次): $error")

        if (consecutiveFailures >= maxConsecutiveFailures && !isDegradedMode) {
            isDegradedMode = true
            _status.value = STATUS_PAUSED
            broadcastStatus(STATUS_PAUSED)
            android.util.Log.w("ScreenOcr", "⚠️ 连续失败 $maxConsecutiveFailures 次，进入降级模式，间隔: ${degradedIntervalMs}ms")
        }
    }

    /**
     * 计算并广播评分
     */
    private fun calculateAndBroadcastScore(match: MatchData) {
        try {
            val scoreResult = scoringEngine.calculateScore(match)
            match.currentScore = scoreResult.totalScore.toInt()
            match.currentGrade = scoreResult.grade

            _currentScore.value = scoreResult.totalScore.toInt()
            _currentGrade.value = scoreResult.grade

            // 更新GameStateManager中的对局数据
            GameStateManager.updateMatchKda(
                match.kdaData.kills,
                match.kdaData.deaths,
                match.kdaData.assists
            )
            if (match.gameTimeSec > 0) {
                GameStateManager.updateMatchTime(match.gameTimeSec)
            }
            GameStateManager.updateMatchEconomy(match.economyData.gold, match.gameTimeSec)

            android.util.Log.d("ScreenOcr", "📊 评分: ${scoreResult.totalScore}分, 段位: ${scoreResult.grade}")

            // 广播评分更新
            val intent = android.content.Intent(ACTION_OCR_STATUS).apply {
                putExtra(EXTRA_OCR_STATUS, STATUS_RUNNING)
                putExtra(EXTRA_SCORE, scoreResult.totalScore.toInt())
                putExtra(EXTRA_GRADE, scoreResult.grade.name)
                putExtra(EXTRA_KDA, "${match.kdaData.kills}/${match.kdaData.deaths}/${match.kdaData.assists}")
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)

        } catch (e: Exception) {
            android.util.Log.e("ScreenOcr", "评分计算失败", e)
        }
    }

    /**
     * 重置对局
     */
    private fun resetMatch() {
        currentMatch = MatchData(
            gameName = "王者荣耀",
            phase = GameConstants.MatchPhase.IN_GAME
        )
        _currentScore.value = 0
        _currentGrade.value = GameConstants.ScoreGrade.B
        android.util.Log.d("ScreenOcr", "🔄 新对局开始，数据已重置")
    }

    /**
     * 结束对局
     */
    private fun finalizeMatch() {
        val match = currentMatch ?: return
        match.endTime = System.currentTimeMillis()
        match.isActive = false

        // 最终评分
        val finalResult = scoringEngine.calculateScore(match)
        match.finalResult = finalResult

        android.util.Log.i("ScreenOcr", "🏆 对局结束: ${finalResult.totalScore}分, ${finalResult.grade}")

        // TODO: 保存到数据库
    }

    /**
     * 广播状态
     */
    private fun broadcastStatus(status: String) {
        val intent = android.content.Intent(ACTION_OCR_STATUS).apply {
            putExtra(EXTRA_OCR_STATUS, status)
            putExtra(EXTRA_SCORE, _currentScore.value)
            putExtra(EXTRA_GRADE, _currentGrade.value.name)
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    /**
     * 获取当前评分
     */
    fun getCurrentScore(): Int = _currentScore.value

    /**
     * 获取当前段位
     */
    fun getCurrentGrade(): GameConstants.ScoreGrade = _currentGrade.value

    /**
     * 获取最后一次OCR结果
     */
    fun getLastResult(): OcrStructuredResult? = lastResult

    /**
     * 动态调整OCR间隔
     */
    fun setInterval(intervalMs: Long) {
        this.ocrIntervalMs = intervalMs
        android.util.Log.d("ScreenOcr", "⚙️ OCR间隔调整为: ${intervalMs}ms")
    }
}
