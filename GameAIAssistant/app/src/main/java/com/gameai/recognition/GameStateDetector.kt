// GameStateDetector.kt - 游戏状态检测器 (基于颜色/区域特征)
package com.gameai.recognition

import android.graphics.Bitmap
import android.graphics.Color
import com.gameai.common.constants.GameConstants

class GameStateDetector {

    // 检测对局阶段 (基于画面颜色特征)
    fun detectPhase(bitmap: Bitmap): GameConstants.MatchPhase {
        val width = bitmap.width
        val height = bitmap.height

        // 采样关键区域
        val topCenterColor = getRegionAverageColor(bitmap, width / 4, 0, width * 3 / 4, height / 10)
        val topRightColor = getRegionAverageColor(bitmap, width * 8 / 10, 0, width, height / 10)
        val bottomCenterColor = getRegionAverageColor(bitmap, width / 4, height * 9 / 10, width * 3 / 4, height)

        // 检测加载画面 (大面积黑色 + 加载进度条区域)
        if (isDarkColor(topCenterColor) && isDarkColor(bottomCenterColor)) {
            return GameConstants.MatchPhase.LOADING
        }

        // 检测对局中画面 (小地图通常右下角有颜色区域)
        val minimapColor = getRegionAverageColor(bitmap, width * 8 / 10, height * 7 / 10, width, height * 9 / 10)
        if (!isDarkColor(minimapColor) && getColorBrightness(minimapColor) in 30..200) {
            return GameConstants.MatchPhase.IN_GAME
        }

        // 检测结算画面 (通常中间有亮色弹窗)
        val centerColor = getRegionAverageColor(bitmap, width / 3, height / 3, width * 2 / 3, height * 2 / 3)
        if (getColorBrightness(centerColor) > 150 && !isDarkColor(centerColor)) {
            // 可能是结算或选人
            val topLeftColor = getRegionAverageColor(bitmap, 0, 0, width / 5, height / 10)
            if (isDarkColor(topLeftColor)) {
                return GameConstants.MatchPhase.RESULT
            }
            return GameConstants.MatchPhase.HERO_SELECT
        }

        // 大厅画面
        if (getColorBrightness(topCenterColor) > 100) {
            return GameConstants.MatchPhase.LOBBY
        }

        return GameConstants.MatchPhase.LOBBY
    }

    // 检测是否有小地图 (右下角区域检测)
    fun hasMinimap(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height

        // 小地图通常在右下角，约1/5画面大小
        val x1 = width * 75 / 100
        val y1 = height * 70 / 100
        val x2 = width * 98 / 100
        val y2 = height * 95 / 100

        val edgeColors = mutableListOf<Int>()
        // 检测边缘
        for (x in x1..x2 step 5) {
            edgeColors.add(bitmap.getPixel(x, y1))
            edgeColors.add(bitmap.getPixel(x, y2))
        }
        for (y in y1..y2 step 5) {
            edgeColors.add(bitmap.getPixel(x1, y))
            edgeColors.add(bitmap.getPixel(x2, y))
        }

        val darkCount = edgeColors.count { getColorBrightness(it) < 40 }
        return darkCount.toFloat() / edgeColors.size > 0.3f
    }

    // 检测击杀/死亡事件 (屏幕中央红色闪烁)
    fun detectKillEvent(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val centerColors = mutableListOf<Int>()
        val cx = width / 2
        val cy = height / 2
        val radius = width / 6

        for (x in cx - radius..cx + radius step 20) {
            for (y in cy - radius..cy + radius step 20) {
                if (x in 0 until width && y in 0 until height) {
                    centerColors.add(bitmap.getPixel(x, y))
                }
            }
        }

        val redCount = centerColors.count { pixel ->
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            r > 180 && g < 80 && b < 80
        }

        return redCount > centerColors.size * 0.15f
    }

    // 检测比分面板 (屏幕顶部中间区域)
    fun detectScoreboard(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val topBarColor = getRegionAverageColor(bitmap, width / 3, 0, width * 2 / 3, height / 15)
        return !isDarkColor(topBarColor) && getColorBrightness(topBarColor) > 50
    }

    // 辅助方法
    private fun getRegionAverageColor(bitmap: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val sx = x1.coerceIn(0, bitmap.width - 1)
        val sy = y1.coerceIn(0, bitmap.height - 1)
        val ex = x2.coerceIn(1, bitmap.width)
        val ey = y2.coerceIn(1, bitmap.height)

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0

        for (x in sx until ex step 5) {
            for (y in sy until ey step 5) {
                val pixel = bitmap.getPixel(x, y)
                rSum += Color.red(pixel)
                gSum += Color.green(pixel)
                bSum += Color.blue(pixel)
                count++
            }
        }

        if (count == 0) return Color.BLACK
        return Color.rgb(
            (rSum / count).toInt(),
            (gSum / count).toInt(),
            (bSum / count).toInt()
        )
    }

    private fun getColorBrightness(color: Int): Int {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
    }

    private fun isDarkColor(color: Int): Boolean {
        return getColorBrightness(color) < 30
    }
}
