package com.gameai.ui.fragments

import android.animation.ObjectAnimator
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
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
import java.net.HttpURLConnection
import java.net.URL

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
                configured -> resources.getColor(R.color.accent_primary, null)
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
                setTextColor(resources.getColor(R.color.bg_primary, null))
                background = resources.getDrawable(when (model.usedFor) {
                    "conversation" -> R.drawable.bg_chip_selected
                    "analysis" -> R.drawable.bg_chip_selected
                    "stt" -> R.drawable.bg_chip_selected
                    else -> R.drawable.bg_chip_normal
                }, null)
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

            // 删除按钮
            val delBtn = TextView(requireContext()).apply {
                text = "✕"; textSize = 16f; setPadding(10, 4, 12, 4)
                setTextColor(resources.getColor(R.color.status_error, null))
                setOnClickListener {
                    currentModels.removeAt(idx)
                    renderBoundModels()
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

    private fun fetchModelsFromApi(baseUrl: String, apiKey: String): List<String> {
        val url = URL("$baseUrl/models")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        return try {
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseModelList(response)
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }

    private fun parseModelList(json: String): List<String> {
        val models = mutableListOf<String>()
        var idx = json.indexOf("\"data\"")
        if (idx < 0) idx = json.indexOf("\"models\"")
        if (idx < 0) return models
        var remaining = json.substring(idx)
        var safety = 0
        while (safety < 500) {
            safety++
            val idIdx = remaining.indexOf("\"id\"") ?: break
            if (idIdx < 0) break
            val colonIdx = remaining.indexOf(':', idIdx) ?: break
            if (colonIdx < 0) break
            val startQuote = remaining.indexOf('"', colonIdx + 1) ?: break
            if (startQuote < 0) break
            val endQuote = remaining.indexOf('"', startQuote + 1) ?: break
            if (endQuote < 0) break
            val modelName = remaining.substring(startQuote + 1, endQuote)
            if (modelName.isNotEmpty() && modelName.length > 1 && !modelName.contains(":")) {
                models.add(modelName)
            }
            remaining = remaining.substring(endQuote + 1)
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

        val newCfg = ProviderConfig(
            provider = currentProvider,
            apiKey = apiKey,
            baseUrl = baseUrl.ifEmpty { currentProvider.defaultBaseUrl },
            modelName = currentModels.first().modelName,
            enabled = true,
            models = currentModels.toList()
        )

        providerConfigs[currentProvider.name] = newCfg

        // 写 MMKV（直接写，绕过 GameConfig 延迟问题）
        prefs.saveString("provider_${currentProvider.name}_api_key", apiKey)
        prefs.saveString("provider_${currentProvider.name}_base_url", baseUrl.ifEmpty { currentProvider.defaultBaseUrl })
        prefs.saveString("provider_${currentProvider.name}_model", currentModels.first().modelName)
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
        "conversation" -> "语音对话"
        "analysis" -> "画面分析"
        "stt" -> "语音转文字"
        else -> "通用"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        carouselHandler.removeCallbacksAndMessages(null)
        _binding = null
    }
}
