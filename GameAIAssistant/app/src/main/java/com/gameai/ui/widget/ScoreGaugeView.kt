// ScoreGaugeView.kt - 圆形评分仪表盘自定义View（暗色电竞主题）
package com.gameai.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

class ScoreGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 暗色主题颜色
    private val bgArcColor = Color.parseColor("#1A2040")
    private val glowColor = Color.parseColor("#3300E5FF")
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val gradeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }

    private var currentScore = 0f
    private var targetScore = 0f
    private var currentGrade = "--"
    private var animatedProgress = 0f

    private var arcStrokeWidth = 0f
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var gradeCircleRadius = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            animatedProgress = it.animatedValue as Float
            invalidate()
        }
    }

    fun setScore(score: Int, grade: String) {
        if (score == targetScore.toInt() && grade == currentGrade) return
        targetScore = score.coerceIn(0, 100).toFloat()
        currentGrade = grade
        animator.cancel()
        animator.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        arcStrokeWidth = min(w, h) * 0.11f
        cx = w / 2f
        cy = h / 2f - arcStrokeWidth * 0.3f
        radius = (min(w, h) - arcStrokeWidth) / 2f - 6f
        gradeCircleRadius = radius * 0.28f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val lerp = animator.isRunning
        val displayScore = if (lerp) {
            currentScore + (targetScore - currentScore) * animatedProgress
        } else {
            currentScore
        }

        val arcColor = getScoreColor(displayScore.toInt())

        // 发光光晕（背景弧外侧）
        glowPaint.color = modifyAlpha(arcColor, 60)
        glowPaint.strokeWidth = arcStrokeWidth + 20f
        canvas.drawArc(
            cx - radius - 10f, cy - radius - 10f,
            cx + radius + 10f, cy + radius + 10f,
            135f, 270f, false, glowPaint
        )

        // 背景弧线
        trackPaint.color = bgArcColor
        trackPaint.strokeWidth = arcStrokeWidth
        trackPaint.maskFilter = null
        canvas.drawArc(
            cx - radius, cy - radius, cx + radius, cy + radius,
            135f, 270f, false, trackPaint
        )

        // 进度弧线带发光
        val sweepAngle = (displayScore / 100f) * 270f
        if (sweepAngle > 0) {
            // 发光层
            trackPaint.color = modifyAlpha(arcColor, 50)
            trackPaint.strokeWidth = arcStrokeWidth + 8f
            trackPaint.maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawArc(
                cx - radius - 4f, cy - radius - 4f,
                cx + radius + 4f, cy + radius + 4f,
                135f, sweepAngle, false, trackPaint
            )

            // 实体层
            trackPaint.color = arcColor
            trackPaint.strokeWidth = arcStrokeWidth
            trackPaint.maskFilter = null
            canvas.drawArc(
                cx - radius, cy - radius, cx + radius, cy + radius,
                135f, sweepAngle, false, trackPaint
            )
        }

        // 分数大数字
        textPaint.apply {
            textSize = radius * 0.50f
            typeface = Typeface.DEFAULT_BOLD
            color = Color.parseColor("#F0F4FF")
            setShadowLayer(8f, 0f, 0f, modifyAlpha(arcColor, 120))
        }
        canvas.drawText(displayScore.toInt().toString(), cx, cy + textPaint.textSize * 0.3f, textPaint)
        textPaint.clearShadowLayer()

        // "/100" 小字
        textPaint.apply {
            textSize = radius * 0.15f
            typeface = Typeface.DEFAULT
            color = Color.parseColor("#5A6488")
        }
        canvas.drawText("/100", cx, cy + radius * 0.44f, textPaint)

        // 评级圆形徽章
        val gcy = cy + radius + arcStrokeWidth * 0.6f
        gradeBgPaint.apply {
            color = arcColor
            style = Paint.Style.FILL
            setShadowLayer(12f, 0f, 0f, modifyAlpha(arcColor, 130))
        }
        canvas.drawCircle(cx, gcy, gradeCircleRadius, gradeBgPaint)
        gradeBgPaint.clearShadowLayer()

        // 评级文字
        textPaint.apply {
            textSize = gradeCircleRadius * 0.85f
            typeface = Typeface.DEFAULT_BOLD
            color = Color.WHITE
        }
        canvas.drawText(currentGrade, cx, gcy + textPaint.textSize * 0.35f, textPaint)

        // 动画结束时更新
        if (!animator.isRunning && targetScore != currentScore) {
            currentScore = targetScore
        }
    }

    companion object {
        fun getScoreColor(score: Int): Int = when {
            score >= 90 -> Color.parseColor("#00E676") // 绿 S/顶级
            score >= 80 -> Color.parseColor("#00E5FF") // 青 A/金牌
            score >= 65 -> Color.parseColor("#FFD700") // 金 B/银牌
            score >= 50 -> Color.parseColor("#7C3AED") // 紫 C/铜牌
            else -> Color.parseColor("#FF5252")          // 红 D
        }

        private fun modifyAlpha(color: Int, alpha: Int): Int {
            return (color and 0x00FFFFFF) or (alpha shl 24)
        }
    }
}
