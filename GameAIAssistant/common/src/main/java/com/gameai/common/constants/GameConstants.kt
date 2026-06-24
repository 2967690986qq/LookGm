// GameConstants.kt - 游戏相关常量
package com.gameai.common.constants

object GameConstants {
    // 游戏列表
    val SUPPORTED_GAMES = mapOf(
        "王者荣耀" to GameInfo("王者荣耀", "honor_of_kings", "moba"),
        "和平精英" to GameInfo("和平精英", "pubg_mobile", "fps"),
        "英雄联盟手游" to GameInfo("英雄联盟手游", "lol_wild_rift", "moba"),
        "原神" to GameInfo("原神", "genshin_impact", "rpg"),
        "崩坏：星穹铁道" to GameInfo("崩坏：星穹铁道", "honkai_star_rail", "rpg"),
        "金铲铲之战" to GameInfo("金铲铲之战", "teamfight_tactics", "auto_chess"),
        "穿越火线手游" to GameInfo("穿越火线手游", "cf_mobile", "fps"),
        "QQ飞车手游" to GameInfo("QQ飞车手游", "qq_speed", "racing")
    )

    // 对局阶段
    enum class MatchPhase(val displayName: String) {
        LOBBY("大厅"),
        MATCHING("匹配中"),
        RANK_LOBBY("排位大厅"),
        HERO_SELECT("选英雄"),
        LOADING("加载中"),
        IN_GAME("对局中"),
        RESULT("结算")
    }

    // 评分等级
    enum class ScoreGrade(val label: String, val minScore: Int, val maxScore: Int, val color: String) {
        S("S", 90, 100, "#FFD700"),
        A("A", 80, 89, "#FF6347"),
        B("B", 65, 79, "#4CAF50"),
        C("C", 50, 64, "#2196F3"),
        D("D", 0, 49, "#9E9E9E")
    }

    // 游戏位置
    enum class GamePosition(val label: String) {
        TOP("上路"),
        JUNGLE("打野"),
        MID("中路"),
        ADC("下路"),
        SUPPORT("辅助")
    }

    data class GameInfo(
        val name: String,
        val code: String,
        val genre: String
    )
}
