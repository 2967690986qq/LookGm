package com.gameai.ui.fragments

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gameai.R
import com.gameai.ai.VoiceCommandHandler
import com.gameai.ai.VoiceConversationEngine
import com.gameai.ai.UsageTracker
import com.gameai.databinding.FragmentDashboardBinding
import com.gameai.model.ScoreResult
import com.gameai.ui.widget.ScoreGaugeView
import com.gameai.viewmodel.MainViewModel
import kotlinx.coroutines.*

class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: MainViewModel
    private var isCapturing = false

    // 语音对话
    private var voiceReceiver: BroadcastReceiver? = null
    private var isVoiceActive = false

    // 录音权限请求 launcher
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            VoiceConversationEngine.startConversation()
        } else {
            Toast.makeText(requireContext(), "需要录音权限才能使用语音对话", Toast.LENGTH_LONG).show()
        }
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            viewModel.startCapture(result.resultCode, result.data!!)
            isCapturing = true
            updateCaptureBtn()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        // ----- 评分仪表盘 -----
        viewModel.currentScore.observe(viewLifecycleOwner) { score ->
            val grade = viewModel.currentGrade.value ?: "--"
            binding.gaugeScore.setScore(score, grade)
        }
        viewModel.currentGrade.observe(viewLifecycleOwner) { grade ->
            binding.gaugeScore.setScore(viewModel.currentScore.value ?: 0, grade)
        }

        // ----- 评分维度分解 -----
        viewModel.scoreBreakdown.observe(viewLifecycleOwner) { categories ->
            updateCategoryBars(categories)
        }

        // ----- 快速状态区 -----
        viewModel.statusText.observe(viewLifecycleOwner) { text ->
            binding.tvStatus.text = text ?: "就绪"
        }
        viewModel.matchPhase.observe(viewLifecycleOwner) { phase ->
            binding.tvPhase.text = phase.displayName
            binding.tvPhaseIcon.text = phaseIcon(phase)
        }
        viewModel.fps.observe(viewLifecycleOwner) { fps ->
            binding.tvFps.text = if (fps > 0) fps.toString() else "--"
        }
        viewModel.isConnected.observe(viewLifecycleOwner) { connected ->
            binding.tvConnectionStatus.text = if (connected) "已连接" else "未连接"
            binding.dotConnection.setBackgroundResource(
                if (connected) R.drawable.bg_grade_circle else R.drawable.bg_input
            )
        }
        viewModel.latestAnalysis.observe(viewLifecycleOwner) { analysis ->
            if (analysis != null && analysis.isNotEmpty()) {
                binding.cardAnalysis.visibility = View.VISIBLE
                binding.tvAnalysis.text = analysis
            } else {
                binding.cardAnalysis.visibility = View.GONE
            }
        }
        viewModel.isCapturing.observe(viewLifecycleOwner) { capturing ->
            isCapturing = capturing
            updateCaptureBtn()
        }

        // ===== 观察实时对局数据（OCR 识别后刷新）=====
        viewModel.matchData.observe(viewLifecycleOwner) { match ->
            updateMatchStatsUI(match)
        }

        // ----- 按钮 -----
        binding.btnCapture.setOnClickListener {
            if (isCapturing) {
                viewModel.stopCapture()
                isCapturing = false
            } else {
                val mm = requireContext().getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager
                captureLauncher.launch(mm.createScreenCaptureIntent())
            }
            updateCaptureBtn()
        }
        binding.btnSimulate.setOnClickListener { viewModel.simulateScoreUpdate() }
        binding.btnSimulateEnd.setOnClickListener { viewModel.simulateMatchEnd() }

        // ===== 语音对话控制 =====
        binding.btnVoiceToggle.setOnClickListener {
            if (isVoiceActive) {
                VoiceConversationEngine.stopConversation()
            } else {
                // 检查录音权限
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
                    ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                } else {
                    VoiceConversationEngine.startConversation()
                }
            }
        }

        // 注册语音对话广播
        voiceReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    VoiceConversationEngine.ACTION_VOICE_STATE -> {
                        updateVoiceDashboardUI()
                    }
                    VoiceConversationEngine.ACTION_VOICE_MESSAGE -> {
                        val role = intent.getStringExtra(VoiceConversationEngine.EXTRA_ROLE) ?: "assistant"
                        val text = intent.getStringExtra(VoiceConversationEngine.EXTRA_TEXT) ?: ""
                        addVoiceMessageBubble(role, text)
                    }
                    // v3.0: 流式文字更新（打字效果）
                    VoiceConversationEngine.ACTION_VOICE_STREAMING -> {
                        val text = intent.getStringExtra(VoiceConversationEngine.EXTRA_TEXT) ?: ""
                        val isStreaming = intent.getBooleanExtra(VoiceConversationEngine.EXTRA_STREAMING, false)
                        updateStreamingText(text, isStreaming)
                    }
                    // v3.0: 语音指令广播
                    VoiceCommandHandler.ACTION_VOICE_COMMAND -> {
                        val cmdType = intent.getStringExtra(VoiceCommandHandler.EXTRA_COMMAND_TYPE) ?: ""
                        addVoiceMessageBubble("system", "✅ 已执行: $cmdType")
                    }
                }
            }
        }
        val voiceFilter = IntentFilter().apply {
            addAction(VoiceConversationEngine.ACTION_VOICE_STATE)
            addAction(VoiceConversationEngine.ACTION_VOICE_MESSAGE)
            addAction(VoiceConversationEngine.ACTION_VOICE_STREAMING)
            addAction(VoiceCommandHandler.ACTION_VOICE_COMMAND)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(voiceReceiver!!, voiceFilter)
    }

    // ===== 语音对话 UI 方法 =====

    private fun updateVoiceDashboardUI() {
        val active = VoiceConversationEngine.isConversationActive()
        isVoiceActive = active

        if (active) {
            binding.btnVoiceToggle.text = "\u23F9 结束"
            binding.btnVoiceToggle.setBackgroundColor(resources.getColor(R.color.status_error, null))
        } else {
            binding.btnVoiceToggle.text = "\uD83C\uDFA4 对话"
            binding.btnVoiceToggle.background = resources.getDrawable(R.drawable.bg_btn_primary, null)
            binding.btnVoiceToggle.setTextColor(resources.getColor(R.color.bg_primary, null))
        }

        // 面板可见性
        if (active) {
            binding.cardVoiceDashboard.visibility = View.VISIBLE
            binding.layoutVoiceMessages.removeAllViews()
        } else {
            binding.cardVoiceDashboard.visibility = View.GONE
        }

        // 状态文字
        val state = VoiceConversationEngine.state
        binding.tvVoiceDashboardState.text = when (state) {
            VoiceConversationEngine.State.IDLE -> "\uD83C\uDFA4 点击麦克风开始对话"
            VoiceConversationEngine.State.LISTENING -> "\uD83C\uDFA4 正在聆听，请说话..."
            VoiceConversationEngine.State.PROCESSING -> "\uD83E\uDD14 AI 正在分析屏幕..."
            VoiceConversationEngine.State.SPEAKING -> "\uD83D\uDD0A AI 正在回复..."
        }
    }

    private fun addVoiceMessageBubble(role: String, text: String) {
        val ctx = requireContext()
        val container = binding.layoutVoiceMessages

        val bubble = TextView(ctx).apply {
            this.text = if (role == "user") "👤 你：$text" else "🤖 小G：$text"
            textSize = 13f
            setPadding(10, 8, 10, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dpToPx() }

            if (role == "user") {
                setTextColor(resources.getColor(R.color.accent_primary, null))
                setBackgroundColor(resources.getColor(R.color.accent_bg, null))
            } else {
                setTextColor(resources.getColor(R.color.accent_purple_light, null))
                setBackgroundColor(resources.getColor(R.color.bg_input, null))
            }
        }

        container.addView(bubble)
        binding.scrollVoiceDashboard.post {
            binding.scrollVoiceDashboard.fullScroll(View.FOCUS_DOWN)
        }

        if (container.childCount > 20) {
            container.removeViewAt(0)
        }
    }

    /** v3.0: 实时更新流式 AI 回复文字（打字效果） */
    private var streamingBubble: TextView? = null

    private fun updateStreamingText(text: String, isStreaming: Boolean) {
        if (!isStreaming || text.isEmpty()) {
            // 流式结束，重置气泡引用
            streamingBubble = null
            return
        }

        val container = binding.layoutVoiceMessages
        val ctx = context ?: return

        if (streamingBubble == null) {
            // 创建新的流式气泡
            streamingBubble = TextView(ctx).apply {
                textSize = 13f
                setTextColor(resources.getColor(R.color.accent_primary, null))
                setBackgroundColor(resources.getColor(R.color.accent_bg, null))
                setPadding(16, 10, 16, 10)
                isSingleLine = false
                maxLines = 5
            }
            container.addView(streamingBubble)
        }

        streamingBubble?.text = "AI: $text（输入中...）"

        binding.scrollVoiceDashboard.post {
            binding.scrollVoiceDashboard.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateCategoryBars(categories: Map<String, ScoreResult.CategoryScore>) {
        binding.layoutCategories.removeAllViews()
        if (categories.isEmpty()) {
            val tv = TextView(requireContext()).apply {
                text = "开始对局后显示评分维度"
                textSize = 12f
                setTextColor(resources.getColor(R.color.text_hint, null))
                gravity = Gravity.CENTER
                setPadding(0, 6, 0, 6)
            }
            binding.layoutCategories.addView(tv)
            return
        }

        val orderedKeys = listOf("kda", "economy", "teamfight", "vision", "damage", "survival", "develop", "tempo")
        val nameMap = mapOf(
            "kda" to "KDA", "economy" to "经济", "teamfight" to "参团率", "vision" to "视野",
            "damage" to "输出", "survival" to "生存", "develop" to "发育", "tempo" to "节奏"
        )

        for (key in orderedKeys) {
            val cat = categories[key] ?: continue
            val label = nameMap[key] ?: cat.name
            val percent = if (cat.maxScore > 0) cat.score.toFloat() / cat.maxScore else 0f
            val barColor = getPercentColor(percent)

            // 行容器
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = if (key == orderedKeys.last()) 0 else 8.dpToPx() }
            }

            // 标签行
            val labelRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val labelTv = TextView(requireContext()).apply {
                text = label
                textSize = 12f
                setTextColor(resources.getColor(R.color.text_secondary, null))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val scoreTv = TextView(requireContext()).apply {
                text = "${cat.score}/${cat.maxScore}"
                textSize = 11f
                setTextColor(resources.getColor(R.color.text_hint, null))
            }
            labelRow.addView(labelTv)
            labelRow.addView(scoreTv)
            row.addView(labelRow)

            // 进度条
            val barH = 5.dpToPx()
            val barBg = View(requireContext()).apply {
                setBackgroundColor(resources.getColor(R.color.bg_input, null))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, barH
                ).apply { topMargin = 5.dpToPx() }
            }
            row.addView(barBg)

            val barFg = View(requireContext()).apply {
                setBackgroundColor(barColor)
                layoutParams = LinearLayout.LayoutParams(0, barH)
            }
            row.addView(barFg)

            binding.layoutCategories.addView(row)

            // 动画
            val barWidth = barBg.layoutParams.width
            barFg.post {
                val targetWidth = if (barBg.width > 0) (barBg.width * percent).toInt() else 0
                ValueAnimator.ofInt(0, targetWidth).apply {
                    duration = 500
                    interpolator = DecelerateInterpolator()
                    startDelay = 60
                    addUpdateListener {
                        val lp = barFg.layoutParams
                        lp.width = it.animatedValue as Int
                        barFg.layoutParams = lp
                    }
                }.start()
            }
        }
    }

    private fun phaseIcon(phase: com.gameai.common.constants.GameConstants.MatchPhase): String = when (phase) {
        com.gameai.common.constants.GameConstants.MatchPhase.LOBBY -> "\uD83C\uDFE0"
        com.gameai.common.constants.GameConstants.MatchPhase.MATCHING -> "\uD83D\uDD0D"
        com.gameai.common.constants.GameConstants.MatchPhase.HERO_SELECT -> "\uD83E\uDDB8"
        com.gameai.common.constants.GameConstants.MatchPhase.LOADING -> "⏳"
        com.gameai.common.constants.GameConstants.MatchPhase.IN_GAME -> "⚔️"
        com.gameai.common.constants.GameConstants.MatchPhase.RESULT -> "\uD83C\uDFC6"
        com.gameai.common.constants.GameConstants.MatchPhase.RANK_LOBBY -> "\uD83C\uDFC5"
    }

    private fun getPercentColor(percent: Float): Int = when {
        percent >= 0.9f -> resources.getColor(R.color.green, null)
        percent >= 0.75f -> resources.getColor(R.color.accent_primary, null)
        percent >= 0.55f -> resources.getColor(R.color.gold, null)
        percent >= 0.35f -> resources.getColor(R.color.accent_purple, null)
        else -> resources.getColor(R.color.status_error, null)
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun updateCaptureBtn() {
        if (isCapturing) {
            binding.btnCapture.text = "⏹ 停止辅助"
            binding.btnCapture.background = resources.getDrawable(R.drawable.bg_card, null)
            binding.btnCapture.setTextColor(resources.getColor(R.color.status_error, null))
        } else {
            binding.btnCapture.text = "▶ 开始辅助"
            binding.btnCapture.background = resources.getDrawable(R.drawable.bg_btn_primary, null)
            binding.btnCapture.setTextColor(resources.getColor(R.color.bg_primary, null))
        }
    }

    /** 更新对局实时数据展示（KDA/经济/时间）*/
    private fun updateMatchStatsUI(match: com.gameai.model.MatchData?) {
        if (match != null && match.isActive) {
            // KDA
            binding.tvKdaValue.text = "${match.kdaData.kills}/${match.kdaData.deaths}/${match.kdaData.assists}"
            // 经济（总金币）
            binding.tvEconomyValue.text = if (match.economyData.gold > 0) "${match.economyData.gold}" else "--"
            // 对局时间
            val min = match.gameTimeSec / 60
            val sec = match.gameTimeSec % 60
            binding.tvGameTime.text = String.format("%02d:%02d", min, sec)
        } else {
            binding.tvKdaValue.text = "--/--/--"
            binding.tvEconomyValue.text = "--"
            binding.tvGameTime.text = "--:--"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voiceReceiver?.let {
            try {
                LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(it)
            } catch (e: Exception) { android.util.Log.w("Dashboard", "unregisterReceiver error", e) }
        }
        _binding = null
    }
}
