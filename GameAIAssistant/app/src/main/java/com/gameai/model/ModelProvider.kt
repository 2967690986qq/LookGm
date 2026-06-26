// ModelProvider.kt - 多供应商多模型绑定系统
package com.gameai.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 单个模型绑定 — 一个供应商可以绑定多个模型，各有不同用途
 */
data class ModelBinding(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val modelName: String,                              // 实际模型ID
    val displayLabel: String = "",                       // 用户自定义标签
    val usedFor: String = "all"                          // "conversation" / "analysis" / "stt" / "all"
) {
    /** 是否用于某用途 */
    fun matches(use: String): Boolean =
        usedFor == "all" || usedFor.contains(use, ignoreCase = true)

    companion object {
        fun fromJson(json: JSONObject): ModelBinding = ModelBinding(
            id = json.optString("id", java.util.UUID.randomUUID().toString().take(8)),
            modelName = json.getString("modelName"),
            displayLabel = json.optString("displayLabel", ""),
            usedFor = json.optString("usedFor", "all")
        )
    }
}

/**
 * AI模型供应商枚举
 */
enum class ModelProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val defaultSttModel: String = "",   // 默认语音转文字模型（空=不支持STT）
    val defaultVisionModel: String = "", // 默认视觉模型（空=不支持视觉）
    val isLocal: Boolean = false,
    val description: String = "",
    val icon: String = ""
) {
    // ===== 云端供应商 =====
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o",
        defaultSttModel = "whisper-1",
        defaultVisionModel = "gpt-4o",
        description = "GPT-4o/GPT-4o-mini 等",
        icon = "\uD83E\uDD16"
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        defaultVisionModel = "deepseek-vl",
        description = "DeepSeek-V3/R1/VL 等",
        icon = "\uD83D\uDC0B"
    ),
    QWEN(
        displayName = "通义千问",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-max",
        defaultVisionModel = "qwen-vl-max",
        description = "Qwen-Max/Plus/Turbo/VL 等",
        icon = "\u2601\uFE0F"
    ),
    ERNIE(
        displayName = "文心一言",
        defaultBaseUrl = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat",
        defaultModel = "ernie-4.0-8k",
        defaultVisionModel = "ernie-4.0-vision",
        description = "ERNIE 4.0/3.5/Vision 等",
        icon = "\uD83D\uDCD8"
    ),
    ZHIPU(
        displayName = "智谱AI",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4-plus",
        defaultVisionModel = "glm-4v",
        description = "GLM-4/GLM-4V 等",
        icon = "\uD83D\uDC8E"
    ),
    SILICONFLOW(
        displayName = "硅基流动",
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        defaultModel = "Qwen/Qwen2.5-7B-Instruct",
        defaultSttModel = "FunAudioLLM/SenseVoiceSmall",
        defaultVisionModel = "deepseek-ai/DeepSeek-OCR",
        description = "免费语音模型+多模态推理",
        icon = "\uD83C\uDF0A"
    ),

    // ===== 本地模型服务 =====
    LOCAL_OLLAMA(
        displayName = "Ollama",
        defaultBaseUrl = "http://192.168.1.100:11434/v1",
        defaultModel = "qwen2.5:7b",
        defaultVisionModel = "qwen2.5-vl:7b",
        isLocal = true,
        description = "Ollama本地部署",
        icon = "\uD83E\uDD99"
    ),
    LOCAL_VLLM(
        displayName = "vLLM",
        defaultBaseUrl = "http://192.168.1.100:8000/v1",
        defaultModel = "Qwen2.5-VL-7B-Instruct",
        defaultVisionModel = "Qwen2.5-VL-7B-Instruct",
        isLocal = true,
        description = "vLLM高性能推理",
        icon = "\u26A1"
    ),
    LOCAL_LM_STUDIO(
        displayName = "LM Studio",
        defaultBaseUrl = "http://192.168.1.100:1234/v1",
        defaultModel = "local-model",
        isLocal = true,
        description = "LM Studio部署",
        icon = "\uD83D\uDCBB"
    ),
    CUSTOM(
        displayName = "自定义接口",
        defaultBaseUrl = "http://192.168.1.100:8080/v1",
        defaultModel = "custom-model",
        description = "任意OpenAI兼容接口",
        icon = "\uD83D\uDD27"
    );

    companion object {
        fun fromName(name: String): ModelProvider =
            entries.find { it.name.equals(name, ignoreCase = true) }
                ?: entries.find { it.displayName == name }
                ?: CUSTOM
    }
}

/**
 * 单个供应商的完整配置（支持多模型绑定）
 */
