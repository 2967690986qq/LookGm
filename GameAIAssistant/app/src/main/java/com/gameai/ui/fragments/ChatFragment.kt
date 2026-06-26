package com.gameai.ui.fragments

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gameai.R
import com.gameai.ai.CloudAiClient
import com.gameai.ai.VoiceCommandHandler
import com.gameai.ai.VoiceConversationEngine
import com.gameai.model.ModelProvider
import com.gameai.model.ProviderConfig
import com.gameai.utils.PreferencesManager
import kotlinx.coroutines.*

class ChatFragment : Fragment() {

    // ---- Views ----
    private var rvMessages: RecyclerView? = null
    private var etInput: EditText? = null
    private var ivSend: ImageView? = null
    private var ivMic: ImageView? = null
    private var tvSelectedModel: TextView? = null
    private var llModelSwitcher: LinearLayout? = null
    private var llVoiceStatus: LinearLayout? = null
    private var tvVoiceState: TextView? = null
    private var btnVoiceStop: TextView? = null
    private var vVoiceDot: View? = null

    // ---- State ----
    private lateinit var adapter: ChatAdapter
    private lateinit var prefs: PreferencesManager
    private val messages = mutableListOf<ChatMessage>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var selectedProviderName: String = ""
    private var selectedModelName: String = ""
    private var selectedApiKey: String = ""
    private var selectedBaseUrl: String = ""
    private var selectedProvider: ModelProvider? = null

    private var activeStreamingCall: CloudAiClient.StreamingCall? = null
    private var streamingMessageIndex: Int = -1
    private var isVoiceActive = false

    // ---- BroadcastReceiver ----
    private var voiceReceiver: BroadcastReceiver? = null

