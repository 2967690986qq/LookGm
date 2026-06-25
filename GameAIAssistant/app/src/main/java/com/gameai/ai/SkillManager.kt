// SkillManager.kt — 策略技能管理器
// 参考 OpenClaw Skills 系统：技能以可组合的提示词片段存在，按游戏/英雄/类别组织，可启用/禁用
package com.gameai.ai

import android.content.Context
import com.gameai.db.AppDatabase
import com.gameai.db.SkillEntity
import kotlinx.coroutines.flow.Flow

object SkillManager {

    private var db: AppDatabase? = null

    private val dao get() = db?.skillDao()

    fun init(context: Context) {
        db = AppDatabase.getInstance(context)
    }

    fun getEnabledSkills(): Flow<List<SkillEntity>>? = dao?.getEnabledSkills()

    fun getSkillsByGame(gameName: String): Flow<List<SkillEntity>>? = dao?.getSkillsByGame(gameName)

    fun getSkillsByHero(heroName: String): Flow<List<SkillEntity>>? = dao?.getSkillsByHero(heroName)

    /**
     * 构建注入 AI 的完整技能提示词上下文
     * 参考 OpenClaw 的 SKILL.md 编码风格 — 紧凑、只注入核心要点
     */
    suspend fun buildSkillContext(gameName: String, heroName: String = "", maxSkills: Int = 5): String {
        var skills = dao?.getSkillsByGameSnapshot(gameName) ?: emptyList()

        // 如果有英雄名，优先放英雄专属技能 + 通用技能
        if (heroName.isNotBlank()) {
            val heroSkills = dao?.getSkillsByHeroSnapshot(heroName) ?: emptyList()
            // 去重+优先：英雄专属在前
            val merged = LinkedHashSet<SkillEntity>()
            merged.addAll(heroSkills)
            merged.addAll(skills)
            skills = merged.toList()
        }

        if (skills.isEmpty()) return ""

        val snippets = skills.take(maxSkills).map { it.toSystemPromptSnippet() }
        return buildString {
            append("\n\n## 已装载策略技能\n")
            append("以下是对当前对局的游戏策略技能知识，请将它们融入你的分析和建议中：\n\n")
            append(snippets.joinToString("\n\n"))
        }
    }

    /** 导入内置技能 */
    suspend fun importBuiltinSkills() {
        if ((dao?.getBuiltinSkillCount() ?: 0) >= 5) return

        val builtinSkills = listOf(
            SkillEntity(
                skillId = "builtin_map_awareness",
                name = "地图意识",
                description = "提醒玩家关注小地图，及时察觉敌方动向",
                category = "strategy",
                gameName = "王者荣耀",
                content = "经常看小地图。每3-5秒扫一眼。敌方打野消失超过5秒立即提醒。河道视野缺失时警告。"
            ),
            SkillEntity(
                skillId = "builtin_laning_phase",
                name = "对线期策略",
                description = "对线期的补刀、换血、控线指导",
                category = "strategy",
                gameName = "王者荣耀",
                content = "优先补刀获取金币。注意敌方技能CD，在对手交完技能后换血。控线不要在劣势时推线。"
            ),
            SkillEntity(
                skillId = "builtin_teamfight_basics",
                name = "团战意识",
                description = "团战的站位、时机、目标选择",
                category = "strategy",
                gameName = "王者荣耀",
                content = "团战前确认队友位置。找准切入时机，优先击杀敌方C位。注意自己的站位不要脱节。"
            ),
            SkillEntity(
                skillId = "builtin_item_build",
                name = "出装思路",
                description = "根据局势动态调整装备推荐",
                category = "item_build",
                gameName = "王者荣耀",
                content = "看敌方阵容决定核心防御装。对方法师多先出魔女，物理多先出不详。优势出输出，劣势出防御。"
            ),
            SkillEntity(
                skillId = "builtin_objective_control",
                name = "资源控制",
                description = "暴君、主宰、防御塔等关键资源的争夺时机",
                category = "strategy",
                gameName = "王者荣耀",
                content = "暴君刷新前30秒开始集合。击杀敌方打野后是拿龙最佳时机。推塔优先于杀人。"
            ),
            SkillEntity(
                skillId = "builtin_roaming_support",
                name = "游走与支援",
                description = "中路和游走位的前期支援节奏",
                category = "strategy",
                gameName = "王者荣耀",
                content = "清完线立即游走支援边路。注意看小地图哪条路被压就帮哪路。支援完立刻回线不要漏兵。"
            ),
        )

        for (skill in builtinSkills) {
            dao?.insertSkill(skill)
        }
    }
}
