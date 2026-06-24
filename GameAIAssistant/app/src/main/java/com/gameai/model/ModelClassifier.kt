// ModelClassifier.kt — 模型类型自动检测分类器
package com.gameai.model

/**
 * 根据模型名称自动推断其用途（conversation / analysis / stt）
 * 规则基于业界通用的模型命名规范
 */
object ModelClassifier {

    /** 返回推荐的 usedFor 值 */
    fun classify(modelName: String): String {
        val name = modelName.lowercase().trim()

        // ===== STT / 语音转文字模型 =====
        for (keyword in STT_KEYWORDS) {
            if (name.contains(keyword)) return "stt"
        }

        // ===== 多模态 / 视觉模型（可以看图片做分析）=====
        for (keyword in VISION_KEYWORDS) {
            if (name.contains(keyword)) return "analysis"
        }

        // ===== 纯对话模型 =====
        return "conversation"
    }

    /** 返回人类可读的标签 */
    fun classifyLabel(modelName: String): String = when (classify(modelName)) {
        "stt" -> "\uD83C\uDF99\uFE0F 语音识别"
        "analysis" -> "\uD83D\uDD2C 多模态分析"
        "conversation" -> "\uD83D\uDCAC 对话"
        else -> "\uD83D\uDCAC 通用"
    }

    /** 获取分类置信度描述 */
    fun classifyReason(modelName: String): String {
        val name = modelName.lowercase().trim()
        for (keyword in STT_KEYWORDS) {
            if (name.contains(keyword)) return "\"$keyword\" → 语音转文字模型"
        }
        for (keyword in VISION_KEYWORDS) {
            if (name.contains(keyword)) return "\"$keyword\" → 多模态/视觉模型"
        }
        return "默认 → 对话模型"
    }

    // ===== 关键词库 =====

    /** STT/语音转文字模型关键词 */
    private val STT_KEYWORDS = listOf(
        "whisper",
        "sensevoice",
        "speech",
        "asr",
        "transcri",
        "stt",
        "paraformer",
        "funasr",
        "telespeech",
        "voice",
        "audio"
    )

    /** 多模态/视觉模型关键词 */
    private val VISION_KEYWORDS = listOf(
        "vl",        // Qwen2.5-VL, InternVL
        "vision",
        "omni",      // gpt-4o (omni), gemini
        "4o",        // gpt-4o, gpt-4o-mini
        "multimodal",
        "claude",    // Claude 3+ 支持视觉
        "gemini",    // Gemini 支持视觉
        "glm-4v",    // 智谱视觉模型
        "cogvlm",
        "llava",
        "visual",
        "video"
    )
}
