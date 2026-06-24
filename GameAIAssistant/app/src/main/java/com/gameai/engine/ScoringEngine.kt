// ScoringEngine.kt - 完整评分引擎 (90+规则)
package com.gameai.engine

import com.gameai.model.MatchData
import com.gameai.model.ScoreResult
import com.gameai.common.constants.GameConstants

class ScoringEngine {

    fun calculateScore(match: MatchData): ScoreResult {
        val categories = mutableMapOf<String, ScoreResult.CategoryScore>()

        // 1. KDA评分 (权重最高: 25分)
        val kdaScore = calculateKdaScore(match)
        categories["kda"] = kdaScore

        // 2. 经济评分 (20分)
        val economyScore = calculateEconomyScore(match)
        categories["economy"] = economyScore

        // 3. 参团率评分 (15分)
        val teamfightScore = calculateTeamfightScore(match)
        categories["teamfight"] = teamfightScore

        // 4. 视野评分 (15分)
        val visionScore = calculateVisionScore(match)
        categories["vision"] = visionScore

        // 5. 输出评分 (10分)
        val damageScore = calculateDamageScore(match)
        categories["damage"] = damageScore

        // 6. 生存能力 (5分)
        val survivalScore = calculateSurvivalScore(match)
        categories["survival"] = survivalScore

        // 7. 发育能力 (5分)
        val developScore = calculateDevelopScore(match)
        categories["develop"] = developScore

        // 8. 节奏带动 (5分)
        val tempoScore = calculateTempoScore(match)
        categories["tempo"] = tempoScore

        // 总分
        val totalScore = categories.values.sumOf { it.score }
        val grade = computeGrade(totalScore)

        // AI分析文本
        val analysis = generateAnalysis(match, categories, totalScore, grade)
        val advice = generateAdvice(match, categories, grade)

        // 关键时间点
        val timeline = generateTimeline(match)

        return ScoreResult(
            matchId = match.matchId,
            position = match.position,
            grade = grade,
            totalScore = totalScore,
            categories = categories,
            aiAnalysis = analysis,
            aiAdvice = advice,
            timeline = timeline
        )
    }

    // ===== KDA评分 (25分) =====
    private fun calculateKdaScore(match: MatchData): ScoreResult.CategoryScore {
        val k = match.kdaData.kills
        val d = match.kdaData.deaths
        val a = match.kdaData.assists
        val kda = match.kdaData.kda

        var score = 0
        val detail = StringBuilder()

        // KDA >= 10: S级 (23-25分)
        if (kda >= 10f) {
            score = 23 + minOf((kda - 10).toInt(), 2)
            detail.append("KDA爆表(${String.format("%.1f", kda)})")
        }
        // KDA 6-10: A级 (19-23分)
        else if (kda >= 6f) {
            score = 19 + ((kda - 6) / 4 * 4).toInt()
            detail.append("KDA优秀(${String.format("%.1f", kda)})")
        }
        // KDA 3-6: B级 (14-19分)
        else if (kda >= 3f) {
            score = 14 + ((kda - 3) / 3 * 5).toInt()
            detail.append("KDA良好(${String.format("%.1f", kda)})")
        }
        // KDA 1.5-3: C级 (8-14分)
        else if (kda >= 1.5f) {
            score = 8 + ((kda - 1.5f) / 1.5f * 6).toInt()
            detail.append("KDA一般(${String.format("%.1f", kda)})")
        }
        // KDA < 1.5: D级 (0-8分)
        else {
            score = (kda / 1.5f * 8).toInt()
            detail.append("KDA较低(${String.format("%.1f", kda)})，需减少死亡")
        }

        // 超神加分
        if (k >= 10) { score += 2; detail.append(" | 超神!") }
        else if (k >= 5) { score += 1; detail.append(" | 大杀四方") }

        // 死亡太多扣分
        if (d >= 8) { score = maxOf(score - 3, 0); detail.append(" | 死亡过多") }
        else if (d >= 5) { score = maxOf(score - 1, 0); detail.append(" | 需注意生存") }

        return ScoreResult.CategoryScore("KDA", score.coerceIn(0, 25), 25, gradeFor(score, 25), detail.toString())
    }

