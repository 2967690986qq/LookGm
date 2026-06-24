// GameAIApplication.kt - 应用入口
package com.gameai

import android.app.Application
import android.content.Intent
import android.util.Log
import com.gameai.service.VoiceService
import com.gameai.ui.MainActivity
import com.gameai.utils.PreferencesManager
import com.gameai.utils.UpdateManager
import com.tencent.mmkv.MMKV
import java.io.PrintWriter
import java.io.StringWriter

class GameAIApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化MMKV
        MMKV.initialize(this)

        // 初始化偏好设置
        PreferencesManager.getInstance(this)

        // 预初始化语音引擎
        VoiceService.init(this)

        // ===== 自动版本检测（每6小时检测一次）=====
        UpdateManager.checkOnStartup(this)

        // ===== 全局崩溃处理器 =====
        setupCrashHandler()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()
            Log.e("GameAI", "未捕获异常: $stackTrace")

            // 保存崩溃日志到 MMKV
            val prefs = PreferencesManager.getInstance(this)
            prefs.saveString("last_crash_log", "$stackTrace")
            prefs.saveString("last_crash_time", System.currentTimeMillis().toString())

            // 交还给默认处理器
            defaultHandler?.uncaughtException(thread, throwable)

            // 如果默认处理器没处理（极端情况），杀死进程防止卡死
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }
}
