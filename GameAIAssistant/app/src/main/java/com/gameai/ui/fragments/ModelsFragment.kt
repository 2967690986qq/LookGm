package com.gameai.ui.fragments

import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gameai.R
import com.gameai.databinding.FragmentModelsBinding
import com.gameai.model.ModelBinding
import com.gameai.model.ModelClassifier
import com.gameai.model.ModelProvider
import com.gameai.model.ProviderConfig
import com.gameai.utils.ModelConnectionTester
import com.gameai.utils.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class ModelsFragment : Fragment(R.layout.fragment_models) {

    private var _binding: FragmentModelsBinding? = null
    private val binding get() = _binding!!

    private val prefs by lazy { PreferencesManager.getInstance(requireContext()) }
    private val config by lazy { prefs.loadConfig() }
    private val carouselHandler = Handler(Looper.getMainLooper())

    // 内存缓存
    private val providerConfigs = mutableMapOf<String, ProviderConfig>()
    private var currentProvider = ModelProvider.OPENAI
    private var currentModels = mutableListOf<ModelBinding>()   // 当前供应商的模型列表
    private var availableModels = mutableListOf<String>()

    private val allProviders = listOf(
        ModelProvider.OPENAI, ModelProvider.DEEPSEEK, ModelProvider.QWEN,
        ModelProvider.ERNIE, ModelProvider.ZHIPU, ModelProvider.SILICONFLOW,
        ModelProvider.LOCAL_OLLAMA, ModelProvider.LOCAL_VLLM, ModelProvider.LOCAL_LM_STUDIO,
        ModelProvider.CUSTOM
    )

    private var carouselRunnable: Runnable? = null
    private var resumeCarouselRunnable: Runnable? = null
    private var currentCarouselIdx = 0

    // ASR 测试相关
    private var asrTestDialog: Dialog? = null
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showAsrTestDialog()
        else Toast.makeText(requireContext(), "需要麦克风权限才能测试语音转文字", Toast.LENGTH_LONG).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentModelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadAllProviderConfigs()
        setupProviderChips()
        setupListeners()
        loadCurrentProviderUI()
        refreshConfiguredProvidersOverview()
        startCarousel()
    }

    // ===== 加载 =====

    private fun loadAllProviderConfigs() {
        providerConfigs.clear()
        val freshConfig = prefs.loadConfig()
        for (provider in allProviders) {
            val saved = freshConfig.cloudProviderConfigs[provider.name]
            if (saved != null) providerConfigs[provider.name] = saved
        }
        currentProvider = ModelProvider.fromName(prefs.getString("current_provider", "OPENAI"))
    }

    // ===== 供应商 Chips =====

    private fun setupProviderChips() {
        val chipContainer = binding.layoutProviderChips
        chipContainer.removeAllViews()
        for (provider in allProviders) {
            val isConfigured = providerConfigs.containsKey(provider.name)
            val isCurrent = provider == currentProvider
            val chip = buildChip(provider, isConfigured, isCurrent)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 }
            chipContainer.addView(chip, params)
        }
        updateConfiguredCountBadge()
    }

    private fun buildChip(provider: ModelProvider, configured: Boolean, current: Boolean): TextView {
        val cfg = providerConfigs[provider.name]
        val modelCount = cfg?.modelCount ?: 0
        val extra = when {
            configured && modelCount > 1 -> " ·${modelCount}"
            configured -> " ✓"
            else -> ""
        }
        val label = "${provider.icon}  ${provider.displayName}$extra"
        val bgRes = if (current || configured) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal
        val textColor = when {
            current -> resources.getColor(R.color.bg_secondary, null)
            configured -> resources.getColor(R.color.accent_primary, null)
            else -> resources.getColor(R.color.text_secondary, null)
        }
        return TextView(requireContext()).apply {
            text = label; textSize = 13.5f
            setPadding(16, 11, 16, 11); gravity = Gravity.CENTER
            setTextColor(textColor); background = resources.getDrawable(bgRes, null)
            typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setOnClickListener { onProviderChipClicked(provider, this) }
        }
    }

    private fun onProviderChipClicked(provider: ModelProvider, chip: TextView) {
        if (provider == currentProvider) return
        animateChipPress(chip)
        saveCurrentToMemory()
        currentProvider = provider
        prefs.saveString("current_provider", provider.name)
        refreshAllChipStyles()
        loadCurrentProviderUI()
        currentCarouselIdx = allProviders.indexOf(provider)
        scrollToCurrentChip()
    }

    private fun animateChipPress(view: View) {
        ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.08f, 1f).apply { duration = 200 }.start()
        ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.08f, 1f).apply { duration = 200 }.start()
    }

    private fun refreshAllChipStyles() {
        val chipContainer = binding.layoutProviderChips
        for (i in 0 until chipContainer.childCount) {
            val chip = chipContainer.getChildAt(i) as? TextView ?: continue
            val provider = allProviders.getOrNull(i) ?: continue
            val configured = providerConfigs.containsKey(provider.name)
            val current = provider == currentProvider
            val cfg = providerConfigs[provider.name]
            val modelCount = cfg?.modelCount ?: 0
            val extra = when {
                configured && modelCount > 1 -> " ·${modelCount}"
                configured -> " ✓"
                else -> ""
            }
            val label = "${provider.icon}  ${provider.displayName}$extra"
            chip.text = label
            val bgRes = if (current || configured) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal
            val textColor = when {
                current -> resources.getColor(R.color.bg_secondary, null)
                configured -> resources.getColor(R.color.bg_secondary, null)  // 深色文字在青色背景上可读
                else -> resources.getColor(R.color.text_secondary, null)
            }
            chip.setTextColor(textColor); chip.background = resources.getDrawable(bgRes, null)
            chip.typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        updateConfiguredCountBadge()
    }

    private fun updateConfiguredCountBadge() {
        val count = providerConfigs.size
        if (count > 0) {
            binding.tvConfiguredCount.visibility = View.VISIBLE
            binding.tvConfiguredCount.text = "已绑定 $count/${allProviders.size}"
        } else {
            binding.tvConfiguredCount.visibility = View.GONE
        }
    }

    // ===== 轮播 =====

    private fun startCarousel() {
        carouselRunnable = object : Runnable {
            override fun run() {
                currentCarouselIdx = (currentCarouselIdx + 1) % allProviders.size
                scrollToCurrentChip()
                carouselHandler.postDelayed(this, 3000)
            }
        }
        carouselHandler.postDelayed(carouselRunnable!!, 3000)
    }

    private fun scrollToCurrentChip() {
        val scrollView = binding.hsProviderChips
        val chipContainer = binding.layoutProviderChips
        val chip = chipContainer.getChildAt(currentCarouselIdx) ?: return
        val targetX = (chip.left - (scrollView.width - chip.width) / 2).coerceAtLeast(0)
        scrollView.smoothScrollTo(targetX, 0)
    }

    private fun pauseCarouselTemporarily() {
        carouselHandler.removeCallbacks(carouselRunnable ?: return)
        carouselHandler.removeCallbacks(resumeCarouselRunnable ?: return)
        resumeCarouselRunnable = Runnable { startCarousel() }
        carouselHandler.postDelayed(resumeCarouselRunnable!!, 5000)
    }

    // ===== 当前供应商 UI =====

    private fun saveCurrentToMemory() {
        val apiKey = binding.etApiKey.text.toString().trim()
        val baseUrl = binding.etBaseUrl.text.toString().trim()
        if (apiKey.isNotEmpty() || currentModels.isNotEmpty()) {
            providerConfigs[currentProvider.name] = ProviderConfig(
                provider = currentProvider,
                apiKey = apiKey,
                baseUrl = baseUrl.ifEmpty { currentProvider.defaultBaseUrl },
                modelName = currentModels.firstOrNull()?.modelName ?: currentProvider.defaultModel,
                enabled = true,
                models = currentModels.toList()
            )
        }
    }

    private fun loadCurrentProviderUI() {
        val savedCfg = providerConfigs[currentProvider.name]
        currentModels.clear()
        currentModels.addAll(savedCfg?.models ?: emptyList())

        binding.tvCurrentProvider.text = "${currentProvider.icon}  ${currentProvider.displayName} 配置"
        binding.etApiKey.setText(savedCfg?.apiKey ?: "")
        binding.etBaseUrl.setText(savedCfg?.baseUrl ?: currentProvider.defaultBaseUrl)

        availableModels.clear()
        binding.layoutModelList.removeAllViews()
        binding.tvModelSectionTitle.visibility = View.GONE
        binding.tvFetchStatus.text = ""
        binding.layoutFetchStatus.visibility = View.GONE
        binding.tvModelStatus.text = ""

        renderBoundModels()
    }

    // ===== 已绑定模型列表渲染 =====

    private fun renderBoundModels() {
        val container = binding.layoutBoundModels
        container.removeAllViews()

        if (currentModels.isEmpty()) {
            binding.tvBoundModelsTitle.visibility = View.GONE
            binding.tvNoBoundModels.visibility = View.VISIBLE
            return
        }

        binding.tvBoundModelsTitle.visibility = View.VISIBLE
        binding.tvBoundModelsTitle.text = "已绑定的模型 · ${currentModels.size} 个"
        binding.tvNoBoundModels.visibility = View.GONE

        for ((idx, model) in currentModels.withIndex()) {
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 10, 4, 10)
                background = resources.getDrawable(R.drawable.bg_model_card, null)
            }

            // 名称 + 标签
            val infoLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
            }

            val nameRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val nameTv = TextView(requireContext()).apply {
                text = model.modelName
                textSize = 13f; typeface = Typeface.MONOSPACE
                setTextColor(resources.getColor(R.color.text_primary, null))
                maxLines = 1
            }
            nameRow.addView(nameTv)

            if (model.displayLabel.isNotBlank()) {
                val labelTv = TextView(requireContext()).apply {
                    text = "  ${model.displayLabel}"
                    textSize = 11f
                    setTextColor(resources.getColor(R.color.text_hint, null))
                    maxLines = 1
                }
                nameRow.addView(labelTv)
            }
            infoLayout.addView(nameRow)

            // 用途标签
            val tagTv = TextView(requireContext()).apply {
                text = usedForLabel(model.usedFor)
                textSize = 10f
                background = resources.getDrawable(when (model.usedFor) {
                    "conversation" -> R.drawable.bg_chip_selected
                    "analysis" -> R.drawable.bg_chip_selected
                    "stt" -> R.drawable.bg_chip_selected
                    else -> R.drawable.bg_chip_normal
                }, null)
                setTextColor(resources.getColor(
                    when (model.usedFor) {
                        "conversation", "analysis", "stt" -> R.color.bg_secondary  // 深色字在青色底上
                        else -> R.color.text_primary  // 亮色字在深色底上
                    }, null))
                setPadding(6, 2, 6, 2)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 3 }
            }
            infoLayout.addView(tagTv)
            card.addView(infoLayout, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))

            // 编辑按钮
            val editBtn = TextView(requireContext()).apply {
                text = "✎"; textSize = 18f; setPadding(10, 4, 6, 4)
                setTextColor(resources.getColor(R.color.text_secondary, null))
                setOnClickListener { showModelDialog(model, idx) }
            }
            card.addView(editBtn)

            // ASR 测试按钮（仅对用途为 stt 的模型显示）
            if (model.usedFor == "stt") {
                val testBtn = TextView(requireContext()).apply {
                    text = "\uD83C\uDFA4"; textSize = 16f; setPadding(6, 4, 10, 4)
                    setTextColor(resources.getColor(R.color.accent_primary, null))
                    setOnClickListener { openAsrTest() }
                }
                card.addView(testBtn)
            }

            // 删除按钮
            val delBtn = TextView(requireContext()).apply {
                text = "✕"; textSize = 16f; setPadding(10, 4, 12, 4)
                setTextColor(resources.getColor(R.color.status_error, null))
                setOnClickListener {
                    currentModels.removeAt(idx)
                    renderBoundModels()
                    autoSaveProviderModels()
                }
            }
            card.addView(delBtn)

            container.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6 })
        }
    }

    private fun showModelDialog(
        existing: ModelBinding? = null,
        editIdx: Int = -1,
        prefillModelName: String = ""  // 从 API 列表点击时的预填模型名
    ) {
        val isEdit = existing != null
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_model_edit, null)
        val etModelName = dialogView.findViewById<EditText>(R.id.et_dialog_model_name)
        val etLabel = dialogView.findViewById<EditText>(R.id.et_dialog_label)
        val spinnerUsedFor = dialogView.findViewById<Spinner>(R.id.spinner_used_for)
        val tvClassifyHint = dialogView.findViewById<TextView>(R.id.tv_classify_hint)

        val usedForOptions = arrayOf("conversation（语音对话）", "analysis（画面分析）", "stt（语音转文字）", "all（通用）")
        val usedForValues = arrayOf("conversation", "analysis", "stt", "all")
        val spinnerAdapter = ArrayAdapter(requireContext(), R.layout.item_spinner_selected, usedForOptions)
        spinnerAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spinnerUsedFor.adapter = spinnerAdapter

        // 模型名称变化时自动检测分类
        val onModelNameChanged: (String) -> Unit = { name ->
            val detected = ModelClassifier.classify(name.trim())
            val idx = usedForValues.indexOf(detected).coerceAtLeast(0)
            spinnerUsedFor.setSelection(idx)
            tvClassifyHint?.apply {
                text = "自动检测: ${ModelClassifier.classifyLabel(name.trim())} — ${ModelClassifier.classifyReason(name.trim())}"
                visibility = if (name.trim().isNotEmpty()) View.VISIBLE else View.GONE
            }
        }

        if (existing != null) {
            etModelName.setText(existing.modelName)
            etLabel.setText(existing.displayLabel)
            val idx = usedForValues.indexOf(existing.usedFor).coerceAtLeast(0)
            spinnerUsedFor.setSelection(idx)
            onModelNameChanged(existing.modelName)
        } else if (prefillModelName.isNotEmpty()) {
            etModelName.setText(prefillModelName)
            etLabel.setText(ModelClassifier.classifyLabel(prefillModelName).replace(Regex("^[^\\p{L}]+"), ""))
            onModelNameChanged(prefillModelName)
        }

        // 监听输入变化实时检测
        etModelName.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                onModelNameChanged(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isEdit) "编辑模型" else "添加模型")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val name = etModelName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), "请输入模型名称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val label = etLabel.text.toString().trim()
                val usedFor = usedForValues[spinnerUsedFor.selectedItemPosition]
                val binding = ModelBinding(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString().take(8),
                    modelName = name,
                    displayLabel = label,
                    usedFor = usedFor
                )
                if (isEdit && editIdx >= 0) {
                    currentModels[editIdx] = binding
                } else {
                    currentModels.add(binding)
                }
                renderBoundModels()
                // 自动保存配置
                autoSaveProviderModels()
            }
            .setNegativeButton("取消", null)
            .show()

        // 设置对话框样式
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_card)
    }

    // ===== 可用模型列表（获取到的，点击添加到绑定）=====

    private fun renderModelList(models: List<String>) {
        availableModels.clear()
        availableModels.addAll(models)
        val container = binding.layoutModelList
        container.removeAllViews()
        if (models.isEmpty()) return

        binding.tvModelSectionTitle.visibility = View.VISIBLE
        binding.tvModelSectionTitle.text = "可用模型 · ${models.size} 个（点击弹出对话框，自动检测类型）"

        val boundNames = currentModels.map { it.modelName }.toSet()

        for (model in models) {
            val alreadyBound = model in boundNames
            val detectedType = ModelClassifier.classify(model)

            val card = layoutInflater.inflate(R.layout.item_model_card, container, false)
            val viewAccent = card.findViewById<View>(R.id.view_accent)
            val tvModelName = card.findViewById<TextView>(R.id.tv_model_name)
            val tvCheck = card.findViewById<TextView>(R.id.tv_check)

            // 模型名 + 检测类型标签
            val typeEmoji = when (detectedType) {
                "stt" -> " 🎙️"
                "analysis" -> " 🔬"
                else -> ""
            }
            tvModelName.text = model + typeEmoji

            // 根据检测类型设置左侧彩色条
            val accentColor = when (detectedType) {
                "stt" -> resources.getColor(R.color.status_warning, null)
                "analysis" -> resources.getColor(R.color.accent_primary, null)
                else -> resources.getColor(R.color.divider, null)
            }

            if (alreadyBound) {
                card.background = resources.getDrawable(R.drawable.bg_model_card_selected, null)
                viewAccent.setBackgroundColor(resources.getColor(R.color.status_success, null))
                tvCheck.visibility = View.VISIBLE
                tvCheck.text = "✓"
            } else {
                card.background = resources.getDrawable(R.drawable.bg_model_card, null)
                viewAccent.setBackgroundColor(accentColor)
                tvCheck.visibility = View.GONE
            }

            card.setOnClickListener {
                if (alreadyBound) {
                    Toast.makeText(requireContext(), "该模型已在绑定列表中", Toast.LENGTH_SHORT).show()
                } else {
                    // 弹出对话框，预填模型名并自动分类
                    showModelDialog(prefillModelName = model)
                }
            }
            container.addView(card)
        }
    }

    // ===== 监听器 =====

    private fun setupListeners() {
        binding.btnFetchModels.setOnClickListener { fetchModelList() }
        binding.btnAddModel.setOnClickListener { showModelDialog() }
        binding.btnTestModel.setOnClickListener { testConnection() }
        binding.btnSaveConfig.setOnClickListener { saveConfig() }
        binding.hsProviderChips.setOnTouchListener { _, _ ->
            pauseCarouselTemporarily()
            false
        }
    }

    // ===== 获取模型列表 =====

    private fun fetchModelList() {
        val apiKey = binding.etApiKey.text.toString().trim()
        val baseUrl = binding.etBaseUrl.text.toString().trim()
        if (apiKey.isEmpty() && !currentProvider.isLocal) {
            Toast.makeText(requireContext(), "请先填写 API Key", Toast.LENGTH_SHORT).show(); return
        }
        if (baseUrl.isEmpty()) {
            Toast.makeText(requireContext(), "请填写接口地址", Toast.LENGTH_SHORT).show(); return
        }
        binding.layoutFetchStatus.visibility = View.VISIBLE
        binding.pbFetch.visibility = View.VISIBLE
        binding.tvFetchStatus.text = "正在获取模型列表..."
        binding.tvFetchStatus.setTextColor(resources.getColor(R.color.text_secondary, null))

        lifecycleScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    fetchModelsFromApi(baseUrl.trimEnd('/'), apiKey)
                }
                if (models.isNotEmpty()) {
                    renderModelList(models)
                    binding.tvFetchStatus.text = "✓ 获取到 ${models.size} 个模型，点击可添加到绑定列表"
                    binding.tvFetchStatus.setTextColor(resources.getColor(R.color.status_success, null))
                } else {
                    binding.tvFetchStatus.text = "未获取到模型列表"
                    binding.tvFetchStatus.setTextColor(resources.getColor(R.color.status_error, null))
                }
            } catch (e: Exception) {
                binding.tvFetchStatus.text = "获取失败: ${e.message}"
                binding.tvFetchStatus.setTextColor(resources.getColor(R.color.status_error, null))
            } finally {
                binding.pbFetch.visibility = View.GONE
            }
        }
    }

    /**
     * 通过 OpenAI 兼容 API 获取模型列表。
     * 自动拼接 {baseUrl}/models，使用 Bearer Token 鉴权。
     */
    private fun fetchModelsFromApi(baseUrl: String, apiKey: String): List<String> {
        val fullUrl = "${baseUrl.trimEnd('/')}/models"
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val reqBuilder = Request.Builder().url(fullUrl).get()
        if (apiKey.isNotEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        val response = client.newCall(reqBuilder.build()).execute()
        val body = response.body?.string() ?: ""
        val code = response.code

        if (!response.isSuccessful) {
            val detail = try {
                JSONObject(body).optString("error", JSONObject(body).optString("message", body.take(200)))
            } catch (_: Exception) { body.take(200) }
            throw RuntimeException(when (code) {
                401 -> "API Key 无效或已过期"
                403 -> "无权访问该接口"
                404 -> "接口地址可能不正确：$fullUrl"
                else -> "HTTP $code: $detail"
            })
        }

        return parseModelList(body)
    }

    /**
     * 解析 OpenAI 兼容 /models 返回的 JSON。
     * 标准格式：{"object":"list","data":[{"id":"model-name",...},...]}
     * 兼容格式：{"models":[{"id":"model-name",...},...]}
     * 也兼容直接数组格式：[{"id":"model-name",...},...]
     */
    private fun parseModelList(json: String): List<String> {
        val models = mutableListOf<String>()
        try {
            val root = JSONObject(json)
            // 标准 OpenAI 格式："data" 数组
            var arr = root.optJSONArray("data")
            // 某些供应商用 "models" 键
            if (arr == null || arr.length() == 0) {
                arr = root.optJSONArray("models")
            }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id", "")
                    if (id.isNotEmpty()) models.add(id)
                }
            }
        } catch (_: Exception) {
            // 如果不是 JSONObject，尝试解析为 JSONArray
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id", "")
                    if (id.isNotEmpty()) models.add(id)
                }
            } catch (_: Exception) {
                // 回退：搜索所有带 "id" 的字符串值（兼容非标准格式）
                var idx = json.indexOf("\"id\"")
                var safety = 0
                while (idx >= 0 && safety < 500) {
                    safety++
                    val colonIdx = json.indexOf(':', idx)
                    if (colonIdx < 0) break
                    val startQ = json.indexOf('"', colonIdx + 1)
                    if (startQ < 0) break
                    val endQ = json.indexOf('"', startQ + 1)
                    if (endQ < 0) break
                    val name = json.substring(startQ + 1, endQ)
                    if (name.isNotEmpty() && name !in models) models.add(name)
                    idx = json.indexOf("\"id\"", endQ + 1)
                }
            }
        }
        return models
    }

    // ===== 测试连接 =====

    private fun testConnection() {
        val apiKey = binding.etApiKey.text.toString().trim()
        val baseUrl = binding.etBaseUrl.text.toString().trim()
        if (baseUrl.isEmpty()) {
            Toast.makeText(requireContext(), "请填写接口地址", Toast.LENGTH_SHORT).show(); return
        }
        binding.tvModelStatus.text = "测试中..."
        binding.tvModelStatus.setTextColor(resources.getColor(R.color.text_secondary, null))

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val tester = ModelConnectionTester()
                tester.testOpenAICompatible(baseUrl.trimEnd('/'), apiKey)
            }
            if (result.success) {
                binding.tvModelStatus.text = "✓ 连接成功 (${result.latencyMs}ms)"
                binding.tvModelStatus.setTextColor(resources.getColor(R.color.status_success, null))
                if (result.availableModels.isNotEmpty()) renderModelList(result.availableModels)
            } else {
                binding.tvModelStatus.text = "✗ 连接失败: ${result.message}"
                binding.tvModelStatus.setTextColor(resources.getColor(R.color.status_error, null))
            }
        }
    }

    // ===== 保存配置 =====

    /**
     * 自动保存当前供应商的模型配置（添加/编辑模型后自动调用）
     */
    private fun autoSaveProviderModels() {
        val apiKey = binding.etApiKey.text.toString().trim()
        val baseUrl = binding.etBaseUrl.text.toString().trim()

        val newCfg = ProviderConfig(
            provider = currentProvider,
            apiKey = apiKey,
            baseUrl = baseUrl.ifEmpty { currentProvider.defaultBaseUrl },
            modelName = currentModels.firstOrNull()?.modelName ?: currentProvider.defaultModel,
            enabled = true,
            models = currentModels.toList()
        )

        providerConfigs[currentProvider.name] = newCfg

        // 写 MMKV
        prefs.saveString("provider_${currentProvider.name}_api_key", apiKey)
        prefs.saveString("provider_${currentProvider.name}_base_url", baseUrl.ifEmpty { currentProvider.defaultBaseUrl })
        prefs.saveString("provider_${currentProvider.name}_model", currentModels.firstOrNull()?.modelName ?: "")
        prefs.saveString("provider_${currentProvider.name}_enabled", "true")
        prefs.saveString("current_provider", currentProvider.name)
        // 保存多模型 JSON
        val modelsJson = org.json.JSONArray()
        currentModels.forEach { m ->
            modelsJson.put(org.json.JSONObject().apply {
                put("id", m.id); put("modelName", m.modelName)
                put("displayLabel", m.displayLabel); put("usedFor", m.usedFor)
            })
        }
        prefs.saveString("provider_${currentProvider.name}_models", modelsJson.toString())

        // 同时更新 GameConfig
        val updatedConfig = config.copy(
            currentProvider = currentProvider,
            cloudProviderConfigs = providerConfigs.toMap()
        )
        prefs.saveConfig(updatedConfig)

        refreshAllChipStyles()
        refreshConfiguredProvidersOverview()
    }

    private fun saveConfig() {
        val apiKey = binding.etApiKey.text.toString().trim()
        val baseUrl = binding.etBaseUrl.text.toString().trim()

        if (currentModels.isEmpty()) {
            Toast.makeText(requireContext(), "请至少添加一个模型（获取列表或手动添加）", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查模型用途覆盖
        val hasConversation = currentModels.any { it.matches("conversation") || it.matches("all") }
        val hasAnalysis = currentModels.any { it.matches("analysis") || it.matches("all") }
        val hasStt = currentModels.any { it.matches("stt") }

        val warnings = mutableListOf<String>()
        if (!hasConversation) warnings.add("未绑定\"语音对话\"用途的模型，语音对话功能将不可用")
        if (!hasAnalysis) warnings.add("未绑定\"画面分析\"用途的模型，画面分析将使用对话模型代替")
        if (!hasStt && currentProvider.defaultSttModel.isBlank()) {
            warnings.add("当前供应商不支持语音转文字且未绑定STT模型，语音对话将无法使用")
        }

        // 使用自动保存方法
        autoSaveProviderModels()

        refreshAllChipStyles()
        refreshConfiguredProvidersOverview()

        val savedMsg = "✓ ${currentProvider.displayName} 已保存 ${currentModels.size} 个模型"
        if (warnings.isNotEmpty()) {
            Toast.makeText(requireContext(), "$savedMsg\n⚠️ ${warnings.first()}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(requireContext(), savedMsg, Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 已配置供应商概览 =====

    private fun refreshConfiguredProvidersOverview() {
        val container = binding.layoutConfiguredProviders
        container.removeAllViews()
        if (providerConfigs.isEmpty()) {
            binding.tvNoConfigured.visibility = View.VISIBLE; return
        }
        binding.tvNoConfigured.visibility = View.GONE

        for ((name, cfg) in providerConfigs) {
            val provider = ModelProvider.fromName(name)
            val modelCount = cfg.modelCount
            val modelsSummary = if (cfg.models.isNotEmpty()) {
                cfg.models.joinToString(" · ") { m ->
                    val tag = when (m.usedFor) { "conversation" -> "🗣" ; "analysis" -> "🔍" ; "stt" -> "🎤" ; else -> "" }
                    "$tag${m.modelName}"
                }
            } else cfg.modelName

            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
                background = resources.getDrawable(R.drawable.bg_provider_card, null)
                setOnClickListener {
                    saveCurrentToMemory()
                    currentProvider = provider
                    prefs.saveString("current_provider", provider.name)
                    currentCarouselIdx = allProviders.indexOf(provider)
                    refreshAllChipStyles()
                    loadCurrentProviderUI()
                    scrollToCurrentChip()
                }
            }

            val nameTv = TextView(requireContext()).apply {
                text = "${provider.icon} ${provider.displayName}  · ${modelCount}个模型"
                textSize = 13f; setTextColor(resources.getColor(R.color.text_primary, null))
                typeface = Typeface.DEFAULT_BOLD
            }
            card.addView(nameTv)

            val modelTv = TextView(requireContext()).apply {
                text = modelsSummary
                textSize = 11f; setTextColor(resources.getColor(R.color.accent_primary, null))
                maxLines = 3; layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
            }
            card.addView(modelTv)

            container.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 })
        }
    }

    private fun usedForLabel(usedFor: String): String = when (usedFor) {
        "conversation" -> "\u8bed\u97f3\u5bf9\u8bdd"
        "analysis" -> "\u753b\u9762\u5206\u6790"
        "stt" -> "\u8bed\u97f3\u8f6c\u6587\u5b57"
        else -> "\u901a\u7528"
    }

    // ===== ASR \u8bed\u97f3\u8f6c\u6587\u5b57\u6d4b\u8bd5 =====

    private fun openAsrTest() {
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else {
            showAsrTestDialog()
        }
    }

    /**
     * \u663e\u793a ASR \u8bed\u97f3\u8f6c\u6587\u5b57\u6d4b\u8bd5\u6d6e\u52a8\u5bf9\u8bdd\u6846\u3002
     *
     * \u3010\u6279\u91cf\u6587\u4ef6\u6a21\u5f0f\u3011
     *   - \u957f\u6309\u5f55\u97f3 \u2192 \u6301\u7eed\u5f55\u5236 16kHz/mono/16bit PCM
     *   - \u677e\u624b \u2192 \u5b8c\u6574 PCM \u8f6c WAV \u2192 \u5355\u6b21 HTTP POST SiliconFlow
     *   - \u6a21\u578b\uff1aFunAudioLLM/SenseVoiceSmall\uff08\u6c38\u4e45\u514d\u8d39\uff09
     *   - \u4e0d\u652f\u6301\u5b9e\u65f6\u8fb9\u5f55\u8fb9\u51fa\u5b57\uff1a\u4ec5\u5f55\u97f3\u7ed3\u675f\u540e\u4e00\u6b21\u6027\u8bc6\u522b
     *
     * \u3010\u539f TeleAI/TeleSpeechASR \u62a5\u9519\u539f\u56e0\u3011
     *   \u8be5\u6a21\u578b\u4ec5\u652f\u6301\u6279\u91cf\u6587\u4ef6\u4e0a\u4f20\uff0c\u4e0d\u652f\u6301 WebSocket \u6d41\u5f0f\u63a8\u6d41\u3002
     *   \u4e4b\u524d\u5c1d\u8bd5\u7528 HTTP \u77ed\u5206\u7247\u6a21\u62df\u6d41\u5f0f\u5bfc\u81f4\u5206\u7247\u95f4\u4e22\u5b57/\u65ad\u53e5\u4e0d\u5b8c\u6574\u3002
     *   \u73b0\u6539\u7528\u5b8c\u6574\u6587\u4ef6\u4e0a\u4f20\u65b9\u6848\uff0c\u4e00\u6b21\u5f55\u97f3\u4e00\u6b21\u4e0a\u4f20\u3002
     */
    private fun showAsrTestDialog() {
        dismissAsrTestDialog()

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_asr_test, null)

        val btnClose = dialogView.findViewById<TextView>(R.id.btn_close)
        val btnRecord = dialogView.findViewById<TextView>(R.id.btn_record)
        val btnClear = dialogView.findViewById<TextView>(R.id.btn_clear)
        val btnRetry = dialogView.findViewById<TextView>(R.id.btn_retry)
        val tvResult = dialogView.findViewById<TextView>(R.id.tv_result)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tv_status)
        val pbLoading = dialogView.findViewById<ProgressBar>(R.id.pb_loading)
        val dotStatus = dialogView.findViewById<View>(R.id.dot_status)
        val tvHint = dialogView.findViewById<TextView>(R.id.tv_hint)

        // \u5f55\u97f3\u76f8\u5173\u72b6\u6001
        var audioRecord: AudioRecord? = null
        var isRecording = false
        var recordingThread: Thread? = null
        val accumulatedAudio = ByteArrayOutputStream()

        fun updateStatus(text: String, state: String) {
            tvStatus.text = text
            dotStatus.setBackgroundColor(
                when (state) {
                    "ready" -> ContextCompat.getColor(requireContext(), R.color.text_hint)
                    "recording" -> ContextCompat.getColor(requireContext(), R.color.status_success)
                    "loading" -> ContextCompat.getColor(requireContext(), R.color.accent_primary)
                    "error" -> ContextCompat.getColor(requireContext(), R.color.status_error)
                    else -> ContextCompat.getColor(requireContext(), R.color.text_hint)
                }
            )
        }

        fun stopAudioRecord() {
            if (!isRecording) return
            isRecording = false
            recordingThread?.interrupt()
            recordingThread = null
            try { audioRecord?.stop() } catch (_: Exception) {}
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
        }

        fun pcmToWav(pcmData: ByteArray): ByteArray {
            val sampleRate = 16000
            val totalDataLen = pcmData.size + 36
            val byteRate = sampleRate * 2
            return ByteArrayOutputStream(pcmData.size + 44).apply {
                val buf = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.put("RIFF".toByteArray()); buf.putInt(totalDataLen)
                buf.put("WAVE".toByteArray()); buf.put("fmt ".toByteArray())
                buf.putInt(16); buf.putShort(1); buf.putShort(1)
                buf.putInt(sampleRate); buf.putInt(byteRate)
                buf.putShort(2); buf.putShort(16)
                buf.put("data".toByteArray()); buf.putInt(pcmData.size)
                write(buf.array()); write(pcmData)
            }.toByteArray()
        }

        /** 获取 STT 专用 API Key — 优先从跨供应商 STT 配置获取，而非当前查看的供应商 */
        fun getSttApiKey(): String {
            // 优先：跨所有供应商搜索 STT 绑定的模型
            val sttCfg = prefs.getSttModelConfig()
            if (sttCfg != null && sttCfg.apiKey.isNotBlank()) return sttCfg.apiKey
            // 兜底：当前查看的供应商（用户可能就在看 STT 供应商）
            val savedCfg = providerConfigs[currentProvider.name]
            if (!savedCfg?.apiKey.isNullOrBlank()) return savedCfg!!.apiKey
            return ""
        }

        /**
         * \u4e0a\u4f20\u5b8c\u6574 WAV \u97f3\u9891\u5230 SiliconFlow\u3002
         * \u5355\u6b21 HTTP POST \u4e0a\u4f20\uff0c\u5b8c\u6574\u8bc6\u522b\u3002
         */
        fun setError(msg: String) {
            pbLoading.visibility = View.GONE
            btnRecord.text = "\u6309\u4f4f\u5f55\u97f3"
            btnRecord.isEnabled = true
            when {
                msg.contains("401") -> tvResult.text = "\u274c API Key \u65e0\u6548\u6216\u8fc7\u671f\n\u8bf7\u5728\u6a21\u578b\u914d\u7f6e\u4e2d\u66f4\u65b0 SiliconFlow API Key"
                msg.contains("timeout") || msg.contains("\u7f51\u7edc") -> tvResult.text = "\u274c \u7f51\u7edc\u8fde\u63a5\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u540e\u91cd\u8bd5"
                msg.contains("\u683c\u5f0f") -> tvResult.text = "\u274c $msg"
                else -> tvResult.text = "\u274c \u8bc6\u522b\u5931\u8d25: $msg"
            }
            tvResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error))
            updateStatus("\u8bc6\u522b\u5931\u8d25", "error")
            tvHint.text = "\u957f\u6309\u6309\u94ae\u91cd\u65b0\u5f55\u97f3"
            btnRetry.visibility = View.VISIBLE
        }

        fun setSuccess(text: String) {
            pbLoading.visibility = View.GONE
            btnRecord.text = "\u6309\u4f4f\u5f55\u97f3"
            btnRecord.isEnabled = true
            if (text.isNotBlank()) {
                tvResult.text = text.trim()
                tvResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                updateStatus("\u2713 \u8bc6\u522b\u5b8c\u6210", "ready")
                tvHint.text = "\u6a21\u578b: FunAudioLLM/SenseVoiceSmall\uff08\u6c38\u4e45\u514d\u8d39\uff09| \u6279\u91cf\u6587\u4ef6\u8f6c\u5199"
                btnRetry.visibility = View.GONE
            } else {
                tvResult.text = "\u26a0\ufe0f \u672a\u8bc6\u522b\u5230\u8bed\u97f3\u5185\u5bb9\uff0c\u8bf7\u5927\u58f0\u6e05\u6670\u5730\u8bf4\u4e00\u53e5\u8bdd"
                tvResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_warning))
                updateStatus("\u5b8c\u6210 \u2014 \u65e0\u5185\u5bb9", "ready")
                tvHint.text = "\u5f55\u97f3\u540e\u4e00\u6b21\u6027\u4e0a\u4f20\u8bc6\u522b\uff0c\u975e\u5b9e\u65f6\u8fb9\u5f55\u8fb9\u51fa\u5b57"
                btnRetry.visibility = View.VISIBLE
            }
        }

        fun uploadAudio(wavData: ByteArray) {
            pbLoading.visibility = View.VISIBLE
            btnRecord.isEnabled = false
            btnRecord.text = "\u8bc6\u522b\u4e2d..."
            updateStatus("\u6b63\u5728\u8bc6\u522b...", "loading")

            lifecycleScope.launch {
                try {
                    val apiKey = getSttApiKey()
                    if (apiKey.isBlank()) {
                        withContext(Dispatchers.Main) {
                            setError("\u672a\u914d\u7f6e API Key\uff0c\u8bf7\u5728\u6a21\u578b\u914d\u7f6e\u4e2d\u7ed1\u5b9a")
                        }
                        return@launch
                    }

                    val text = withContext(Dispatchers.IO) {
                        val mediaType = "audio/wav".toMediaType()
                        // SiliconFlow /v1/audio/transcriptions 仅接受 file + model 两个参数
                        val requestBody = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("file", "recording.wav", wavData.toRequestBody(mediaType))
                            .addFormDataPart("model", "FunAudioLLM/SenseVoiceSmall")
                            .build()

                        val request = Request.Builder()
                            .url("https://api.siliconflow.cn/v1/audio/transcriptions")
                            .post(requestBody)
                            .addHeader("Authorization", "Bearer $apiKey")
                            .addHeader("Accept", "application/json")
                            .build()

                        val response = OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .build()
                            .newCall(request).execute()

                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            val result = JSONObject(body).optString("text", "")
                            response.close()
                            result
                        } else {
                            val code = response.code
                            val errBody = response.body?.string() ?: ""
                            response.close()
                            val detail = try { JSONObject(errBody).optString("message", errBody.take(100)) } catch (_: Exception) { errBody.take(100) }
                            val msg = when (code) {
                                400 -> if (detail.contains("file", ignoreCase = true)) "\u97f3\u9891\u683c\u5f0f\u9519\u8bef\uff1a\u9700 16kHz \u5355\u58f0\u9053 WAV" else "\u53c2\u6570\u9519\u8bef: $detail"
                                401 -> "API Key \u65e0\u6548\u6216\u5df2\u8fc7\u671f (401)"
                                403 -> "\u65e0\u6743\u8bbf\u95ee (403)"
                                404 -> "\u6a21\u578b FunAudioLLM/SenseVoiceSmall \u4e0d\u5b58\u5728 (404)"
                                429 -> "\u8bf7\u6c42\u9891\u7387\u8fc7\u9ad8 (429)"
                                in 500..599 -> "\u670d\u52a1\u5668\u9519\u8bef ($code)"
                                else -> "HTTP $code: $detail"
                            }
                            throw RuntimeException("\u274c $msg")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        setSuccess(text)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ASR_TEST", "\u4e0a\u4f20\u5931\u8d25", e)
                    withContext(Dispatchers.Main) {
                        setError(e.message ?: "\u672a\u77e5\u9519\u8bef")
                    }
                }
            }
        }

        // \u957f\u6309\u5f55\u97f3 \u2192 \u677e\u624b\u4e0a\u4f20\u8bc6\u522b
        btnRecord.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isRecording = true
                    accumulatedAudio.reset()
                    btnRecord.text = "\u5f55\u97f3\u4e2d..."
                    btnRecord.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.status_error))
                    updateStatus("\uD83C\uDF99 \u6b63\u5728\u5f55\u97f3...", "recording")
                    tvHint.text = "\u677e\u624b\u540e\u81ea\u52a8\u4e0a\u4f20\u8bc6\u522b\uff08\u6279\u91cf\u6a21\u5f0f\uff0c\u975e\u5b9e\u65f6\uff09"
                    tvResult.text = "\u5f55\u97f3\u4e2d\uff0c\u677e\u624b\u540e\u81ea\u52a8\u8bc6\u522b..."
                    tvResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                    btnRetry.visibility = View.GONE

                    try {
                        val bufSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
                        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                        audioRecord?.startRecording()
                        recordingThread = Thread {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                            val buffer = ByteArray(bufSize)
                            while (isRecording) {
                                val read = audioRecord?.read(buffer, 0, bufSize) ?: -1
                                if (read > 0) synchronized(accumulatedAudio) { accumulatedAudio.write(buffer, 0, read) }
                            }
                        }.apply { start() }
                    } catch (e: SecurityException) {
                        updateStatus("\u7f3a\u5c11\u5f55\u97f3\u6743\u9650", "error")
                        tvResult.text = "\u274c \u7f3a\u5c11\u5f55\u97f3\u6743\u9650"
                        tvResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error))
                        isRecording = false
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isRecording) {
                        btnRecord.text = "\u6309\u4f4f\u5f55\u97f3"
                        btnRecord.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.accent_primary))
                        return@setOnTouchListener true
                    }
                    stopAudioRecord()

                    val audioData: ByteArray
                    synchronized(accumulatedAudio) { audioData = accumulatedAudio.toByteArray() }

                    if (audioData.size < 16000 / 2) {
                        btnRecord.text = "\u6309\u4f4f\u5f55\u97f3"
                        btnRecord.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.accent_primary))
                        tvResult.text = "\u26a0\ufe0f \u5f55\u97f3\u592a\u77ed (${audioData.size / 32}ms)\uff0c\u8bf7\u81f3\u5c11\u8bf4 0.5 \u79d2"
                        tvResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_warning))
                        updateStatus("\u5f55\u97f3\u592a\u77ed", "ready")
                        tvHint.text = "\u5f55\u97f3\u540e\u4e00\u6b21\u6027\u4e0a\u4f20\u8bc6\u522b"
                        return@setOnTouchListener true
                    }

                    val wavData = pcmToWav(audioData)
                    uploadAudio(wavData)
                    true
                }

                else -> false
            }
        }

        btnClear.setOnClickListener {
            tvResult.text = "\u8bc6\u522b\u7ed3\u679c\u5c06\u5728\u8fd9\u91cc\u663e\u793a..."
            tvResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
            updateStatus("\u5df2\u6e05\u7a7a \u2014 \u957f\u6309\u6309\u94ae\u5f00\u59cb\u5f55\u97f3", "ready")
            tvHint.text = "\u6a21\u578b: FunAudioLLM/SenseVoiceSmall\uff08\u6c38\u4e45\u514d\u8d39\uff09| \u6279\u91cf\u6587\u4ef6\u8f6c\u5199"
            btnRetry.visibility = View.GONE
        }

        btnRetry.setOnClickListener {
            tvResult.text = "\u8bc6\u522b\u7ed3\u679c\u5c06\u5728\u8fd9\u91cc\u663e\u793a..."
            tvResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
            updateStatus("\u5c31\u7eea \u2014 \u957f\u6309\u6309\u94ae\u5f00\u59cb\u5f55\u97f3", "ready")
            btnRetry.visibility = View.GONE
        }

        btnClose.setOnClickListener { dismissAsrTestDialog() }
        dialogView.setOnClickListener { /* no-op */ }
        dialogView.findViewById<View>(R.id.card_root)?.setOnClickListener { /* no-op */ }

        val dialog = Dialog(requireContext(), android.R.style.Theme_Translucent_NoTitleBar).apply {
            setContentView(dialogView)
            setCancelable(true)
            setOnCancelListener { stopAudioRecord(); dismissAsrTestDialog() }
            window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawableResource(android.R.color.transparent)
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            }
        }

        asrTestDialog = dialog
        dialog.show()
    }

    private fun dismissAsrTestDialog() {
        asrTestDialog?.dismiss()
        asrTestDialog = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        carouselHandler.removeCallbacksAndMessages(null)
        _binding = null
    }
}
