// UsageEntity.kt — API 用量追踪实体
// 参考 OpenClaw model-usage 技能：追踪各模型消耗的 token 和估算费用
package com.gameai.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage_records")
data class UsageEntity(
    @PrimaryKey val recordId: String,     // UUID
    val providerName: String,             // OpenAI, DeepSeek, SiliconFlow...
    val modelName: String,                // gpt-4o, deepseek-chat...
    val purpose: String,                  // conversation, analysis, stt, embedding
    val promptTokens: Int = 0,            // 输入 token
    val completionTokens: Int = 0,        // 输出 token
    val totalTokens: Int = 0,             // 总计
    val latencyMs: Long = 0,              // 响应延迟
    val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean = true
) {
    companion object {
        /**
         * 价格估算（元/1K tokens）— 参考各供应商官方定价
         * 输入和输出价格不同：gpt-4o 输入 0.015, 输出 0.06
         */
        private val priceMap = mapOf(
            // OpenAI
            "gpt-4o" to Pair(0.015, 0.06),
            "gpt-4o-mini" to Pair(0.00015, 0.0006),
            "gpt-4-turbo" to Pair(0.03, 0.06),
            "o3-mini" to Pair(0.0005, 0.002),
            "whisper-1" to Pair(0.006, 0.0),  // 音频 $0.006/min ≈ 折算

            // DeepSeek
            "deepseek-chat" to Pair(0.001, 0.002),
            "deepseek-reasoner" to Pair(0.002, 0.008),

            // Qwen (通义千问)
            "qwen-plus" to Pair(0.002, 0.006),
            "qwen-max" to Pair(0.02, 0.06),
            "qwen-turbo" to Pair(0.0003, 0.0006),

            // Ernie (文心一言)
            "ernie-bot" to Pair(0.002, 0.004),
            "ernie-4.0" to Pair(0.03, 0.06),

            // Zhipu (智谱)
            "glm-4" to Pair(0.001, 0.001),
            "glm-4-plus" to Pair(0.05, 0.05),

            // SiliconFlow (以官方最新定价为准)
            "FunAudioLLM/SenseVoiceSmall" to Pair(0.0, 0.0),
            "deepseek-ai/DeepSeek-V3" to Pair(0.001, 0.002),
        )

        /** 估算用量费用（人民币元） */
        fun estimateCostYuan(
            providerName: String,
            modelName: String,
            promptTokens: Int,
            completionTokens: Int
        ): Double {
            // 特殊供应商：SiliconFlow 部分模型免费
            if (providerName == "SiliconFlow") {
                val price = priceMap[modelName] ?: Pair(0.001, 0.002)
                if (price.first == 0.0) return 0.0
            }

            val (inputPrice, outputPrice) = priceMap[modelName]
                ?: priceMap.entries.firstOrNull { (k, _) -> k.contains(modelName.take(8)) }?.value
                ?: Pair(0.001, 0.002)  // 未知模型默认低价估算

            val inputCost = (promptTokens / 1000.0) * inputPrice
            val outputCost = (completionTokens / 1000.0) * outputPrice
            return inputCost + outputCost
        }
    }

    /** 估算本次请求费用 */
    fun estimateCostYuan(): Double {
        return estimateCostYuan(providerName, modelName, promptTokens, completionTokens)
    }
}
