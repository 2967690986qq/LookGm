// MemoryManager.kt — 增强记忆管理器
// 参考 OpenClaw session-memory：会话记忆 + 用户画像 + 自动清理压缩
package com.gameai.ai

import android.content.Context
import com.gameai.db.AppDatabase
import com.gameai.db.SessionMemoryEntity
import kotlinx.coroutines.*
import java.util.UUID

object MemoryManager {

    private var db: AppDatabase? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentSessionId: String = UUID.randomUUID().toString()

    fun init(context: Context) {
        db = AppDatabase.getInstance(context)
        startNewSession()
    }

    fun startNewSession() {
        currentSessionId = UUID.randomUUID().toString()
    }

    fun getSessionId(): String = currentSessionId

    /** 保存用户偏好 */
    fun savePreference(key: String, content: String, importance: Float = 0.5f) {
        scope.launch {
            val existing = db?.sessionMemoryDao()?.getByKey(key)
            val memory = if (existing != null) {
                existing.copy(
                    content = content,
                    importance = (existing.importance + importance) / 2f,  // 平滑更新重要性
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                SessionMemoryEntity(
                    memoryId = UUID.randomUUID().toString(),
                    sessionId = currentSessionId,
                    category = "pref",
                    key = key,
                    content = content,
                    importance = importance
                )
            }
            db?.sessionMemoryDao()?.insert(memory)
        }
    }

    /** 记录对话上下文 */
    fun saveContext(key: String, content: String) {
        scope.launch {
            val memory = SessionMemoryEntity(
                memoryId = UUID.randomUUID().toString(),
                sessionId = currentSessionId,
                category = "context",
                key = key,
                content = content.take(500),
                importance = 0.3f
            )
            db?.sessionMemoryDao()?.insert(memory)
        }
    }

    /** 记录 AI 洞察（长期有价值） */
    fun saveInsight(key: String, content: String, importance: Float = 0.7f) {
        scope.launch {
            val memory = SessionMemoryEntity(
                memoryId = UUID.randomUUID().toString(),
                sessionId = currentSessionId,
                category = "insight",
                key = key,
                content = content.take(500),
                importance = importance
            )
            db?.sessionMemoryDao()?.insert(memory)
        }
    }

    /** 记录游戏经历 */
    fun saveGameExperience(heroName: String, content: String) {
        scope.launch {
            val memory = SessionMemoryEntity(
                memoryId = UUID.randomUUID().toString(),
                sessionId = currentSessionId,
                category = "game",
                key = "hero_${heroName}_exp",
                content = content.take(500),
                importance = 0.6f
            )
            db?.sessionMemoryDao()?.insert(memory)
        }
    }

    /** 构建用户画像提示词片段 */
    suspend fun buildUserProfileContext(): String {
        val prefs = db?.sessionMemoryDao()?.getUserPreferences() ?: emptyList()
        val insights = db?.sessionMemoryDao()?.getInsights() ?: emptyList()
        return SessionMemoryEntity.buildUserProfileSnapshot(prefs + insights)
    }

    /** 获取完整上下文（偏好+洞察+游戏经历） */
    suspend fun getFullContext(): String {
        val memories = db?.sessionMemoryDao()?.getAllByRelevance() ?: emptyList()
        if (memories.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append(SessionMemoryEntity.buildUserProfileSnapshot(memories, 600))

        // 标记关键记忆为已访问
        for (m in memories.take(5)) {
            db?.sessionMemoryDao()?.recordAccess(m.memoryId)
        }
        return sb.toString()
    }

    /** 记忆压缩：清理低重要性、低访问量的旧记忆 */
    suspend fun compact() {
        val count = db?.sessionMemoryDao()?.count() ?: 0
        if (count > 60) {
            db?.sessionMemoryDao()?.compact(keep = 50)
        }
    }

    /** 清除当前会话记忆 */
    suspend fun clearCurrentSession() {
        db?.sessionMemoryDao()?.deleteBySession(currentSessionId)
    }
}
