// CloudSpeechRecognizer.kt — SiliconFlow FunAudioLLM/SenseVoiceSmall 批量文件语音转文字
//
// 【模型说明】
//   TeleAI/TeleSpeechASR → 仅支持批量文件上传，不支持实时边录边出字 → 已废弃
//   FunAudioLLM/SenseVoiceSmall → 永久免费，替代上述模型，解决原模型无法实时流式的报错
//
// 【接口】
//   POST https://api.siliconflow.cn/v1/audio/transcriptions
//   multipart/form-data 上传完整 WAV 音频文件
//   注意1：该接口为批量文件转写 API，不支持实时边录边出字
//   注意2：该接口仅接受 file + model 两个参数，不支持 language/prompt/response_format/temperature
//
// 【核心流程（批量模式 — 最简单方案，杜绝报错）】
//   AudioRecord 16kHz/mono/16bit PCM 持续录制（内置 AEC 回声消除 + NS 降噪）
//   → 用户停止说话（VAD 检测 800ms 静音）或主动停止
//   → 完整 PCM 转 WAV（添加 44 字节 RIFF 头）
//   → 单次 HTTP POST 上传完整文件到 SiliconFlow
//   → 解析 response JSON 中 text 字段 → onResult 回调
//   → UI 展示完整转写文字
//
// 【拒绝流式分片，采用批量文件方案】
//   - 不用 WebSocket 流式推送：旧代码 TeleSpeechAsrClient.kt 已删除
//   - 不用 HTTP 短分片模拟流式：分片上下文断裂会导致识别不准
//   - 采用最可靠方案：完整录音 → 完整 WAV → 单次 POST → 完整文字
//   - 彻底杜绝流式转录常见报错（断句不完整/语调丢失/分片间丢字）
//
// 【该模型永久免费说明】
//   FunAudioLLM/SenseVoiceSmall 是硅基流动平台永久免费的语音识别模型，
//   支持中英混合、多语言、多方言、情感识别，无需付费即可使用。
//   原 TeleAI/TeleSpeechASR 报错根因：该模型仅支持批量文件转写，
//   不支持实时麦克风流式推流，之前代码尝试用 WebSocket/HTTP 短分片
//   模拟流式导致大量报错。

