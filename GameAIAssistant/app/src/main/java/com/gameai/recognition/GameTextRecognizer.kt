// GameTextRecognizer.kt - ML Kit 中文文字识别，提取游戏数据
package com.gameai.recognition

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.Text
import java.util.regex.Pattern

/**
 * 游戏画面文字识别器
 * 使用 ML Kit 中文识别，从游戏截图中提取 KDA、经济、对局时间等数据
 */
class GameTextRecognizer {

    companion object {
        private const val TAG = "GameTextRecognizer"
    }

    // ML Kit 中文文字识别器（使用默认配置，内部自行管理线程）
    private val recognizer: com.google.mlkit.vision.text.TextRecognizer =
        com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build()
        )

    /** 从 Bitmap 中识别文字并解析游戏数据 */
    fun recognize(bitmap: Bitmap, callback: (GameTextResult) -> Unit) {
        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val result = parseGameData(visionText)
                callback(result)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR 识别失败: ${e.message}")
                callback(GameTextResult())
            }
    }

    /** 解析 ML Kit 识别结果，提取游戏关键数据 */
    private fun parseGameData(visionText: Text): GameTextResult {
        val allText = visionText.text
        Log.d(TAG, "OCR 识别文字: $allText")

        // KDA 解析：匹配 "3/2/5" 或 "KDA 3/2/5" 格式
        val kdaPattern: Pattern = Pattern.compile("(\\d+)[/／](\\d+)[/／](\\d+)")
        var kills = -1
        var deaths = -1
        var assists = -1

        val blocks = visionText.textBlocks
        for (block in blocks) {
            for (line in block.lines) {
                val lineText = line.text
                val matcher = kdaPattern.matcher(lineText.replace(" ", ""))
                if (matcher.find()) {
                    try {
                        kills   = matcher.group(1).toInt()
                        deaths  = matcher.group(2).toInt()
                        assists = matcher.group(3).toInt()
                        Log.d(TAG, "识别到 KDA: $kills/$deaths/$assists (来源: $lineText)")
                        break
                    } catch (e: Exception) { Log.w(TAG, "parse error", e) }
                }
            }
            if (kills != -1) break
        }

        // 经济/金币解析：匹配数字 + "G" 或纯数字（在合理范围内 0~50000）
        var economy = -1
        val economyPattern: Pattern = Pattern.compile("(\\d{3,5})")
        for (block in blocks) {
            for (line in block.lines) {
                val lineText = line.text
                // 过滤掉明显不是经济的数字（如 FPS、评分等）
                if (lineText.contains("G", true) ||
                    lineText.contains("金", true) ||
                    lineText.contains("钱", true) ||
                    lineText.contains("￥", true)) {
                    val matcher = economyPattern.matcher(lineText.replace(" ", ""))
                    if (matcher.find()) {
                        val value = matcher.group(1).toInt()
                        if (value in 500..50000) {
                            economy = value
                            Log.d(TAG, "识别到经济: $economy (来源: $lineText)")
                            break
                        }
                    }
                }
            }
            if (economy != -1) break
        }

        // 对局时间解析：匹配 "12:34" 格式
        var gameTimeSec = -1
        val timePattern: Pattern = Pattern.compile("(\\d{1,2}):(\\d{2})")
        for (block in blocks) {
            for (line in block.lines) {
                val lineText = line.text
                val matcher = timePattern.matcher(lineText)
                if (matcher.find()) {
                    try {
                        val min = matcher.group(1).toInt()
                        val sec = matcher.group(2).toInt()
                        if (min in 0..60 && sec in 0..59) {
                            gameTimeSec = min * 60 + sec
                            Log.d(TAG, "识别到对局时间: $min:$sec")
                            break
                        }
                    } catch (e: Exception) { Log.w(TAG, "parse error", e) }
                }
            }
            if (gameTimeSec != -1) break
        }

        return GameTextResult(kills, deaths, assists, economy, gameTimeSec, allText)
    }

    fun release() {
        recognizer.close()
    }
}

/** OCR 识别结果数据类 */
data class GameTextResult(
    val kills: Int = -1,
    val deaths: Int = -1,
    val assists: Int = -1,
    val economy: Int = -1,
    val gameTimeSec: Int = -1,
    val rawText: String = ""
) {
    fun hasKda(): Boolean = kills != -1 && deaths != -1 && assists != -1
    fun hasEconomy(): Boolean = economy != -1
    fun hasGameTime(): Boolean = gameTimeSec != -1
    fun getKdaString(): String = if (hasKda()) "$kills/$deaths/$assists" else "--/--/--"
}
