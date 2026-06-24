// ScreenStreamHelper.kt - 屏幕流辅助工具类
package com.gameai.screenstream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.YuvImage
import android.graphics.Rect
import java.io.ByteArrayOutputStream

object ScreenStreamHelper {

    fun yuvToBitmap(yuvData: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuvImage = YuvImage(yuvData, android.graphics.ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            val jpegData = out.toByteArray()
            BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
        } catch (e: Exception) {
            null
        }
    }

    fun bitmapToJpeg(bitmap: Bitmap, quality: Int = 60): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val ratio = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
        if (ratio >= 1f) return bitmap
        val newW = (bitmap.width * ratio).toInt()
        val newH = (bitmap.height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
