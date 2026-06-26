// ScreenCaptureService.kt - 录屏服务 (完整实现)
package com.gameai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gameai.R
import com.gameai.ai.ScreenAnalysisEngine
import com.gameai.common.constants.*
import com.gameai.engine.GameStateManager
import com.gameai.recognition.GameStateDetector
import com.gameai.recognition.GameTextRecognizer
import com.gameai.utils.FrameCompressor
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_capture_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.gameai.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_GAME_NAME = "game_name"
        const val EXTRA_WS_HOST = "ws_host"
        const val EXTRA_WS_PORT = "ws_port"

        // 游戏状态广播
        const val ACTION_GAME_STATE_CHANGED = "com.gameai.GAME_STATE_CHANGED"
        const val EXTRA_GAME_PHASE = "game_phase"
        const val EXTRA_GAME_HERO = "game_hero"
        const val EXTRA_GAME_POSITION = "game_position"

        // 实时评分广播（发送给悬浮窗）
        const val ACTION_SCORE_UPDATE = "com.gameai.SCORE_UPDATE"
        const val EXTRA_SCORE = "score"
        const val EXTRA_GRADE = "grade"

        var isRunning = false
            private set

        fun requestPermission(activity: androidx.fragment.app.FragmentActivity, requestCode: Int) {
            val manager = activity.getSystemService(MediaProjectionManager::class.java)
            activity.startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
        }
    }

    // 录屏相关
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // 游戏状态检测
    private var gameStateDetector: GameStateDetector? = null
    private var textRecognizer: GameTextRecognizer? = null
    private var lastDetectedPhase: GameConstants.MatchPhase = GameConstants.MatchPhase.LOBBY
    private var lastPhaseBroadcastTime: Long = 0L
    private val PHASE_BROADCAST_INTERVAL = 2000L  // 状态广播最少间隔 2 秒
    private var lastOcrTime: Long = 0L
    private val OCR_INTERVAL = 5000L  // OCR 识别间隔 5 秒

    // 帧采集
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var captureJob: Job? = null
    private var lastFrameBytes: ByteArray? = null
    private var frameCount = 0L
    private var lastFpsTime = 0L
    private var currentFps = 0
    private var captureFps = 10

    // 配置
    private var gameName = "王者荣耀"
    private var wsHost = "192.168.1.100"
    private var wsPort = 8765
    private var jpegQuality = 60

    // 评分缓存
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastScore: Int = 0
    private var lastGrade: String = "B"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        gameName = intent?.getStringExtra(EXTRA_GAME_NAME) ?: "王者荣耀"
        wsHost = intent?.getStringExtra(EXTRA_WS_HOST) ?: "192.168.1.100"
        wsPort = intent?.getIntExtra(EXTRA_WS_PORT, 8765) ?: 8765
        captureFps = intent?.getIntExtra("capture_fps", 10) ?: 10
        jpegQuality = intent?.getIntExtra("jpeg_quality", 60) ?: 60

        if (resultCode != -1 && data != null) {
            startScreenCapture(resultCode, data)
        }

        return START_STICKY
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            mediaProjection = manager.getMediaProjection(resultCode, data)

            val width = 720
            val height = 1280
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "GameAICapture",
                width, height, 240,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            // 初始化游戏状态检测器
            gameStateDetector = GameStateDetector()
            textRecognizer = GameTextRecognizer()
            ScreenAnalysisEngine.init(this)
            lastDetectedPhase = GameConstants.MatchPhase.LOBBY
            lastPhaseBroadcastTime = 0L
            lastOcrTime = 0L

            startNotification()
            startFrameCapture()
            isRunning = true

            // 连接WebSocket
            WebSocketService.startConnection(this, wsHost, wsPort, gameName)

        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun startFrameCapture() {
        val frameInterval = 1000L / captureFps
        captureJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    val image = imageReader?.acquireLatestImage()
                    if (image != null) {
                        val bitmap = rgbaImageToBitmap(image)
                        image.close()

                        if (bitmap != null) {
                            // 把最新帧喂给语音对话引擎（豆包模式：边看边聊）
                            // 注意：必须 copy 一份，因为当前 bitmap 在本帧结束后会被 recycle
                            val oldBitmap = com.gameai.ai.VoiceConversationEngine.latestBitmap
                            val newBitmap = bitmap.copy(bitmap.config, false)
                            com.gameai.ai.VoiceConversationEngine.latestBitmap = newBitmap
                            oldBitmap?.recycle()

                            val now = System.currentTimeMillis()

                            // === 游戏状态检测（每 2 秒一次）===
                            if (now - lastPhaseBroadcastTime >= 2000L && gameStateDetector != null) {
                                lastPhaseBroadcastTime = now
                                detectAndBroadcastState(bitmap)
                            }

                            // === OCR 文字识别（每 5 秒一次，仅在对局中）===
                            if (now - lastOcrTime >= OCR_INTERVAL && textRecognizer != null
                                    && lastDetectedPhase == GameConstants.MatchPhase.IN_GAME) {
                                lastOcrTime = now
                                // 复制 bitmap 供 OCR 异步使用，原图本轮结束后 recycle
                                val ocrBitmap = bitmap.copy(bitmap.config, false)
                                textRecognizer?.recognize(ocrBitmap) { result ->
                                    if (result.hasKda()) {
                                        GameStateManager.updateMatchKda(result.kills, result.deaths, result.assists)
                                    }
                                    if (result.hasEconomy()) {
                                        GameStateManager.updateMatchEconomy(result.economy)
                                    }
                                    if (result.hasGameTime()) {
                                        GameStateManager.updateMatchTime(result.gameTimeSec)
                                    }
                                    ocrBitmap.recycle()
                                }
                            }

                            // === 事件驱动 AI 分析（仅在对局中）===
                            if (lastDetectedPhase == GameConstants.MatchPhase.IN_GAME
                                    && gameStateDetector != null) {
                                val aiBitmap = bitmap.copy(bitmap.config, false)
                                ScreenAnalysisEngine.onFrameCaptured(aiBitmap, gameStateDetector!!)
                                aiBitmap.recycle()
                            }

                            val frameBytes = FrameCompressor.compressFrame(bitmap, jpegQuality)

                            // 差分检测 - 帧变化超过2%才发送
                            val shouldSend = lastFrameBytes == null ||
                                    FrameCompressor.frameDiffPercent(lastFrameBytes!!, frameBytes) > 0.02

                            if (shouldSend) {
                                lastFrameBytes = frameBytes
                                WebSocketService.sendFrame(frameBytes)
                                frameCount++
                            }

                            bitmap.recycle()
                        }

                        // 计算FPS
                        val now = System.currentTimeMillis()
                        if (now - lastFpsTime >= 1000) {
                            currentFps = ((frameCount * 1000) / (now - lastFpsTime)).toInt()
                            frameCount = 0
                            lastFpsTime = now
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ScreenCapture", "frame capture error (suppressed)", e)
                }
                delay(frameInterval)
            }
        }
    }

    private fun rgbaImageToBitmap(image: android.media.Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            } else bitmap
        } catch (e: Exception) {
            null
        }
    }

    // ============ 通知 ============

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "游戏AI助手",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "屏幕采集服务运行中"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startNotification() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("游戏AI助手运行中")
            .setContentText("正在分析 $gameName")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止", stopPendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopCapture() {
        isRunning = false
        captureJob?.cancel()
        scope.cancel()

        textRecognizer?.release()
        textRecognizer = null

        // 释放语音对话引擎中的bitmap引用
        com.gameai.ai.VoiceConversationEngine.latestBitmap?.recycle()
        com.gameai.ai.VoiceConversationEngine.latestBitmap = null

        ScreenAnalysisEngine.release()

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.stop()
        mediaProjection = null

        imageReader?.close()
        imageReader = null

        WebSocketService.stopConnection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ============ 游戏状态检测 + 评分广播 ============

    private fun detectAndBroadcastState(bitmap: Bitmap) {
        val phase = gameStateDetector?.detectPhase(bitmap) ?: return
        if (phase == lastDetectedPhase) return

        lastDetectedPhase = phase

        // 对局刚开始：触发 AI 阵容分析（由于已有 early return 确保 phase != 旧值）
        if (phase == GameConstants.MatchPhase.IN_GAME) {
            ScreenAnalysisEngine.triggerOnMatchStart(bitmap)
        }

        val intent = Intent(ACTION_GAME_STATE_CHANGED).apply {
            putExtra(EXTRA_GAME_PHASE, phase.name)
            putExtra(EXTRA_GAME_NAME, gameName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        WebSocketService.sendGameState(phase.name)
    }

    private fun broadcastScoreUpdate(score: Int, grade: String) {
        lastScore = score
        lastGrade = grade

        val intent = Intent(ACTION_SCORE_UPDATE).apply {
            putExtra(EXTRA_SCORE, score)
            putExtra(EXTRA_GRADE, grade)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
