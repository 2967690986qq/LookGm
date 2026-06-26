// VoiceService.kt - 语音服务 (TTS播报 + 语音识别接口)
// v2.0: 后台保活优化 — 前台服务 + WakeLock + START_STICKY + 状态自动恢复
package com.gameai.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.gameai.R
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class VoiceService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val CHANNEL_ID = "voice_service_channel"
        private const val NOTIFICATION_ID = 2002

        // ===== 服务动作 =====
        const val ACTION_START_VOICE = "com.gameai.START_VOICE"
        const val ACTION_STOP_VOICE = "com.gameai.STOP_VOICE"

        private var tts: TextToSpeech? = null
        private var isTtsReady = false
        private val speakQueue = ConcurrentLinkedQueue<QueuedSpeech>()
        private var isSpeaking = false
        private val handler = Handler(Looper.getMainLooper())
        private var ttsRate = 1.0f
        private var ttsPitch = 1.0f

        // WakeLock：保持 CPU 唤醒，确保后台录音不中断
        private var wakeLock: PowerManager.WakeLock? = null
        private var isWakeLockHeld = false

        // WiFi Lock：保持 WiFi 唤醒，确保语音识别网络请求不中断
        private var wifiLock: WifiManager.WifiLock? = null
        private var isWifiLockHeld = false

        // 语音对话激活标记（用于服务重启后恢复状态）
        @Volatile var isVoiceConversationActive = false
            private set

        // 播报完成回调（供 VoiceConversationEngine 使用）
        var onSpeakDone: (() -> Unit)? = null
            set(value) {
                field = value
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        field?.invoke()
                        processQueue()
                    }
                    @Deprecated("")
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        field?.invoke()
                        processQueue()
                    }
                })
            }

        fun init(context: Context) {
            if (tts == null) {
                tts = TextToSpeech(context) { status ->
                    isTtsReady = (status == TextToSpeech.SUCCESS)
                    if (isTtsReady) {
                        tts?.language = Locale.CHINESE
                        tts?.setSpeechRate(ttsRate)
                        tts?.setPitch(ttsPitch)
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                isSpeaking = false
                                onSpeakDone?.invoke()
                                processQueue()
                            }
                            @Deprecated("")
                            override fun onError(utteranceId: String?) {
                                isSpeaking = false
                                onSpeakDone?.invoke()
                                processQueue()
                            }
                        })
                        processQueue()
                    }
                }
            }
        }

        // ===== STT（语音识别 — 纯云端方案，不依赖系统引擎） =====
        var cloudRecognizer: com.gameai.voice.CloudSpeechRecognizer? = null
        private var sttCallback: ((String) -> Unit)? = null
        private var sttErrorCallback: ((String) -> Unit)? = null
        private var sttReadyCallback: (() -> Unit)? = null
        private var sttRmsCallback: ((Float) -> Unit)? = null
        private var sttSpeechBeginCallback: (() -> Unit)? = null
        private var sttSpeechEndCallback: (() -> Unit)? = null

        fun startListening(
            context: Context,
            apiKey: String? = null,
            onResult: (String) -> Unit,
            onError: (String) -> Unit,
            onReady: (() -> Unit)? = null,
            onRmsChanged: ((Float) -> Unit)? = null,
            onSpeechBegin: (() -> Unit)? = null,
            onSpeechEnd: (() -> Unit)? = null,
            onSilence: (() -> Unit)? = null
        ) {
            // 确保服务以前台服务方式运行（后台录音必须前台服务）
            ensureServiceRunning(context)
            // 持有 WakeLock，防止 CPU 休眠导致录音中断
            acquireWakeLock(context)
            // 持有 WiFi Lock，防止 WiFi 休眠导致识别失败
            acquireWifiLock(context)
            isVoiceConversationActive = true

            sttCallback = onResult
            sttErrorCallback = onError
            sttReadyCallback = onReady
            sttRmsCallback = onRmsChanged
            sttSpeechBeginCallback = onSpeechBegin
            sttSpeechEndCallback = onSpeechEnd
            stopRecognizer()
            val keyProvider: () -> String = {
                apiKey ?: run {
                    val prefs = com.gameai.utils.PreferencesManager.getInstance(context)
                    val sttCfg = prefs.getSttModelConfig() ?: prefs.getCurrentProviderConfig()
                    sttCfg?.apiKey ?: ""
                }
            }
            cloudRecognizer = com.gameai.voice.CloudSpeechRecognizer(
                context = context.applicationContext,
                apiKeyProvider = keyProvider,
                onResult = { text -> sttCallback?.invoke(text) },
                onError = { error -> sttErrorCallback?.invoke(error) },
                onReady = { sttReadyCallback?.invoke() },
                onRmsChanged = { rms -> sttRmsCallback?.invoke(rms) },
                onSpeechBegin = { sttSpeechBeginCallback?.invoke() },
                onSpeechEnd = { sttSpeechEndCallback?.invoke() },
                onSilence = { onSilence?.invoke() }
            )
            cloudRecognizer?.startListening()
        }

        /** 确保服务以前台服务方式运行 */
        private fun ensureServiceRunning(context: Context) {
            val intent = Intent(context, VoiceService::class.java).apply {
                action = ACTION_START_VOICE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.w("VoiceService", "启动前台服务失败: ${e.message}")
            }
        }

        /** 获取 WakeLock：保持 CPU 唤醒 */
        private fun acquireWakeLock(context: Context) {
            if (isWakeLockHeld) return
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LookGm:VoiceWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(10 * 60 * 1000L) // 最长持有 10 分钟（安全起见）
                }
                isWakeLockHeld = true
                android.util.Log.d("VoiceService", "✓ WakeLock 已获取")
            } catch (e: Exception) {
                android.util.Log.w("VoiceService", "获取 WakeLock 失败: ${e.message}")
            }
        }

        /** 释放 WakeLock */
        private fun releaseWakeLock() {
            if (!isWakeLockHeld) return
            try {
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
                wakeLock = null
                isWakeLockHeld = false
                android.util.Log.d("VoiceService", "✓ WakeLock 已释放")
            } catch (e: Exception) {
                android.util.Log.w("VoiceService", "释放 WakeLock 失败: ${e.message}")
            }
        }

        /** 获取 WiFi Lock：保持 WiFi 唤醒 */
        private fun acquireWifiLock(context: Context) {
            if (isWifiLockHeld) return
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "LookGm:VoiceWifiLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                isWifiLockHeld = true
                android.util.Log.d("VoiceService", "✓ WiFi Lock 已获取")
            } catch (e: Exception) {
                android.util.Log.w("VoiceService", "获取 WiFi Lock 失败: ${e.message}")
            }
        }

        /** 释放 WiFi Lock */
        private fun releaseWifiLock() {
            if (!isWifiLockHeld) return
            try {
                wifiLock?.let {
                    if (it.isHeld) it.release()
                }
                wifiLock = null
                isWifiLockHeld = false
                android.util.Log.d("VoiceService", "✓ WiFi Lock 已释放")
            } catch (e: Exception) {
                android.util.Log.w("VoiceService", "释放 WiFi Lock 失败: ${e.message}")
            }
        }

        private fun stopRecognizer() {
            cloudRecognizer?.stopListening()
            cloudRecognizer = null
        }

        fun stopListening() {
            stopRecognizer()
            sttCallback = null
            // 注意：不立即释放 WakeLock 和停止前台服务，
            // 因为 TTS 播报可能还在进行，由 stopVoiceConversation 统一管理
        }

        /** 完全停止语音对话，释放所有资源 */
        fun stopVoiceConversation(context: Context) {
            isVoiceConversationActive = false
            stopRecognizer()
            stopSpeaking()
            clearSpeakQueue()
            sttCallback = null

            // 释放 WakeLock
            releaseWakeLock()
            // 释放 WiFi Lock
            releaseWifiLock()

            // 停止前台服务
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.stopService(Intent(context, VoiceService::class.java))
                } else {
                    context.stopService(Intent(context, VoiceService::class.java))
                }
            } catch (e: Exception) {
                android.util.Log.w("VoiceService", "停止服务失败: ${e.message}")
            }
        }

        /** 豆包式打断监听：TTS 播报时保持麦克风开启，仅做 VAD 检测用户打断 */
        fun startBargeInDetection(onBargeIn: () -> Unit) {
            // 确保 WakeLock 持有（打断监听期间也需要 CPU 运行）
            cloudRecognizer?.let { recognizer ->
                // 如果已有 recognizer，直接切换到 monitor 模式
                recognizer.startMonitorMode(onBargeIn)
            }
        }

        /** 停止打断监听 */
        fun stopBargeInDetection() {
            cloudRecognizer?.stopMonitorMode()
        }

        fun isListening(): Boolean = cloudRecognizer != null

        fun speak(context: Context, text: String, priority: Int = 0) {
            if (tts == null) init(context)
            speakQueue.offer(QueuedSpeech(text, priority))
            if (isTtsReady && !isSpeaking) processQueue()
        }

        /** 立即停止当前播报并清空队列（打断用） */
        fun stopSpeaking() {
            tts?.stop()
            isSpeaking = false
            speakQueue.clear()
        }

        /** 清空TTS队列（取消未播报内容） */
        fun clearSpeakQueue() {
            speakQueue.clear()
        }

        /** 当前是否正在播报 */
        fun isSpeaking(): Boolean = isSpeaking

        fun speakScore(context: Context, score: Int, grade: String) {
            val text = when {
                grade == "S" -> "当前评分$grade，${score}分，完美表现！"
                grade == "A" -> "当前评分$grade，${score}分，表现优秀"
                grade == "B" -> "当前评分$grade，${score}分，稳定发挥"
                grade == "C" -> "当前评分$grade，${score}分，需要加油"
                grade == "D" -> "当前评分$grade，${score}分，状态不佳"
                else -> "当前评分$grade，${score}分"
            }
            speak(context, text, 0)
        }

        fun speakAlert(context: Context, text: String) {
            speak(context, text, 10) // 高优先级
        }

        private fun processQueue() {
            if (isSpeaking || speakQueue.isEmpty()) return
            val queued = speakQueue.poll() ?: return
            isSpeaking = true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.speak(queued.text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
            } else {
                @Suppress("DEPRECATED")
                tts?.speak(queued.text, TextToSpeech.QUEUE_FLUSH, null)
            }
        }

        fun setRate(rate: Float) {
            ttsRate = rate
            tts?.setSpeechRate(rate)
        }

        fun setPitch(pitch: Float) {
            ttsPitch = pitch
            tts?.setPitch(pitch)
        }

        fun shutdown() {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsReady = false
            speakQueue.clear()
        }

        private data class QueuedSpeech(
            val text: String,
            val priority: Int
        )
    }

    // ============================================================
    //  Service 生命周期
    // ============================================================

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("VoiceService", "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START_VOICE -> {
                init(this)
                startForegroundNotification("语音对话中", "小吉正在聆听您的声音")
                isVoiceConversationActive = true
            }
            ACTION_STOP_VOICE -> {
                isVoiceConversationActive = false
                releaseWakeLock()
                releaseWifiLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                init(this)
                startForegroundNotification()
            }
        }

        // START_STICKY：服务被系统杀死后自动重启
        return START_STICKY
    }

    override fun onInit(status: Int) {
        isTtsReady = (status == TextToSpeech.SUCCESS)
        if (isTtsReady) {
            tts?.language = Locale.CHINESE
            tts?.setSpeechRate(ttsRate)
            tts?.setPitch(ttsPitch)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        android.util.Log.d("VoiceService", "onDestroy() — 释放资源")
        releaseWakeLock()
        releaseWifiLock()
        shutdown()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        android.util.Log.d("VoiceService", "onTaskRemoved — 用户滑掉了任务")
        // 如果语音对话正在进行，不停止服务（保持后台运行）
        if (!isVoiceConversationActive) {
            stopSelf()
        }
    }

    // ========== 前台通知 ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "语音播报与识别服务"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(
        title: String = "游戏AI助手",
        content: String = "语音服务就绪"
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }
}
