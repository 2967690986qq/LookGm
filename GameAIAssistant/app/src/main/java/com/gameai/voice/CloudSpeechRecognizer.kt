// CloudSpeechRecognizer.kt — 云端语音识别（自建，不依赖 Google App）
// 使用 AudioRecord 录制 → Whisper API 转文字 → 回调结果
package com.gameai.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.gameai.model.ProviderConfig
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

class CloudSpeechRecognizer(
    private val context: Context,
    private val getProviderConfig: () -> ProviderConfig?,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onReady: () -> Unit = {},
    private val onSilence: () -> Unit = {},
    private val onRmsChanged: (Float) -> Unit = {},
    private val onSpeechBegin: () -> Unit = {},
    private val onSpeechEnd: () -> Unit = {}
) {
    companion object {
        private const val TAG = "CloudSpeechRec"
        private const val SAMPLE_RATE = 16000          // Whisper 推荐采样率
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE = 2048            // 每帧字节数
        private const val SILENCE_TIMEOUT_MS = 3000L    // 静音超时
        private const val SPEECH_START_THRESHOLD = 500.0 // 语音起始 RMS 阈值
        private const val SPEECH_END_THRESHOLD = 300.0   // 语音结束 RMS 阈值
        private const val SPEECH_END_CONSECUTIVE = 8     // 连续静音帧数达到此值判定语音结束
        private const val MAX_RECORD_DURATION_MS = 15000L // 最长录音时长
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var isSpeechActive = false
    private var accumulatedAudio = ByteArrayOutputStream()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var silenceTimer: Runnable? = null
    private var silenceFrameCount = 0
    private var speechFrameCount = 0
    private var rmsDbHelper = 1.0  // 用于平滑 RMS

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val recordingThread = Thread {
        processAudioLoop()
    }

    fun startListening() {
        // 检查录音权限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                onError("缺少录音权限")
                return
            }
        }

        stopListening() // 先清理

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = maxOf(minBufSize, BUFFER_SIZE * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                onError("麦克风初始化失败，请检查权限")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            isSpeechActive = false
            silenceFrameCount = 0
            speechFrameCount = 0
            accumulatedAudio.reset()

            mainHandler.post {
                onReady()
                Log.d(TAG, "云端语音识别已启动")
            }

            // 启动录音处理线程
            recordingThread.start()

            // 最长录音保护
            mainHandler.postDelayed({
                if (isRecording) {
                    Log.d(TAG, "达到最长录音时长，强制结束")
                    finishSpeech()
                }
            }, MAX_RECORD_DURATION_MS)

        } catch (e: SecurityException) {
            onError("录音权限被拒绝")
        } catch (e: Exception) {
            onError("麦克风错误: ${e.message}")
        }
    }

    private fun processAudioLoop() {
        val buffer = ByteArray(BUFFER_SIZE)

        while (isRecording && !Thread.currentThread().isInterrupted) {
            val bytesRead = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: -1

            if (bytesRead <= 0) {
                if (!isRecording) break
                continue
            }

            // 计算 RMS (均方根音量)
            val rms = calculateRms(buffer, bytesRead)

            // 平滑 RMS
            rmsDbHelper = rmsDbHelper * 0.7 + rms * 0.3

            // 通知音量变化
            val rmsDisplay = (rmsDbHelper / 1000.0).toFloat().coerceIn(0f, 15f)
            mainHandler.post { onRmsChanged(rmsDisplay) }

            // 保存音频数据
            synchronized(accumulatedAudio) {
                accumulatedAudio.write(buffer, 0, bytesRead)
            }

            // 简单 VAD
            if (!isSpeechActive) {
                // 等待语音开始
                if (rmsDbHelper > SPEECH_START_THRESHOLD) {
                    speechFrameCount++
                    if (speechFrameCount >= 3) {
                        isSpeechActive = true
                        silenceFrameCount = 0
                        mainHandler.post { onSpeechBegin() }
                        Log.d(TAG, "检测到语音开始")
                    }
                } else {
                    speechFrameCount = 0
                }
            } else {
                // 检测语音结束
                if (rmsDbHelper < SPEECH_END_THRESHOLD) {
                    silenceFrameCount++
                    if (silenceFrameCount >= SPEECH_END_CONSECUTIVE) {
                        Log.d(TAG, "检测到语音结束")
                        mainHandler.post { onSpeechEnd() }
                        finishSpeech()
                        return
                    }
                } else {
                    silenceFrameCount = 0
                }
            }
        }
    }

    private fun finishSpeech() {
        if (!isRecording) return
        isRecording = false

        mainHandler.removeCallbacksAndMessages(null)

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        // 获取录音数据
        val audioData: ByteArray
        synchronized(accumulatedAudio) {
            audioData = accumulatedAudio.toByteArray()
            accumulatedAudio.reset()
        }

        if (audioData.size < SAMPLE_RATE / 2) {  // < 0.5 秒
            Log.d(TAG, "录音太短 (${audioData.size} bytes), 忽略")
            restartListening()
            return
        }

        // 异步发送到 Whisper API
        scope.launch {
            try {
                val wavData = pcmToWav(audioData)
                val text = sendToWhisperApi(wavData)

                if (text.isNullOrBlank()) {
                    mainHandler.post {
                        Log.w(TAG, "未识别到文字")
                        restartListening()
                    }
                } else {
                    mainHandler.post {
                        Log.d(TAG, "识别结果: $text")
                        onResult(text.trim())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "云端识别失败", e)
                mainHandler.post {
                    onError("语音识别失败: ${e.message}")
                    restartListening()
                }
            }
        }
    }

    // ============ Whisper API 调用 ============

    private suspend fun sendToWhisperApi(wavData: ByteArray): String? {
        val config = getProviderConfig() ?: run {
            mainHandler.post { onError("未配置 AI 模型") }
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = normalizeApiUrl(config.baseUrl)
                val url = "$baseUrl/audio/transcriptions"

                val mediaType = "audio/wav".toMediaType()
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        "audio.wav",
                        wavData.toRequestBody(mediaType)
                    )
                    .addFormDataPart("model", "whisper-1")
                    .addFormDataPart("language", "zh")
                    .addFormDataPart("response_format", "json")
                    .build()

                var builder = Request.Builder()
                    .url(url)
                    .post(requestBody)

                if (config.apiKey.isNotEmpty()) {
                    builder = builder.addHeader("Authorization", "Bearer ${config.apiKey}")
                }

                val response = httpClient.newCall(builder.build()).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    // 解析 JSON: {"text": "..."}
                    val text = org.json.JSONObject(body).optString("text", "")
                    if (text.isBlank()) null else text
                } else {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "Whisper API error ${response.code}: $errorBody")

                    // 404/405 表示不支持 Whisper，用 Base64 + Chat Completions 降级
                    if (response.code == 404 || response.code == 405) {
                        Log.w(TAG, "Whisper API 不可用，尝试用 Base64 PCM + Chat 降级")
                        return@withContext transcribeViaChatCompletion(wavData, config)
                    }

                    null
                }
            } catch (e: IOException) {
                Log.e(TAG, "Whisper API call failed", e)
                null
            }
        }
    }

    /** 降级方案：将 PCM 数据 Base64 编码后发给多模态 Chat API（支持音频输入的模型） */
    private suspend fun transcribeViaChatCompletion(wavData: ByteArray, config: ProviderConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                val base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP)
                val url = "${normalizeApiUrl(config.baseUrl)}/chat/completions"

                val messages = org.json.JSONArray()
                messages.put(org.json.JSONObject().apply {
                    put("role", "system")
                    put("content", "你是一个语音转文字工具。请将用户提供的音频内容精确转录为中文文字，只输出转录的文字，不要添加任何解释或格式。")
                })
                messages.put(org.json.JSONObject().apply {
                    put("role", "user")
                    put("content", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", org.json.JSONObject().apply {
                                put("data", base64Audio)
                                put("format", "wav")
                            })
                        })
                    })
                })

                val body = org.json.JSONObject().apply {
                    put("model", config.modelName)
                    put("messages", messages)
                    put("max_tokens", 200)
                    put("temperature", 0.0)
                }.toString()

                val requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val respBody = response.body?.string() ?: ""
                    val json = org.json.JSONObject(respBody)
                    val choices = json.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val message = choices.getJSONObject(0).getJSONObject("message")
                        message.getString("content")?.trim()
                    } else null
                } else {
                    Log.e(TAG, "Chat fallback failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chat fallback error", e)
                null
            }
        }
    }

    // ============ 音频工具 ============

    /** PCM → WAV (添加 44 字节头) */
    private fun pcmToWav(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = SAMPLE_RATE * 2 // 16-bit mono

        return ByteArrayOutputStream(pcmData.size + 44).apply {
            val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray())
            buf.putInt(totalDataLen)
            buf.put("WAVE".toByteArray())
            buf.put("fmt ".toByteArray())
            buf.putInt(16)                // Subchunk1 size (PCM)
            buf.putShort(1)               // Audio format (PCM = 1)
            buf.putShort(1)               // Channels (mono)
            buf.putInt(SAMPLE_RATE)       // Sample rate
            buf.putInt(byteRate)          // Byte rate
            buf.putShort(2)               // Block align
            buf.putShort(16)              // Bits per sample
            buf.put("data".toByteArray())
            buf.putInt(pcmData.size)      // Data size
            write(buf.array())
            write(pcmData)
        }.toByteArray()
    }

    /** 计算 RMS 均方根音量 */
    private fun calculateRms(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length step 2) {
            // 将两个字节合并为 short (小端序)
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += (sample.toDouble() * sample.toDouble())
        }
        val samples = length / 2
        return if (samples > 0) Math.sqrt(sum / samples) else 0.0
    }

    private fun normalizeApiUrl(url: String): String {
        return url.trimEnd('/').let {
            if (it.endsWith("/v1")) it.removeSuffix("/v1") else it
        }.let { it + "/v1" }
    }

    // ============ 控制方法 ============

    private fun restartListening() {
        mainHandler.postDelayed({
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "重试时权限不足")
                    return@postDelayed
                }
            }
            startListening()
        }, 500)
    }

    fun stopListening() {
        isRecording = false
        mainHandler.removeCallbacksAndMessages(null)
        silenceTimer?.let { mainHandler.removeCallbacks(it) }
        silenceTimer = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        scope.coroutineContext.cancelChildren()
    }

    fun pauseListening() {
        isRecording = false
        try { audioRecord?.stop() } catch (_: Exception) {}
    }

    fun resumeListening() {
        if (!isRecording) startListening()
    }
}