    // ---- Permission launcher ----
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            VoiceConversationEngine.startConversation()
        } else {
            Toast.makeText(requireContext(), "需要录音权限才能使用语音对话", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = PreferencesManager.getInstance(requireContext())

        // Bind views
        rvMessages = view.findViewById(R.id.rv_messages)
        etInput = view.findViewById(R.id.et_input)
        ivSend = view.findViewById(R.id.iv_send)
        ivMic = view.findViewById(R.id.iv_mic)
        tvSelectedModel = view.findViewById(R.id.tv_selected_model)
        llModelSwitcher = view.findViewById(R.id.ll_model_switcher)
        llVoiceStatus = view.findViewById(R.id.ll_voice_status)
        tvVoiceState = view.findViewById(R.id.tv_voice_state)
        btnVoiceStop = view.findViewById(R.id.btn_voice_stop)
        vVoiceDot = view.findViewById(R.id.v_voice_dot)

        adapter = ChatAdapter(messages)
        rvMessages?.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
            adapter = this@ChatFragment.adapter
        }

        // 初始选择对话模型
        loadDefaultModel()

        // 模型切换器点击
        llModelSwitcher?.setOnClickListener { showModelSwitcherDialog() }

        // 发送按钮
        ivSend?.setOnClickListener { sendTextMessage() }

        // 回车发送
        etInput?.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                sendTextMessage()
                true
            } else false
        }

        // 语音按钮 — 开始/结束语音对话
        ivMic?.setOnClickListener { toggleVoiceConversation() }

        // 状态条中的停止按钮
        btnVoiceStop?.setOnClickListener { toggleVoiceConversation() }

        // 注册语音对话广播
        registerVoiceReceiver()

        // 显示欢迎消息
        if (messages.isEmpty()) {
            addSystemMessage("👋 欢迎使用AI对话，点击顶部选择模型即可开始")
        }
    }

    // ============================================================
    //  模型切换（仅选择已配置的模型，不配置新模型）
    // ============================================================

    private fun loadDefaultModel() {
        val config = prefs.getConversationModelConfig()
        if (config != null && config.apiKey.isNotBlank()) {
            selectedProvider = config.provider
            selectedProviderName = config.provider.displayName
            selectedModelName = config.modelName
            selectedApiKey = config.apiKey
            selectedBaseUrl = config.baseUrl
            tvSelectedModel?.text = "${config.modelName.take(15)} · ${config.provider.displayName}"
        } else {
            val base = prefs.getCurrentProviderConfig()
            if (base.apiKey.isNotBlank()) {
                selectedProvider = base.provider
                selectedProviderName = base.provider.displayName
                selectedModelName = base.modelName
                selectedApiKey = base.apiKey
                selectedBaseUrl = base.baseUrl
                tvSelectedModel?.text = "${base.modelName.take(15)} · ${base.provider.displayName}"
            } else {
                tvSelectedModel?.text = "选择模型"
            }
        }
    }

    private fun showModelSwitcherDialog() {
        val options = mutableListOf<ModelOption>()
        val config = prefs.loadConfig()

        for ((_, providerConfig) in config.cloudProviderConfigs) {
            if (providerConfig.apiKey.isBlank()) continue

            if (providerConfig.models.isEmpty()) {
                options.add(ModelOption(
                    label = "${providerConfig.modelName} · ${providerConfig.provider.displayName}",
                    provider = providerConfig.provider,
                    providerName = providerConfig.provider.displayName,
                    modelName = providerConfig.modelName,
                    apiKey = providerConfig.apiKey,
                    baseUrl = providerConfig.baseUrl
                ))
            } else {
                for (binding in providerConfig.models) {
                    if (binding.matches("conversation") || binding.matches("all")) {
                        // 显示格式：模型名称 · 供应商（displayLabel仅用于显示简称，不同时显示两个名称）
                        val label = binding.displayLabel.ifBlank { binding.modelName }
                        val displayText = if (binding.displayLabel.isNotBlank() && binding.displayLabel != binding.modelName) {
                            // 用户自定义了显示名，显示简称和完整模型名
                            "${label}(${binding.modelName}) · ${providerConfig.provider.displayName}"
                        } else {
                            // 没有自定义显示名，直接显示模型名
                            "${binding.modelName} · ${providerConfig.provider.displayName}"
                        }
                        options.add(ModelOption(
                            label = displayText,
                            provider = providerConfig.provider,
                            providerName = providerConfig.provider.displayName,
                            modelName = binding.modelName,
                            apiKey = providerConfig.apiKey,
                            baseUrl = providerConfig.baseUrl
                        ))
                    }
                }
            }
        }

        if (options.isEmpty()) {
            Toast.makeText(requireContext(), "请先在「模型」页面配置供应商和模型", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("选择对话模型")
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                val selected = options[which]
                selectedProvider = selected.provider
                selectedProviderName = selected.providerName
                selectedModelName = selected.modelName
                selectedApiKey = selected.apiKey
                selectedBaseUrl = selected.baseUrl
                tvSelectedModel?.text = "${selected.modelName.take(15)} · ${selected.providerName}"
                // 保存选择，下次打开 App 自动使用这个模型
                prefs.saveConversationModel(
                    providerName = selected.providerName,
                    modelName = selected.modelName,
                    apiKey = selected.apiKey,
                    baseUrl = selected.baseUrl
                )
                addSystemMessage("已切换到 ${selected.label}")
            }
            .show()
    }

    data class ModelOption(
        val label: String,
        val provider: ModelProvider,
        val providerName: String,
        val modelName: String,
        val apiKey: String,
        val baseUrl: String
    )

    // ============================================================
    //  文本消息
    // ============================================================

    private fun sendTextMessage() {
        val text = etInput?.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        if (selectedApiKey.isBlank()) {
            Toast.makeText(requireContext(), "请先选择对话模型", Toast.LENGTH_SHORT).show()
            return
        }

        etInput?.text?.clear()
        activeStreamingCall?.cancel()
        activeStreamingCall = null

        addUserMessage(text)
        addAiThinking()

        val config = ProviderConfig(
            provider = selectedProvider ?: ModelProvider.OPENAI,
            apiKey = selectedApiKey,
            baseUrl = selectedBaseUrl,
            modelName = selectedModelName
        )

        activeStreamingCall = CloudAiClient.converseStreaming(
            bitmap = null,
            config = config,
            userMessage = text,
            onToken = { accumulated -> updateStreamingMessage(accumulated, false) },
            onComplete = { fullText -> updateStreamingMessage(fullText, true) },
            onError = { error ->
                mainHandler.post {
                    removeStreamingPlaceholder()
                    addSystemMessage("❌ $error")
                    Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ============================================================
    //  语音对话
    // ============================================================

    private fun toggleVoiceConversation() {
        val engine = VoiceConversationEngine

        if (engine.isConversationActive()) {
            engine.stopConversation()
            addSystemMessage("语音对话已结束")
            return
        }

        // 检查录音权限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else {
            VoiceConversationEngine.startConversation()
        }
    }

    private fun registerVoiceReceiver() {
        voiceReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    VoiceConversationEngine.ACTION_VOICE_STATE -> updateVoiceStateUI()
                    VoiceConversationEngine.ACTION_VOICE_MESSAGE -> {
                        val role = intent.getStringExtra(VoiceConversationEngine.EXTRA_ROLE) ?: "assistant"
                        val text = intent.getStringExtra(VoiceConversationEngine.EXTRA_TEXT) ?: ""
                        val isStreaming = intent.getBooleanExtra(VoiceConversationEngine.EXTRA_STREAMING, false)
                        mainHandler.post { handleVoiceMessage(role, text, isStreaming) }
                    }
                    VoiceConversationEngine.ACTION_VOICE_STREAMING -> {
                        val text = intent.getStringExtra(VoiceConversationEngine.EXTRA_TEXT) ?: ""
                        val isStreaming = intent.getBooleanExtra(VoiceConversationEngine.EXTRA_STREAMING, false)
                        mainHandler.post { handleStreamingText(text, isStreaming) }
                    }
                    VoiceCommandHandler.ACTION_VOICE_COMMAND -> {
                        val cmdType = intent.getStringExtra(VoiceCommandHandler.EXTRA_COMMAND_TYPE) ?: ""
                        mainHandler.post { addSystemMessage("✅ 已执行: $cmdType") }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(VoiceConversationEngine.ACTION_VOICE_STATE)
            addAction(VoiceConversationEngine.ACTION_VOICE_MESSAGE)
            addAction(VoiceConversationEngine.ACTION_VOICE_STREAMING)
            addAction(VoiceCommandHandler.ACTION_VOICE_COMMAND)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(voiceReceiver!!, filter)
    }

    private fun updateVoiceStateUI() {
        val active = VoiceConversationEngine.isConversationActive()
        isVoiceActive = active

        if (active) {
            llVoiceStatus?.visibility = View.VISIBLE
            vVoiceDot?.visibility = View.VISIBLE

            val state = VoiceConversationEngine.state
            tvVoiceState?.text = when (state) {
                VoiceConversationEngine.State.IDLE -> "🎤 等待说话中..."
                VoiceConversationEngine.State.LISTENING -> "🎙 正在聆听..."
                VoiceConversationEngine.State.PROCESSING -> "🧠 AI 思考中..."
                VoiceConversationEngine.State.SPEAKING -> "🔊 小吉 正在回复..."
            }

            // 语音激活时改变麦克风按钮颜色
            ivMic?.setColorFilter(resources.getColor(R.color.status_error, null))
        } else {
            llVoiceStatus?.visibility = View.GONE
            ivMic?.clearColorFilter()
        }
    }

    private var streamingMessageIdx: Int = -1  // 流式消息在列表中的位置

    private fun handleVoiceMessage(role: String, text: String, isStreaming: Boolean) {
        if (isStreaming) {
            // 流式开始
            handleStreamingText(text, true)
            return
        }

        // 非流式：直接添加
        when (role) {
            "user" -> addUserMessage(text)
            "assistant" -> {
                // 如果存在流式占位，替换内容
                if (streamingMessageIdx >= 0 && streamingMessageIdx < messages.size) {
                    messages[streamingMessageIdx] = ChatMessage(text, ChatMessage.Type.AI, isStreaming = false)
                    adapter.notifyItemChanged(streamingMessageIdx)
                    streamingMessageIdx = -1
                } else {
                    addAiMessage(text)
                }
            }
            "system" -> addSystemMessage(text)
        }
    }

    private fun handleStreamingText(text: String, isStreaming: Boolean) {
        if (!isStreaming || text.isEmpty()) {
            // 流式结束
            if (streamingMessageIdx >= 0 && streamingMessageIdx < messages.size) {
                messages[streamingMessageIdx] = messages[streamingMessageIdx].copy(isStreaming = false)
                adapter.notifyItemChanged(streamingMessageIdx)
                streamingMessageIdx = -1
            }
            return
        }

        if (streamingMessageIdx < 0 || streamingMessageIdx >= messages.size) {
            // 创建新的流式气泡
            messages.add(ChatMessage(text, ChatMessage.Type.AI, isStreaming = true))
            streamingMessageIdx = messages.size - 1
            adapter.notifyItemInserted(streamingMessageIdx)
        } else {
            messages[streamingMessageIdx] = messages[streamingMessageIdx].copy(text = text)
            adapter.notifyItemChanged(streamingMessageIdx)
        }
        scrollToBottom()
    }

    // ============================================================
    //  消息管理
    // ============================================================

    private fun addUserMessage(text: String) {
        messages.add(ChatMessage(text, ChatMessage.Type.USER))
        adapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()
    }

    private fun addAiMessage(text: String) {
        messages.add(ChatMessage(text, ChatMessage.Type.AI))
        adapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()
    }

    private fun addSystemMessage(text: String) {
        messages.add(ChatMessage(text, ChatMessage.Type.SYSTEM))
        adapter.notifyItemInserted(messages.size - 1)
        // 最多保留 100 条消息
        while (messages.size > 100) {
            messages.removeAt(0)
            adapter.notifyItemRemoved(0)
        }
        scrollToBottom()
    }

    private fun addAiThinking() {
        messages.add(ChatMessage("思考中...", ChatMessage.Type.AI, isStreaming = true))
        streamingMessageIndex = messages.size - 1
        adapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()
    }

    private fun updateStreamingMessage(text: String, isComplete: Boolean) {
        if (streamingMessageIndex < 0 || streamingMessageIndex >= messages.size) return

        if (isComplete) {
            messages[streamingMessageIndex] = messages[streamingMessageIndex].copy(text = text, isStreaming = false)
            adapter.notifyItemChanged(streamingMessageIndex)
            streamingMessageIndex = -1
            activeStreamingCall = null
        } else {
            messages[streamingMessageIndex] = messages[streamingMessageIndex].copy(text = text)
            adapter.notifyItemChanged(streamingMessageIndex)
        }
        scrollToBottom()
    }

    private fun removeStreamingPlaceholder() {
        if (streamingMessageIndex >= 0 && streamingMessageIndex < messages.size) {
            messages.removeAt(streamingMessageIndex)
            adapter.notifyItemRemoved(streamingMessageIndex)
        }
        streamingMessageIndex = -1
        activeStreamingCall = null
    }

    private fun scrollToBottom() {
        rvMessages?.postDelayed({
            val lastIndex = adapter.itemCount - 1
            if (lastIndex >= 0) {
                rvMessages?.smoothScrollToPosition(lastIndex)
            }
        }, 100)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activeStreamingCall?.cancel()
        activeStreamingCall = null
        voiceReceiver?.let {
            try {
                LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(it)
            } catch (e: Exception) {}
        }
    }

    // ============================================================
    //  RecyclerView Adapter
    // ============================================================

    class ChatAdapter(private val messages: List<ChatMessage>) :
        RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

        override fun getItemViewType(position: Int): Int = messages[position].type.ordinal

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val layoutRes = when (ChatMessage.Type.entries[viewType]) {
                ChatMessage.Type.USER -> R.layout.item_chat_message_user
                ChatMessage.Type.AI -> R.layout.item_chat_message_ai
                ChatMessage.Type.SYSTEM -> R.layout.item_chat_message_system
            }
            return ViewHolder(LayoutInflater.from(parent.context).inflate(layoutRes, parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(messages[position])
        }

        override fun getItemCount(): Int = messages.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvContent: TextView = itemView.findViewById(R.id.tv_content)
            private val tvStreamingDot: TextView? = itemView.findViewById(R.id.tv_streaming_dot)

            fun bind(msg: ChatMessage) {
                tvContent.text = msg.text
                tvStreamingDot?.visibility = if (msg.isStreaming) View.VISIBLE else View.GONE
            }
        }
    }

    // ============================================================
    //  数据模型
    // ============================================================

    data class ChatMessage(
        val text: String,
        val type: Type,
        val isStreaming: Boolean = false
    ) {
        enum class Type { USER, AI, SYSTEM }
    }
}
