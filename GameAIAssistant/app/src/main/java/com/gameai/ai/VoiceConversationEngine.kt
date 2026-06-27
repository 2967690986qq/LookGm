// VoiceConversationEngine.kt — 实时流式语音对话引擎
// 仿豆包电话体验：SSE 流式 LLM + 分句 TTS + 打断支持
// v3.0 新增：语音指令控制 + 连续对话模式 + 智能节奏控制
package com.gameai.ai

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gameai.model.ProviderConfig
import com.gameai.service.VoiceService
import com.gameai.utils.PreferencesManager
import kotlinx.coroutines.*

/**
 * 实时语音对话引擎 — 仿豆包电话体验
 *
 * 核心改进（相比传统的 ASR→LLM→TTS 流水线）：
 *
 * 1. SSE 流式 LLM：模型逐 token 生成时实时回调，用户"看到"回复的速度 = 第一个 token 的速度（~500ms）
 *    而不是等完整回复（~3-5s），消除"等待感"。
 *
 * 2. 分句 TTS：检测到句号/感叹号/问号时立即将该句子送入 TTS 播报，后续句子继续流式生成。
 *    用户听到第一句话的时间 ≈ 第一个句子生成完毕（~1.5s），而不是等所有字生成完。
 *
 * 3. 打断 (Barge-in)：用户在 AI 播报时说话 → 立即取消 SSE 流 + 停止 TTS + 开始新的聆听。
 *    豆包电话的核心体验之一：想打断就能打断。
 *
 * 流程：
 *   LISTENING → 用户说完 → STT 转文字
 *   PROCESSING → SSE 流式 LLM 逐 token 回调 → 分句发送 TTS
 *   SPEAKING → TTS 播报中
 *   任何阶段检测到用户说话 → 打断 → LISTENING
 */
object VoiceConversationEngine {

    private const val TAG = "VoiceConversation"
    private const val IDLE_TIMEOUT_MS = 15000L