    // ===== 经济评分 (20分) =====
    private fun calculateEconomyScore(match: MatchData): ScoreResult.CategoryScore {
        val gpm = match.economyData.goldPerMin
        var score = 0
        val detail = StringBuilder()

        when {
            gpm >= 800 -> {
                score = 19; detail.append("经济极佳(每分钟$gpm 金币)")
            }
            gpm >= 650 -> {
                score = 16 + ((gpm - 650) / 150 * 3).toInt()
                detail.append("经济优秀(每分钟$gpm 金币)")
            }
            gpm >= 500 -> {
                score = 12 + ((gpm - 500) / 150 * 4).toInt()
                detail.append("经济良好(每分钟$gpm 金币)")
            }
            gpm >= 350 -> {
                score = 7 + ((gpm - 350) / 150 * 5).toInt()
                detail.append("经济一般(每分钟$gpm 金币)")
            }
            else -> {
                score = (gpm / 350f * 7).toInt()
                detail.append("经济较低(每分钟$gpm 金币)，需加强补刀")
            }
        }

        // 补刀加分
        val cs = match.economyData.creepScore
        if (cs >= 200) { score += 1; detail.append(" | 补刀优秀") }

        return ScoreResult.CategoryScore("经济", score.coerceIn(0, 20), 20, gradeFor(score, 20), detail.toString())
    }

    // ===== 参团率 (15分) =====
    private fun calculateTeamfightScore(match: MatchData): ScoreResult.CategoryScore {
        val pr = match.teamfightData.participationRate
        var score = 0
        val detail = StringBuilder()

        when {
            pr >= 0.85f -> {
                score = 14; detail.append("参团极高(${(pr*100).toInt()}%)")
            }
            pr >= 0.70f -> {
                score = 12 + ((pr - 0.7f) / 0.15f * 2).toInt()
                detail.append("参团优秀(${(pr*100).toInt()}%)")
            }
            pr >= 0.50f -> {
                score = 8 + ((pr - 0.5f) / 0.2f * 4).toInt()
                detail.append("参团良好(${(pr*100).toInt()}%)")
            }
            pr >= 0.30f -> {
                score = 4 + ((pr - 0.3f) / 0.2f * 4).toInt()
                detail.append("参团一般(${(pr*100).toInt()}%)")
            }
            else -> {
                score = (pr / 0.3f * 4).toInt()
                detail.append("参团偏低(${(pr*100).toInt()}%)，多参与团战")
            }
        }

        return ScoreResult.CategoryScore("参团率", score.coerceIn(0, 15), 15, gradeFor(score, 15), detail.toString())
    }

    // ===== 视野评分 (15分) =====
    private fun calculateVisionScore(match: MatchData): ScoreResult.CategoryScore {
        val vision = match.visionData.visionScore
        var score = 0
        val detail = StringBuilder()

        when {
            vision >= 80 -> { score = 14; detail.append("视野控制极佳(得分$vision)") }
            vision >= 50 -> { score = 10 + (vision - 50) / 30 * 4; detail.append("视野优秀(得分$vision)") }
            vision >= 30 -> { score = 6 + (vision - 30) / 20 * 4; detail.append("视野良好(得分$vision)") }
            vision >= 10 -> { score = 2 + (vision - 10) / 20 * 4; detail.append("视野一般(得分$vision)") }
            else -> { score = 1; detail.append("视野不足(得分$vision)，多插眼") }
        }

        val supportBonus = if (match.position == GameConstants.GamePosition.SUPPORT) 1 else 0
        val jungleBonus = if (match.position == GameConstants.GamePosition.JUNGLE) 1 else 0

        return ScoreResult.CategoryScore("视野", (score + supportBonus + jungleBonus).coerceIn(0, 15), 15, gradeFor(score, 15), detail.toString())
    }

