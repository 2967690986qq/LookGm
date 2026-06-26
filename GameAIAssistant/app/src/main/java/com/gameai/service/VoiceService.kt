// VoiceService.kt - 语音服务 (TTS播报 + 语音识别接口)
package com.gameai.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

        private var tts: TextToSpeech? = null
        private var isTtsReady = false
        private val speakQueue = ConcurrentLinkedQueue<QueuedSpeech>()
        private var isSpeaking = false
        private val handler = Handler(Looper.getMainLooper())
        private var ttsRate = 1.0f
        private var ttsPitch = 1.0f

        // 播报完成回调（供 VoiceConversationEngine 使用）
        // 设置后自动重新注册 UtteranceProgressListener 确保回调生效
        var onSpeakDone: (() -> Unit)? = null
            set(value) {
                field = value
                // 如果 TTS 已初始化，重新注册 listener（因为旧 listener 捕获的是旧的 field 值）
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

        private fun stopRecognizer() {
            cloudRecognizer?.stopListening()
            cloudRecognizer = null
        }

        fun stopListening() {
            stopRecognizer()
            sttCallback = null
        }

        /** 暂停麦克风录音（不释放资源，TTS播报期间防止自识别） */
        fun pauseListening() {
            cloudRecognizer?.pauseListening()
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
                @Suppress("DEPRECATION")
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        init(this)
        startForegroundNotification()
        return START_NOT_STICKY
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
        shutdown()
        super.onDestroy()
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

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("游戏AI助手")
            .setContentText("语音服务就绪")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }
}
