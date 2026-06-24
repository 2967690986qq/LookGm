// FloatingWindowService.kt - 悬浮窗服务（边缘吸附 + 分路任务 + 横屏适配）
package com.gameai.service

import android.Manifest
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gameai.R
import com.gameai.ai.ScreenAnalysisEngine
import com.gameai.ai.VoiceConversationEngine
import com.gameai.utils.PreferencesManager
import org.json.JSONObject

class FloatingWindowService : Service() {

    companion object {
        const val CHANNEL_ID = "floating_window_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_UPDATE_SCORE = "com.gameai.action.UPDATE_SCORE"
        const val ACTION_UPDATE_LANE = "com.gameai.action.UPDATE_LANE"
        const val ACTION_UPDATE_ANALYSIS = "com.gameai.action.UPDATE_ANALYSIS"
        const val ACTION_HIDE = "com.gameai.action.HIDE"
        const val ACTION_SHOW = "com.gameai.action.SHOW"
        const val EXTRA_SCORE = "score"
        const val EXTRA_GRADE = "grade"
        const val EXTRA_FPS = "fps"
        const val EXTRA_LANE = "lane"
        const val EXTRA_HERO = "hero"
        const val EXTRA_ANALYSIS = "analysis"

        var isShowing = false
            private set
    }

    // === 窗口管理 ===
    private var windowManager: WindowManager? = null
    private var ballView: View? = null
    private var panelView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var isExpanded = false

    // === 球内控件 ===
    private var bgCircle: View? = null
    private var tvScore: TextView? = null
    private var tvGrade: TextView? = null
    private var layoutScoreMode: View? = null
    private var layoutVoiceMode: View? = null
    private var voicePulseDot: View? = null
    private var tvVoiceModeText: TextView? = null
    private var tvVoiceCurText: TextView? = null
    private var btnMic: TextView? = null

    // === 面板控件 ===
    private var tvLaneTitle: TextView? = null
    private var tvTaskTitle: TextView? = null
    private var tvHero: TextView? = null
    private var tvCurrentScore: TextView? = null
    private var layoutMatchInfo: View? = null
    private var layoutLaneTasks: View? = null
    private var layoutTaskList: LinearLayout? = null
    private var tvAiAnalysis: TextView? = null
    private var cardAiAnalysis: View? = null
    private val laneViews = mutableMapOf<String, TextView>()

    // === 拖拽状态 ===
    private var initX = 0
    private var initY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var isDragging = false
    private var draggingBall = false

    // === 当前数据 ===
    private var currentScore = 0
    private var currentGrade = "B"
    private var currentFps = 0
    private var currentLane = ""
    private var currentHero = ""
    private var currentAnalysis: String? = null

    // === 分路任务数据 ===
    data class TierTask(val tier: String, val color: Int, val tasks: List<String>)

