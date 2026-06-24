// GameReportActivity.kt - 对局复盘独立报告页面
package com.gameai.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.gameai.R
import com.gameai.ui.fragments.GameHistoryFragment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

class GameReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MATCH_ID = "match_id"
        const val EXTRA_GAME_NAME = "game_name"
        const val EXTRA_HERO_NAME = "hero_name"
        const val EXTRA_POSITION = "position"
        const val EXTRA_START_TIME = "start_time"
        const val EXTRA_END_TIME = "end_time"
        const val EXTRA_TOTAL_SCORE = "total_score"
        const val EXTRA_GRADE = "grade"
        const val EXTRA_KILLS = "kills"
        const val EXTRA_DEATHS = "deaths"
        const val EXTRA_ASSISTS = "assists"
        const val EXTRA_GPM = "gpm"
        const val EXTRA_DAMAGE = "damage"
        const val EXTRA_SCORE_JSON = "score_json"
        const val EXTRA_IS_VICTORY = "is_victory"
    }

    private val dateFormatFull = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // 评分维度颜色
    private val categoryColors = arrayOf(
        "#00E5FF", "#FF6B35", "#4CAF50", "#FFD700",
        "#FF4081", "#7C4DFF", "#00BCD4", "#FF9800"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_report)

        val extras = intent.extras ?: run { finish(); return }

        // 返回按钮
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        // 基本信息
        val heroName = extras.getString(EXTRA_HERO_NAME, "未知")
        val gameName = extras.getString(EXTRA_GAME_NAME, "未知")
        val position = extras.getString(EXTRA_POSITION, "未知")
        val startTime = extras.getLong(EXTRA_START_TIME, 0L)
        val endTime = extras.getLong(EXTRA_END_TIME, 0L)
        val grade = extras.getString(EXTRA_GRADE, "C")
        val isVictory = extras.getBoolean(EXTRA_IS_VICTORY, true)

        findViewById<TextView>(R.id.tv_hero_name).text = heroName
        findViewById<TextView>(R.id.tv_game_name).text = gameName
        findViewById<TextView>(R.id.tv_position).text = position
        findViewById<TextView>(R.id.tv_match_time).text = if (startTime > 0)
            dateFormatFull.format(Date(startTime)) else ""

        val durationMin = if (endTime > 0 && startTime > 0) {
            (endTime - startTime) / 1000 / 60
        } else 0
        findViewById<TextView>(R.id.tv_duration).text =
            if (durationMin > 0) "${durationMin}分钟" else ""

        // 段位徽章
        val gradeBadge = findViewById<TextView>(R.id.tv_grade_badge)
        gradeBadge.text = GameHistoryFragment.getGradeShortName(grade)
        val gradeColorInt = GameHistoryFragment.getGradeColor(grade)
        gradeBadge.background?.setTint(gradeColorInt)

        // 战绩数据
        val totalScore = extras.getInt(EXTRA_TOTAL_SCORE, 0)
        val kills = extras.getInt(EXTRA_KILLS, 0)
        val deaths = extras.getInt(EXTRA_DEATHS, 0)
        val assists = extras.getInt(EXTRA_ASSISTS, 0)
        val gpm = extras.getInt(EXTRA_GPM, 0)
        val damage = extras.getInt(EXTRA_DAMAGE, 0)

        findViewById<TextView>(R.id.tv_total_score).text = totalScore.toString()
        findViewById<TextView>(R.id.tv_kda).text = "$kills/$deaths/$assists"
        findViewById<TextView>(R.id.tv_gpm).text = gpm.toString()
        findViewById<TextView>(R.id.tv_damage).text = formatDamage(damage)

        val kdaRatio = if (deaths > 0) {
            String.format("%.1f", (kills + assists).toFloat() / deaths)
        } else {
            if (kills + assists > 0) "∞" else "0.0"
        }
        findViewById<TextView>(R.id.tv_kda_ratio).text = kdaRatio

        // 胜负标签
        val resultTv = findViewById<TextView>(R.id.tv_result)
        if (isVictory) {
            resultTv.text = "胜利 ✓"
            // keep default green background from bg_victory_tag
        } else {
            resultTv.text = "失败 ✗"
            resultTv.background?.setTint(Color.parseColor("#B71C1C"))
        }

        // 评分明细
        val scoreJson = extras.getString(EXTRA_SCORE_JSON)
        buildScoreCategories(scoreJson)

        // AI 分析
        buildAiAnalysis(scoreJson)
    }

    /** 格式化伤害数字（万为单位） */
    private fun formatDamage(damage: Int): String {
        return when {
            damage >= 1_000_000 -> String.format("%.2fM", damage / 1_000_000.0)
            damage >= 10_000 -> String.format("%.1f万", damage / 10_000.0)
            else -> damage.toString()
        }
    }

    /** 动态构建评分明细条 */
    private fun buildScoreCategories(scoreJson: String?) {
        val container = findViewById<LinearLayout>(R.id.layout_score_categories)
        container.removeAllViews()

        if (scoreJson.isNullOrBlank()) {
            container.addView(createEmptyHint("暂无评分明细"))
            return
        }

        try {
            val gson = Gson()
            val json = gson.fromJson(scoreJson, com.google.gson.JsonObject::class.java)
            val categoriesJson = json.getAsJsonObject("categories")
            if (categoriesJson == null || categoriesJson.entrySet().isEmpty()) {
                container.addView(createEmptyHint("暂无评分明细"))
                return
            }

            var colorIndex = 0
            for ((key, value) in categoriesJson.entrySet().toList()) {
                val cat = value.asJsonObject
                val name = cat.get("name")?.asString ?: key
                val score = cat.get("score")?.asInt ?: 0
                val maxScore = cat.get("maxScore")?.asInt ?: 100
                val rating = cat.get("rating")?.asString ?: ""

                val color = categoryColors[colorIndex % categoryColors.size]
                container.addView(createCategoryRow(name, score, maxScore, rating, color))
                colorIndex++
            }
        } catch (e: Exception) {
            container.addView(createEmptyHint("评分数据解析失败"))
        }
    }

    /** 创建单个评分维度行 */
    private fun createCategoryRow(
        name: String,
        score: Int,
        maxScore: Int,
        rating: String,
        colorHex: String
    ): View {
        val color = Color.parseColor(colorHex)
        val percentage = (score.toFloat() / maxScore * 100).toInt().coerceIn(0, 100)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp2px(10))
        }

        // 标签行：维度名 + 评分 + 等级
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp2px(4))
        }

        val nameTv = TextView(this).apply {
            text = name
            textSize = 14f
            setTextColor(Color.parseColor("#E8ECF1"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val ratingTv = TextView(this).apply {
            text = rating
            textSize = 13f
            setTextColor(color)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp2px(8) }
        }

        val scoreTv = TextView(this).apply {
            text = "$score/$maxScore"
            textSize = 13f
            setTextColor(Color.parseColor("#E8ECF1"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        labelRow.addView(nameTv)
        labelRow.addView(ratingTv)
        labelRow.addView(scoreTv)
        container.addView(labelRow)

        // 进度条
        val barBg = View(this).apply {
            setBackgroundColor(Color.parseColor("#1A2240"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2px(8)
            ).apply { topMargin = dp2px(2) }
        }

        val barFg = View(this).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(
                (percentage * dp2px(300) / 100), dp2px(8)
            ).apply { topMargin = dp2px(2) }
        }

        // 嵌套FrameLayout实现进度条
        val barLayout = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2px(8)
            )
            addView(barBg)
            addView(barFg)
        }

        container.addView(barLayout)
        // 重新设置barFg宽度基于实际容器
        barLayout.post {
            barFg.layoutParams.width = (percentage * barLayout.width / 100)
            barFg.requestLayout()
        }

        return container
    }

    private fun createEmptyHint(text: String): TextView {
        return TextView(this).apply {
            setText(text)
            setTextColor(Color.parseColor("#8899AA"))
            textSize = 13f
            setPadding(0, dp2px(8), 0, dp2px(8))
        }
    }

    /** 构建 AI 分析报告区域 */
    private fun buildAiAnalysis(scoreJson: String?) {
        val cardAi = findViewById<View>(R.id.card_ai_analysis)
        if (scoreJson.isNullOrBlank()) {
            cardAi.visibility = View.GONE
            return
        }

        try {
            val gson = Gson()
            val json = gson.fromJson(scoreJson, com.google.gson.JsonObject::class.java)
            val analysis = json.get("aiAnalysis")?.asString
            val advice = json.get("aiAdvice")?.asString

            if (analysis.isNullOrBlank() && advice.isNullOrBlank()) {
                cardAi.visibility = View.GONE
                return
            }

            cardAi.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tv_ai_analysis).text = analysis?.ifBlank { "暂无分析" } ?: "暂无分析"
            findViewById<TextView>(R.id.tv_ai_advice).text = advice?.ifBlank { "暂无需改进建议" } ?: "暂无需改进建议"
        } catch (e: Exception) {
            cardAi.visibility = View.GONE
        }
    }

    private fun dp2px(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
