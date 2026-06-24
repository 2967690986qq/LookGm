// UpdateManager.kt - GitHub Releases 自动检测与内置更新（无需跳转浏览器）
package com.gameai.utils

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.gameai.R
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * UpdateManager - GitHub Releases 版本检测与内置静默更新
 *
 * 功能：
 * 1. 自动检测 GitHub Releases 最新版本
 * 2. 弹窗展示更新日志、版本特性
 * 3. 后台静默下载 APK
 * 4. 下载完成后一键弹窗安装（无需跳转外部页面）
 * 5. 版本择优：默认稳定版，可选测试版通道
 * 6. 支持「忽略当前版本」「稍后提醒」
 */
object UpdateManager {

    private const val TAG = "UpdateManager"

    // ===== 配置项 =====
    // 替换为实际 GitHub 仓库，例如 "your-github-username/GameAIAssistant"
    private const val GITHUB_REPO = "2967690986qq/LookGm"
    private const val GITHUB_RELEASES_API = "https://api.github.com/repos/$GITHUB_REPO/releases"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L  // 6小时检测一次

    private const val DOWNLOAD_CHANNEL_ID = "update_download_channel"
    private const val DOWNLOAD_NOTIFY_ID = 9901
    private const val PREFS_NAME = "update_prefs"
    private const val KEY_IGNORED_VERSION = "ignored_version"
    private const val KEY_BETA_CHANNEL = "beta_channel"
    private const val KEY_LAST_CHECK = "last_check_time"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var downloadJob: Job? = null
    private var checkJob: Job? = null

    data class ReleaseInfo(
        val versionName: String,     // 格式：20260623 或 20260623-beta1
        val tagName: String,
        val releaseName: String,
        val body: String,            // 更新日志
        val apkUrl: String,
        val isBeta: Boolean,
        val publishedAt: String
    )

    // ===== 外部入口 =====

    /**
     * 启动时检测（APP启动调用，带节流：6小时内不重复检测）
     */
    fun checkOnStartup(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()

        if (now - lastCheck < CHECK_INTERVAL_MS) {
            Log.d(TAG, "距上次检测时间不足6小时，跳过")
            return
        }

        checkForUpdate(context, silent = true)
    }

    /**
     * 手动检测（用户主动触发，立即执行，有Toast反馈）
     */
    fun checkManually(context: Context) {
        checkForUpdate(context, silent = false)
    }

    // ===== 核心检测逻辑 =====