    private val laneTaskMap: Map<String, List<TierTask>> by lazy {
        mapOf(
            // ============================================================
            //  对抗路（top）
            // ============================================================
            "top" to listOf(
                TierTask("铜牌", 0xFF9CA3AF.toInt(), listOf(
                    "评分超越 50% 同段位同英雄",
                    "坦边：承伤占比 > 20%",
                    "战边：输出占比 > 15%",
                    "参团率 > 40%",
                    "对局助攻 ≥ 6 次，无频繁送头"
                )),
                TierTask("银牌", 0xFF3B82F6.toInt(), listOf(
                    "评分超越 30% 同段位同英雄",
                    "坦边：承伤 > 25%",
                    "战边：输出 > 20%",
                    "参团率 > 50%",
                    "有效助攻 ≥ 8 次，具备对线压制"
                )),
                TierTask("金牌", 0xFFEF4444.toInt(), listOf(
                    "对局评分 ≥ 10 分",
                    "坦边：承伤 ≥ 30%，参团率 ≥ 60%",
                    "坦边：推塔 ≥ 4 座",
                    "战边：输出 ≥ 25%，单杀 ≥ 2 次",
                    "战边：经济领先对位 ≥ 1500"
                )),
                TierTask("顶级", 0xFFF59E0B.toInt(), listOf(
                    "数据排名：同段位同英雄前 1%",
                    "推塔 ≥ 2 座",
                    "坦边：承伤 > 30%",
                    "战边：输出 > 25%",
                    "参团率 > 60%，单杀 > 2 次"
                ))
            ),

            // ============================================================
            //  打野（jungle）
            // ============================================================
            "jungle" to listOf(
                TierTask("铜牌", 0xFF9CA3AF.toInt(), listOf(
                    "评分超越 50% 同段位同英雄",
                    "有效控龙 ≥ 2 条",
                    "参团率 ≥ 40%",
                    "击杀 + 助攻总次数 ≥ 10 次",
                    "节奏无严重断层"
                )),
                TierTask("银牌", 0xFF3B82F6.toInt(), listOf(
                    "评分超越 30% 同段位同英雄",
                    "队内经济排名第一",
                    "控龙 ≥ 2 条",
                    "击杀 + 助攻 ≥ 10 次",
                    "参团率 ≥ 40%，野区节奏稳定"
                )),
                TierTask("金牌", 0xFFEF4444.toInt(), listOf(
                    "对局评分 ≥ 11 分",
                    "经济占比 ≥ 20%",
                    "参团率 ≥ 50%",
                    "反野入侵 ≥ 3 次",
                    "控龙 ≥ 2 条，击杀+助攻 ≥ 15 次",
                    "死亡 ≤ 4 次，全局节奏掌控"
                )),
                TierTask("顶级", 0xFFF59E0B.toInt(), listOf(
                    "队内经济排名第一",
                    "有效反野 ≥ 3 次",
                    "击杀 + 助攻 > 15 次",
                    "控龙 > 2 条",
                    "参团率 ≥ 50%，全局节奏碾压"
                ))
            ),

            // ============================================================
            //  中路（mid）
            // ============================================================
            "mid" to listOf(
                TierTask("铜牌", 0xFF9CA3AF.toInt(), listOf(
                    "评分超越 50% 同段位同英雄",
                    "输出占比 > 20%",
                    "参团率 > 55%",
                    "有效助攻 ≥ 8 次",
                    "死亡次数可控，无多次无脑暴毙"
                )),
                TierTask("银牌", 0xFF3B82F6.toInt(), listOf(
                    "评分超越 30% 同段位同英雄",
                    "输出占比 > 25%",
                    "参团率 > 65%",
                    "击杀 + 助攻 ≥ 10 次",
                    "死亡次数少，持续输出与支援"
                )),
                TierTask("金牌", 0xFFEF4444.toInt(), listOf(
                    "对局评分 ≥ 11 分",
                    "输出占比 ≥ 30%",
                    "有效支援 ≥ 5 次",
                    "推塔 ≥ 3 座",
                    "参团率 ≥ 75%",
                    "死亡 ≤ 3 次，少失误多支援"
                )),
                TierTask("顶级", 0xFFF59E0B.toInt(), listOf(
                    "数据排名：同段位同英雄前 1%",
                    "输出占比 > 30%",
                    "死亡 ≤ 2 次",
                    "参团率 ≥ 75%",
                    "击杀 + 助攻 ≥ 15 次，零失误"
                ))
            ),

            // ============================================================
            //  发育路（adc）
            // ============================================================
            "adc" to listOf(
                TierTask("铜牌", 0xFF9CA3AF.toInt(), listOf(
                    "评分超越 50% 同段位同英雄",
                    "输出占比 > 22%",
                    "经济排名：队内前三及以上",
                    "单局死亡次数 < 4 次",
                    "保证基础发育与生存"
                )),
                TierTask("银牌", 0xFF3B82F6.toInt(), listOf(
                    "评分超越 30% 同段位同英雄",
                    "队内经济前二",
                    "推塔 ≥ 2 座",
                    "输出占比 > 28%",
                    "单局死亡 < 3 次，容错率低"
                )),
                TierTask("金牌", 0xFFEF4444.toInt(), listOf(
                    "对局评分 ≥ 12 分",
                    "输出占比 ≥ 35%",
                    "队内经济前二",
                    "推塔 ≥ 5 座",
                    "死亡 ≤ 3 次",
                    "对局击杀率 ≥ 40%，稳定 carry"
                )),
                TierTask("顶级", 0xFFF59E0B.toInt(), listOf(
                    "数据排名：同段位同英雄前 1%",
                    "队内经济稳居第一",
                    "输出占比 > 35%",
                    "死亡 ≤ 2 次",
                    "推塔 ≥ 3 座，极低失误"
                ))
            ),

            // ============================================================
            //  游走（support）
            // ============================================================
            "support" to listOf(
                TierTask("铜牌", 0xFF9CA3AF.toInt(), listOf(
                    "评分超越 50% 同段位同英雄",
                    "参团率 > 60%",
                    "软辅：治疗占比 > 10%",
                    "硬辅：承伤占比 > 10%",
                    "视野获取价值 > 15%，助攻充足"
                )),
                TierTask("银牌", 0xFF3B82F6.toInt(), listOf(
                    "评分超越 30% 同段位同英雄",
                    "参团率 > 70%",
                    "视野价值 > 25%",
                    "有效控制时长 > 15 秒",
                    "软辅：治疗 > 15%，硬辅：承伤 > 15%",
                    "死亡 < 4 次"
                )),
                TierTask("金牌", 0xFFEF4444.toInt(), listOf(
                    "对局评分 ≥ 10 分",
                    "参团率 ≥ 80%",
                    "软辅：治疗量占比 ≥ 20%",
                    "硬辅：承伤占比 ≥ 20%",
                    "视野价值 ≥ 35%",
                    "有效控制时长 ≥ 25 秒，团队贡献拉满"
                )),
                TierTask("顶级", 0xFFF59E0B.toInt(), listOf(
                    "参团率 > 80%",
                    "单局死亡 ≤ 3 次",
                    "软辅：治疗 > 20%，硬辅：承伤 > 25%",
                    "视野价值 > 35%",
                    "控制时长 > 25 秒，全方位团队支撑"
                ))
            )
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: PreferencesManager

    // === 评分广播接收器（来自 GameStateManager）===
    private var scoreReceiver: BroadcastReceiver? = null

    // === 吸附动画 ===
    private var snapAnimator: ValueAnimator? = null

    // === 语音脉冲动画 ===
    private var pulseAnimator: ValueAnimator? = null
    @Volatile private var currentRms = 0f

    // === 语音模式标志 ===
    private var isVoiceMode = false

    private val BALL_SIZE_DP = 52
    private val PANEL_WIDTH_DP = 160
    private val SNAP_DURATION = 250L
    private val EXPAND_DURATION = 200L

    // 自动收起计时器
    private var autoCollapseRunnable: Runnable? = null
    private val AUTO_COLLAPSE_DELAY = 8000L  // 8秒无操作自动收起

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = PreferencesManager.getInstance(this)

        // ===== 前台通知（Android 8.0+，防止系统杀进程）=====
        createNotificationChannel()
        startForegroundNotification()

        // 注册评分广播接收器（来自 GameStateManager）
        scoreReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_UPDATE_SCORE -> {
                        currentScore = intent.getIntExtra(EXTRA_SCORE, 0)
                        currentGrade = intent.getStringExtra(EXTRA_GRADE) ?: "B"
                        currentFps = intent.getIntExtra(EXTRA_FPS, 0)
                        refreshUI()
                        refreshMatchInfo()
                    }
                    ACTION_UPDATE_ANALYSIS -> {
                        currentAnalysis = intent.getStringExtra(EXTRA_ANALYSIS)
                        refreshAiAnalysis()
                    }
                    // 语音对话消息 → 更新球内文字
                    VoiceConversationEngine.ACTION_VOICE_MESSAGE -> {
                        val role = intent.getStringExtra(VoiceConversationEngine.EXTRA_ROLE) ?: ""
                        val text = intent.getStringExtra(VoiceConversationEngine.EXTRA_TEXT) ?: ""
                        updateBallVoiceText(role, text)
                    }
                    // 语音对话状态变化
                    VoiceConversationEngine.ACTION_VOICE_STATE -> {
                        updateVoiceUI()
                    }
                    // 语音识别就绪
                    VoiceConversationEngine.ACTION_VOICE_READY -> {
                        updateVoiceUI()
                    }
                    // RMS 音量变化 → 脉冲动画
                    VoiceConversationEngine.ACTION_VOICE_RMS -> {
                        val rms = intent.getFloatExtra(VoiceConversationEngine.EXTRA_RMS, 0f)
                        currentRms = rms
                        updatePulseAnimation()
                    }
                    // 检测到语音开始
                    VoiceConversationEngine.ACTION_VOICE_SPEECH_BEGIN -> {
                        updateVoiceUI()
                    }
                    // 语音结束
                    VoiceConversationEngine.ACTION_VOICE_SPEECH_END -> {
                        updateVoiceUI()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_SCORE)
            addAction(ACTION_UPDATE_ANALYSIS)
            addAction(VoiceConversationEngine.ACTION_VOICE_MESSAGE)
            addAction(VoiceConversationEngine.ACTION_VOICE_STATE)
            addAction(VoiceConversationEngine.ACTION_VOICE_RMS)
            addAction(VoiceConversationEngine.ACTION_VOICE_READY)
            addAction(VoiceConversationEngine.ACTION_VOICE_SPEECH_BEGIN)
            addAction(VoiceConversationEngine.ACTION_VOICE_SPEECH_END)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(scoreReceiver!!, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_SCORE -> {
                currentScore = intent.getIntExtra(EXTRA_SCORE, 0)
                currentGrade = intent.getStringExtra(EXTRA_GRADE) ?: "B"
                currentFps = intent.getIntExtra(EXTRA_FPS, 0)
                refreshUI()
            }
            ACTION_UPDATE_LANE -> {
                currentLane = intent.getStringExtra(EXTRA_LANE) ?: ""
                currentHero = intent.getStringExtra(EXTRA_HERO) ?: ""
                highlightLane(currentLane)
                refreshMatchInfo()
                showLaneTasks(currentLane)
            }
            ACTION_UPDATE_ANALYSIS -> {
                currentAnalysis = intent.getStringExtra(EXTRA_ANALYSIS)
                refreshAiAnalysis()
            }
            ACTION_HIDE -> destroyWindows()
            ACTION_SHOW -> {
                if (!isShowing) createWindows()
            }
            else -> {
                if (!isShowing) createWindows()
            }
        }
        return START_STICKY
    }

    // =================================================================
    //  创建窗口
    // =================================================================

    private fun createWindows() {
        if (isShowing && ballView != null) return
        try {
            createBall()
            // 恢复上次选择的分路
            val savedLane = prefs.getString("current_lane", "")
            if (savedLane.isNotEmpty()) {
                currentLane = savedLane
            }
            isShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createBall() {
        val inflater = LayoutInflater.from(this)
        ballView = inflater.inflate(R.layout.layout_floating_ball, null)

        // 背景
        bgCircle = ballView?.findViewById(R.id.bg_circle)

        // 评分模式
        layoutScoreMode = ballView?.findViewById(R.id.layout_score_mode)
        tvScore = ballView?.findViewById(R.id.tv_score)
        tvGrade = ballView?.findViewById(R.id.tv_grade)

        // 语音模式
        layoutVoiceMode = ballView?.findViewById(R.id.layout_voice_mode)
        voicePulseDot = ballView?.findViewById(R.id.voice_pulse_dot)
        tvVoiceModeText = ballView?.findViewById(R.id.tv_voice_mode_text)
        tvVoiceCurText = ballView?.findViewById(R.id.tv_voice_cur_text)

        // 麦克风按钮 → 启动/停止语音对话
        btnMic = ballView?.findViewById(R.id.btn_mic)
        btnMic?.setOnClickListener {
            if (!VoiceConversationEngine.isConversationActive()) {
                startVoiceConversation()
            } else {
                VoiceConversationEngine.stopConversation()
                updateVoiceUI()
            }
        }

        val ballPx = dpToPx(BALL_SIZE_DP)
        ballParams = WindowManager.LayoutParams().apply {
            type = getOverlayType()
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            width = ballPx
            height = ballPx
            x = 0  // 默认贴左边缘
            y = getScreenHeight() / 3
        }

        setupBallDrag()
        windowManager?.addView(ballView, ballParams)
        refreshUI()
    }

    private fun createPanel() {
        if (panelView != null) return

        val inflater = LayoutInflater.from(this)
        panelView = inflater.inflate(R.layout.layout_floating_panel, null)

        // 面板控件
        tvLaneTitle = panelView?.findViewById(R.id.tv_lane_title)
        tvTaskTitle = panelView?.findViewById(R.id.tv_task_title)
        tvHero = panelView?.findViewById(R.id.tv_hero_name)
        tvCurrentScore = panelView?.findViewById(R.id.tv_current_score)
        layoutMatchInfo = panelView?.findViewById(R.id.layout_match_info)
        layoutLaneTasks = panelView?.findViewById(R.id.layout_lane_tasks)
        layoutTaskList = panelView?.findViewById(R.id.layout_task_list)
        tvAiAnalysis = panelView?.findViewById(R.id.tv_ai_analysis)
        cardAiAnalysis = panelView?.findViewById(R.id.card_ai_analysis)

        // 分路按钮
        laneViews["top"] = panelView!!.findViewById(R.id.lane_top)
        laneViews["jungle"] = panelView!!.findViewById(R.id.lane_jungle)
        laneViews["mid"] = panelView!!.findViewById(R.id.lane_mid)
        laneViews["adc"] = panelView!!.findViewById(R.id.lane_adc)
        laneViews["support"] = panelView!!.findViewById(R.id.lane_support)

        panelView?.findViewById<View>(R.id.btn_collapse)?.setOnClickListener { collapsePanel() }
        panelView?.findViewById<View>(R.id.btn_minimize)?.setOnClickListener { collapsePanel() }

        // 分路点击事件
        val laneKeys = listOf("top", "jungle", "mid", "adc", "support")
        val laneNames = listOf("对抗路", "打野", "中路", "发育路", "游走")
        laneKeys.forEachIndexed { i, key ->
            laneViews[key]?.setOnClickListener {
                selectLane(key, laneNames[i])
            }
        }

        // 计算面板位置
        val ballX = ballParams?.x ?: 0
        val ballY = ballParams?.y ?: 200
        val screenWidth = getScreenWidth()
        val panelPx = dpToPx(PANEL_WIDTH_DP)
        val ballPx = dpToPx(BALL_SIZE_DP)

        val panelX = if (ballX < screenWidth / 2) {
            ballX + ballPx + dpToPx(6)
        } else {
            ballX - panelPx - dpToPx(6)
        }

        panelParams = WindowManager.LayoutParams().apply {
            type = getOverlayType()
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            width = panelPx
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = panelX.coerceIn(0, screenWidth - panelPx)
            y = (ballY - dpToPx(10)).coerceIn(0, getScreenHeight() - dpToPx(350))
        }

        setupPanelDrag()
        windowManager?.addView(panelView, panelParams)

        // 恢复分路高亮和任务（没有分路时显示提示）
        highlightLane(currentLane)
        showLaneTasks(currentLane)
    }

    // =================================================================
    //  拖拽逻辑 — 悬浮球（横屏优化：吸附到左右边缘）
    // =================================================================

    private fun setupBallDrag() {
        ballView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = ballParams?.x ?: 0
                    initY = ballParams?.y ?: 0
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    draggingBall = true
                    snapAnimator?.cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        isDragging = true
                    }
                    if (isDragging) {
                        ballParams?.let {
                            it.x = initX + dx
                            it.y = initY + dy
                            windowManager?.updateViewLayout(ballView, it)
                        }
                        // 拖拽时关闭展开面板
                        if (isExpanded) destroyPanel()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // 点击：展开/收起
                        if (isExpanded) {
                            collapsePanel()
                        } else {
                            expandPanel()
                        }
                    } else {
                        // 拖拽松手 → 边缘吸附
                        snapToEdge()
                    }
                    draggingBall = false
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    // =================================================================
    //  拖拽逻辑 — 展开面板
    // =================================================================

    private fun setupPanelDrag() {
        panelView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = panelParams?.x ?: 0
                    initY = panelParams?.y ?: 0
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    resetAutoCollapseTimer()  // 用户触碰面板 → 重新计时
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) isDragging = true
                    if (isDragging) {
                        panelParams?.let {
                            it.x = initX + dx
                            it.y = initY + dy
                            windowManager?.updateViewLayout(panelView, it)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    // =================================================================
    //  边缘吸附动画（横屏：优先吸附左右，Y轴也吸附到合理位置）
    // =================================================================

    private fun snapToEdge() {
        val params = ballParams ?: return
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()
        val ballPx = dpToPx(BALL_SIZE_DP)
        val centerX = params.x + ballPx / 2

        // X轴：吸附到左或右边缘
        val targetX = if (centerX < screenWidth / 2) {
            0
        } else {
            screenWidth - ballPx
        }

        // Y轴：吸附到上/中/下三档
        val thirdH = screenHeight / 3
        val targetY = when {
            params.y < thirdH -> 0                     // 上三分之一 → 贴顶
            params.y < thirdH * 2 -> thirdH            // 中三分之一 → 居中
            else -> (screenHeight - ballPx).coerceAtLeast(0)  // 下三分之一 → 贴底
        }

        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_DURATION
            interpolator = DecelerateInterpolator(1.5f)
            val startX = params.x
            val startY = params.y
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                params.x = (startX + (targetX - startX) * fraction).toInt()
                params.y = (startY + (targetY - startY) * fraction).toInt()
                windowManager?.updateViewLayout(ballView, params)
            }
            start()
        }
    }

    // =================================================================
    //  自动收起计时器
    // =================================================================

    private fun startAutoCollapseTimer() {
        cancelAutoCollapseTimer()
        autoCollapseRunnable = Runnable {
            if (isExpanded) {
                collapsePanel()
            }
        }
        mainHandler.postDelayed(autoCollapseRunnable!!, AUTO_COLLAPSE_DELAY)
    }

    private fun resetAutoCollapseTimer() {
        cancelAutoCollapseTimer()
        startAutoCollapseTimer()
    }

    private fun cancelAutoCollapseTimer() {
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        autoCollapseRunnable = null
    }

    // =================================================================
    //  展开/收起面板
    // =================================================================

    private fun expandPanel() {
        // 语音模式：不展开面板，球内显示对话内容
        if (isVoiceMode) return
        if (isExpanded) return
        createPanel()
        isExpanded = true

        panelView?.apply {
            alpha = 0f
            scaleX = 0.85f
            scaleY = 0.85f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(EXPAND_DURATION)
                .setInterpolator(OvershootInterpolator(0.8f))
                .start()
        }

        ballView?.animate()
            ?.scaleX(0.85f)
            ?.scaleY(0.85f)
            ?.setDuration(EXPAND_DURATION)
            ?.start()

        refreshMatchInfo()
        highlightLane(currentLane)
        showLaneTasks(currentLane)

        // 启动自动收起计时器
        startAutoCollapseTimer()
    }

    private fun collapsePanel() {
        if (!isExpanded) return
        isExpanded = false
        cancelAutoCollapseTimer()

        panelView?.animate()
            ?.alpha(0f)
            ?.scaleX(0.85f)
            ?.scaleY(0.85f)
            ?.setDuration(150)
            ?.setInterpolator(DecelerateInterpolator())
            ?.withEndAction { destroyPanel() }
            ?.start()

        ballView?.animate()
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(150)
            ?.start()
    }

    private fun destroyPanel() {
        cancelAutoCollapseTimer()
        try {
            panelView?.let { windowManager?.removeView(it) }
            panelView = null
            panelParams = null
            isExpanded = false
        } catch (e: Exception) { android.util.Log.w("FloatingWindow", "destroyPanel error", e) }
    }

    // =================================================================
    //  分路选择 + 任务展示
    // =================================================================

    private fun selectLane(key: String, name: String) {
        currentLane = key
        highlightLane(key)
        showLaneTasks(key)

        // 更新面板标题
        val icon = when (key) {
            "top" -> "⚔️"
            "jungle" -> "🌲"
            "mid" -> "🔮"
            "adc" -> "🏹"
            "support" -> "🛡️"
            else -> "📍"
        }
        tvLaneTitle?.text = "$icon $name"

        Toast.makeText(this, "已选择: $name", Toast.LENGTH_SHORT).show()

        // 保存到首选项
        prefs.saveString("current_lane", key)

        // 用户操作 → 重新计时
        resetAutoCollapseTimer()

        // 发送广播通知
        val intent = Intent("com.gameai.LANE_SELECTED").apply {
            putExtra("lane", key)
            putExtra("lane_name", name)
        }
        sendBroadcast(intent)
    }

    private fun highlightLane(laneKey: String) {
        laneViews.forEach { (key, view) ->
            if (key == laneKey) {
                view.setBackgroundResource(R.drawable.bg_chip_selected)
                view.setTextColor(0xFFFFFFFF.toInt())
            } else {
                view.setBackgroundResource(R.drawable.bg_chip_normal)
                view.setTextColor(resources.getColor(R.color.text_primary, null))
            }
        }
    }

    private fun showLaneTasks(laneKey: String) {
        val tasks = laneTaskMap[laneKey]
        layoutTaskList?.removeAllViews()

        if (tasks == null) {
            // 没有选择分路时显示提示
            val hintTv = TextView(this).apply {
                text = "👆 点击上方分路标签\n查看该分路任务指引"
                setTextColor(0xFFE8ECF1.toInt())
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                setPadding(12, 30, 12, 30)
            }
            layoutTaskList?.addView(hintTv)
            tvTaskTitle?.text = "任务指引"
            return
        }

        // 更新任务标题
        val laneName = when (laneKey) {
            "top" -> "⚔️ 对抗路"
            "jungle" -> "🌲 打野"
            "mid" -> "🔮 中路"
            "adc" -> "🏹 发育路"
            "support" -> "🛡️ 游走"
            else -> laneKey
        }
        tvTaskTitle?.text = "$laneName · 任务指引"

        val tierIcons = mapOf(
            "铜牌" to "🥉",
            "银牌" to "🥈",
            "金牌" to "🥇",
            "顶级" to "👑"
        )

        for (tierTask in tasks) {
            // 分段标题
            val titleTv = TextView(this).apply {
                text = "${tierIcons[tierTask.tier] ?: "●"} ${tierTask.tier}"
                setTextColor(tierTask.color)
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 6, 0, 3)
            }
            layoutTaskList?.addView(titleTv)

            // 任务列表 — 亮色文字，暗底清晰可读
            for (task in tierTask.tasks) {
                val taskTv = TextView(this).apply {
                    text = "  • $task"
                    setTextColor(0xFFE8ECF1.toInt())
                    textSize = 10f
                    setPadding(6, 2, 0, 2)
                }
                layoutTaskList?.addView(taskTv)
            }
        }
    }

    // =================================================================
    //  UI 刷新
    // =================================================================

    private fun refreshUI() {
        mainHandler.post {
            if (currentScore > 0) {
                tvScore?.text = "$currentScore"
            } else {
                tvScore?.text = "--"
            }
            tvGrade?.text = currentGrade

            val color = when (currentGrade) {
                "S", "顶级" -> resources.getColor(R.color.gold, null)
                "A", "金牌" -> resources.getColor(R.color.orange, null)
                "B", "银牌" -> resources.getColor(R.color.green, null)
                "C", "铜牌" -> resources.getColor(R.color.blue, null)
                "D", "无评级" -> 0xFF9CA3AF.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
            tvGrade?.setTextColor(color)
            tvScore?.setTextColor(color)
        }
    }

    private fun refreshAiAnalysis() {
        mainHandler.post {
            val analysis = currentAnalysis
            if (analysis != null && analysis.isNotBlank()) {
                cardAiAnalysis?.visibility = View.VISIBLE
                tvAiAnalysis?.text = "🤖 $analysis"
            } else {
                cardAiAnalysis?.visibility = View.GONE
            }
        }
    }

    private fun refreshMatchInfo() {
        if (currentHero.isNotEmpty()) {
            layoutMatchInfo?.visibility = View.VISIBLE
            tvHero?.text = currentHero
            tvCurrentScore?.text = if (currentScore > 0) "评分: $currentScore" else ""
        } else {
            layoutMatchInfo?.visibility = View.GONE
        }
    }

    // =================================================================
    //  前台通知
    // =================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "游戏AI悬浮球",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮球辅助服务运行中"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("游戏AI助手")
            .setContentText("悬浮球运行中")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    // =================================================================
    //  语音对话（豆包模式）
    // =================================================================

    private fun startVoiceConversation() {
        // 检查录音权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要录音权限才能使用语音对话", Toast.LENGTH_LONG).show()
            return
        }

        VoiceConversationEngine.init(this)
        VoiceConversationEngine.startConversation()

        // 不展开面板，球内显示对话
        updateVoiceUI()
    }

    private fun updateVoiceUI() {
        mainHandler.post {
            val active = VoiceConversationEngine.isConversationActive()
            val state = VoiceConversationEngine.state

            if (active) {
                // === 进入语音模式（不扩大球体，保持圆形） ===
                if (!isVoiceMode) {
                    isVoiceMode = true
                    // 关闭面板
                    if (isExpanded) destroyPanel()
                }

                // 评分隐藏，语音显示
                layoutScoreMode?.visibility = View.GONE
                tvGrade?.visibility = View.GONE
                layoutVoiceMode?.visibility = View.VISIBLE

                // 状态文字
                when (state) {
                    VoiceConversationEngine.State.LISTENING -> {
                        tvVoiceModeText?.text = "聆听"
                        tvVoiceModeText?.setTextColor(resources.getColor(R.color.accent_primary, null))
                        btnMic?.text = "⏹"
                        startPulseAnimation()
                        // 回到聆听：清除文字
                        tvVoiceCurText?.visibility = View.GONE
                    }
                    VoiceConversationEngine.State.PROCESSING -> {
                        tvVoiceModeText?.text = "思考"
                        tvVoiceModeText?.setTextColor(resources.getColor(R.color.accent_gold, null))
                        btnMic?.text = "⏳"
                        stopPulseAnimation()
                    }
                    VoiceConversationEngine.State.SPEAKING -> {
                        tvVoiceModeText?.text = "播报"
                        tvVoiceModeText?.setTextColor(resources.getColor(R.color.status_success, null))
                        btnMic?.text = "🔊"
                        stopPulseAnimation()
                    }
                    else -> {
                        tvVoiceModeText?.text = "就绪"
                        tvVoiceModeText?.setTextColor(resources.getColor(R.color.text_secondary, null))
                        btnMic?.text = "🎤"
                        stopPulseAnimation()
                    }
                }
            } else {
                // === 退出语音模式 ===
                isVoiceMode = false

                // 评分显示，语音隐藏
                layoutScoreMode?.visibility = View.VISIBLE
                tvGrade?.visibility = View.VISIBLE
                layoutVoiceMode?.visibility = View.GONE
                btnMic?.text = "🎤"
                stopPulseAnimation()
                // 清除语音文字
                tvVoiceCurText?.visibility = View.GONE

                // 恢复评分
                refreshUI()
            }
        }
    }

    /** 动态修改球体尺寸，保持视觉中心不变 */
    private fun setBallSize(widthDp: Int, heightSpec: Int) {
        val params = ballParams ?: return
        val newWidthPx = dpToPx(widthDp)
        val currentWidthPx = params.width
        val screenWidth = getScreenWidth()

        // 基于当前实际宽度计算中心x
        val centerX = params.x + currentWidthPx / 2
        val newX = (centerX - newWidthPx / 2).coerceIn(0, screenWidth - newWidthPx)

        params.width = newWidthPx
        params.height = heightSpec
        params.x = newX
        ballView?.let { windowManager?.updateViewLayout(it, params) }
    }

    /** 启动脉冲动画（聆听时根据 RMS 音量缩放圆点） */
    private fun startPulseAnimation() {
        stopPulseAnimation()
        val dot = voicePulseDot ?: return

        pulseAnimator = ValueAnimator.ofFloat(0.8f, 1.6f).apply {
            duration = 400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                val scale = if (currentRms > 1f) {
                    // 有声音时缩放跟随 RMS (0~15 -> 0.8~2.0)
                    (0.8f + currentRms / 15f * 1.2f).coerceIn(0.8f, 2.0f)
                } else {
                    anim.animatedValue as Float
                }
                dot.scaleX = scale
                dot.scaleY = scale
            }
            start()
        }
    }

    /** 更新脉冲缩放（由 RMS 广播触发） */
    private fun updatePulseAnimation() {
        // 脉冲动画的 addUpdateListener 会在下一帧自动应用 currentRms
        // 如果动画未运行，启动它
        if (pulseAnimator == null || pulseAnimator?.isRunning != true) {
            startPulseAnimation()
        }
    }

    /** 停止脉冲动画 */
    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        voicePulseDot?.apply {
            scaleX = 1f
            scaleY = 1f
        }
        currentRms = 0f
    }

