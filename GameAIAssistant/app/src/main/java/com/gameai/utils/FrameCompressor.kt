// FrameCompressor.kt - 帧压缩工具
package com.gameai.utils

import android.graphics.*
import java.io.ByteArrayOutputStream

object FrameCompressor {
    // 目标尺寸
    private const val TARGET_WIDTH = 360
    private const val TARGET_HEIGHT = 640

    // YUV到RGB转换
    fun yuvToBitmap(yuvData: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuvImage = YuvImage(yuvData, android.graphics.ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
            val jpegData = out.toByteArray()
            BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
        } catch (e: Exception) {
            null
        }
    }

    // Bitmap转换为JPEG字节数组
    fun bitmapToJpeg(bitmap: Bitmap, quality: Int = 60): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    // 缩放并压缩
    fun compressFrame(bitmap: Bitmap, quality: Int = 60): ByteArray {
        val scaled = if (bitmap.width > TARGET_WIDTH || bitmap.height > TARGET_HEIGHT) {
            val ratio = minOf(
                TARGET_WIDTH.toFloat() / bitmap.width,
                TARGET_HEIGHT.toFloat() / bitmap.height
            )
            val newWidth = (bitmap.width * ratio).toInt()
            val newHeight = (bitmap.height * ratio).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }
        val jpegData = bitmapToJpeg(scaled, quality)
        if (scaled != bitmap) scaled.recycle()
        return jpegData
    }

    // RGBA转Bitmap
    fun rgbaToBitmap(rgbaData: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgbaData))
        return bitmap
    }

    // 计算两帧之间的差异程度 (0.0 - 1.0)
    fun frameDiffPercent(frame1: ByteArray, frame2: ByteArray): Double {
        if (frame1.size != frame2.size) return 1.0
        var diffCount = 0
        val sampleStep = maxOf(1, frame1.size / 500)
        for (i in frame1.indices step sampleStep) {
            if (frame1[i] != frame2[i]) diffCount++
        }
        return diffCount.toDouble() / (frame1.size / sampleStep)
    }

    // 生成帧缩略图用于快速预览
    fun createThumbnail(bitmap: Bitmap, size: Int = 128): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, size, size * bitmap.height / bitmap.width, true)
    }
}