    private fun checkForUpdate(context: Context, silent: Boolean) {
        checkJob?.cancel()
        checkJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

                val betaChannel = prefs.getBoolean(KEY_BETA_CHANNEL, false)
                val ignoredVersion = prefs.getString(KEY_IGNORED_VERSION, "") ?: ""

                // 1. 请求 GitHub Releases API
                val releases = fetchReleases()
                if (releases.isEmpty()) {
                    if (!silent) {
                        mainHandler.post {
                            Toast.makeText(context, "暂未找到可用更新", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }

                // 2. 择优：默认稳定版，beta通道选beta
                val latest = releases.firstOrNull { release ->
                    if (betaChannel) true else !release.isBeta
                } ?: releases.first()

                // 3. 对比版本号
                val currentVersion = getCurrentVersion(context)
                Log.d(TAG, "本地版本：$currentVersion，远端最新：${latest.versionName}")

                val hasUpdate = compareVersions(latest.versionName, currentVersion) > 0

                if (!hasUpdate) {
                    if (!silent) {
                        mainHandler.post {
                            Toast.makeText(context, "当前已是最新版本 ${currentVersion}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }

                // 4. 已被用户忽略的版本直接跳过（静默模式下才跳过）
                if (silent && ignoredVersion == latest.versionName) {
                    Log.d(TAG, "版本 ${latest.versionName} 已被用户忽略，跳过")
                    return@launch
                }

                // 5. 在主线程弹更新对话框
                mainHandler.post {
                    showUpdateDialog(context, latest, currentVersion)
                }

            } catch (e: Exception) {
                Log.e(TAG, "检测更新异常: ${e.message}", e)
                if (!silent) {
                    mainHandler.post {
                        Toast.makeText(context, "检测失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun fetchReleases(): List<ReleaseInfo> {
        val url = URL(GITHUB_RELEASES_API)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("User-Agent", "GameAIAssistant-Android")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000

        val response = try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }

        val releases = mutableListOf<ReleaseInfo>()
        val jsonArray = org.json.JSONArray(response)

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            if (obj.optBoolean("draft", false)) continue  // 草稿跳过

            val tagName = obj.optString("tag_name", "")
            val releaseName = obj.optString("name", tagName)
            val body = obj.optString("body", "")
            val publishedAt = obj.optString("published_at", "")
            val isBeta = tagName.lowercase().contains("beta") || obj.optBoolean("prerelease", false)

            // 解析 versionName（去掉 "v" 前缀）
            val versionName = tagName.removePrefix("v")

            // 找 APK 下载地址
            val assets = obj.optJSONArray("assets") ?: continue
            var apkUrl = ""
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url", "")
                    break
                }
            }
            if (apkUrl.isEmpty()) continue  // 没有APK资产跳过

            releases.add(ReleaseInfo(
                versionName = versionName,
                tagName = tagName,
                releaseName = releaseName,
                body = body,
                apkUrl = apkUrl,
                isBeta = isBeta,
                publishedAt = publishedAt
            ))
        }

        return releases
    }

    // ===== 更新提示对话框 =====

    private fun showUpdateDialog(context: Context, release: ReleaseInfo, currentVersion: String) {
        val betaBadge = if (release.isBeta) "【Beta】" else "【正式版】"
        val title = "发现新版本 $betaBadge"
        val changelog = if (release.body.isNotBlank()) release.body.take(600) else "暂无更新说明"
        val msg = """
            🆕 最新版本：${release.versionName}
            📦 当前版本：$currentVersion
            
            📝 更新内容：
            $changelog
        """.trimIndent()

        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("立即更新") { _, _ ->
                startDownload(context, release)
            }
            .setNeutralButton("稍后提醒") { _, _ ->
                Toast.makeText(context, "将在下次启动时再次提醒", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("忽略此版本") { _, _ ->
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_IGNORED_VERSION, release.versionName).apply()
                Toast.makeText(context, "已忽略版本 ${release.versionName}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ===== 后台下载 APK =====

    fun startDownload(context: Context, release: ReleaseInfo) {
        createDownloadChannel(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifBuilder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在下载更新")
            .setContentText("LookGm ${release.versionName}")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, 0, false)

        notificationManager.notify(DOWNLOAD_NOTIFY_ID, notifBuilder.build())

        downloadJob?.cancel()
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val apkFile = downloadApk(context, release.apkUrl) { progress ->
                    notifBuilder.setProgress(100, progress, false)
                        .setContentText("正在下载... $progress%")
                    notificationManager.notify(DOWNLOAD_NOTIFY_ID, notifBuilder.build())
                }

                // 下载完成
                notifBuilder
                    .setProgress(0, 0, false)
                    .setContentText("下载完成，点击安装")
                    .setOngoing(false)

                val installIntent = buildInstallIntent(context, apkFile)
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, installIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
                )
                notifBuilder.setContentIntent(pendingIntent)
                notificationManager.notify(DOWNLOAD_NOTIFY_ID, notifBuilder.build())

                // 主线程弹安装对话框
                mainHandler.post {
                    showInstallDialog(context, apkFile, release)
                }

            } catch (e: Exception) {
                Log.e(TAG, "下载失败: ${e.message}", e)
                notifBuilder
                    .setProgress(0, 0, false)
                    .setContentText("下载失败：${e.message}")
                    .setOngoing(false)
                notificationManager.notify(DOWNLOAD_NOTIFY_ID, notifBuilder.build())

                mainHandler.post {
                    Toast.makeText(context, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): File {
        val url = URL(apkUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.connect()

        val totalSize = conn.contentLength.toLong()
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val apkFile = File(dir, "lookgm_update.apk")

        conn.inputStream.use { input ->
            FileOutputStream(apkFile).use { output ->
                val buf = ByteArray(8192)
                var downloaded = 0L
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    downloaded += read
                    if (totalSize > 0) {
                        val progress = (downloaded * 100 / totalSize).toInt()
                        onProgress(progress)
                    }
                }
            }
        }

        conn.disconnect()
        return apkFile
    }

    private fun showInstallDialog(context: Context, apkFile: File, release: ReleaseInfo) {
        AlertDialog.Builder(context)
            .setTitle("下载完成")
            .setMessage("LookGm ${release.versionName} 已下载完成，是否立即安装？")
            .setCancelable(false)
            .setPositiveButton("立即安装") { _, _ ->
                installApk(context, apkFile)
            }
            .setNegativeButton("稍后") { _, _ ->
                Toast.makeText(context, "APK 已保存，可在通知中点击安装", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun installApk(context: Context, apkFile: File) {
        val intent = buildInstallIntent(context, apkFile)
        context.startActivity(intent)
    }

    private fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val intent = Intent(Intent.ACTION_VIEW)
        val apkUri: Uri

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            apkUri = Uri.fromFile(apkFile)
        }

        intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    // ===== 通知渠道 =====

    private fun createDownloadChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "版本更新下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "更新APK下载进度" }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    // ===== 版本号工具 =====

    fun getCurrentVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "20240623"
        } catch (e: Exception) {
            "20240623"
        }
    }

    /**
     * 版本号对比：支持格式 20260623 / 20260623-beta1 / 2026062402
     * 返回正数 = a 更新，返回负数 = b 更新，返回0 = 相同
     */
    fun compareVersions(a: String, b: String): Int {
        // 提取纯数字部分：先去掉 beta 后缀，再去掉所有非数字后缀
        val numA = a.replace(Regex("[-.].*"), "").trim().toLongOrNull() ?: 0L
        val numB = b.replace(Regex("[-.].*"), "").trim().toLongOrNull() ?: 0L

        return when {
            numA > numB -> 1
            numA < numB -> -1
            else -> {
                // 日期相同，比较 beta 后缀
                val isBetaA = a.contains("beta", ignoreCase = true)
                val isBetaB = b.contains("beta", ignoreCase = true)
                when {
                    !isBetaA && isBetaB -> 1   // 正式版 > beta
                    isBetaA && !isBetaB -> -1
                    isBetaA && isBetaB -> {
                        // 都是beta，比较beta编号
                        val betaNumA = a.replace(Regex(".*beta"), "").toIntOrNull() ?: 0
                        val betaNumB = b.replace(Regex(".*beta"), "").toIntOrNull() ?: 0
                        betaNumA.compareTo(betaNumB)
                    }
                    else -> 0
                }
            }
        }
    }

    // ===== Beta通道开关 =====

    fun setBetaChannel(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BETA_CHANNEL, enabled).apply()
    }

    fun isBetaChannelEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BETA_CHANNEL, false)
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }
}
