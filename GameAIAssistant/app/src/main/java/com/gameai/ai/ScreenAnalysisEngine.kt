// ScreenAnalysisEngine.kt - 事件驱动的 AI 屏幕分析引擎
package com.gameai.ai

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gameai.model.ProviderConfig
import com.gameai.recognition.GameStateDetector
import com.gameai.utils.PreferencesManager
import kotlinx.coroutines.*

/**
 * 事件驱动的 AI 屏幕分析引擎
 * 
 * 触发策略：
 * - 击杀/死亡事件 → 立即分析（战术复盘）
 * - 团战检测 → 1秒后分析（总结团战胜负）
 * - 对局刚开始 → 延迟3秒分析（阵容分析）
 * - 冷却机制：最少间隔 8 秒，防止频繁调用
 */
object ScreenAnalysisEngine {

    private const val TAG = "ScreenAnalysisEngine"
    private const val MIN_ANALYSIS_INTERVAL = 8000L  // 最小分析间隔 8 秒
    private const val COOLDOWN_AFTER_KILL = 3000L     // 击杀后冷却 3 秒再检测

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastAnalysisTime = 0L
    private var lastKillEventTime = 0L
    private var isAnalyzing = false
    private var appContext: Context? = null

    /** 最新分析结果 */
    var latestAnalysis: String? = null
        private set

    /** 分析结果变化回调 */
    var onAnalysisResult: ((String) -> Unit)? = null

    // ============================================================
    //  初始化
    // ============================================================

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun release() {
        scope.cancel()
        appContext = null
    }

    // ============================================================
    //  画面分析触发入口
    // ============================================================

    /**
     * 每帧调用：检测是否有值得分析的事件
     * @param bitmap 当前屏幕截图
     * @param detector 游戏状态检测器
     */
    fun onFrameCaptured(bitmap: Bitmap, detector: GameStateDetector) {
        val now = System.currentTimeMillis()

        // 冷却检查
        if (now - lastAnalysisTime < MIN_ANALYSIS_INTERVAL) return
        if (isAnalyzing) return

        // === 检测击杀/死亡事件 ===
        if (detector.detectKillEvent(bitmap)) {
            // 击杀后冷却
            if (now - lastKillEventTime < COOLDOWN_AFTER_KILL) return
            lastKillEventTime = now

            // 延迟 2 秒（等击杀特效消退），截图分析
            val frameCopy = bitmap.copy(bitmap.config, false)
            scope.launch {
                delay(2000)
                triggerAnalysis(frameCopy, "刚刚发生了一次击杀或死亡事件，请基于当前画面分析发生了什么，并给出后续操作建议。")
                frameCopy.recycle()
            }
            return
        }

        // === 检测团战（比分面板出现 + 画面红色较多） ===
        if (detector.detectScoreboard(bitmap)) {
            val frameCopy = bitmap.copy(bitmap.config, false)
            scope.launch {
                delay(1000)
                triggerAnalysis(frameCopy, "比分面板可见，可能正在或刚结束团战。请分析当前局势并给出战术建议。")
                frameCopy.recycle()
            }
        }
    }

    /**
     * 手动触发一次分析（对局刚开始时由外部调用）
     */
    fun triggerOnMatchStart(bitmap: Bitmap) {
        val frameCopy = bitmap.copy(bitmap.config, false)
        scope.launch {
            delay(3000)  // 延迟 3 秒等加载完
            triggerAnalysis(frameCopy, "对局刚开始，请分析双方阵容（如果能看出），给出开局建议和需要小心的英雄。")
            frameCopy.recycle()
        }
    }

    // ============================================================
    //  分析执行
    // ============================================================

    private suspend fun triggerAnalysis(bitmap: Bitmap, context: String) {
        if (isAnalyzing) return
        isAnalyzing = true
        lastAnalysisTime = System.currentTimeMillis()

        try {
            val config = getModelConfig() ?: run {
                isAnalyzing = false
                return
            }

            val systemPrompt = buildSystemPrompt(context)

            val result = CloudAiClient.analyze(bitmap, config, systemPrompt)

            if (result != null && result.isNotBlank()) {
                latestAnalysis = result
                onAnalysisResult?.invoke(result)
                broadcastAnalysis(result)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Analysis failed", e)
        } finally {
            isAnalyzing = false
        }
    }

    // ============================================================
    //  配置与提示词
    // ============================================================

    /** 从 PreferencesManager 读取分析专用模型配置 */
    private fun getModelConfig(): ProviderConfig? {
        val ctx = appContext ?: return null
        val prefs = PreferencesManager.getInstance(ctx)
        val config = prefs.getAnalysisModelConfig() ?: prefs.getCurrentProviderConfig()

        // 云端模式必须配置 API Key
        if (prefs.getModelMode() == com.gameai.model.ModelMode.CLOUD && config.apiKey.isBlank()) {
            android.util.Log.w(TAG, "API Key 未配置，跳过分析")
            return null
        }
        return config
    }

    /** 构建系统提示词 */
    private fun buildSystemPrompt(context: String): String {
        return """
你是一个王者荣耀游戏AI助手，你的任务是分析游戏画面并给出简洁实用的战术建议。

游戏背景：
- 这是王者荣耀手游，5v5 MOBA
- 用户正在使用游戏辅助工具，需要实时战术指导

分析要求：
1. 基于画面内容判断当前局势（优势/劣势/均势）
2. 给出 1-2 条具体可操作的建议（不超过50字）
3. 如果有危险，提醒用户撤退
4. 如果看到击杀机会，给出进攻提示
5. 回复简洁，直接给建议，不要分析过程

$context

请用中文回复，直接给出建议，不要问候语。
""".trimIndent()
    }

    // ============================================================
    //  广播
    // ============================================================

    const val ACTION_AI_ANALYSIS = "com.gameai.AI_ANALYSIS"
    const val EXTRA_ANALYSIS_TEXT = "analysis_text"
    const val EXTRA_TIMESTAMP = "timestamp"

    private fun broadcastAnalysis(analysis: String) {
        val ctx = appContext ?: return
        val intent = Intent(ACTION_AI_ANALYSIS).apply {
            putExtra(EXTRA_ANALYSIS_TEXT, analysis)
            putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }
}
