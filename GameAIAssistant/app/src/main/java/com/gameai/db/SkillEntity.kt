// SkillEntity.kt — 策略技能实体
// 参考 OpenClaw SKILL.md 设计：紧凑描述 + 可组合提示词片段
package com.gameai.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val skillId: String,
    val name: String,
    val description: String,
    val category: String,        // hero_guide, strategy, item_build, counter_pick, communication
    val gameName: String,        // 王者荣耀, etc.
    val heroName: String = "",   // 关联英雄，空=通用
    val content: String,         // skill body: 策略提示词片段
    val source: String = "builtin",  // builtin, github, user
    val sourceUrl: String = "",
    val version: String = "1.0",
    val isEnabled: Boolean = true,
    val installedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 生成注入到 AI 对话的优化提示词片段
     * 格式参考 OpenClaw SKILL.md 的紧凑设计：每次只注入最核心的要点
     */
    fun toSystemPromptSnippet(): String {
        if (!isEnabled) return ""
        val sb = StringBuilder()
        sb.append("### $name")
        if (heroName.isNotBlank()) sb.append(" ($heroName)")
        sb.append("\n- $description")
        if (content.isNotBlank()) {
            // 控制长度避免 token 浪费，每个技能最多 300 字
            sb.append("\n- ${content.take(300)}")
        }
        return sb.toString()
    }
}
