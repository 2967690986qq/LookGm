// CommonExtensions.kt - 通用扩展函数
package com.gameai.common.extensions

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

// Bitmap扩展
fun Bitmap.toJpegByteArray(quality: Int = 60): ByteArray {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, stream)
    return stream.toByteArray()
}

fun Bitmap.resize(maxWidth: Int, maxHeight: Int): Bitmap {
    val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
    if (ratio >= 1f) return this
    val matrix = Matrix().apply { postScale(ratio, ratio) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

fun ByteArray.toBitmap(): Bitmap? {
    return BitmapFactory.decodeByteArray(this, 0, size)
}

// 帧差分检测
fun ByteArray.frameDifference(other: ByteArray, threshold: Int = 5): Boolean {
    if (size != other.size) return true
    var diffCount = 0
    val sampleRate = maxOf(1, size / 1000) // 采样1/1000
    for (i in indices step sampleRate) {
        if (abs((this[i].toInt() and 0xFF) - (other[i].toInt() and 0xFF)) > threshold) {
            diffCount++
        }
    }
    return diffCount > sampleRate / 10 // 超过10%的采样点变化
}

// MD5哈希
fun String.md5(): String {
    val digest = MessageDigest.getInstance("MD5")
    return digest.digest(toByteArray()).joinToString("") { "%02x".format(it) }
}

// 当前时间戳
fun currentTimestamp(): Long = System.currentTimeMillis()

// 安全的数字转换
fun String?.safeToInt(default: Int = 0): Int {
    return try { this?.toInt() ?: default } catch (e: Exception) { default }
}

fun String?.safeToFloat(default: Float = 0f): Float {
    return try { this?.toFloat() ?: default } catch (e: Exception) { default }
}
