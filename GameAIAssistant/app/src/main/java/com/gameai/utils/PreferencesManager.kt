// PreferencesManager.kt - 偏好设置管理器（重构版）
package com.gameai.utils

import android.content.Context
import com.gameai.model.*
import com.tencent.mmkv.MMKV

class PreferencesManager(context: Context) {
    private val kv: MMKV = MMKV.mmkvWithID("game_ai_prefs")

    companion object {
        @Volatile
        private var INSTANCE: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context).also { INSTANCE = it }
            }
        }
    }

    fun saveConfig(config: GameConfig) {
        config.toPreferences().forEach { (key, value) ->
            kv.encode(key, value)
        }
    }

    fun loadConfig(): GameConfig {
        val keys = kv.allKeys() ?: arrayOf()
        return GameConfig.fromPreferences(keys.associateWith { kv.decodeString(it) ?: "" })
    }

    // ========== 基础配置读取 ==========
    fun getServerHost(): String = kv.decodeString("server_host") ?: "192.168.1.100"
    fun getServerPort(): Int = kv.decodeInt("server_port", 8765)
    fun getGameName(): String = kv.decodeString("game_name") ?: "王者荣耀"
    fun isVoiceEnabled(): Boolean = kv.decodeBool("enable_voice", true)
    fun isFloatingBallEnabled(): Boolean = kv.decodeBool("enable_floating_ball", false)
    fun getFloatingBallAlpha(): Float = kv.decodeFloat("floating_ball_alpha", 0.8f)
    fun getCaptureFps(): Int = kv.decodeInt("capture_fps", 10)
    fun getJpegQuality(): Int = kv.decodeInt("jpeg_quality", 60)
    fun isAutoStart(): Boolean = kv.decodeBool("auto_start", false)

    // ========== 模型配置读取 ==========
    fun getModelMode(): ModelMode =
        ModelMode.fromName(kv.decodeString("model_mode") ?: "CLOUD")

    fun getCurrentProvider(): ModelProvider =
        ModelProvider.fromName(kv.decodeString("current_provider") ?: "OPENAI")

    fun getCurrentProviderConfig(): ProviderConfig {
        val mode = getModelMode()
        val provider = getCurrentProvider()
        return when (mode) {
            ModelMode.CLOUD -> {
                ProviderConfig.fromPrefs(provider) { key -> kv.decodeString(key) }
            }
            ModelMode.LOCAL -> {
                val host = kv.decodeString("local_model_host") ?: "192.168.1.100"
                val port = kv.decodeInt("local_model_port", 11434)
                val model = kv.decodeString("local_model_name") ?: "qwen2.5:7b"
                ProviderConfig(
                    provider = provider,
                    baseUrl = "http://${host}:${port}/v1",
                    modelName = model
                )
            }
            ModelMode.CUSTOM -> ProviderConfig(
                provider = ModelProvider.CUSTOM,
                apiKey = kv.decodeString("custom_api_key") ?: "",
                baseUrl = kv.decodeString("custom_base_url") ?: "",
                modelName = kv.decodeString("custom_model_name") ?: ""
            )
        }
    }

    fun getLocalModelHost(): String = kv.decodeString("local_model_host") ?: "192.168.1.100"
    fun getLocalModelPort(): Int = kv.decodeInt("local_model_port", 11434)
    fun getLocalModelName(): String = kv.decodeString("local_model_name") ?: "qwen2.5:7b"

    // ========== 多模型绑定配置 ==========
    /** 构建对话模型配置：优先从ProviderConfig.models中选conversation标签 */
    fun getConversationModelConfig(): ProviderConfig? {
        val baseConfig = getCurrentProviderConfig()
        if (baseConfig.models.isEmpty()) {
            // 旧版兼容：检查 conversation_model_name
            val convModel = kv.decodeString("conversation_model_name") ?: ""
            if (convModel.isNotBlank()) return baseConfig.copy(modelName = convModel)
            return baseConfig
        }
        return baseConfig.forPurpose("conversation")
    }

    /** 构建分析模型配置：优先从ProviderConfig.models中选analysis标签 */
    fun getAnalysisModelConfig(): ProviderConfig? {
        val baseConfig = getCurrentProviderConfig()
        if (baseConfig.models.isEmpty()) {
            val analysisModel = kv.decodeString("analysis_model_name") ?: ""
            if (analysisModel.isNotBlank()) return baseConfig.copy(modelName = analysisModel)
            return baseConfig
        }
        return baseConfig.forPurpose("analysis")
    }

    /** 获取语音转文字模型配置 — 跨所有云供应商搜索 STT 模型 */
    fun getSttModelConfig(): ProviderConfig? {
        // 1. 先检查当前供应商
        val sttFromCurrent = pickSttFromProvider(getCurrentProviderConfig())
        if (sttFromCurrent != null) return sttFromCurrent

        // 2. 跨所有已配置的云供应商搜索
        val allConfigs = loadConfig().cloudProviderConfigs
        for ((name, config) in allConfigs) {
            if (name == getCurrentProvider().name) continue // 已检查过
            val sttConfig = pickSttFromProvider(config)
            if (sttConfig != null) return sttConfig
        }

        // 3. 所有供应商都没有 STT 模型
        return null
    }

    /**
     * 从单个供应商配置中提取 STT 模型。
     * 拒绝 fallback 到非 STT 模型 — 没有明确 STT 标签就返回 null，
     * 避免聊天模型的 API Key 被错误发给 STT 接口导致 401。
     */
    private fun pickSttFromProvider(config: ProviderConfig): ProviderConfig? {
        if (config.apiKey.isBlank()) return null
        if (config.models.isEmpty()) {
            // 无多模型绑定：仅当供应商有声明 defaultSttModel 时使用
            val defaultStt = config.provider.defaultSttModel
            return if (defaultStt.isNotBlank())
                config.copy(modelName = defaultStt)
            else null
        }
        // 有多模型绑定：查找 usedFor=="stt" 的模型
        val sttBinding = config.models.firstOrNull { it.usedFor == "stt" }
            ?: config.models.firstOrNull { it.matches("all") && config.provider.defaultSttModel.isNotBlank() }
        return if (sttBinding != null)
            config.copy(modelName = sttBinding.modelName)
        else null
    }

    // ========== 通用读写 ==========
    fun saveString(key: String, value: String) = kv.encode(key, value)
    fun getString(key: String, default: String = ""): String = kv.decodeString(key) ?: default
    fun saveInt(key: String, value: Int) = kv.encode(key, value)
    fun getInt(key: String, default: Int = 0): Int = kv.decodeInt(key, default)
    fun saveBoolean(key: String, value: Boolean) = kv.encode(key, value)
    fun getBoolean(key: String, default: Boolean = false): Boolean = kv.decodeBool(key, default)
    fun saveFloat(key: String, value: Float) = kv.encode(key, value)
    fun getFloat(key: String, default: Float = 0f): Float = kv.decodeFloat(key, default)

    // ========== 设备ID ==========
    fun getDeviceId(): String {
        var id = kv.decodeString("device_id")
        if (id == null) {
            id = "android_" + (10000000..99999999).random().toString()
            kv.encode("device_id", id)
        }
        return id
    }
}
