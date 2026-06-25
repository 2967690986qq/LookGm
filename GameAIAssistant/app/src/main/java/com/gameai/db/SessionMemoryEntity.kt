// SessionMemoryEntity.kt — 会话记忆实体
// 参考 OpenClaw session-memory：会话上下文持久化 + 用户偏好学习 + 压缩清理
package com.gameai.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_memories")
data class SessionMemoryEntity(
    @PrimaryKey val memoryId: String,   // UUID
    val sessionId: String,             // 会话分组 ID
    val category: String,              // pref(用户偏好), context(对话上下文), game(游戏经历), insight(AI洞察)
    val key: String,                   // 记忆键名
    val content: String,               // 记忆内容（最多 500 字）
    val importance: Float = 0.5f,      // 重要性评分 0~1，用于清理时的优先级
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,          // 被访问次数
    val lastAccessedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 生成注入到 AI 系统提示词的用户画像摘要 */
        fun buildUserProfileSnapshot(memories: List<SessionMemoryEntity>, maxLen: Int = 500): String {
            if (memories.isEmpty()) return ""

            val prefs = memories.filter { it.category == "pref" }
            val insights = memories.filter { it.category == "insight" }

            if (prefs.isEmpty() && insights.isEmpty()) return ""

            val sb = StringBuilder()
            sb.append("## 用户画像\n")
            for (p in prefs.take(5)) {
                sb.append("- ${p.key}: ${p.content.take(80)}\n")
            }
            if (insights.isNotEmpty()) {
                sb.append("\n## AI 洞察\n")
                for (i in insights.take(3)) {
                    sb.append("- ${i.content.take(100)}\n")
                }
            }
            return sb.toString().take(maxLen)
        }

        /** 压缩旧记忆：删除不再重要的记忆 */
        fun compact(memories: List<SessionMemoryEntity>, maxCount: Int = 50): List<SessionMemoryEntity> {
            if (memories.size <= maxCount) return emptyList()
            return memories
                .sortedByDescending { it.importance * 0.7 + (if (it.accessCount > 2) 0.3f else 0f) }
                .drop(maxCount)
        }
    }
}