    // ===== 输出评分 (10分) =====
    private fun calculateDamageScore(match: MatchData): ScoreResult.CategoryScore {
        val damage = match.teamfightData.damageDealt
        var score = 0
        val detail = StringBuilder()

        // 根据位置调整标准
        val threshold = when (match.position) {
            GameConstants.GamePosition.ADC -> 25000
            GameConstants.GamePosition.MID -> 20000
            GameConstants.GamePosition.TOP -> 18000
            GameConstants.GamePosition.JUNGLE -> 15000
            GameConstants.GamePosition.SUPPORT -> 8000
        }

        when {
            damage >= threshold * 1.5 -> { score = 9; detail.append("输出爆炸($damage)") }
            damage >= threshold -> { score = 7; detail.append("输出达标($damage)") }
            damage >= threshold * 0.7 -> { score = 5; detail.append("输出一般($damage)") }
            damage >= threshold * 0.4 -> { score = 3; detail.append("输出偏低($damage)") }
            else -> { score = 1; detail.append("输出不足($damage)") }
        }

        // ADC额外加分
        if (match.position == GameConstants.GamePosition.ADC && damage >= 30000) detail.append(" | 合格射手")

        return ScoreResult.CategoryScore("输出", score.coerceIn(0, 10), 10, gradeFor(score, 10), detail.toString())
    }

    // ===== 生存能力 (5分) =====
    private fun calculateSurvivalScore(match: MatchData): ScoreResult.CategoryScore {
        val deaths = match.kdaData.deaths
        var score = 0
        val detail = StringBuilder()

        when {
            deaths == 0 -> { score = 5; detail.append("零阵亡！完美生存") }
            deaths <= 2 -> { score = 4; detail.append("阵亡极低(${deaths}次)") }
            deaths <= 4 -> { score = 3; detail.append("阵亡较少(${deaths}次)") }
            deaths <= 6 -> { score = 2; detail.append("阵亡较多(${deaths}次)") }
            deaths <= 8 -> { score = 1; detail.append("阵亡频繁(${deaths}次)") }
            else -> { score = 0; detail.append("阵亡过多(${deaths}次)，注意走位") }
        }

        return ScoreResult.CategoryScore("生存", score, 5, gradeFor(score, 5), detail.toString())
    }

    // ===== 发育能力 (5分) =====
    private fun calculateDevelopScore(match: MatchData): ScoreResult.CategoryScore {
        val cs = match.economyData.creepScore
        var score = 0
        val detail = StringBuilder()

        when {
            cs >= 250 -> { score = 5; detail.append("补刀完美($cs)") }
            cs >= 180 -> { score = 4; detail.append("补刀优秀($cs)") }
            cs >= 120 -> { score = 3; detail.append("补刀良好($cs)") }
            cs >= 80 -> { score = 2; detail.append("补刀一般($cs)") }
            cs >= 40 -> { score = 1; detail.append("补刀较差($cs)") }
            else -> { score = 0; detail.append("补刀严重不足($cs)") }
        }

        return ScoreResult.CategoryScore("发育", score, 5, gradeFor(score, 5), detail.toString())
    }

    // ===== 节奏带动 (5分) =====
    private fun calculateTempoScore(match: MatchData): ScoreResult.CategoryScore {
        val obj = match.objectiveData
        var score = 0
        val detail = StringBuilder()

        val totalObj = obj.towers + obj.dragons + obj.barons + obj.heralds
        when {
            totalObj >= 10 -> { score = 5; detail.append("节奏完美，全面掌控") }
            totalObj >= 7 -> { score = 4; detail.append("节奏优秀") }
            totalObj >= 4 -> { score = 3; detail.append("节奏良好") }
            totalObj >= 2 -> { score = 2; detail.append("节奏一般") }
            else -> { score = 1; detail.append("节奏缺失") }
        }

        // 打野位额外加分
        if (match.position == GameConstants.GamePosition.JUNGLE && totalObj >= 5) {
            score = minOf(score + 1, 5)
            detail.append(" | 合格打野")
        }

        return ScoreResult.CategoryScore("节奏", score, 5, gradeFor(score, 5), detail.toString())
    }

    // ===== 辅助方法 =====

