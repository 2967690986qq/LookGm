package com.gameai.ai

import android.graphics.Bitmap
import com.gameai.model.MatchData
import com.gameai.common.constants.GameConstants
import com.gameai.utils.PreferencesManager
import com.gameai.model.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * OCR结构化分析引擎
 * 从DeepSeek-OCR识别结果中提取王者荣耀对局核心指标
 */
class OcrStructuredAnalyzer(private val appContext: android.content.Context) {

    companion object {
        @Volatile private var instance: OcrStructuredAnalyzer? = null
        fun init(context: android.content.Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = OcrStructuredAnalyzer(context.applicationContext)
                    }
                }
            }
        }
        fun getInstance(): OcrStructuredAnalyzer = instance
            ?: throw IllegalStateException("OcrStructuredAnalyzer not initialized")
    }

    private val prefs = PreferencesManager.getInstance(appContext)

    /**
     * OCR识别并结构化解析
     * 返回: OcrStructuredResult 结构化结果
     */
    suspend fun analyzeScreenshot(bitmap: Bitmap): OcrStructuredResult? {
        val visionConfig = getVisionConfig() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val ocrText = CloudAiClient.ocrRecognize(bitmap, visionConfig)
                if (ocrText.isNullOrBlank()) {
                    android.util.Log.w("OcrAnalyzer", "OCR识别结果为空")
                    return@withContext null
                }
                android.util.Log.d("OcrAnalyzer", "OCR原始文本长度: ${ocrText.length}")
                val result = parseOcrText(ocrText)
                android.util.Log.d("OcrAnalyzer", "结构化解析结果: $result")
                result
            } catch (e: Exception) {
                android.util.Log.e("OcrAnalyzer", "OCR分析失败", e)
                null
            }
        }
    }

    private fun getVisionConfig(): ProviderConfig? {
        return try {
            prefs.getVisionModelConfig()
        } catch (e: Exception) {
            android.util.Log.e("OcrAnalyzer", "获取视觉模型配置失败", e)
            null
        }
    }

    /**
     * 解析OCR文本，提取王者荣耀对局数据
     */
    fun parseOcrText(ocrText: String): OcrStructuredResult {
        val result = OcrStructuredResult()
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotBlank() }

        result.rawText = ocrText
        result.detectedFields = mutableListOf()

        // ===== 1. KDA 提取 =====
        extractKda(lines, result)

        // ===== 2. 经济提取 =====
        extractEconomy(lines, result)

        // ===== 3. 输出/承伤/参团率提取 =====
        extractBattleStats(lines, result)

        // ===== 4. 控龙/推塔提取 =====
        extractObjectives(lines, result)

        // ===== 5. 视野/控制/治疗提取 =====
        extractSupportStats(lines, result)

        // ===== 6. 游戏状态判断 =====
        detectGamePhase(lines, result)

        // ===== 7. 英雄/分路提取 =====
        extractHeroAndPosition(lines, result)

        return result
    }

    // ===== KDA 提取 =====
    private fun extractKda(lines: List<String>, result: OcrStructuredResult) {
        var kills = 0
        var deaths = 0
        var assists = 0
        var found = false

        for (line in lines) {
            // 模式: 击杀/死亡/助攻 或 K/D/A
            val kdaPattern = Regex("""(\d+)\s*[/|]\s*(\d+)\s*[/|]\s*(\d+)""")
            val match = kdaPattern.find(line)
            if (match != null) {
                val k = match.groupValues[1].toIntOrNull()
                val d = match.groupValues[2].toIntOrNull()
                val a = match.groupValues[3].toIntOrNull()
                if (k != null && d != null && a != null && k < 50 && d < 30 && a < 50) {
                    kills = k
                    deaths = d
                    assists = a
                    found = true
                    result.detectedFields.add("KDA: $k/$d/$a")
                    break
                }
            }
        }

        // 备用：分别查找击杀、死亡、助攻
        if (!found) {
            for (line in lines) {
                if (!foundKills(line, result)) kills = extractNumberByKeyword(line, "击杀", "击败", "kills")?.also {
                    result.detectedFields.add("击杀: $it")
                } ?: kills
                if (!foundDeaths(line, result)) deaths = extractNumberByKeyword(line, "死亡", "阵亡", "deaths")?.also {
                    result.detectedFields.add("死亡: $it")
                } ?: deaths
                if (!foundAssists(line, result)) assists = extractNumberByKeyword(line, "助攻", "assists")?.also {
                    result.detectedFields.add("助攻: $it")
                } ?: assists
            }
        }

        result.kills = kills
        result.deaths = deaths
        result.assists = assists
    }

    private fun foundKills(line: String, result: OcrStructuredResult): Boolean =
        result.detectedFields.any { it.contains("击杀") }
    private fun foundDeaths(line: String, result: OcrStructuredResult): Boolean =
        result.detectedFields.any { it.contains("死亡") }
    private fun foundAssists(line: String, result: OcrStructuredResult): Boolean =
        result.detectedFields.any { it.contains("助攻") }

    // ===== 经济提取 =====
    private fun extractEconomy(lines: List<String>, result: OcrStructuredResult) {
        var gold = 0
        var goldPerMin = 0

        for (line in lines) {
            // 经济/金币
            val goldMatch = Regex("""经济[^\d]*([\d,]+)""").find(line)
                ?: Regex("""金币[^\d]*([\d,]+)""").find(line)
                ?: Regex("""(\d{4,6})\s*金币""").find(line)
            if (goldMatch != null) {
                val value = goldMatch.groupValues[1].replace(",", "").toIntOrNull()
                if (value != null && value in 0..30000) {
                    gold = value
                    result.detectedFields.add("经济: $gold")
                    break
                }
            }
        }

        // 分均经济
        for (line in lines) {
            val gpmMatch = Regex("""分均经济[^\d]*([\d,]+)""").find(line)
                ?: Regex("""经济/分钟[^\d]*([\d,]+)""").find(line)
            if (gpmMatch != null) {
                val value = gpmMatch.groupValues[1].replace(",", "").toIntOrNull()
                if (value != null && value in 0..2000) {
                    goldPerMin = value
                    result.detectedFields.add("分均经济: $goldPerMin")
                    break
                }
            }
        }

        result.gold = gold
        result.goldPerMin = goldPerMin
    }

    // ===== 输出/承伤/参团率提取 =====
    private fun extractBattleStats(lines: List<String>, result: OcrStructuredResult) {
        for (line in lines) {
            // 输出占比
            val damageMatch = Regex("""输出占比[^\d]*(\d+\.?\d*)%""").find(line)
                ?: Regex("""伤害占比[^\d]*(\d+\.?\d*)%""").find(line)
            if (damageMatch != null) {
                val value = damageMatch.groupValues[1].toFloatOrNull()
                if (value != null && value in 0f..100f) {
                    result.damageDealtPercent = value
                    result.detectedFields.add("输出占比: ${value}%")
                }
            }

            // 承伤占比
            val tankMatch = Regex("""承伤占比[^\d]*(\d+\.?\d*)%""").find(line)
                ?: Regex("""承受伤害[^\d]*(\d+\.?\d*)%""").find(line)
            if (tankMatch != null) {
                val value = tankMatch.groupValues[1].toFloatOrNull()
                if (value != null && value in 0f..100f) {
                    result.damageTakenPercent = value
                    result.detectedFields.add("承伤占比: ${value}%")
                }
            }

            // 参团率
            val participationMatch = Regex("""参团率[^\d]*(\d+\.?\d*)%""").find(line)
                ?: Regex("""参团占比[^\d]*(\d+\.?\d*)%""").find(line)
            if (participationMatch != null) {
                val value = participationMatch.groupValues[1].toFloatOrNull()
                if (value != null && value in 0f..100f) {
                    result.participationRate = value
                    result.detectedFields.add("参团率: ${value}%")
                }
            }

            // 输出伤害数值
            val damageValueMatch = Regex("""输出伤害[^\d]*([\d,]+)""").find(line)
                ?: Regex("""总伤害[^\d]*([\d,]+)""").find(line)
            if (damageValueMatch != null) {
                val value = damageValueMatch.groupValues[1].replace(",", "").toIntOrNull()
                if (value != null && value > 0) {
                    result.damageDealt = value
                    result.detectedFields.add("输出伤害: $value")
                }
            }
        }
    }

    // ===== 控龙/推塔提取 =====
    private fun extractObjectives(lines: List<String>, result: OcrStructuredResult) {
        for (line in lines) {
            // 推塔
            val towerMatch = Regex("""推塔[^\d]*(\d+)""").find(line)
                ?: Regex("""摧毁防御塔[^\d]*(\d+)""").find(line)
            if (towerMatch != null) {
                val value = towerMatch.groupValues[1].toIntOrNull()
                if (value != null && value in 0..20) {
                    result.towers = value
                    result.detectedFields.add("推塔: $value")
                }
            }

            // 控龙（主宰/暴君）
            val dragonMatch = Regex("""控龙[^\d]*(\d+)""").find(line)
                ?: Regex("""击杀主宰[^\d]*(\d+)""").find(line)
                ?: Regex("""龙[^\d]*(\d+)""").find(line)
            if (dragonMatch != null) {
                val value = dragonMatch.groupValues[1].toIntOrNull()
                if (value != null && value in 0..10) {
                    result.dragons = value
                    result.detectedFields.add("控龙: $value")
                }
            }
        }
    }

    // ===== 视野/控制/治疗提取 =====
    private fun extractSupportStats(lines: List<String>, result: OcrStructuredResult) {
        for (line in lines) {
            // 视野价值
            val visionMatch = Regex("""视野价值[^\d]*(\d+\.?\d*)%""").find(line)
                ?: Regex("""视野占比[^\d]*(\d+\.?\d*)%""").find(line)
            if (visionMatch != null) {
                val value = visionMatch.groupValues[1].toFloatOrNull()
                if (value != null && value in 0f..100f) {
                    result.visionPercent = value
                    result.detectedFields.add("视野价值: ${value}%")
                }
            }

            // 控制时长
            val ccMatch = Regex("""控制时长[^\d]*(\d+\.?\d*)\s*秒""").find(line)
                ?: Regex("""硬控时长[^\d]*(\d+\.?\d*)\s*秒""").find(line)
            if (ccMatch != null) {
                val value = ccMatch.groupValues[1].toFloatOrNull()
                if (value != null && value >= 0f) {
                    result.ccDuration = value
                    result.detectedFields.add("控制时长: ${value}秒")
                }
            }

            // 治疗量
            val healMatch = Regex("""治疗量[^\d]*([\d,]+)""").find(line)
                ?: Regex("""总治疗[^\d]*([\d,]+)""").find(line)
            if (healMatch != null) {
                val value = healMatch.groupValues[1].replace(",", "").toIntOrNull()
                if (value != null && value > 0) {
                    result.healing = value
                    result.detectedFields.add("治疗量: $value")
                }
            }

            // 承伤数值
            val takenMatch = Regex("""承受伤害[^\d]*([\d,]+)""").find(line)
            if (takenMatch != null && !line.contains("%")) {
                val value = takenMatch.groupValues[1].replace(",", "").toIntOrNull()
                if (value != null && value > 0) {
                    result.damageTaken = value
                    result.detectedFields.add("承受伤害: $value")
                }
            }
        }
    }

    // ===== 游戏状态判断 =====
    private fun detectGamePhase(lines: List<String>, result: OcrStructuredResult) {
        val fullText = lines.joinToString(" ")

        result.gamePhase = when {
            // 结算页面
            hasAnyKeyword(fullText, "胜利", "失败", "平局", "结算", "MVP", "金牌", "银牌", "铜牌") -> {
                result.detectedFields.add("状态: 结算")
                GameConstants.MatchPhase.RESULT
            }
            // 选人/禁用页面
            hasAnyKeyword(fullText, "选择英雄", "禁用", "ban", "pick", "确认选择", "等待玩家") -> {
                result.detectedFields.add("状态: 选人")
                GameConstants.MatchPhase.HERO_SELECT
            }
            // 对局中
            hasAnyKeyword(fullText, "击杀", "助攻", "死亡", "金币", "技能", "装备", "回城") -> {
                result.detectedFields.add("状态: 对局中")
                GameConstants.MatchPhase.IN_GAME
            }
            // 大厅/匹配
            hasAnyKeyword(fullText, "开始游戏", "匹配中", "排位", "巅峰赛", "万象天工") -> {
                result.detectedFields.add("状态: 大厅/匹配")
                GameConstants.MatchPhase.LOBBY
            }
            else -> {
                result.detectedFields.add("状态: 未知")
                GameConstants.MatchPhase.LOBBY
            }
        }
    }

    // ===== 英雄/分路提取 =====
    private fun extractHeroAndPosition(lines: List<String>, result: OcrStructuredResult) {
        val heroList = listOf(
            "亚瑟", "安琪拉", "甄姬", "妲己", "鲁班七号", "后羿", "狄仁杰", "铠", "孙悟空",
            "韩信", "李白", "貂蝉", "赵云", "花木兰", "诸葛亮", "孙尚香", "虞姬", "马可波罗",
            "关羽", "张飞", "牛魔", "蔡文姬", "孙膑", "庄周", "东皇太一", "鬼谷子", "明世隐",
            "澜", "司马懿", "元歌", "曜", "李信", "马超", "蒙恬", "夏洛特", "司空震",
            "戈娅", "莱西奥", "赵怀真", "亚连", "姬小满", "海诺", "大司命"
        )

        val fullText = lines.joinToString(" ")

        for (hero in heroList) {
            if (fullText.contains(hero)) {
                result.heroName = hero
                result.detectedFields.add("英雄: $hero")
                break
            }
        }

        // 分路判断
        result.position = when {
            hasAnyKeyword(fullText, "对抗路", "上单", "坦边", "战边") -> GameConstants.GamePosition.TOP
            hasAnyKeyword(fullText, "中路", "中单", "法师") -> GameConstants.GamePosition.MID
            hasAnyKeyword(fullText, "发育路", "射手", "下路", "ADC") -> GameConstants.GamePosition.ADC
            hasAnyKeyword(fullText, "游走", "辅助", "软辅", "硬辅") -> GameConstants.GamePosition.SUPPORT
            hasAnyKeyword(fullText, "打野", "刺客", "野王") -> GameConstants.GamePosition.JUNGLE
            else -> GameConstants.GamePosition.MID
        }
        if (result.position != GameConstants.GamePosition.MID || result.heroName.isNotBlank()) {
            result.detectedFields.add("分路: ${positionName(result.position)}")
        }
    }

    // ===== 工具方法 =====
    private fun extractNumberByKeyword(line: String, vararg keywords: String): Int? {
        for (keyword in keywords) {
            if (line.contains(keyword)) {
                val match = Regex("""$keyword[^\d]*(\d+)""").find(line)
                if (match != null) {
                    val value = match.groupValues[1].toIntOrNull()
                    if (value != null) return value
                }
            }
        }
        return null
    }

    private fun hasAnyKeyword(text: String, vararg keywords: String): Boolean {
        return keywords.any { text.contains(it, ignoreCase = true) }
    }

    private fun positionName(pos: GameConstants.GamePosition): String = when (pos) {
        GameConstants.GamePosition.TOP -> "对抗路"
        GameConstants.GamePosition.MID -> "中路"
        GameConstants.GamePosition.ADC -> "发育路"
        GameConstants.GamePosition.SUPPORT -> "游走"
        GameConstants.GamePosition.JUNGLE -> "打野"
    }
}

