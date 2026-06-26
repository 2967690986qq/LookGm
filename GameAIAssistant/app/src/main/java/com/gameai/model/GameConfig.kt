// GameConfig.kt - 完整游戏配置模型（重构版）
package com.gameai.model

data class GameConfig(
    // ========== 游戏配置 ==========
    val gameName: String = "王者荣耀",

    // ========== PC服务器配置 ==========
    val serverHost: String = "192.168.1.100",
    val serverPort: Int = 8765,

    // ========== AI模型配置（新版多供应商）==========
    val modelMode: ModelMode = ModelMode.CLOUD,
    val currentProvider: ModelProvider = ModelProvider.OPENAI,

    // 云端供应商配置 (每个供应商独立存储)
    val cloudProviderConfigs: Map<String, ProviderConfig> = emptyMap(),

    // 本地模型配置
    val localModelHost: String = "192.168.1.100",
    val localModelPort: Int = 11434,
    val localModelName: String = "qwen2.5:7b",

    // 自定义接口配置
    val customBaseUrl: String = "",
    val customApiKey: String = "",
    val customModelName: String = "",

    // ========== 功能开关 ==========
    val enableVoice: Boolean = true,
    val enableFloatingBall: Boolean = false,
    val floatingBallAlpha: Float = 0.8f,
    val autoStart: Boolean = false,
    val enableBargeIn: Boolean = false,

    // ========== 采集参数 ==========
    val captureFps: Int = 10,
    val jpegQuality: Int = 60,

    // ========== 其他 ==========
    val showTimeline: Boolean = true,
    val enableAutoUpdate: Boolean = true
) {
    /**
     * 获取当前激活的ProviderConfig
     */
    fun getCurrentProviderConfig(): ProviderConfig {
        return when (modelMode) {
            ModelMode.CLOUD -> {
                cloudProviderConfigs[currentProvider.name]
                    ?: ProviderConfig(provider = currentProvider)
            }
            ModelMode.LOCAL -> {
                when (currentProvider) {
                    ModelProvider.LOCAL_OLLAMA -> ProviderConfig(
                        provider = ModelProvider.LOCAL_OLLAMA,
                        baseUrl = "http://${localModelHost}:${localModelPort}/v1",
                        modelName = localModelName
                    )
                    ModelProvider.LOCAL_VLLM -> ProviderConfig(
                        provider = ModelProvider.LOCAL_VLLM,
                        baseUrl = "http://${localModelHost}:${localModelPort}/v1",
                        modelName = localModelName
                    )
                    ModelProvider.LOCAL_LM_STUDIO -> ProviderConfig(
                        provider = ModelProvider.LOCAL_LM_STUDIO,
                        baseUrl = "http://${localModelHost}:${localModelPort}/v1",
                        modelName = localModelName
                    )
                    else -> ProviderConfig(
                        provider = ModelProvider.LOCAL_OLLAMA,
                        baseUrl = "http://${localModelHost}:${localModelPort}/v1",
                        modelName = localModelName
                    )
                }
            }
            ModelMode.CUSTOM -> ProviderConfig(
                provider = ModelProvider.CUSTOM,
                apiKey = customApiKey,
                baseUrl = customBaseUrl,
                modelName = customModelName
            )
        }
    }

    fun toPreferences(): Map<String, String> {
        val map = mutableMapOf(
            "game_name" to gameName,
            "server_host" to serverHost,
            "server_port" to serverPort.toString(),
            "model_mode" to modelMode.name,
            "current_provider" to currentProvider.name,
            "local_model_host" to localModelHost,
            "local_model_port" to localModelPort.toString(),
            "local_model_name" to localModelName,
            "custom_base_url" to customBaseUrl,
            "custom_api_key" to customApiKey,
            "custom_model_name" to customModelName,
            "enable_voice" to enableVoice.toString(),
            "enable_floating_ball" to enableFloatingBall.toString(),
            "floating_ball_alpha" to floatingBallAlpha.toString(),
            "auto_start" to autoStart.toString(),
            "enable_barge_in" to enableBargeIn.toString(),
            "capture_fps" to captureFps.toString(),
            "jpeg_quality" to jpegQuality.toString(),
            "show_timeline" to showTimeline.toString(),
            "enable_auto_update" to enableAutoUpdate.toString()
        )
        // 合并所有云端供应商配置
        cloudProviderConfigs.values.forEach { config ->
            map.putAll(config.toPrefs())
        }
        return map
    }

    companion object {
        fun fromPreferences(prefs: Map<String, String>): GameConfig {
            // 解析所有供应商配置（每个供应商独立存储，包括LOCAL和CUSTOM）
            // 使用 prefs 中实际存在的 provider_*_model key 来判断哪些供应商已配置
            val cloudConfigs = mutableMapOf<String, ProviderConfig>()
            ModelProvider.entries.forEach { provider ->
                val modelKey = "provider_${provider.name}_model"
                // 只要 MMKV 中存在该供应商的 model key 就加载（值可以是空字符串，表示用户清空了模型名）
                if (prefs.containsKey(modelKey)) {
                    val cfg = ProviderConfig.fromPrefs(provider) { key -> prefs[key] }
                    cloudConfigs[provider.name] = cfg
                }
            }
            val finalCloudConfigs = cloudConfigs.toMutableMap()

            // 迁移：旧版CUSTOM配置（custom_api_key → provider_CUSTOM_api_key）
            if (!finalCloudConfigs.containsKey("CUSTOM")) {
                val oldApiKey = prefs["custom_api_key"] ?: ""
                val oldBaseUrl = prefs["custom_base_url"] ?: ""
                val oldModel = prefs["custom_model_name"] ?: ""
                if (oldApiKey.isNotEmpty() || oldBaseUrl.isNotEmpty() || oldModel.isNotEmpty()) {
                    finalCloudConfigs["CUSTOM"] = ProviderConfig(
                        provider = ModelProvider.CUSTOM,
                        apiKey = oldApiKey,
                        baseUrl = oldBaseUrl.ifEmpty { ModelProvider.CUSTOM.defaultBaseUrl },
                        modelName = oldModel.ifEmpty { ModelProvider.CUSTOM.defaultModel }
                    )
                }
            }

            return GameConfig(
                gameName = prefs["game_name"] ?: "王者荣耀",
                serverHost = prefs["server_host"] ?: "192.168.1.100",
                serverPort = prefs["server_port"]?.toIntOrNull() ?: 8765,
                modelMode = ModelMode.fromName(prefs["model_mode"] ?: "CLOUD"),
                currentProvider = ModelProvider.fromName(prefs["current_provider"] ?: "OPENAI"),
                cloudProviderConfigs = finalCloudConfigs,
                localModelHost = prefs["local_model_host"] ?: "192.168.1.100",
                localModelPort = prefs["local_model_port"]?.toIntOrNull() ?: 11434,
                localModelName = prefs["local_model_name"] ?: "qwen2.5:7b",
                customBaseUrl = prefs["custom_base_url"] ?: "",
                customApiKey = prefs["custom_api_key"] ?: "",
                customModelName = prefs["custom_model_name"] ?: "",
                enableVoice = prefs["enable_voice"]?.toBooleanStrictOrNull() ?: true,
                enableFloatingBall = prefs["enable_floating_ball"]?.toBooleanStrictOrNull() ?: false,
                floatingBallAlpha = prefs["floating_ball_alpha"]?.toFloatOrNull() ?: 0.8f,
                autoStart = prefs["auto_start"]?.toBooleanStrictOrNull() ?: false,
                enableBargeIn = prefs["enable_barge_in"]?.toBooleanStrictOrNull() ?: false,
                captureFps = prefs["capture_fps"]?.toIntOrNull() ?: 10,
                jpegQuality = prefs["jpeg_quality"]?.toIntOrNull() ?: 60,
                showTimeline = prefs["show_timeline"]?.toBooleanStrictOrNull() ?: true,
                enableAutoUpdate = prefs["enable_auto_update"]?.toBooleanStrictOrNull() ?: true
            )
        }
    }
}
