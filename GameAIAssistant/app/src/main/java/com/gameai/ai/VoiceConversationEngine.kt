// VoiceConversationEngine.kt - 语音对话引擎（豆包模式：边看屏幕边聊）
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
 * 语音对话引擎 — 像豆包一样边看手机屏幕边对话沟通
 *
 * 工作流程：
 * 1. 用户点击麦克风 → LISTENING（语音识别）
 * 2. 识别到文字 → PROCESSING（抓取屏幕 → AI 分析 → TTS 播报）
 * 3. AI 回复 → SPEAKING（TTS 朗读）
 * 4. 朗读完毕 → IDLE（等待下次触发）
 */
object VoiceConversationEngine {

    private const val TAG = "VoiceConversation"
    private const val IDLE_TIMEOUT_MS = 15000L   // 空闲 15 秒自动退出对话模式

    // 状态
    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }
    @Volatile var state: State = State.IDLE
        private set

    // 回调用消息体（不保留历史）
    data class ChatMessage(
        val role: String,
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = null
    private var idleTimer: Handler? = null
    private var isActive = false

    // 最新屏幕截图（由外部每帧更新）
    @Volatile var latestBitmap: Bitmap? = null

    // ============================================================
    //  广播常量
    // ============================================================
    const val ACTION_VOICE_STATE = "com.gameai.VOICE_STATE"
    const val ACTION_VOICE_MESSAGE = "com.gameai.VOICE_MESSAGE"
    const val ACTION_VOICE_RMS = "com.gameai.VOICE_RMS"
    const val ACTION_VOICE_READY = "com.gameai.VOICE_READY"
    const val ACTION_VOICE_SPEECH_BEGIN = "com.gameai.VOICE_SPEECH_BEGIN"
    const val ACTION_VOICE_SPEECH_END = "com.gameai.VOICE_SPEECH_END"
    const val EXTRA_STATE = "voice_state"
    const val EXTRA_ROLE = "role"
    const val EXTRA_TEXT = "text"
    const val EXTRA_TIMESTAMP = "timestamp"
    const val EXTRA_RMS = "rms"

    // ============================================================
    //  回调
    // ============================================================
    var onStateChanged: ((State) -> Unit)? = null
    var onMessageReceived: ((ChatMessage) -> Unit)? = null
    var onUserSpeech: ((String) -> Unit)? = null       // 用户说了什么（显示用）

    // ============================================================
    //  初始化
    // ============================================================
    fun init(context: Context) {
        appContext = context.applicationContext
        // 先设回调，再 init TTS（确保 listener 创建时就能捕获 onSpeakDone）
        VoiceService.onSpeakDone = {
            if (isActive && state == State.SPEAKING) {
                startListening()
            }
        }
        VoiceService.init(context)
        idleTimer = Handler(Looper.getMainLooper())
    }

    fun release() {
        stopListening()
        scope.cancel()
        // 重建 scope 以便后续复用
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        appContext = null
        VoiceService.onSpeakDone = null
    }

    // ============================================================
    //  启动/停止语音对话
    // ============================================================

    /** 启动语音对话模式 */
    fun startConversation() {
        val ctx = appContext ?: return
        if (isActive) return

        // ===== 启动前诊断 =====
        val diagError = diagnosePrerequisites(ctx)
        if (diagError != null) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, "语音对话不可用: $diagError", Toast.LENGTH_LONG).show()
            }
            android.util.Log.e(TAG, "语音对话启动失败: $diagError")
            broadcastMessage("system", "⚠️ $diagError")
            return
        }

        isActive = true
        transitionState(State.IDLE)

        // 自动开始聆听
        startListening()
    }

    /** 停止语音对话模式 */
    fun stopConversation() {
        isActive = false
        stopListening()
        transitionState(State.IDLE)
        cancelIdleTimer()
    }

    /** 是否处于对话模式 */
    fun isConversationActive(): Boolean = isActive

    /**
     * 启动前诊断：检查所有前置条件
     * @return null 表示一切正常，否则返回错误描述
     */
    private fun diagnosePrerequisites(context: Context): String? {
        // 1. 检查录音权限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return "缺少录音权限，请在设置中授权"
            }
        }
        // 2. 检查 AI 模型配置（云端识别依赖 API）
        val config = getModelConfig()
        if (config == null || config.apiKey.isBlank()) {
            return "未配置 AI 模型，请在\"模型设置\"中配置 API Key"
        }
        return null
    }

    // ============================================================
    //  语音识别
    // ============================================================

    private fun startListening() {
        val ctx = appContext ?: return
        if (!isActive) return

        transitionState(State.LISTENING)

        VoiceService.startListening(
            context = ctx,
            onResult = { text ->
                onUserSpeech?.invoke(text)
                broadcastMessage("user", text)
                onMessageReceived?.invoke(ChatMessage("user", text))

                // 停止聆听，进入处理
                VoiceService.stopListening()
                transitionState(State.PROCESSING)

                // 处理用户输入
                processUserInput(text)
            },
            onError = { error ->
                android.util.Log.w(TAG, "语音识别错误: $error")
                // 显示 Toast 让用户知道
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(ctx, "语音识别: $error", Toast.LENGTH_SHORT).show()
                }
                // 先停旧识别器，再重试
                VoiceService.stopListening()
                if (isActive && state == State.LISTENING) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isActive) startListening()
                    }, 1000)
                }
            },
            onReady = ready@{
                // 识别器就绪，通知UI
                val c = appContext ?: return@ready
                LocalBroadcastManager.getInstance(c)
                    .sendBroadcast(Intent(ACTION_VOICE_READY))
            },
            onRmsChanged = rmsChanged@{ rmsValue ->
                // 音量变化，广播给悬浮球
                val c = appContext ?: return@rmsChanged
                Intent(ACTION_VOICE_RMS).apply {
                    putExtra(EXTRA_RMS, rmsValue)
                }.also {
                    LocalBroadcastManager.getInstance(c).sendBroadcast(it)
                }
            },
            onSpeechBegin = speechBegin@{
                val c = appContext ?: return@speechBegin
                LocalBroadcastManager.getInstance(c)
                    .sendBroadcast(Intent(ACTION_VOICE_SPEECH_BEGIN))
            },
            onSpeechEnd = speechEnd@{
                val c = appContext ?: return@speechEnd
                LocalBroadcastManager.getInstance(c)
                    .sendBroadcast(Intent(ACTION_VOICE_SPEECH_END))
            },
            onSilence = {
                // 静默超时：重启监听，确保麦克风持续工作
                android.util.Log.d(TAG, "静默超时，重启监听")
                if (isActive) {
                    VoiceService.stopListening()
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isActive) startListening()
                    }, 200)
                }
            }
        )
    }

    private fun stopListening() {
        VoiceService.stopListening()
    }

    // ============================================================
    //  AI 处理（核心：截图 + 语音 → AI → TTS）
    // ============================================================

    private fun processUserInput(text: String) {
        scope.launch {
            try {
                // 获取当前屏幕截图
                val bitmap = latestBitmap
                val config = getModelConfig()

                if (config == null) {
                    val reply = "请先在设置中配置 AI 模型和 API Key"
                    appContext?.let {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(it, "未配置 AI 模型，请在设置中填写 API Key", Toast.LENGTH_LONG).show()
                        }
                    }
                    handleAssistantReply(reply)
                    return@launch
                }

                // 调用 AI 多轮对话
                val reply = CloudAiClient.converse(
                    bitmap = bitmap,
                    config = config,
                    userMessage = text
                )

                if (reply != null && reply.isNotBlank()) {
                    handleAssistantReply(reply)
                } else {
                    handleAssistantReply("抱歉，AI 暂时无法回应，请稍后再试")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程被取消（如调用 release）→ 恢复状态
                android.util.Log.w(TAG, "AI处理被取消，恢复状态")
                Handler(Looper.getMainLooper()).post {
                    if (state == State.PROCESSING) {
                        transitionState(State.IDLE)
                    }
                }
                throw e  // 重新抛出以正确传播取消
            } catch (e: Exception) {
                android.util.Log.e(TAG, "AI处理失败", e)
                handleAssistantReply("抱歉，处理出错了：${e.message}")
            }
        }
    }

    private fun handleAssistantReply(text: String) {
        broadcastMessage("assistant", text)
        onMessageReceived?.invoke(ChatMessage("assistant", text))

        // TTS 播报（完成回调由 VoiceService.onSpeakDone 处理）
        transitionState(State.SPEAKING)
        val ctx = appContext ?: return
        VoiceService.speak(ctx, text)
    }

    // ============================================================
    //  状态管理
    // ============================================================

    private fun transitionState(newState: State) {
        state = newState
        onStateChanged?.invoke(newState)

        // 广播状态变化
        val ctx = appContext ?: return
        val intent = Intent(ACTION_VOICE_STATE).apply {
            putExtra(EXTRA_STATE, newState.name)
        }
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)

        // 空闲计时器
        if (newState == State.IDLE && isActive) {
            resetIdleTimer()
        }
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
    //  配置
    // ============================================================

    private fun getModelConfig(): ProviderConfig? {
        val ctx = appContext ?: return null
        val prefs = PreferencesManager.getInstance(ctx)
        val config = prefs.getConversationModelConfig() ?: prefs.getCurrentProviderConfig()

        if (prefs.getModelMode() == com.gameai.model.ModelMode.CLOUD && config.apiKey.isBlank()) {
            return null
        }
        return config
    }
}