    /** 更新球内语音对话文字（只显示当前轮简短摘要，不记录历史） */
    private fun updateBallVoiceText(role: String, text: String) {
        mainHandler.post {
            val short = if (text.length > 10) text.substring(0, 10) + "…" else text
            when (role) {
                "user" -> {
                    tvVoiceCurText?.text = short
                    tvVoiceCurText?.visibility = View.VISIBLE
                }
                "assistant" -> {
                    tvVoiceCurText?.text = short
                    tvVoiceCurText?.visibility = View.VISIBLE
                }
                "system" -> {
                    tvVoiceCurText?.text = text.take(14)
                    tvVoiceCurText?.visibility = View.VISIBLE
                }
            }
        }
    }

    // =================================================================
    //  窗口生命周期
    // =================================================================

    private fun destroyWindows() {
        destroyPanel()
        try {
            ballView?.let { windowManager?.removeView(it) }
            ballView = null
            ballParams = null
            isShowing = false
        } catch (e: Exception) { android.util.Log.w("FloatingWindow", "destroyWindows error", e) }
    }

    // =================================================================
    //  工具方法
    // =================================================================

    private fun getOverlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun getScreenWidth(): Int = resources.displayMetrics.widthPixels
    private fun getScreenHeight(): Int = resources.displayMetrics.heightPixels
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        stopPulseAnimation()
        scoreReceiver?.let {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
            } catch (e: Exception) { android.util.Log.w("FloatingWindow", "unregisterReceiver error", e) }
        }
        destroyWindows()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
