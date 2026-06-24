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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gameai.R
import com.gameai.databinding.FragmentModelsBinding
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

    // 每个供应商独立配置
    private val providerConfigs = mutableMapOf<String, ProviderConfig>()
    private var currentProvider = ModelProvider.OPENAI
    private var availableModels = mutableListOf<String>()

    private val allProviders = listOf(
        ModelProvider.OPENAI, ModelProvider.DEEPSEEK, ModelProvider.QWEN,
        ModelProvider.ERNIE, ModelProvider.ZHIPU,
        ModelProvider.LOCAL_OLLAMA, ModelProvider.LOCAL_VLLM, ModelProvider.LOCAL_LM_STUDIO,
        ModelProvider.CUSTOM
    )

    // 轮播相关
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
        startCarousel()
    }

    // ===== 加载所有已保存配置 =====

    private fun loadAllProviderConfigs() {
        providerConfigs.clear()
        val freshConfig = prefs.loadConfig()
        for (provider in allProviders) {
            val saved = freshConfig.cloudProviderConfigs[provider.name]
            if (saved != null) {
                providerConfigs[provider.name] = saved
            }
        }
        currentProvider = ModelProvider.fromName(prefs.getString("current_provider", "OPENAI"))
    }

    // ===== 供应商 Chips（图标 + 动画 + 轮播）=====

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
        val label = "${provider.icon}  ${provider.displayName}${if (configured) " ✓" else ""}"

        val bgRes = when {
            current -> R.drawable.bg_chip_selected
            configured -> R.drawable.bg_chip_selected
            else -> R.drawable.bg_chip_normal
        }

        val textColor = when {
            current -> resources.getColor(R.color.bg_secondary, null)
            configured -> resources.getColor(R.color.accent_primary, null)
            else -> resources.getColor(R.color.text_secondary, null)
        }

        return TextView(requireContext()).apply {
            text = label
            textSize = 13.5f
            setPadding(16, 11, 16, 11)
            gravity = Gravity.CENTER
            setTextColor(textColor)
            background = resources.getDrawable(bgRes, null)
            typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setOnClickListener { onProviderChipClicked(provider, this) }
        }
    }

    private fun onProviderChipClicked(provider: ModelProvider, chip: TextView) {
        if (provider == currentProvider) return

        // 动画缩放
        animateChipPress(chip)

        // 保存当前编辑内容
        saveCurrentToMemory()

        currentProvider = provider
        prefs.saveString("current_provider", provider.name)
        refreshAllChipStyles()
        loadCurrentProviderUI()

        // 滚动到当前选中
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

            val label = "${provider.icon}  ${provider.displayName}${if (configured) " ✓" else ""}"
            chip.text = label

            val bgRes = when {
                current -> R.drawable.bg_chip_selected
                configured -> R.drawable.bg_chip_selected
                else -> R.drawable.bg_chip_normal
            }
            val textColor = when {
                current -> resources.getColor(R.color.bg_secondary, null)
                configured -> resources.getColor(R.color.accent_primary, null)
                else -> resources.getColor(R.color.text_secondary, null)
            }

            chip.setTextColor(textColor)
            chip.background = resources.getDrawable(bgRes, null)
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

    // ===== 轮播效果 =====

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

    // ===== 当前供应商 UI 加载 =====

    private fun saveCurrentToMemory() {
        val apiKey = binding.etApiKey.text.toString().trim()
        val baseUrl = binding.etBaseUrl.text.toString().trim()
        val model = binding.etModelName.text.toString().trim()

        if (apiKey.isNotEmpty() || model.isNotEmpty()) {
            providerConfigs[currentProvider.name] = ProviderConfig(
                provider = currentProvider,
                apiKey = apiKey,
                baseUrl = baseUrl.ifEmpty { currentProvider.defaultBaseUrl },
                modelName = model.ifEmpty { currentProvider.defaultModel },
                enabled = true
            )
        }
    }

    private fun loadCurrentProviderUI() {
        val savedCfg = providerConfigs[currentProvider.name]

        binding.tvCurrentProvider.text = "${currentProvider.icon}  ${currentProvider.displayName} 配置"

        // API Key — 始终显示
        binding.etApiKey.setText(savedCfg?.apiKey ?: "")

        // Base URL
        binding.etBaseUrl.setText(savedCfg?.baseUrl ?: currentProvider.defaultBaseUrl)

        // 模型名输入框 — 始终显示
        binding.etModelName.setText(savedCfg?.modelName ?: "")

        // 清空模型列表
        availableModels.clear()
        binding.layoutModelList.removeAllViews()
        binding.tvModelSectionTitle.visibility = View.GONE

        // 状态清空
        binding.tvFetchStatus.text = ""
        binding.layoutFetchStatus.visibility = View.GONE
        binding.tvModelStatus.text = ""
    }

    // ===== 模型列表渲染（卡片式，点击填充到输入框）=====

    private fun renderModelList(models: List<String>) {
        availableModels.clear()
        availableModels.addAll(models)
        val container = binding.layoutModelList
        container.removeAllViews()

        if (models.isEmpty()) return

        binding.tvModelSectionTitle.visibility = View.VISIBLE
        binding.tvModelSectionTitle.text = "可用模型 · ${models.size} 个（点击选择）"

        val currentModel = binding.etModelName.text.toString().trim()

        for (model in models) {
            val isSelected = model == currentModel

            val card = layoutInflater.inflate(R.layout.item_model_card, container, false)

            val viewAccent = card.findViewById<View>(R.id.view_accent)
            val tvModelName = card.findViewById<TextView>(R.id.tv_model_name)
            val tvCheck = card.findViewById<TextView>(R.id.tv_check)

            tvModelName.text = model

            if (isSelected) {
                card.background = resources.getDrawable(R.drawable.bg_model_card_selected, null)
                viewAccent.setBackgroundColor(resources.getColor(R.color.accent_primary, null))
                tvCheck.visibility = View.VISIBLE
                tvModelName.typeface = Typeface.DEFAULT_BOLD
            } else {
                card.background = resources.getDrawable(R.drawable.bg_model_card, null)
                viewAccent.setBackgroundColor(resources.getColor(R.color.divider, null))
                tvCheck.visibility = View.GONE
                tvModelName.typeface = Typeface.DEFAULT
            }

            // 点击 → 填充到输入框
            card.setOnClickListener {
                // 动画反馈
                animateChipPress(card)

                binding.etModelName.setText(model)
                binding.etModelName.setSelection(model.length)
                renderModelList(models) // 刷新选中状态
            }

            container.addView(card)
        }
    }

    // ===== 监听器 =====

    private fun setupListeners() {
        binding.btnFetchModels.setOnClickListener { fetchModelList() }
        binding.btnTestModel.setOnClickListener { testConnection() }
        binding.btnSaveConfig.setOnClickListener { saveConfig() }

        // 触摸横向滑动时暂停轮播
        binding.hsProviderChips.setOnTouchListener { _, _ ->
            pauseCarouselTemporarily()
            false // 不消费触摸，让ScrollView正常滚动
        }
    }

    // ===== 获取模型列表 =====

    private fun fetchModelList() {
        val apiKey = binding.etApiKey.text.toString().trim()
        val baseUrl = binding.etBaseUrl.text.toString().trim()

        if (apiKey.isEmpty() && !currentProvider.isLocal) {
            Toast.makeText(requireContext(), "请先填写 API Key", Toast.LENGTH_SHORT).show()
            return
        }
        if (baseUrl.isEmpty()) {
            Toast.makeText(requireContext(), "请填写接口地址", Toast.LENGTH_SHORT).show()
            return
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
                    binding.tvFetchStatus.text = "✓ 获取到 ${models.size} 个模型"
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
            val idIdx = remaining.indexOf("\"id\"")
            if (idIdx < 0) break
            val colonIdx = remaining.indexOf(':', idIdx)
            if (colonIdx < 0) break
            val startQuote = remaining.indexOf('"', colonIdx + 1)
            if (startQuote < 0) break
            val endQuote = remaining.indexOf('"', startQuote + 1)
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
            Toast.makeText(requireContext(), "请填写接口地址", Toast.LENGTH_SHORT).show()
            return
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
                if (result.availableModels.isNotEmpty()) {
                    renderModelList(result.availableModels)
                }
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
        val modelName = binding.etModelName.text.toString().trim()

        if (modelName.isEmpty()) {
            Toast.makeText(requireContext(), "请输入或选择模型名称", Toast.LENGTH_SHORT).show()
            return
        }

        val newCfg = ProviderConfig(
            provider = currentProvider,
            apiKey = apiKey,
            baseUrl = baseUrl.ifEmpty { currentProvider.defaultBaseUrl },
            modelName = modelName,
            enabled = true
        )

        // 立即更新内存
        providerConfigs[currentProvider.name] = newCfg

        // 立即写入 MMKV（绕过 GameConfig.toPreferences 可能丢失的问题）
        prefs.saveString("provider_${currentProvider.name}_api_key", apiKey)
        prefs.saveString("provider_${currentProvider.name}_base_url", baseUrl.ifEmpty { currentProvider.defaultBaseUrl })
        prefs.saveString("provider_${currentProvider.name}_model", modelName)
        prefs.saveString("provider_${currentProvider.name}_enabled", "true")
        prefs.saveString("current_provider", currentProvider.name)

        // 同时更新 GameConfig 整体
        val updatedConfig = config.copy(
            currentProvider = currentProvider,
            cloudProviderConfigs = providerConfigs.toMap()
        )
        prefs.saveConfig(updatedConfig)

        refreshAllChipStyles()
        refreshConfiguredProvidersOverview()

        Toast.makeText(requireContext(), "✓ ${currentProvider.displayName} 配置已保存", Toast.LENGTH_SHORT).show()
    }

    // ===== 已配置供应商概览 =====

    private fun refreshConfiguredProvidersOverview() {
        val container = binding.layoutConfiguredProviders
        container.removeAllViews()

        if (providerConfigs.isEmpty()) {
            binding.tvNoConfigured.visibility = View.VISIBLE
            return
        }

        binding.tvNoConfigured.visibility = View.GONE

        for ((name, cfg) in providerConfigs) {
            val provider = ModelProvider.fromName(name)

            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
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
                text = "${provider.icon} ${provider.displayName}"
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_primary, null))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 8, 0)
            }
            card.addView(nameTv)

            val modelTv = TextView(requireContext()).apply {
                text = cfg.modelName
                textSize = 11f
                setTextColor(resources.getColor(R.color.accent_primary, null))
                maxLines = 1
            }
            card.addView(modelTv, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))

            val arrow = TextView(requireContext()).apply {
                text = "→"
                textSize = 16f
                setTextColor(resources.getColor(R.color.text_hint, null))
                setPadding(8, 0, 0, 0)
            }
            card.addView(arrow)

            container.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        carouselHandler.removeCallbacksAndMessages(null)
        _binding = null
    }
}
