// AIDetector.kt - AI检测引擎 (轻量版，不依赖NCNN)
package com.gameai.aiengine

import android.graphics.Bitmap
import android.graphics.Color

object AIDetector {

    // 检测游戏画面中的关键区域
    data class GameRegion(
        val minimap: RectArea?,
        val scoreboard: RectArea?,
        val heroInfo: RectArea?,
        val skillBar: RectArea?
    )

    data class RectArea(val x: Int, val y: Int, val width: Int, val height: Int)

    fun detectRegions(bitmap: Bitmap, screenWidth: Int, screenHeight: Int): GameRegion {
        val w = bitmap.width
        val h = bitmap.height

        // 小地图通常在右下角
        val minimap = RectArea(
            (w * 0.7).toInt(),
            (h * 0.65).toInt(),
            (w * 0.28).toInt(),
            (h * 0.3).toInt()
        )

        // 比分面板通常在顶部中间
        val scoreboard = RectArea(
            (w * 0.25).toInt(),
            0,
            (w * 0.5).toInt(),
            (h * 0.06).toInt()
        )

        // 英雄信息通常在左上角
        val heroInfo = RectArea(
            0,
            (h * 0.15).toInt(),
            (w * 0.2).toInt(),
            (h * 0.15).toInt()
        )

        // 技能栏通常在底部
        val skillBar = RectArea(
            (w * 0.15).toInt(),
            (h * 0.88).toInt(),
            (w * 0.7).toInt(),
            (h * 0.12).toInt()
        )

        return GameRegion(minimap, scoreboard, heroInfo, skillBar)
    }

    fun isDominantColor(bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int, targetColor: Int, tolerance: Int = 30): Boolean {
        val sx = x.coerceIn(0, bitmap.width - 1)
        val sy = y.coerceIn(0, bitmap.height - 1)
        val ex = (x + w).coerceIn(0, bitmap.width)
        val ey = (y + h).coerceIn(0, bitmap.height)

        var matchCount = 0
        var totalCount = 0

        for (px in sx until ex step 3) {
            for (py in sy until ey step 3) {
                val pixel = bitmap.getPixel(px, py)
                val dr = abs(Color.red(pixel) - Color.red(targetColor))
                val dg = abs(Color.green(pixel) - Color.green(targetColor))
                val db = abs(Color.blue(pixel) - Color.blue(targetColor))
                if (dr <= tolerance && dg <= tolerance && db <= tolerance) matchCount++
                totalCount++
            }
        }

        return totalCount > 0 && matchCount.toFloat() / totalCount > 0.5f
    }

    private fun abs(v: Int) = if (v < 0) -v else v
}