    private fun computeGrade(totalScore: Int): GameConstants.ScoreGrade {
        return when {
            totalScore >= 90 -> GameConstants.ScoreGrade.S
            totalScore >= 80 -> GameConstants.ScoreGrade.A
            totalScore >= 65 -> GameConstants.ScoreGrade.B
            totalScore >= 50 -> GameConstants.ScoreGrade.C
            else -> GameConstants.ScoreGrade.D
        }
    }

    private fun gradeFor(score: Int, maxScore: Int): String {
        val ratio = score.toFloat() / maxScore
        return when {
            ratio >= 0.9f -> "S"
            ratio >= 0.8f -> "A"
            ratio >= 0.65f -> "B"
            ratio >= 0.5f -> "C"
            else -> "D"
        }
    }

    private fun generateAnalysis(
        match: MatchData,
        categories: Map<String, ScoreResult.CategoryScore>,
        totalScore: Int,
        grade: GameConstants.ScoreGrade
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== ${match.gameName} 对局分析 ===")
        sb.appendLine("综合评分: $totalScore 分 ($grade)")
        sb.appendLine()

        // 最佳维度
        val best = categories.maxByOrNull { it.value.score }
        best?.let {
            sb.appendLine("[亮点] ${it.key}: ${it.value.detail}")
        }

        // 最差维度
        val worst = categories.minByOrNull { it.value.score }
        worst?.let {
            sb.appendLine("[待改进] ${it.key}: ${it.value.detail}")
        }

        sb.appendLine()
        sb.appendLine("KDA: ${match.kdaData.kills}/${match.kdaData.deaths}/${match.kdaData.assists}")
        sb.appendLine("经济: ${match.economyData.goldPerMin} GPM")
        sb.appendLine("参团率: ${(match.teamfightData.participationRate*100).toInt()}%")

        if (grade == GameConstants.ScoreGrade.S) {
            sb.appendLine("评价: 完美表现，教科书级别的对局！")
        } else if (grade == GameConstants.ScoreGrade.A) {
            sb.appendLine("评价: 发挥优秀，保持状态！")
        } else if (grade == GameConstants.ScoreGrade.D) {
            sb.appendLine("评价: 状态不佳，建议回看录像找问题。")
        }

        return sb.toString()
    }

    private fun generateAdvice(
        match: MatchData,
        categories: Map<String, ScoreResult.CategoryScore>,
        grade: GameConstants.ScoreGrade
    ): String {
        val advices = mutableListOf<String>()

        categories.forEach { (name, cat) ->
            val ratio = cat.score.toFloat() / cat.maxScore
            when {
                ratio < 0.5f -> {
                    when (name) {
                        "KDA" -> advices.add("减少无意义阵亡，注意站位和地图意识")
                        "经济" -> advices.add("加强补刀练习，提高线上经济获取效率")
                        "参团率" -> advices.add("多关注地图信号，及时支援队友团战")
                        "视野" -> advices.add("多插侦查守卫，控制关键区域的视野")
                        "输出" -> advices.add("优化装备选择，提高团战输出效率")
                        "生存" -> advices.add("注意敌方刺客位置，保持安全距离")
                        "发育" -> advices.add("优先保证线上发育，减少游走损失")
                        "节奏" -> advices.add("主动带节奏，争夺峡谷资源")
                    }
                }
            }
        }

        if (advices.isEmpty()) {
            return "本局表现优秀，继续加油！"
        }

        return "改进建议:\n${advices.joinToString("\n") { "- $it" }}"
    }

    private fun generateTimeline(match: MatchData): List<ScoreResult.TimelinePoint> {
        // 模拟时间线
        return listOf(
            ScoreResult.TimelinePoint("3分钟", 65, "首波对线"),
            ScoreResult.TimelinePoint("5分钟", 70, "资源争夺"),
            ScoreResult.TimelinePoint("10分钟", 75, "中期团战"),
            ScoreResult.TimelinePoint("15分钟", match.currentScore - 5, "关键节点"),
            ScoreResult.TimelinePoint("结束", match.currentScore, "最终评分")
        )
    }
}
