// ModelProvider.kt - 多供应商模型配置系统
package com.gameai.model

/**
 * AI模型供应商枚举
 * 支持云端供应商 + 本地模型服务
 */
enum class ModelProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val isLocal: Boolean = false,
    val description: String = "",
    val icon: String = ""
) {
    // ===== 云端供应商 =====
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o",
        description = "GPT-4o/GPT-4o-mini 等",
        icon = "\uD83E\uDD16"
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        description = "DeepSeek-V3/R1 等",
        icon = "\uD83D\uDC0B"
    ),
    QWEN(
        displayName = "通义千问",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-max",
        description = "Qwen-Max/Plus/Turbo 等",
        icon = "\u2601\uFE0F"
    ),
    ERNIE(
        displayName = "文心一言",
        defaultBaseUrl = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat",
        defaultModel = "ernie-4.0-8k",
        description = "ERNIE 4.0/3.5 等",
        icon = "\uD83D\uDCD8"
    ),
    ZHIPU(
        displayName = "智谱AI",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4-plus",
        description = "GLM-4/GLM-4V 等",
        icon = "\uD83D\uDC8E"
    ),

    // ===== 本地模型服务 =====
    LOCAL_OLLAMA(
        displayName = "Ollama",
        defaultBaseUrl = "http://192.168.1.100:11434/v1",
        defaultModel = "qwen2.5:7b",
        isLocal = true,
        description = "Ollama本地部署",
        icon = "\uD83E\uDD99"
    ),
    LOCAL_VLLM(
        displayName = "vLLM",
        defaultBaseUrl = "http://192.168.1.100:8000/v1",
        defaultModel = "Qwen2.5-VL-7B-Instruct",
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
 * 单个供应商的完整配置
 */
data class ProviderConfig(
    val provider: ModelProvider = ModelProvider.OPENAI,
    val apiKey: String = "",
    val baseUrl: String = ModelProvider.OPENAI.defaultBaseUrl,
    val modelName: String = ModelProvider.OPENAI.defaultModel,
    val enabled: Boolean = true
) {
    fun toPrefs(): Map<String, String> = mapOf(
        "provider_${provider.name}_api_key" to apiKey,
        "provider_${provider.name}_base_url" to baseUrl,
        "provider_${provider.name}_model" to modelName,
        "provider_${provider.name}_enabled" to enabled.toString()
    )

    companion object {
        fun fromPrefs(provider: ModelProvider, prefs: (String) -> String?): ProviderConfig {
            val p = provider.name
            return ProviderConfig(
                provider = provider,
                apiKey = prefs("provider_${p}_api_key") ?: "",
                baseUrl = prefs("provider_${p}_base_url") ?: provider.defaultBaseUrl,
                modelName = prefs("provider_${p}_model") ?: provider.defaultModel,
                enabled = prefs("provider_${p}_enabled")?.toBooleanStrictOrNull() ?: true
            )
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
