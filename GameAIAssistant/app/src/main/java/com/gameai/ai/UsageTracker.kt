// UsageTracker.kt — API 用量追踪器
// 参考 OpenClaw model-usage 技能：记录 token 消耗 + 费用估算 + 使用汇总
package com.gameai.ai

import android.content.Context
import com.gameai.db.AppDatabase
import com.gameai.db.UsageEntity
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object UsageTracker {

    private var db: AppDatabase? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 内存缓存：防止同一秒内重复写入同一条记录
    private val recentCache = ConcurrentHashMap<String, Long>()
    private val cacheWindowMs = 2000L

    fun init(context: Context) {
        db = AppDatabase.getInstance(context)
    }

    /** 记录一次 API 调用 */
    fun record(
        providerName: String,
        modelName: String,
        purpose: String,
        promptTokens: Int,
        completionTokens: Int = 0,
        latencyMs: Long = 0,
        success: Boolean = true
    ) {
        val total = promptTokens + completionTokens
        if (total <= 0) return

        // 简单的去重：同一分钟内同一模型的重复记录跳过
        val dedupKey = "$providerName:$modelName:$purpose:${System.currentTimeMillis() / cacheWindowMs}"
        val lastTime = recentCache.getOrDefault(dedupKey, 0L)
        if (System.currentTimeMillis() - lastTime < 500L) return
        recentCache[dedupKey] = System.currentTimeMillis()

        scope.launch {
            if (recentCache.size > 100) recentCache.clear()

            try {
                val record = UsageEntity(
                    recordId = UUID.randomUUID().toString(),
                    providerName = providerName,
                    modelName = modelName,
                    purpose = purpose,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = total,
                    latencyMs = latencyMs,
                    success = success
                )
                db?.usageDao()?.insertRecord(record)
            } catch (e: Exception) {
                android.util.Log.e("UsageTracker", "record failed", e)
            }
        }
    }

    /** 估算 token 数（基于字符数粗略估算，精确值从 API 响应中获取更好） */
    fun estimateTokens(text: String): Int {
        // 中文：约 1.5 字符 = 1 token，英文：约 4 字符 = 1 token
        if (text.isEmpty()) return 0
        val chineseCount = text.count { it in '\u4e00'..'\u9fff' }
        val otherCount = text.length - chineseCount
        return ((chineseCount / 1.5) + (otherCount / 4.0)).toInt().coerceAtLeast(1)
    }

    /** 获取今日用量摘要 */
    suspend fun getTodaySummary(): TodaySummary {
        val startOfDay = getStartOfToday()
        val sum = db?.usageDao()?.getTokenSummarySince(startOfDay)
        val modelBreakdown = db?.usageDao()?.getUsageByModelSince(startOfDay) ?: emptyList()

        var totalCost = 0.0
        for (m in modelBreakdown) {
            totalCost += UsageEntity.estimateCostYuan(
                m.providerName, m.modelName, m.totalTokens / 2, m.totalTokens / 2
            )
        }

        return TodaySummary(
            totalTokens = sum?.totalTokens ?: 0,
            promptTokens = sum?.promptTokens ?: 0,
            completionTokens = sum?.completionTokens ?: 0,
            estimatedCostYuan = totalCost,
            modelBreakdown = modelBreakdown
        )
    }

    /** 获取本周总用量 */
    suspend fun getWeekSummary(): TodaySummary {
        val startOfWeek = getStartOfWeek()
        val sum = db?.usageDao()?.getTokenSummarySince(startOfWeek)
        val modelBreakdown = db?.usageDao()?.getUsageByModelSince(startOfWeek) ?: emptyList()

        var totalCost = 0.0
        for (m in modelBreakdown) {
            totalCost += UsageEntity.estimateCostYuan(
                m.providerName, m.modelName, m.totalTokens / 2, m.totalTokens / 2
            )
        }

        return TodaySummary(
            totalTokens = sum?.totalTokens ?: 0,
            promptTokens = sum?.promptTokens ?: 0,
            completionTokens = sum?.completionTokens ?: 0,
            estimatedCostYuan = totalCost,
            modelBreakdown = modelBreakdown
        )
    }

    /** 清理 30 天前的旧记录 */
    suspend fun cleanup() {
        val thirtyDaysAgo = System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L
        db?.usageDao()?.deleteOldRecords(thirtyDaysAgo)
    }

    private fun getStartOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getStartOfWeek(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    data class TodaySummary(
        val totalTokens: Int,
        val promptTokens: Int,
        val completionTokens: Int,
        val estimatedCostYuan: Double,
        val modelBreakdown: List<com.gameai.db.UsageDao.ModelUsageSummary>
    ) {
        fun totalTokensFormatted(): String = when {
            totalTokens >= 1_000_000 -> String.format("%.1fM", totalTokens / 1_000_000.0)
            totalTokens >= 1_000 -> String.format("%.1fK", totalTokens / 1_000.0)
            else -> totalTokens.toString()
        }

        fun costFormatted(): String = when {
            estimatedCostYuan >= 1.0 -> String.format("%.2f 元", estimatedCostYuan)
            estimatedCostYuan >= 0.01 -> String.format("%.4f 元", estimatedCostYuan)
            else -> "< 0.01 元"
        }
    }
}
