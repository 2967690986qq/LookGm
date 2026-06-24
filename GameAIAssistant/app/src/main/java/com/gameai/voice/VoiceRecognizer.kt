// VoiceRecognizer.kt - 离线/在线语音识别模块
package com.gameai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class VoiceRecognizer(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onReady: () -> Unit = {},
    private val onSilence: () -> Unit = {},
    private val onRmsChanged: (Float) -> Unit = {},
    private val onSpeechBegin: () -> Unit = {},
    private val onSpeechEnd: () -> Unit = {}
) {
    companion object {
        private const val TAG = "VoiceRecognizer"
        private const val SILENCE_TIMEOUT_MS = 5000L
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var silenceTimer: android.os.Handler? = null
    private var accumulatedText = StringBuilder()

    fun startListening() {
        // 先销毁旧识别器，防止泄漏和 BUSY 错误
        if (speechRecognizer != null) {
            Log.w(TAG, "旧识别器未销毁，先清理")
            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
            try { speechRecognizer?.destroy() } catch (_: Exception) {}
            speechRecognizer = null
        }

        // 检查录音权限（Android 6.0+）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                onError("缺少录音权限")
                return
            }
        }

        if (SpeechRecognizer.isRecognitionAvailable(context).not()) {
            onError("语音识别不可用，请安装语音服务（需 Google App）")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "就绪，开始监听")
                isListening = true
                onReady()
                resetSilenceTimer()
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "检测到语音")
                cancelSilenceTimer()
                onSpeechBegin()
            }

            override fun onRmsChanged(rmsdB: Float) {
                // 音量变化 (rmsdB: 0~10+)
                onRmsChanged(rmsdB)
                if (rmsdB > 1.0f) {
                    resetSilenceTimer()
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "语音结束")
                onSpeechEnd()
                resetSilenceTimer()
            }

            override fun onError(error: Int) {
                isListening = false
                cancelSilenceTimer()

                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误，请检查网络"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                    SpeechRecognizer.ERROR_NO_MATCH -> "" // 未识别到语音，静默处理
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别引擎繁忙"
                    SpeechRecognizer.ERROR_SERVER -> "服务端错误"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时"
                    else -> "未知错误: $error"
                }

                if (msg.isNotEmpty()) {
                    Log.e(TAG, msg)
                    onError(msg)
                }

                // 自动重启监听（权限不足/网络错误不重试，避免死循环）
                if (error != SpeechRecognizer.ERROR_NO_MATCH
                    && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    && error != SpeechRecognizer.ERROR_NETWORK
                    && error != SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                    restartListening()
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                cancelSilenceTimer()

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val text = matches[0]
                    Log.d(TAG, "识别结果: $text")

                    if (text.isNotBlank()) {
                        onResult(text)
                    }
                }

                // 继续监听
                restartListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (partial != null && partial.isNotEmpty()) {
                    Log.d(TAG, "部分结果: ${partial[0]}")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        isListening = false
        cancelSilenceTimer()
        speechRecognizer?.apply {
            try {
                stopListening()
            } catch (e: Exception) { Log.w(TAG, "stopListening error", e) }
            try {
                destroy()
            } catch (e: Exception) { Log.w(TAG, "destroy error", e) }
        }
        speechRecognizer = null
    }

    fun pauseListening() {
        isListening = false
        cancelSilenceTimer()
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) { Log.w(TAG, "pauseListening error", e) }
    }

    fun resumeListening() {
        if (!isListening) {
            startListening()
        }
    }

    private fun restartListening() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // 重新检查权限
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "重试时权限仍不足，放弃")
                    return@postDelayed
                }
            }
            startListening()
        }, 300)
    }

    private fun resetSilenceTimer() {
        cancelSilenceTimer()
        silenceTimer = android.os.Handler(android.os.Looper.getMainLooper())
        silenceTimer?.postDelayed({
            Log.d(TAG, "静音超时")
            onSilence()
        }, SILENCE_TIMEOUT_MS)
    }

    private fun cancelSilenceTimer() {
        silenceTimer?.removeCallbacksAndMessages(null)
        silenceTimer = null
    }
}