    // 状态
    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }
    @Volatile var state: State = State.IDLE
        private set

    data class ChatMessage(
        val role: String,
        val text: String,
        val isStreaming: Boolean = false,  // true = 流式进行中，文字还会更新
        val timestamp: Long = System.currentTimeMillis()
    )

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = null
    private var idleTimer: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isActive = false

    // 打断机制
    private var currentStreamingCall: CloudAiClient.StreamingCall? = null
    private var isBargingIn = false

    // 分句 TTS：记录已经发送过 TTS 的句子数，避免重复播报
    private var spokenSentenceCount = 0

    // ==== v3.0: 连续对话 + 指令控制 ====
    // 连续对话模式：AI 回复完成后自动开始聆听（默认 true）
    private var continuousConversation = true
    // 聆听暂停（用户说"暂停"后临时停止自动聆听）
    private var isListeningPaused = false
    // 当前正在处理的命令（防重复处理）
    private var currentCommand: VoiceCommandHandler.ParsedCommand? = null

    // 最新屏幕截图
    @Volatile var latestBitmap: Bitmap? = null

    // 状态持久化 key
    private const val PREFS_VOICE_STATE = "voice_conversation_state"
    private const val KEY_IS_ACTIVE = "is_active"

    // ============================================================
    //  广播常量
    // ============================================================
    const val ACTION_VOICE_STATE = "com.gameai.VOICE_STATE"
    const val ACTION_VOICE_MESSAGE = "com.gameai.VOICE_MESSAGE"
    const val ACTION_VOICE_RMS = "com.gameai.VOICE_RMS"
    const val ACTION_VOICE_READY = "com.gameai.VOICE_READY"
    const val ACTION_VOICE_SPEECH_BEGIN = "com.gameai.VOICE_SPEECH_BEGIN"
    const val ACTION_VOICE_SPEECH_END = "com.gameai.VOICE_SPEECH_END"
    const val ACTION_VOICE_STREAMING = "com.gameai.VOICE_STREAMING"  // 流式文字更新
    const val EXTRA_STATE = "voice_state"
    const val EXTRA_ROLE = "role"
    const val EXTRA_TEXT = "text"
    const val EXTRA_TIMESTAMP = "timestamp"
    const val EXTRA_RMS = "rms"
    const val EXTRA_STREAMING = "is_streaming"

    var onStateChanged: ((State) -> Unit)? = null
    var onMessageReceived: ((ChatMessage) -> Unit)? = null
    var onUserSpeech: ((String) -> Unit)? = null

    // ============================================================
    //  初始化
    // ============================================================
    fun init(context: Context) {
        appContext = context.applicationContext
        VoiceService.onSpeakDone = {
            if (isActive && state == State.SPEAKING) {
                val bargeInEnabled = appContext?.let {
                    PreferencesManager.getInstance(it).isBargeInEnabled()
                } ?: false
                if (bargeInEnabled) {
                    stopBargeInDetection()
                }

                // v3.0: 连续对话模式 — TTS 说完自动开始下一轮聆听
                if (continuousConversation && !isListeningPaused) {
                    transitionState(State.IDLE)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isActive && !isListeningPaused) startListening()
                    }, 150)
                } else if (isListeningPaused) {
                    transitionState(State.IDLE)
                    broadcastMessage("system", "聆听已暂停，说\"继续听\"恢复")
                } else {
                    startListening()
                }
            }
        }
        VoiceService.init(context)
        idleTimer = Handler(Looper.getMainLooper())
    }

    fun release() {
        stopListening()
        cancelStreamingCall()
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        appContext = null
        VoiceService.onSpeakDone = null
        // 释放最新的屏幕截图
        latestBitmap?.recycle()
        latestBitmap = null
    }

    // ============================================================
    //  启动/停止语音对话
    // ============================================================

    fun startConversation() {
        val ctx = appContext ?: return
        if (isActive) return

        val diagError = diagnosePrerequisites(ctx)
        if (diagError != null) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, "语音对话不可用: $diagError", Toast.LENGTH_LONG).show()
            }
            android.util.Log.e(TAG, "语音对话启动失败: $diagError")
            broadcastMessage("system", "\u26A0\uFE0F $diagError")
            return
        }

        isActive = true
        spokenSentenceCount = 0
        isBargingIn = false
        continuousConversation = true
        isListeningPaused = false
        currentCommand = null
        transitionState(State.IDLE)

        // 保存状态：语音对话已激活（用于后台恢复）
        saveConversationState(true)

        // 初始化指令处理器上下文
        VoiceCommandHandler.setConversationActive(true)

        // 初始化记忆系统
        MemoryManager.init(ctx)
        MemoryManager.startNewSession()

        // 初始化技能系统
        SkillManager.init(ctx)
        kotlinx.coroutines.GlobalScope.launch {
            try {
                SkillManager.importBuiltinSkills()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "导入内置技能失败", e)
            }
        }

        // 设置当前游戏上下文（同步技能系统）
        CloudAiClient.currentGameName = "王者荣耀"
        CloudAiClient.currentHeroName = ""

        startListening()
    }

    fun stopConversation() {
        android.util.Log.i(TAG, "stopConversation() — 彻底停止语音对话")
        isActive = false
        continuousConversation = false
        isListeningPaused = false

        // 1. 先停止 STT 录音（CloudSpeechRecognizer 内部会设置 isPermanentlyStopped=true）
        stopListening()

        // 2. 取消 SSE 流式请求
        cancelStreamingCall()

        // 3. 停止 TTS 并清空播报队列
        VoiceService.stopSpeaking()
        VoiceService.clearSpeakQueue()

        // 3.5 停止打断监听
        stopBargeInDetection()

        // 4. 重置状态
        spokenSentenceCount = 0
        currentCommand = null
        VoiceCommandHandler.setConversationActive(false)
        transitionState(State.IDLE)
        cancelIdleTimer()

        // 5. 完全停止语音服务（释放 WakeLock、停止前台服务）
        appContext?.let { VoiceService.stopVoiceConversation(it) }

        // 清除保存的状态
        saveConversationState(false)

        android.util.Log.i(TAG, "stopConversation() 完成 — 麦克风已永久释放")
    }

    fun isConversationActive(): Boolean = isActive

    /**
     * 检查是否有未完成的语音对话需要恢复
     * 用于服务重启或应用从后台恢复时
     */
    fun hasSavedConversation(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_VOICE_STATE, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_ACTIVE, false)
    }

    /** 保存语音对话状态（用于后台恢复） */
    private fun saveConversationState(active: Boolean) {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS_VOICE_STATE, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_ACTIVE, active).apply()
    }

    // ============================================================
    //  打断逻辑
    // ============================================================

    /**
     * 执行打断：取消 SSE 流 + 停止 TTS
     * 在检测到用户说话时调用（VAD speech_begin 事件）
     */
    private fun performBargeIn() {
        if (state != State.SPEAKING && state != State.PROCESSING) return
        android.util.Log.i(TAG, "执行打断 — 用户开始说话，取消当前 AI 回复")
        isBargingIn = true

        // 1. 取消 SSE 流式请求
        cancelStreamingCall()

        // 2. 停止 TTS 播报 + 清空队列
        VoiceService.stopSpeaking()
        VoiceService.clearSpeakQueue()

        // 3. 停止打断监听（即将切换到正常聆听模式）
        stopBargeInDetection()

        // 4. 重置分句计数
        spokenSentenceCount = 0

        // 5. 通知 UI 流式结束
        broadcastStreamingEnd()
    }

    private fun cancelStreamingCall() {
        currentStreamingCall?.cancel()
        currentStreamingCall = null
    }

    // ============================================================
    //  语音识别（纯云端 STT）
    // ============================================================

    private fun startListening() {
        val ctx = appContext ?: return
        if (!isActive) return

        transitionState(State.LISTENING)

        // STT 成功 → 先检查语音指令，再交给 AI 处理
        val sttOnResult: (String) -> Unit = sttOnResult@{ text ->
            VoiceService.stopListening()

            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                // 识别结果为空，静默重新监听
                if (isActive && state == State.LISTENING) {
                    startListening()
                }
                return@sttOnResult
            }

            // 一次识别结果作为一条完整的用户消息
            onUserSpeech?.invoke(trimmed)
            broadcastMessage("user", trimmed)
            onMessageReceived?.invoke(ChatMessage("user", trimmed))

            // v3.0: 尝试解析语音指令（优先级高于 AI 对话）
            val cmd = VoiceCommandHandler.tryParseCommand(trimmed)
            if (cmd != null) {
                handleVoiceCommand(cmd)
                // 指令已处理，不再走 AI 对话流程
            } else {
                // 如果之前在暂停聆听状态，用户说任何话都自动恢复
                if (isListeningPaused && !isCommandText(trimmed)) {
                    isListeningPaused = false
                    broadcastMessage("system", "聆听已恢复")
                }

                // 如果检测到打断，清掉之前的流式 AI 消息广播
                if (isBargingIn) {
                    broadcastStreamingEnd()
                    isBargingIn = false
                }

                transitionState(State.PROCESSING)
                processUserInputStreaming(trimmed)
            }
        }

        val sttOnError: (String) -> Unit = { error ->
            android.util.Log.w(TAG, "STT 错误: $error")
            VoiceService.stopListening()
            // 静默处理错误，不打扰用户，自动重试
            if (isActive && state == State.LISTENING) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isActive) startListening()
                }, 800)
            }
        }

        val sttOnSpeechBegin: () -> Unit = {
            // 如果 AI 正在说话，执行打断
            if (state == State.SPEAKING || state == State.PROCESSING) {
                performBargeIn()
            }
            appContext?.let { c ->
                LocalBroadcastManager.getInstance(c)
                    .sendBroadcast(Intent(ACTION_VOICE_SPEECH_BEGIN))
            }
        }

        // 从配置中提取 STT 模型的 API Key（SiliconFlow）
        val sttConfig = getSttConfig()
        val sttApiKey = sttConfig?.apiKey ?: ""

        VoiceService.startListening(
            context = ctx,
            apiKey = sttApiKey,
            onResult = sttOnResult,
            onError = sttOnError,
            onReady = {
                appContext?.let { c ->
                    LocalBroadcastManager.getInstance(c)
                        .sendBroadcast(Intent(ACTION_VOICE_READY))
                }
            },
            onRmsChanged = { rms ->
                appContext?.let { c ->
                    Intent(ACTION_VOICE_RMS).apply {
                        putExtra(EXTRA_RMS, rms)
                    }.also {
                        LocalBroadcastManager.getInstance(c).sendBroadcast(it)
                    }
                }
            },
            onSpeechBegin = sttOnSpeechBegin,
            onSpeechEnd = {
                appContext?.let { c ->
                    LocalBroadcastManager.getInstance(c)
                        .sendBroadcast(Intent(ACTION_VOICE_SPEECH_END))
                }
            },
            onSilence = {
                android.util.Log.d(TAG, "静默超时 (VAD)，开始识别")
                // VAD 静音结束 → CloudSpeechRecognizer 自动完成识别并回调 onResult
            }
        )
    }

    private fun stopListening() {
        VoiceService.stopListening()
    }

    /**
     * 豆包式打断监听：TTS 播报期间麦克风保持开启，
     * 仅运行 VAD 检测用户是否在说话（不调用 STT API）。
     * AEC 硬件回声消除负责过滤 TTS 音频不被误识别。
     *
     * 检测到用户说话 → performBargeIn() → 停止 TTS → 立即切换到正常聆听。
     */
    private fun startBargeInDetection() {
        VoiceService.startBargeInDetection {
            android.util.Log.i(TAG, "VAD 检测到打断语音")
            performBargeIn()
            // 打断后立即开始新一轮聆听（用户的打断语音本身会被捕获）
            Handler(Looper.getMainLooper()).postDelayed({
                if (isActive && !isListeningPaused) startListening()
            }, 30)  // 极短延迟，加快打断响应速度（录音器预热期会保护 AEC 稳定）
        }
    }

    private fun stopBargeInDetection() {
        VoiceService.stopBargeInDetection()
    }

    // ============================================================
    //  SSE 流式 AI 处理 + 分句 TTS
    // ============================================================

    private fun processUserInputStreaming(text: String) {
        val bitmap = latestBitmap
        val visionConfig = getVisionConfig()
        val conversationConfig = getConversationConfig()
        val isScreenCaptureRunning = com.gameai.service.ScreenCaptureService.isRunning
        val hasScreenshotReady = com.gameai.service.ScreenCaptureService.hasScreenshot

        if (conversationConfig == null) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, "未配置对话模型", Toast.LENGTH_LONG).show()
            }
            handleAssistantReplyFallback("请先在模型设置中配置对话模型")
            return
        }

        android.util.Log.d(TAG, "📸 截图状态: bitmap=${bitmap != null}, " +
                "录屏运行=$isScreenCaptureRunning, " +
                "截图就绪=$hasScreenshotReady, " +
                "视觉模型=${visionConfig != null}")

        when {
            // 情况1：有截图 + 有视觉模型 → 先OCR识别屏幕，再用对话模型回复
            bitmap != null && visionConfig != null -> {
                android.util.Log.d(TAG, "📸 检测到屏幕截图，先进行OCR识别")
                CoroutineScope(Dispatchers.IO).launch {
                    val ocrResult = CloudAiClient.ocrRecognize(bitmap, visionConfig)
                    if (ocrResult != null && ocrResult.isNotBlank()) {
                        android.util.Log.d(TAG, "📷 OCR识别成功，长度: ${ocrResult.length}")
                        broadcastScreenOcrResult(ocrResult)
                        val enhancedMessage = buildString {
                            append("【当前屏幕内容】\n")
                            append(ocrResult)
                            append("\n\n【用户语音】\n")
                            append(text)
                        }
                        startStreamingConversation(null, conversationConfig, enhancedMessage)
                    } else {
                        android.util.Log.w(TAG, "OCR识别失败，直接传图给对话模型")
                        startStreamingConversation(bitmap, conversationConfig, text)
                    }
                }
            }
            // 情况2：有截图 + 无视觉模型 → 直接把截图传给对话模型
            bitmap != null && visionConfig == null -> {
                android.util.Log.d(TAG, "📸 有截图但无专门视觉模型，直接传图给对话模型")
                startStreamingConversation(bitmap, conversationConfig, text)
            }
            // 情况3：录屏在运行但暂无截图（第一帧还没捕获）
            bitmap == null && isScreenCaptureRunning && !hasScreenshotReady -> {
                android.util.Log.w(TAG, "录屏正在运行但第一帧还未捕获，稍等后重试")
                handleAssistantReplyFallback("正在获取屏幕画面，请稍候再试")
            }
            // 情况4：录屏已就绪但截图暂时为null（可能正在处理中）
            bitmap == null && isScreenCaptureRunning && hasScreenshotReady -> {
                android.util.Log.d(TAG, "录屏已就绪，暂时无截图，进行正常对话")
                startStreamingConversation(null, conversationConfig, text)
            }
            // 情况5：无截图 + 录屏没运行 → 正常对话
            else -> {
                startStreamingConversation(null, conversationConfig, text)
            }
        }
    }

    /**
     * 广播屏幕OCR识别结果，供UI层展示
     */
    private fun broadcastScreenOcrResult(ocrText: String) {
        val ctx = appContext ?: return
        val intent = android.content.Intent(ACTION_VOICE_MESSAGE).apply {
            putExtra(EXTRA_ROLE, "screen_ocr")
            putExtra(EXTRA_TEXT, ocrText)
            putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }

    /**
     * 开始流式对话
     */
    private fun startStreamingConversation(
        bitmap: android.graphics.Bitmap?,
        config: com.gameai.model.ProviderConfig,
        message: String
    ) {
        spokenSentenceCount = 0
        val accTextRef = StringBuilder()

        currentStreamingCall = CloudAiClient.converseStreaming(
            bitmap = bitmap,
            config = config,
            userMessage = message,
            onToken = { accumulatedText ->
                accTextRef.clear()
                accTextRef.append(accumulatedText)

                // 广播流式文字更新（UI 实时打字效果）
                broadcastStreamingUpdate(accumulatedText, true)

                // 分句 TTS：检测到句子结束符，立即播报该句子
                val newSentences = extractNewSentences(accumulatedText)
                if (newSentences > spokenSentenceCount) {
                    // 第一句开始播报时，切换到 SPEAKING 状态（onSpeakDone 依赖此状态触发下一轮聆听）
                    if (spokenSentenceCount == 0) {
                        transitionState(State.SPEAKING)
                    }
                    val sentences = splitSentences(accumulatedText)
                    // 播报未播放的句子
                    for (i in spokenSentenceCount until newSentences) {
                        if (i < sentences.size) {
                            val s = sentences[i].trim()
                            if (s.isNotEmpty()) {
                                VoiceService.speak(appContext!!, s)
                            }
                        }
                    }
                    spokenSentenceCount = newSentences
                }
            },
            onComplete = { fullText ->
                android.util.Log.d(TAG, "SSE 流完成: ${fullText.take(50)}...")
                currentStreamingCall = null

                // 保存对话上下文到记忆系统
                MemoryManager.saveContext("last_user_msg", message.take(200))
                MemoryManager.saveContext("last_assistant_msg", fullText.take(200))

                // 流结束 → 处理最终结果
                if (isBargingIn) {
                    isBargingIn = false
                    return@converseStreaming
                }

                // 播报剩余未播放的句子
                val sentences = splitSentences(fullText)
                val remainingCount = sentences.size - spokenSentenceCount
                if (remainingCount > 0) {
                    // 确保状态为 SPEAKING，以便 onSpeakDone 能正确触发下一轮聆听
                    if (state != State.SPEAKING) {
                        transitionState(State.SPEAKING)
                    }
                    for (i in spokenSentenceCount until sentences.size) {
                        val s = sentences[i].trim()
                        if (s.isNotEmpty()) {
                            VoiceService.speak(appContext!!, s)
                        }
                    }
                }

                // 广播完整消息
                broadcastMessage("assistant", fullText)
                onMessageReceived?.invoke(ChatMessage("assistant", fullText))
                broadcastStreamingEnd()

                // TTS 播报完成后回到 LISTENING（由 VoiceService.onSpeakDone 触发）
                // 但如果没有任何句子被播报，直接进入 SPEAKING 然后回到 LISTENING
                if (spokenSentenceCount == 0 && sentences.isEmpty()) {
                    handleAssistantReplyFallback("（AI 未返回有效回复）")
                }
            },
            onError = { error ->
                android.util.Log.e(TAG, "SSE 错误: $error")
                currentStreamingCall = null
                broadcastStreamingEnd()

                if (!isBargingIn) {
                    handleAssistantReplyFallback("抱歉，处理出错了：$error")
                }
                isBargingIn = false
            }
        )
    }

    private fun handleAssistantReplyFallback(text: String) {
        broadcastMessage("assistant", text)
        onMessageReceived?.invoke(ChatMessage("assistant", text))
        transitionState(State.SPEAKING)
        val ctx = appContext ?: return
        VoiceService.speak(ctx, text)
    }

    // ============================================================
    //  v3.0: 语音指令处理
    // ============================================================

    /**
     * 处理识别到的语音指令：执行指令 → TTS 确认 → 恢复聆听
     */
    private fun handleVoiceCommand(cmd: VoiceCommandHandler.ParsedCommand) {
        currentCommand = cmd
        val ctx = appContext ?: return

        when (cmd.type) {
            VoiceCommandHandler.CommandType.END_CONVERSATION -> {
                // 播报确认后停止对话
                val ack = VoiceCommandHandler.executeCommand(cmd)
                broadcastMessage("system", ack)
                VoiceService.speak(ctx, VoiceCommandHandler.getCommandAck(cmd))
                // 等待 TTS 完成后退出
                Handler(Looper.getMainLooper()).postDelayed({
                    stopConversation()
                }, 1500)
                return
            }

            VoiceCommandHandler.CommandType.PAUSE_LISTENING -> {
                isListeningPaused = true
                val ack = VoiceCommandHandler.executeCommand(cmd)
                broadcastMessage("system", ack)
                transitionState(State.IDLE)
                VoiceService.speak(ctx, ack)
                // 暂停后不自动恢复聆听（等用户说"继续"）
                return
            }

            VoiceCommandHandler.CommandType.RESUME_LISTENING -> {
                isListeningPaused = false
                val ack = VoiceCommandHandler.executeCommand(cmd)
                broadcastMessage("system", ack)
                transitionState(State.IDLE)
                VoiceService.speak(ctx, ack)
                // TTS 完成后自动恢复聆听
                return
            }

            VoiceCommandHandler.CommandType.HELP -> {
                val helpText = VoiceCommandHandler.executeCommand(cmd)
                broadcastMessage("system", helpText)
                transitionState(State.IDLE)
                VoiceService.speak(ctx, "我可以帮你：开始分析、结束对局、暂停听、查看评分、查看出装等，需要我做什么？")
                // 说完后恢复聆听
                return
            }

            // 其余指令：执行 + 简短确认 + 自动恢复聆听
            else -> {
                val ack = VoiceCommandHandler.getCommandAck(cmd)
                broadcastMessage("system", ack + " — " + VoiceCommandHandler.executeCommand(cmd))
                VoiceCommandHandler.broadcastCommand(ctx, cmd)
                VoiceCommandHandler.executeCommand(cmd)
                transitionState(State.IDLE)
                // 简短确认后恢复聆听
                VoiceService.speak(ctx, ack)
                return
            }
        }
    }

    /** 判断一段文字是否是恢复聆听触发词（非指令但也该触发恢复） */
    private fun isCommandText(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.matches(Regex(".*(结束|停止|关闭|退出|暂停|别说了|安静|继续|恢复|帮助|开始分析|开始对局|切换英雄|查看|查询).*"))
    }

    // ============================================================
    //  分句工具
    // ============================================================

    /** 从累积文本中提取已完成的句子数 */
    private fun extractNewSentences(text: String): Int {
        var count = 0
        var lastEnd = 0
        for (i in text.indices) {
            val c = text[i]
            if (isSentenceEndChar(c)) {
                val seg = text.substring(lastEnd, i).trim()
                if (seg.isNotEmpty()) {
                    count++
                }
                lastEnd = i + 1
            }
        }
        return count
    }

    /** 判断是否为句子结束符 */
    private fun isSentenceEndChar(c: Char): Boolean {
        return when (c) {
            '。', '！', '？', '!', '?', '\n', '；', ';', '…', '—' -> true
            else -> false
        }
    }

    /**
     * 按句子切分文本
     * 支持中英文标点、省略号、分号等句子结束符
     * 每句保留结束标点，确保语义完整
     */
    private fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            sb.append(c)

            // 处理省略号等多字符结束符
            if (c == '…' && i + 1 < text.length && text[i + 1] == '…') {
                sb.append(text[i + 1])
                i++
            }

            if (isSentenceEndChar(c)) {
                val s = sb.toString().trim()
                if (s.isNotEmpty()) {
                    result.add(s)
                }
                sb.clear()
            }
            i++
        }
        val remaining = sb.toString().trim()
        if (remaining.isNotEmpty()) {
            result.add(remaining)
        }
        return result
    }

    /**
     * 智能分句：将长文本拆分为语义完整的句子
     * 优先按标点符号分割，其次按语义长度分割
     */
    private fun smartSplitSentences(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 先按标点分割
        val byPunctuation = splitSentences(trimmed)
        val result = mutableListOf<String>()

        for (sentence in byPunctuation) {
            // 对于超长句子（>50字），尝试按逗号、顿号进一步分割
            if (sentence.length > 50) {
                val subSentences = splitByComma(sentence)
                result.addAll(subSentences)
            } else {
                result.add(sentence)
            }
        }

        return result
    }

    /** 按逗号、顿号分割长句 */
    private fun splitByComma(text: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()

        for (c in text) {
            sb.append(c)

            // 在逗号、顿号处分割，但确保每段至少有15个字
            if ((c == '，' || c == ',' || c == '、') && sb.length >= 15) {
                val s = sb.toString().trim()
                if (s.isNotEmpty()) {
                    result.add(s)
                }
                sb.clear()
            }
        }

        val remaining = sb.toString().trim()
        if (remaining.isNotEmpty()) {
            result.add(remaining)
        }

        // 如果分割后最后一段太短（<5字），合并到前一段
        if (result.size >= 2 && result.last().length < 5) {
            val last = result.removeAt(result.size - 1)
            val prev = result.removeAt(result.size - 1)
            result.add(prev + last)
        }

        return result
    }

    // ============================================================
    //  广播工具
    // ============================================================

    private fun broadcastStreamingUpdate(text: String, isStreaming: Boolean) {
        val ctx = appContext ?: return
        val intent = Intent(ACTION_VOICE_STREAMING).apply {
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_STREAMING, isStreaming)
            putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }

    private fun broadcastStreamingEnd() {
        val ctx = appContext ?: return
        val intent = Intent(ACTION_VOICE_STREAMING).apply {
            putExtra(EXTRA_TEXT, "")
            putExtra(EXTRA_STREAMING, false)
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }

    private fun broadcastMessage(role: String, text: String) {
        val ctx = appContext ?: return
        val intent = Intent(ACTION_VOICE_MESSAGE).apply {
            putExtra(EXTRA_ROLE, role)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }

    // ============================================================
    //  状态管理
    // ============================================================

    private fun transitionState(newState: State) {
        state = newState
        onStateChanged?.invoke(newState)

        val ctx = appContext ?: return
        val intent = Intent(ACTION_VOICE_STATE).apply {
            putExtra(EXTRA_STATE, newState.name)
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)

        if (newState == State.IDLE && isActive) {
            resetIdleTimer()
        }

        // 进入 SPEAKING：
        // - 如果开启了打断功能 → 启动打断监听（麦克风保持开启，AEC 消除 TTS 回声）
        // - 如果未开启打断功能 → 暂停麦克风，防止 TTS 回声被误识别
        if (newState == State.SPEAKING) {
            isBargingIn = false
            val bargeInEnabled = appContext?.let {
                PreferencesManager.getInstance(it).isBargeInEnabled()
            } ?: false
            if (bargeInEnabled) {
                startBargeInDetection()
            } else {
                stopListening()
            }
        }
    }

    private fun resetIdleTimer() {
        cancelIdleTimer()
        idleTimer?.postDelayed({
            if (isActive && state == State.IDLE) {
                stopConversation()
            }
        }, IDLE_TIMEOUT_MS)
    }

    private fun cancelIdleTimer() {
        idleTimer?.removeCallbacksAndMessages(null)
    }

    // ============================================================
    //  启动前诊断
    // ============================================================

    private fun diagnosePrerequisites(context: Context): String? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return "缺少录音权限，请在设置中授权"
            }
        }

        val sttConfig = getSttConfig()
        if (sttConfig == null || sttConfig.apiKey.isBlank()) {
            return "未配置 STT 语音识别模型，请在\"模型设置\"中绑定一个用途为\"语音转文字(stt)\"的模型"
        }
        android.util.Log.i(TAG, "STT 模型: ${sttConfig.modelName} (${sttConfig.provider.displayName})")

        val convConfig = getConversationConfig()
        if (convConfig == null || convConfig.apiKey.isBlank()) {
            return "未配置对话模型，请在\"模型设置\"中配置 API Key"
        }
        android.util.Log.i(TAG, "对话模型: ${convConfig.modelName} (${convConfig.provider.displayName})")

        return null
    }

    // ============================================================
    //  配置（双模型分离）
    // ============================================================

    private fun getConversationConfig(): ProviderConfig? {
        val ctx = appContext ?: return null
        val prefs = PreferencesManager.getInstance(ctx)
        val config = prefs.getConversationModelConfig() ?: prefs.getCurrentProviderConfig()

        if (prefs.getModelMode() == com.gameai.model.ModelMode.CLOUD && config.apiKey.isBlank()) {
            return null
        }
        return config
    }

    /**
     * 获取视觉模型配置
     * 优先级：专门的 vision 模型 > analysis 模型 > all 模型 > 当前对话模型
     */
    private fun getVisionConfig(): ProviderConfig? {
        val ctx = appContext ?: return null
        val prefs = PreferencesManager.getInstance(ctx)
        val config = prefs.getVisionModelConfig() ?: prefs.getCurrentProviderConfig()

        if (prefs.getModelMode() == com.gameai.model.ModelMode.CLOUD && config.apiKey.isBlank()) {
            return null
        }
        return config
    }

    /**
     * 判断是否有可用的视觉模型配置
     * 用于决定是否在对话中自动带上屏幕截图
     * 支持跨所有已配置供应商搜索视觉模型
     */
    private fun hasVisionModel(): Boolean {
        val ctx = appContext ?: return false
        val prefs = PreferencesManager.getInstance(ctx)
        return prefs.getVisionModelConfig() != null
    }

    private fun getSttConfig(): ProviderConfig? {
        val ctx = appContext ?: return null
        val prefs = PreferencesManager.getInstance(ctx)
        val config = prefs.getSttModelConfig() ?: prefs.getCurrentProviderConfig()

        if (prefs.getModelMode() == com.gameai.model.ModelMode.CLOUD && config.apiKey.isBlank()) {
            return null
        }
        return config
    }
}
