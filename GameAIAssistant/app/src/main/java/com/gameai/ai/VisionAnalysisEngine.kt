// VisionAnalysisEngine.kt - 实时视觉屏幕分析引擎
package com.gameai.ai

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gameai.model.ProviderConfig
import com.gameai.service.VoiceService
import com.gameai.utils.PreferencesManager
import kotlinx.coroutines.*

/**
 * 实时视觉屏幕分析引擎
 *
 * 功能：
 * - 开启录屏后，每隔固定时间自动分析当前屏幕
 * - 使用视觉模型（vision/OCR）理解屏幕内容
 * - 分析结果通过语音播报 + 广播通知
 * - 可配置分析间隔、是否开启语音播报
 *
 * 与 ScreenAnalysisEngine 的区别：
 * - ScreenAnalysisEngine：事件驱动（击杀/团战），针对王者荣耀游戏分析
 * - VisionAnalysisEngine：定时分析，通用屏幕理解，使用视觉模型
 */
object VisionAnalysisEngine {

    private const val TAG = "VisionAnalysisEngine"

    // 默认分析间隔 10 秒
    private const val DEFAULT_ANALYSIS_INTERVAL_MS = 10000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = null
    private var isRunning = false
    private var analysisJob: Job? = null
    private var isAnalyzing = false
    private var lastAnalysisTime = 0L

    /** 最新分析结果 */
    var latestResult: String? = null
        private set

    /** 分析结果回调 */
    var onResult: ((String) -> Unit)? = null

    /** 是否启用语音播报 */
    var enableVoiceSpeak = true

    /** 分析间隔（毫秒） */
    var analysisIntervalMs = DEFAULT_ANALYSIS_INTERVAL_MS

    // ============================================================
    //  初始化与启停
    // ============================================================

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun release() {
        stop()
        scope.cancel()
        appContext = null
    }

    /**
     * 启动实时视觉分析
     * @param intervalMs 分析间隔，默认10秒
     */
    fun start(intervalMs: Long = DEFAULT_ANALYSIS_INTERVAL_MS) {
        if (isRunning) return
        if (appContext == null) return

        // 检查是否有视觉模型配置
        val config = getVisionConfig()
        if (config == null) {
            android.util.Log.w(TAG, "未配置视觉模型，跳过实时视觉分析")
            return
        }

        analysisIntervalMs = intervalMs
        isRunning = true
        android.util.Log.d(TAG, "🚀 实时视觉分析已启动，间隔 ${intervalMs}ms，模型: ${config.modelName}")

        analysisJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    delay(analysisIntervalMs)
                    // 从 VoiceConversationEngine 获取最新帧
                    val bitmap = VoiceConversationEngine.latestBitmap
                    if (bitmap != null && isRunning) {
                        triggerAnalysis(bitmap)
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "分析循环出错", e)
                    delay(analysisIntervalMs)
                }
            }
        }
    }

    /** 停止实时视觉分析 */
    fun stop() {
        isRunning = false
        analysisJob?.cancel()
        analysisJob = null
        android.util.Log.d(TAG, "⏹️ 实时视觉分析已停止")
    }

    /** 是否正在运行 */
    fun isActive(): Boolean = isRunning

    // ============================================================
    //  手动触发分析
    // ============================================================

    /**
     * 手动触发一次屏幕分析
     * @param bitmap 屏幕截图
     * @param customPrompt 自定义提示词（可选）
     */
    fun triggerManualAnalysis(bitmap: Bitmap, customPrompt: String? = null) {
        scope.launch {
            triggerAnalysis(bitmap, customPrompt)
        }
    }

    // ============================================================
    //  分析执行
    // ============================================================

    private suspend fun triggerAnalysis(bitmap: Bitmap, customPrompt: String? = null) {
        if (isAnalyzing) return
        val ctx = appContext ?: return

        val config = getVisionConfig() ?: return
        if (config.apiKey.isBlank()) return

        // 复制一份bitmap，防止外部被回收
        val frameCopy = bitmap.copy(bitmap.config, false)

        isAnalyzing = true
        lastAnalysisTime = System.currentTimeMillis()

        try {
            val prompt = customPrompt ?: buildDefaultPrompt()

            android.util.Log.d(TAG, "🔍 开始视觉分析，模型: ${config.modelName}")

            val result = CloudAiClient.analyze(frameCopy, config, prompt)

            if (result != null && result.isNotBlank()) {
                latestResult = result
                android.util.Log.d(TAG, "✅ 视觉分析结果: ${result.take(100)}")

                // 回调通知
                onResult?.invoke(result)

                // 广播通知
                broadcastResult(result)

                // 语音播报
                if (enableVoiceSpeak && !VoiceService.isSpeaking()) {
                    VoiceService.speak(ctx, result)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "视觉分析失败", e)
        } finally {
            isAnalyzing = false
            frameCopy.recycle()
        }
    }

    // ============================================================
    //  配置
    // ============================================================

    private fun getVisionConfig(): ProviderConfig? {
        val ctx = appContext ?: return null
        val prefs = PreferencesManager.getInstance(ctx)
        return prefs.getVisionModelConfig()
    }

    /** 构建默认分析提示词 */
    private fun buildDefaultPrompt(): String {
        return """
请用简洁的语言描述当前屏幕上的内容。

要求：
1. 用 1-2 句话概括屏幕上显示的是什么
2. 如果有重要信息，提炼出关键点
3. 回复要口语化，适合语音播报
4. 不超过 80 个字
5. 用中文回复

直接描述你看到的内容，不要问候语。
""".trimIndent()
    }

    // ============================================================
    //  广播
    // ============================================================

    const val ACTION_VISION_ANALYSIS = "com.gameai.VISION_ANALYSIS"
    const val EXTRA_VISION_RESULT = "vision_result"
    const val EXTRA_TIMESTAMP = "timestamp"

    private fun broadcastResult(result: String) {
        val ctx = appContext ?: return
        val intent = Intent(ACTION_VISION_ANALYSIS).apply {
            putExtra(EXTRA_VISION_RESULT, result)
            putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }
}