/**
 * OCR结构化分析结果
 */
data class OcrStructuredResult(
    var rawText: String = "",
    var detectedFields: MutableList<String> = mutableListOf(),
    var gamePhase: GameConstants.MatchPhase = GameConstants.MatchPhase.LOBBY,
    var heroName: String = "",
    var position: GameConstants.GamePosition = GameConstants.GamePosition.MID,

    // KDA
    var kills: Int = 0,
    var deaths: Int = 0,
    var assists: Int = 0,

    // 经济
    var gold: Int = 0,
    var goldPerMin: Int = 0,

    // 战斗数据
    var damageDealt: Int = 0,
    var damageDealtPercent: Float = 0f,
    var damageTaken: Int = 0,
    var damageTakenPercent: Float = 0f,
    var participationRate: Float = 0f,

    // 目标
    var towers: Int = 0,
    var dragons: Int = 0,
    var barons: Int = 0,

    // 辅助数据
    var visionPercent: Float = 0f,
    var ccDuration: Float = 0f,
    var healing: Int = 0,

    // 时间
    var gameTimeSec: Int = 0
) {
    val kda: Float get() = if (deaths == 0) (kills + assists).toFloat() else (kills + assists).toFloat() / deaths

    /** 更新MatchData */
    fun applyToMatch(match: MatchData) {
        match.phase = gamePhase
        if (heroName.isNotBlank()) match.heroName = heroName
        match.position = position
        match.gameTimeSec = gameTimeSec

        match.kdaData = MatchData.KdaData(
            kills = kills,
            deaths = deaths,
            assists = assists
        )

        match.economyData = MatchData.EconomyData(
            gold = gold,
            goldPerMin = goldPerMin
        )

        match.teamfightData = MatchData.TeamfightData(
            participationRate = participationRate,
            damageDealt = damageDealt,
            damageTaken = damageTaken,
            healing = healing,
            ccScore = ccDuration.roundToInt()
        )

        match.objectiveData = MatchData.ObjectiveData(
            towers = towers,
            dragons = dragons,
            barons = barons
        )

        match.visionData = MatchData.VisionData(
            visionScore = visionPercent.roundToInt()
        )
    }

    override fun toString(): String {
        return "OcrResult(状态=$gamePhase, 英雄=$heroName, KDA=$kills/$deaths/$assists, " +
                "经济=$gold, 参团率=${participationRate}%, 输出占比=${damageDealtPercent}%, " +
                "承伤占比=${damageTakenPercent}%, 推塔=$towers, 控龙=$dragons)"
    }
}