package com.gameai.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gameai.common.config.AppConfig
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
    private val apiKeyProvider: () -> String,
    // 注意：onPartialResult 仅保留以兼容旧调用方，批量模式下不会触发
    private val onPartialResult: (String) -> Unit = {},
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onReady: () -> Unit = {},
    private val onRmsChanged: (Float) -> Unit = {},
    private val onSpeechBegin: () -> Unit = {},
    private val onSpeechEnd: () -> Unit = {},
    private val onSilence: () -> Unit = {}
) {
    companion object {
        private const val TAG = "CloudSpeechRec"

        // ===== 音频硬件参数（SiliconFlow API 要求） =====
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2

        // 音频读取缓冲区大小
        private const val BUFFER_SIZE = 2048

        // ===== VAD 语音活动检测参数 =====
        // 用于自动检测用户是否说完话（静音 ~400ms 自动停止并识别）
        // VAD_CHECK_INTERVAL=2 帧 × 64ms ≈ 128ms 间隔，2 次静音 ≈ 256ms 触发结束
        private const val SPEECH_START_THRESHOLD = 600.0   // 提高阈值，避免扬声器回声误触发
        private const val SPEECH_END_THRESHOLD = 350.0     // 提高静音阈值，更敏感地检测停顿
        private const val SILENCE_CHUNKS_FOR_END = 2       // 连续 2 个静音帧 ≈ 256ms 后自动结束（加速响应）
        private const val VAD_CHECK_INTERVAL = 2           // 每 2 次音频读取做一次 VAD（128ms）
        private const val MAX_RECORD_DURATION_MS = 15000L  // 最长录音 15 秒（安全保护）
        private const val BARGE_IN_COOLDOWN_MS = 400L      // 进入监听模式后 400ms 内不触发打断（给 AEC 稳定时间）
        private const val NORMAL_WARM_UP_MS = 300L         // 正常录音前 300ms 不触发语音检测（AEC 缓冲清空）

        // 自适应噪声底噪估计
        private const val NOISE_FLOOR_WINDOW = 20
        private const val NOISE_FLOOR_MIN = 80.0           // 噪声底噪最低值（提高避免误触发）
        private const val ADAPTIVE_FACTOR = 2.2            // 自适应系数（提高，更严格判断语音）

        // ===== SiliconFlow API =====
        // POST https://api.siliconflow.cn/v1/audio/transcriptions (multipart/form-data)
        private const val SILICONFLOW_URL = "https://api.siliconflow.cn/v1/audio/transcriptions"

        // 模型名称：FunAudioLLM/SenseVoiceSmall
        // 永久免费，低延迟，支持中英混合 + 多方言 + 情感识别
        // 注意：非 TeleAI/TeleSpeechASR（该模型仅批量文件，不支持流式）
        private const val STT_MODEL = "FunAudioLLM/SenseVoiceSmall"
    }

    // ===== 音频硬件 =====
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    // ===== 状态标志 =====
    @Volatile private var isRecording = false
    @Volatile private var isSpeechActive = false
    private var recordingStartTime = 0L  // 录音开始时间戳，用于预热期判断

    // ===== 打断监听模式（豆包式交互） =====
    // 用于 TTS 播报期间保持麦克风开启，检测用户打断语音
    private var isMonitorMode = false          // 仅 VAD 监听，不累积音频/不调 STT API
    private var bargeInCallback: (() -> Unit)? = null
    private var monitorStartTime = 0L          // 进入监听模式时间戳，抑制初始误触发

    /**
     * 永久停止标志：设为 true 后，所有自动重启将被忽略。
     * 修复 "点击结束对话后麦克风继续运行" 的核心机制。
     */
    @Volatile var isPermanentlyStopped = false
        private set

    // ===== 音频数据 =====
    private val accumulatedAudio = ByteArrayOutputStream()   // 累积全部 PCM 数据
    private val audioLock = Any()                             // 线程安全锁

    // ===== 自适应 VAD =====
    private val recentNoiseLevels = mutableListOf<Double>()
    private var estimatedNoiseFloor = 100.0
    private var adaptiveSpeechStart = SPEECH_START_THRESHOLD.toDouble()
    private var adaptiveSpeechEnd = SPEECH_END_THRESHOLD.toDouble()
    private var speechFrameCount = 0
    private var silenceFrameCount = 0

    // ===== 线程管理 =====
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentThread: Thread? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ===== HTTP 客户端 =====
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)     // 完整音频识别可能需要更长时间
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ===== 防抖 =====
    private var failedSttAttempts = 0

    // ============================================================
    //  公共方法
    // ============================================================

    /**
     * 开始录音监听。
     * 录音权限在外部已检查。
     * 每次调用前会清理之前的状态。
     */
    fun startListening() {
        // 权限检查
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                onError("缺少录音权限，请在设置中授权麦克风权限")
                return
            }
        }

        stopListening()

        isPermanentlyStopped = false
        accumulatedAudio.reset()
        isSpeechActive = false
        speechFrameCount = 0
        silenceFrameCount = 0

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = maxOf(minBufSize, BUFFER_SIZE * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // 通信用音频源，AEC 回声消除路由更优
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                onError("麦克风初始化失败，请检查权限或重启应用")
                return
            }

            // 初始化硬件降噪
            initNoiseReduction()

            audioRecord?.startRecording()
            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            // 重置自适应 VAD
            recentNoiseLevels.clear()
            estimatedNoiseFloor = 100.0
            adaptiveSpeechStart = SPEECH_START_THRESHOLD.toDouble()
            adaptiveSpeechEnd = SPEECH_END_THRESHOLD.toDouble()

            mainHandler.post {
                onReady()
                Log.d(TAG, "语音识别已启动 → SiliconFlow $STT_MODEL (批量文件模式)")
            }

            // 启动录音线程
            currentThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                processAudioLoop()
            }.apply { start() }

            // 最长录音保护（15 秒后强制结束并识别）
            mainHandler.postDelayed({
                if (isRecording) {
                    Log.d(TAG, "达到最长录音时长 (${MAX_RECORD_DURATION_MS}ms)，自动停止并识别")
                    finishSpeechAndRecognize()
                }
            }, MAX_RECORD_DURATION_MS)

        } catch (e: SecurityException) {
            onError("录音权限被拒绝，请在系统设置中授予麦克风权限")
        } catch (e: Exception) {
            onError("麦克风错误: ${e.message}")
        }
    }

    /**
     * 停止录音并彻底阻止自动重启。
     * 调用此方法后，麦克风将完全释放，不会再自动恢复。
     */
    fun stopListening() {
        Log.d(TAG, "stopListening() — 永久停止，释放所有资源")
        isPermanentlyStopped = true
        isRecording = false

        // 取消所有延迟任务
        mainHandler.removeCallbacksAndMessages(null)

        // 中断录音线程
        currentThread?.interrupt()
        currentThread = null

        // 释放 AudioRecord
        releaseHardware()

        // 取消所有协程
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        Log.d(TAG, "stopListening() 完成 — AudioRecord 已释放，协程已取消")
    }

    /**
     * 豆包式打断监听模式：TTS 播报期间保持麦克风开启，
     * 仅运行 VAD 检测用户是否说话，不累积音频、不调用 STT API。
     * AEC（回声消除）负责过滤 TTS 播报声音，防止自识别。
     *
     * 检测到用户说话 → 回调 onBargeIn → 上层停止 TTS + 切换到正常聆听模式。
     */
    fun startMonitorMode(onBargeIn: () -> Unit) {
        // 权限检查
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "startMonitorMode: 无录音权限")
                return
            }
        }

        // 先停止当前录音（如果有）
        stopInternal()

        isPermanentlyStopped = false
        isMonitorMode = true
        bargeInCallback = onBargeIn
        monitorStartTime = System.currentTimeMillis()
        accumulatedAudio.reset()
        isSpeechActive = false
        speechFrameCount = 0
        silenceFrameCount = 0

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = maxOf(minBufSize, BUFFER_SIZE * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // 通信用音频源，AEC 回声消除路由更优
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                Log.w(TAG, "监听模式 AudioRecord 初始化失败")
                return
            }

            initNoiseReduction()  // AEC + NS 消除 TTS 回声

            audioRecord?.startRecording()
            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            // 重置自适应 VAD
            recentNoiseLevels.clear()
            estimatedNoiseFloor = 100.0
            adaptiveSpeechStart = SPEECH_START_THRESHOLD.toDouble()
            adaptiveSpeechEnd = SPEECH_END_THRESHOLD.toDouble()

            Log.d(TAG, "👂 打断监听模式已启动 (AEC=ON, 仅VAD)")

            currentThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                monitorAudioLoop()
            }.apply { start() }

        } catch (e: Exception) {
            Log.w(TAG, "监听模式启动失败: ${e.message}")
        }
    }

    /** 停止打断监听模式，释放 AudioRecord */
    fun stopMonitorMode() {
        Log.d(TAG, "停止打断监听模式")
        isMonitorMode = false
        bargeInCallback = null
        stopInternal()
    }

    /** 内部停止（不设置 isPermanentlyStopped） */
    private fun stopInternal() {
        isRecording = false
        mainHandler.removeCallbacksAndMessages(null)
        currentThread?.interrupt()
        currentThread = null
        releaseHardware()
    }

    /** 监听模式音频循环：只做 VAD，不累积音频，不调 API */
    private fun monitorAudioLoop() {
        val buffer = ByteArray(BUFFER_SIZE)
        var vadCheckCounter = 0

        while (isRecording && isMonitorMode && !Thread.currentThread().isInterrupted) {
            val bytesRead = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: -1

            if (bytesRead <= 0) {
                if (!isRecording) break
                continue
            }

            // 每 VAD_CHECK_INTERVAL 次读取做一次 VAD 检测
            vadCheckCounter++
            if (vadCheckCounter >= VAD_CHECK_INTERVAL) {
                vadCheckCounter = 0
                checkMonitorVad(buffer, bytesRead)
            }
        }
    }

    /** 监听模式 VAD：检测到用户说话 → 触发打断回调 */
    private fun checkMonitorVad(buffer: ByteArray, length: Int) {
        val rms = calculateRms(buffer, length)
        val rmsDisplay = (rms / 1000.0).toFloat().coerceIn(0f, 15f)
        mainHandler.post { onRmsChanged(rmsDisplay) }

        // 冷却期：进入监听模式后 BARGE_IN_COOLDOWN_MS 内不触发打断
        // 给 AEC 稳定时间，防止 TTS 刚开播的瞬态回声误触发
        val elapsed = System.currentTimeMillis() - monitorStartTime
        if (elapsed < BARGE_IN_COOLDOWN_MS) {
            // 冷却期内只采集噪声底噪
            recentNoiseLevels.add(rms)
            if (recentNoiseLevels.size > NOISE_FLOOR_WINDOW) {
                recentNoiseLevels.removeAt(0)
            }
            if (recentNoiseLevels.size >= NOISE_FLOOR_WINDOW) {
                val sorted = recentNoiseLevels.sorted()
                estimatedNoiseFloor = sorted[sorted.size / 2].coerceAtLeast(NOISE_FLOOR_MIN)
                adaptiveSpeechStart = (estimatedNoiseFloor * ADAPTIVE_FACTOR)
                    .coerceAtLeast(SPEECH_START_THRESHOLD)
            }
            return
        }

        // 持续更新噪声底噪
        if (!isSpeechActive) {
            recentNoiseLevels.add(rms)
            if (recentNoiseLevels.size > NOISE_FLOOR_WINDOW) {
                recentNoiseLevels.removeAt(0)
            }
            if (recentNoiseLevels.size >= NOISE_FLOOR_WINDOW) {
                val sorted = recentNoiseLevels.sorted()
                estimatedNoiseFloor = sorted[sorted.size / 2].coerceAtLeast(NOISE_FLOOR_MIN)
                adaptiveSpeechStart = (estimatedNoiseFloor * ADAPTIVE_FACTOR)
                    .coerceAtLeast(SPEECH_START_THRESHOLD)
            }
        }

        // 打断检测：检测到持续语音 → 用户正在说话
        if (rms > adaptiveSpeechStart) {
            speechFrameCount++
            if (speechFrameCount >= 5) {  // 连续 5 帧（≈ 960ms）确认，防止 AEC 漏过的短回声
                Log.d(TAG, "🔊 检测到用户打断 (RMS=${rms.toInt()}>阈值=${adaptiveSpeechStart.toInt()})")
                mainHandler.post {
                    bargeInCallback?.invoke()
                }
                // 打断触发后停止监听（上层会重建）
                isMonitorMode = false
                isRecording = false
            }
        } else {
            speechFrameCount = maxOf(speechFrameCount - 1, 0)
        }
    }

    /** @deprecated 豆包模式不再需要暂停麦克风，保留空实现兼容旧调用 */
    @Deprecated("使用 startMonitorMode/stopMonitorMode 替代", ReplaceWith("stopMonitorMode()"))
    fun pauseListening() {
        Log.d(TAG, "pauseListening() 已废弃，改用 stopMonitorMode()")
        stopMonitorMode()
    }

    fun resumeListening() {
        if (!isRecording) startListening()
    }

    // ============================================================
    //  音频处理循环（持续录制 PCM，VAD 检测语音活动）
    // ============================================================

    private fun processAudioLoop() {
        val buffer = ByteArray(BUFFER_SIZE)
        var vadCheckCounter = 0

        while (isRecording && !Thread.currentThread().isInterrupted) {
            val bytesRead = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: -1

            if (bytesRead <= 0) {
                if (!isRecording) break
                continue
            }

            // 累积音频数据
            synchronized(audioLock) {
                accumulatedAudio.write(buffer, 0, bytesRead)
            }

            // 每 VAD_CHECK_INTERVAL 次读取（≈ 192ms）做一次 VAD 检测
            vadCheckCounter++
            if (vadCheckCounter >= VAD_CHECK_INTERVAL) {
                vadCheckCounter = 0
                checkVad(buffer, bytesRead)
            }
        }
    }

    // ============================================================
    //  VAD 语音活动检测
    // ============================================================

    /**
     * 基于 RMS 的自适应 VAD：
     *   - 自适应估计环境噪声底噪
     *   - 检测语音开始、结束事件
     *   - 连续静音 ≈ 576ms → 触发 onSpeechEnd + onSilence → 自动开始识别
     *   - 录音前 NORMAL_WARM_UP_MS 内抑制检测（AEC 缓冲区清空）
     */
    private fun checkVad(buffer: ByteArray, length: Int) {
        val rms = calculateRms(buffer, length)
        val rmsDisplay = (rms / 1000.0).toFloat().coerceIn(0f, 15f)
        mainHandler.post { onRmsChanged(rmsDisplay) }

        // 录音预热期：前 NORMAL_WARM_UP_MS 内不触发语音检测
        // 让 AEC 缓冲区清空，避免扬声器残留回声被识别成用户语音
        val recordingElapsed = System.currentTimeMillis() - recordingStartTime
        if (recordingElapsed < NORMAL_WARM_UP_MS) {
            // 预热期内只采样噪声底噪，不触发语音开始
            recentNoiseLevels.add(rms)
            if (recentNoiseLevels.size > NOISE_FLOOR_WINDOW) {
                recentNoiseLevels.removeAt(0)
            }
            return
        }

        // 更新噪声底噪估计（仅在非语音期间采样）
        if (!isSpeechActive) {
            recentNoiseLevels.add(rms)
            if (recentNoiseLevels.size > NOISE_FLOOR_WINDOW) {
                recentNoiseLevels.removeAt(0)
            }
            if (recentNoiseLevels.size >= NOISE_FLOOR_WINDOW) {
                val sorted = recentNoiseLevels.sorted()
                estimatedNoiseFloor = sorted[sorted.size / 2].coerceAtLeast(NOISE_FLOOR_MIN)
                adaptiveSpeechStart = (estimatedNoiseFloor * ADAPTIVE_FACTOR)
                    .coerceAtLeast(SPEECH_START_THRESHOLD)
                adaptiveSpeechEnd = (estimatedNoiseFloor * 1.4)
                    .coerceAtLeast(SPEECH_END_THRESHOLD)
            }
        }

        if (!isSpeechActive) {
            // 检测语音开始
            if (rms > adaptiveSpeechStart) {
                speechFrameCount++
                if (speechFrameCount >= 3) {  // 连续 3 帧确认
                    isSpeechActive = true
                    silenceFrameCount = 0
                    mainHandler.post {
                        onSpeechBegin()
                        Log.d(TAG, "🎙 语音开始 (RMS=${rms.toInt()}>阈值=${adaptiveSpeechStart.toInt()})")
                    }
                }
            } else {
                speechFrameCount = maxOf(speechFrameCount - 1, 0)
            }
        } else {
            // 语音活跃中，检测静音
            if (rms < adaptiveSpeechEnd) {
                silenceFrameCount++
                if (silenceFrameCount >= SILENCE_CHUNKS_FOR_END) {
                    // 连续静音 ≈ 576ms → 用户说完，自动停止并识别
                    Log.d(TAG, "🔇 VAD 静音结束 (静音帧=$silenceFrameCount, ≈${silenceFrameCount * VAD_CHECK_INTERVAL * 64}ms)")
                    mainHandler.post {
                        onSpeechEnd()
                        onSilence()
                    }
                    finishSpeechAndRecognize()
                }
            } else {
                silenceFrameCount = 0  // 有声音，重置静音计数
            }
        }
    }

    // ============================================================
    //  语音结束 → 完整 PCM 转 WAV → 单次 SiliconFlow HTTP POST
    // ============================================================

    /**
     * 停止录音并提交完整音频识别。
     * 流程：
     *   1. 停止录音线程，取出全部缓存的 PCM 数据
     *   2. PCM → WAV（添加 RIFF WAV 文件头）
     *   3. 单次 POST /v1/audio/transcriptions（multipart/form-data 上传完整 WAV）
     *   4. 解析返回 JSON，提取 text 字段
     *   5. onResult 回调完整转写文字
     */
    private fun finishSpeechAndRecognize() {
        if (!isRecording) return
        isRecording = false

        // 停止录音线程
        currentThread?.interrupt()
        currentThread = null

        // 取消延迟任务
        mainHandler.removeCallbacksAndMessages(null)

        // 取出全部累积的 PCM 数据
        val pcmData: ByteArray
        synchronized(audioLock) {
            pcmData = accumulatedAudio.toByteArray()
            accumulatedAudio.reset()
        }

        // 停止 AudioRecord
        try { audioRecord?.stop() } catch (_: Exception) {}

        // 录音太短：丢弃
        if (pcmData.size < SAMPLE_RATE / 4) {  // < 0.25 秒
            Log.d(TAG, "录音太短 (${pcmData.size}B)，忽略")
            releaseHardware()
            if (!isPermanentlyStopped) {
                mainHandler.postDelayed({ if (!isPermanentlyStopped) startListening() }, 500)
            }
            return
        }

        // PCM → WAV
        val wavData = pcmToWav(pcmData)

        // 获取 API Key
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            Log.w(TAG, "API Key 未配置")
            releaseHardware()
            if (failedSttAttempts == 0) {
                mainHandler.post {
                    onError("未配置 SiliconFlow API Key。请在模型配置中绑定 API Key，用途选择 \"语音转文字(stt)\"")
                }
            }
            failedSttAttempts++
            return
        }

        // 异步上传识别（不阻塞主线程）
        scope.launch {
            try {
                Log.d(TAG, "发送完整音频 → SiliconFlow $STT_MODEL (${wavData.size}B WAV, ${pcmData.size / (SAMPLE_RATE * 2 * 2)}KHz)")
                val text = sendToSiliconFlow(wavData, apiKey)

                // 无论成功失败都释放硬件
                withContext(Dispatchers.Main) { releaseHardware() }

                if (text != null && text.isNotBlank()) {
                    val trimmed = text.trim()
                    failedSttAttempts = 0
                    withContext(Dispatchers.Main) {
                        Log.d(TAG, "✅ 识别完成: $trimmed")
                        onResult(trimmed)
                    }
                } else {
                    // 识别结果为空，用户可能只是清了清嗓子或环境太安静
                    // 不提示错误，静默继续监听（用户不说话时不应该打扰）
                    Log.d(TAG, "未识别到语音内容，静默继续监听")
                    // 静默重新开始监听
                    withContext(Dispatchers.Main) {
                        releaseHardware()  // 确保硬件已释放
                        if (!isPermanentlyStopped) {
                            mainHandler.postDelayed({
                                if (!isPermanentlyStopped) startListening()
                            }, 500)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "识别异常", e)
                withContext(Dispatchers.Main) {
                    releaseHardware()
                    onError("识别失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 发送完整 WAV 音频文件到 SiliconFlow API。
     *
     * 这是标准的批量文件转写调用：
     *   POST https://api.siliconflow.cn/v1/audio/transcriptions
     *   Content-Type: multipart/form-data
     *
     * 参数（SiliconFlow API 仅支持以下两个）：
     *   - file: 完整 WAV 音频文件（16000Hz, mono, 16bit PCM）
     *   - model: FunAudioLLM/SenseVoiceSmall
     *
     * 错误处理（分类处理所有常见错误）：
     *   - 401: API Key 无效或过期
     *   - 400: 音频格式/参数错误
     *   - 403: 权限不足
     *   - 404: 模型不存在
     *   - 429: 请求频率过高
     *   - 5xx: 服务器错误
     *   - IOException: 网络中断
     */
    private suspend fun sendToSiliconFlow(wavData: ByteArray, apiKey: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                // 构建 multipart/form-data 请求体
                val mediaType = "audio/wav".toMediaType()
                // SiliconFlow /v1/audio/transcriptions 仅接受 file + model 两个参数
                // language/prompt/response_format/temperature 均不支持，发送会导致 HTTP 400
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "recording.wav", wavData.toRequestBody(mediaType))
                    .addFormDataPart("model", STT_MODEL)
                    .build()

                val request = Request.Builder()
                    .url(SILICONFLOW_URL)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Accept", "application/json")
                    .build()

                Log.d(TAG, "→ POST $SILICONFLOW_URL (WAV=${wavData.size}B)")
                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    // 成功：解析 JSON 提取 text 字段
                    val body = response.body?.string() ?: ""
                    val text = try {
                        org.json.JSONObject(body).optString("text", "")
                    } catch (e: Exception) {
                        Log.w(TAG, "JSON 解析异常: ${e.message}, body=${body.take(100)}")
                        ""
                    }
                    response.close()
                    if (text.isBlank()) null else text
                } else {
                    val code = response.code
                    val errorBody = response.body?.string() ?: ""
                    response.close()
                    Log.w(TAG, "SiliconFlow HTTP $code: ${errorBody.take(300)}")

                    val errorDetail = try {
                        org.json.JSONObject(errorBody).optString("message", errorBody.take(100))
                    } catch (_: Exception) {
                        errorBody.take(100)
                    }

                    val errorMsg = when (code) {
                        400 -> {
                            if (errorDetail.contains("file", ignoreCase = true) ||
                                errorDetail.contains("audio", ignoreCase = true))
                                "音频格式错误：需要 16000Hz 单声道 16bit PCM WAV 格式。" +
                                "详情: $errorDetail"
                            else if (errorDetail.contains("model", ignoreCase = true))
                                "模型参数错误。当前模型: $STT_MODEL。" +
                                "详情: $errorDetail"
                            else
                                "请求参数错误 (HTTP 400): $errorDetail"
                        }
                        401 -> "API Key 无效或已过期 (HTTP 401)。请在模型配置中更新 SiliconFlow API Key"
                        403 -> "无权访问该资源 (HTTP 403)。请检查 API Key 权限是否包含语音识别"
                        404 -> "模型 $STT_MODEL 不存在或已下线 (HTTP 404)。请验证模型名称是否正确"
                        413 -> "音频文件过大 (HTTP 413)，请缩短录音时间"
                        429 -> "请求频率过高 (HTTP 429)，请稍后重试（每分钟限制次请求数）"
                        503 -> "SiliconFlow 服务暂时不可用 (HTTP 503)，请稍后重试"
                        in 500..599 -> "SiliconFlow 服务器内部错误 (HTTP $code)：请稍后重试"
                        else -> "语音识别失败 (HTTP $code): $errorDetail"
                    }
                    mainHandler.post { onError(errorMsg) }
                    null
                }
            } catch (e: IOException) {
                Log.e(TAG, "SiliconFlow 网络错误: ${e.message}")
                val netMsg = if (e.message?.contains("timeout", ignoreCase = true) == true)
                    "网络连接超时，请检查网络状态后重试"
                else if (e.message?.contains("Unable to resolve", ignoreCase = true) == true)
                    "无法解析域名，请检查网络连接"
                else
                    "网络连接失败: ${e.message}"

                mainHandler.post { onError(netMsg) }
                null
            }
        }
    }

    // ============================================================
    //  音频工具
    // ============================================================

    /**
     * PCM 原始数据 → WAV 文件（添加 44 字节 RIFF 头）。
     *
     * WAV 文件格式：
     *   RIFF 头 (12B) + fmt 子块 (24B) + data 子块 (8B + PCM数据)
     *   = 44 字节文件头 + PCM 数据
     */
    private fun pcmToWav(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = SAMPLE_RATE * BYTES_PER_SAMPLE * 1  // mono

        return ByteArrayOutputStream(pcmData.size + 44).apply {
            val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray())
            buf.putInt(totalDataLen)
            buf.put("WAVE".toByteArray())
            buf.put("fmt ".toByteArray())
            buf.putInt(16)                    // Sub-chunk size (PCM)
            buf.putShort(1)                   // Audio format (1 = PCM)
            buf.putShort(1)                   // Channels (1 = mono)
            buf.putInt(SAMPLE_RATE)           // Sample rate
            buf.putInt(byteRate)              // Byte rate
            buf.putShort(2)                   // Block align
            buf.putShort(16)                  // Bits per sample
            buf.put("data".toByteArray())
            buf.putInt(pcmData.size)          // Data chunk size
            write(buf.array())
            write(pcmData)
        }.toByteArray()
    }

    /** 计算音频帧的 RMS 值（用于 VAD 和音量显示） */
    private fun calculateRms(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        val samples = length / 2
        if (samples <= 0) return 0.0
        for (i in 0 until length step 2) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += (sample.toDouble() * sample.toDouble())
        }
        return Math.sqrt(sum / samples)
    }

    // ============================================================
    //  硬件降噪（AEC 回声消除 + NS 环境噪声抑制）
    // ============================================================

    /**
     * 初始化硬件级音频降噪：
     *  - AcousticEchoCanceler (AEC)：消除 TTS 播报被麦克风回采
     *  - NoiseSuppressor (NS)：抑制环境背景噪声
     *
     * 注意：部分低端设备或模拟器可能不支持，静默降级。
     */
    private fun initNoiseReduction() {
        val audioSessionId = audioRecord?.audioSessionId ?: return

        if (AppConfig.ASR_ENABLE_AEC) {
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(audioSessionId)
                    aec?.enabled = true
                    Log.d(TAG, "✓ AEC 回声消除已启用")
                }
            } catch (e: Exception) {
                Log.w(TAG, "AEC 不可用", e)
            }
        }

        if (AppConfig.ASR_ENABLE_NS) {
            try {
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(audioSessionId)
                    ns?.enabled = true
                    Log.d(TAG, "✓ NS 噪声抑制已启用")
                }
            } catch (e: Exception) {
                Log.w(TAG, "NS 不可用", e)
            }
        }
    }

    /** 释放音频降噪硬件 */
    private fun releaseNoiseReduction() {
        try { aec?.enabled = false; aec?.release() } catch (_: Exception) {}
        aec = null
        try { ns?.enabled = false; ns?.release() } catch (_: Exception) {}
        ns = null
    }

    /** 释放所有音频硬件（AudioRecord + 降噪） */
    private fun releaseHardware() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        releaseNoiseReduction()
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    // ============================================================
    //  控制方法
    // ============================================================

    /**
     * 自动重启录音监听（仅语音对话引擎内部使用）。
     * 如果 isPermanentlyStopped 为 true，阻止重启。
     * 这是修复麦克风泄漏 Bug 的关键机制。
     */
    private fun restartListening() {
        if (isPermanentlyStopped) {
            Log.d(TAG, "restartListening() 被阻止 — 已永久停止")
            return
        }
        mainHandler.postDelayed({
            if (isPermanentlyStopped) {
                Log.d(TAG, "restartListening() 延迟回调被阻止 — 已永久停止")
                return@postDelayed
            }
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
}