data class ProviderConfig(
    val provider: ModelProvider = ModelProvider.OPENAI,
    val apiKey: String = "",
    val baseUrl: String = ModelProvider.OPENAI.defaultBaseUrl,
    val modelName: String = ModelProvider.OPENAI.defaultModel,
    val enabled: Boolean = true,
    val models: List<ModelBinding> = emptyList()
) {
    /** 获取已绑定模型数 */
    val modelCount: Int get() = if (models.isNotEmpty()) models.size else 1

    /** 根据用途获取模型名（含智能 fallback） */
    fun getModelFor(usedFor: String): String {
        if (models.isEmpty()) return modelName
        // 1. 优先精确用途匹配
        models.firstOrNull { it.usedFor == usedFor }?.modelName?.let { return it }
        // 2. STT 特殊性：优先走供应商默认 STT 模型，避免 chat-only 模型被误用
        if (usedFor == "stt") {
            return provider.defaultSttModel.takeIf { it.isNotBlank() }
                ?: models.firstOrNull { it.matches("all") }?.modelName
                ?: modelName
        }
        // 3. vision 视觉模型：优先用 vision，其次用 analysis，最后用 all
        if (usedFor == "vision") {
            return models.firstOrNull { it.usedFor == "analysis" }?.modelName
                ?: models.firstOrNull { it.matches("all") }?.modelName
                ?: modelName
        }
        // 4. "all" 作为通用兜底
        return models.firstOrNull { it.matches("all") }?.modelName ?: modelName
    }

    fun getConversationModel(): String = getModelFor("conversation")
    fun getAnalysisModel(): String = getModelFor("analysis")
    fun getSttModel(): String = getModelFor("stt")
    fun getVisionModel(): String = getModelFor("vision")

    /** 根据用途获取完整ProviderConfig（模型名被替换） */
    fun forPurpose(usedFor: String): ProviderConfig =
        copy(modelName = getModelFor(usedFor))

    fun toPrefs(): Map<String, String> {
        val map = mutableMapOf(
            "provider_${provider.name}_api_key" to apiKey,
            "provider_${provider.name}_base_url" to baseUrl,
            "provider_${provider.name}_model" to modelName,
            "provider_${provider.name}_enabled" to enabled.toString()
        )
        // 多模型列表 → JSON
        if (models.isNotEmpty()) {
            val arr = JSONArray()
            models.forEach { m ->
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("modelName", m.modelName)
                    put("displayLabel", m.displayLabel)
                    put("usedFor", m.usedFor)
                })
            }
            map["provider_${provider.name}_models"] = arr.toString()
        }
        return map
    }

    companion object {
        fun fromPrefs(provider: ModelProvider, prefs: (String) -> String?): ProviderConfig {
            val p = provider.name
            val modelList = parseModelList(prefs("provider_${p}_models"))

            // 迁移：如果 models 列表为空但 modelName 有值，从 modelName 创建一条 all 绑定
            val finalModels = if (modelList.isEmpty()) {
                val legacyModel = prefs("provider_${p}_model")
                if (!legacyModel.isNullOrBlank() && legacyModel != provider.defaultModel) {
                    // 检测是否有分开的 conversation/analysis/stt 配置
                    val convModel = prefs("conversation_model_name")
                    val analysisModel = prefs("analysis_model_name")
                    val results = mutableListOf<ModelBinding>()
                    if (!convModel.isNullOrBlank() && convModel != legacyModel) {
                        results.add(ModelBinding(modelName = convModel, displayLabel = "对话模型", usedFor = "conversation"))
                    }
                    if (!analysisModel.isNullOrBlank() && analysisModel != legacyModel) {
                        results.add(ModelBinding(modelName = analysisModel, displayLabel = "分析模型", usedFor = "analysis"))
                    }
                    // 默认的一条
                    results.add(ModelBinding(
                        modelName = legacyModel,
                        displayLabel = if (results.isEmpty()) "默认模型" else "通用模型",
                        usedFor = "all"
                    ))
                    results
                } else emptyList()
            } else modelList

            return ProviderConfig(
                provider = provider,
                apiKey = prefs("provider_${p}_api_key") ?: "",
                baseUrl = prefs("provider_${p}_base_url") ?: provider.defaultBaseUrl,
                modelName = prefs("provider_${p}_model") ?: provider.defaultModel,
                enabled = prefs("provider_${p}_enabled")?.toBooleanStrictOrNull() ?: true,
                models = finalModels
            )
        }

        private fun parseModelList(json: String?): List<ModelBinding> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    ModelBinding.fromJson(arr.getJSONObject(i))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * 模型模式
 */
enum class ModelMode(val displayName: String) {
    CLOUD("云端模型"),
    LOCAL("内网本地模型"),
    CUSTOM("自定义接口");

    companion object {
        fun fromName(name: String): ModelMode =
            entries.find { it.name.equals(name, ignoreCase = true) }
                ?: entries.find { it.displayName == name }
                ?: CLOUD
    }
}
