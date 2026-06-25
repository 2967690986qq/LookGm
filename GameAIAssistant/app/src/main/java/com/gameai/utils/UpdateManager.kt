// UpdateManager.kt - GitHub Releases 自动检测与内置更新（无需跳转浏览器）
package com.gameai.utils

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.gameai.R
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * UpdateManager - GitHub Releases 版本检测与内置静默更新
 *
 * 修复历史：
 * v2 - 修复 Markdown 乱码（清洗 ## 和加粗符号）
 *    - 修复 FileProvider 路径不匹配导致立即更新无响应
 *    - 修复 GitHub 重定向下载（跟随 301/302）
 *    - 修复非 Activity Context 弹窗崩溃
 */
object UpdateManager {

    private const val TAG = "UpdateManager"

    private const val GITHUB_REPO = "2967690986qq/LookGm"
    private const val GITHUB_RELEASES_API = "https://api.github.com/repos/$GITHUB_REPO/releases"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

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
        val versionName: String,
        val tagName: String,
        val releaseName: String,
        val body: String,
        val apkUrl: String,
        val isBeta: Boolean,
        val publishedAt: String
    )

    // ===== 外部入口 =====

    fun checkOnStartup(context: Context) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        if (now - lastCheck < CHECK_INTERVAL_MS) {
            Log.d(TAG, "距上次检测时间不足6小时，跳过")
            return
        }
        checkForUpdate(appCtx, silent = true)
    }

    fun checkManually(context: Context) {
        checkForUpdate(context.applicationContext, silent = false)
    }

    // ===== 核心检测逻辑 =====

    private fun checkForUpdate(appCtx: Context, silent: Boolean) {
        checkJob?.cancel()
        checkJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

                val betaChannel = prefs.getBoolean(KEY_BETA_CHANNEL, false)
                val ignoredVersion = prefs.getString(KEY_IGNORED_VERSION, "") ?: ""

                val releases = fetchReleases()
                if (releases.isEmpty()) {
                    if (!silent) mainHandler.post {
                        Toast.makeText(appCtx, "暂未找到可用更新", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val latest = releases.firstOrNull { if (betaChannel) true else !it.isBeta }
                    ?: releases.first()

                val currentVersion = getCurrentVersion(appCtx)
                Log.d(TAG, "本地版本：$currentVersion，远端最新：${latest.versionName}")

                val hasUpdate = compareVersions(latest.versionName, currentVersion) > 0
                if (!hasUpdate) {
                    if (!silent) mainHandler.post {
                        Toast.makeText(appCtx, "当前已是最新版本 $currentVersion", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                if (silent && ignoredVersion == latest.versionName) {
                    Log.d(TAG, "版本 ${latest.versionName} 已被忽略")
                    return@launch
                }

                mainHandler.post {
                    showUpdateDialog(appCtx, latest, currentVersion)
                }

            } catch (e: Exception) {
                Log.e(TAG, "检测更新异常: ${e.message}", e)
                if (!silent) mainHandler.post {
                    Toast.makeText(appCtx, "检测失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchReleases(): List<ReleaseInfo> {
        val url = URL(GITHUB_RELEASES_API)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("User-Agent", "LookGm-Android")
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true

        val response = try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }

        val releases = mutableListOf<ReleaseInfo>()
        val jsonArray = org.json.JSONArray(response)

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            if (obj.optBoolean("draft", false)) continue

            val tagName = obj.optString("tag_name", "")
            val releaseName = obj.optString("name", tagName)
            val body = obj.optString("body", "")
            val publishedAt = obj.optString("published_at", "")
            val isBeta = tagName.lowercase().contains("beta") || obj.optBoolean("prerelease", false)
            val versionName = tagName.removePrefix("v")

            val assets = obj.optJSONArray("assets") ?: continue
            var apkUrl = ""
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                if (asset.optString("name", "").endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url", "")
                    break
                }
            }
            if (apkUrl.isEmpty()) continue

            releases.add(
                ReleaseInfo(versionName, tagName, releaseName, body, apkUrl, isBeta, publishedAt)
            )
        }
        return releases
    }

    // ===== 更新提示对话框 =====

    private fun showUpdateDialog(appCtx: Context, release: ReleaseInfo, currentVersion: String) {
        val betaBadge = if (release.isBeta) "【Beta】" else "【正式版】"
        val title = "发现新版本 $betaBadge"

        // ★ 清洗 Markdown 符号，避免原样显示乱码
        val cleanBody = cleanMarkdown(release.body).take(600).ifBlank { "暂无更新说明" }

        val dateStr = release.publishedAt.take(10)  // "2026-06-25"
        val msg = "最新版本：${release.versionName}\n当前版本：$currentVersion\n发布日期：$dateStr\n\n更新内容：\n$cleanBody"

        // ★ 必须使用 Application Context + 系统级窗口类型，避免在 Service 中崩溃
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AlertDialog.Builder(appCtx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        } else {
            AlertDialog.Builder(appCtx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        }

        val dialog = builder
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("立即更新") { _, _ ->
                startDownload(appCtx, release)
            }
            .setNeutralButton("稍后提醒") { _, _ ->
                Toast.makeText(appCtx, "将在下次启动时再次提醒", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("忽略此版本") { _, _ ->
                appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_IGNORED_VERSION, release.versionName).apply()
                Toast.makeText(appCtx, "已忽略版本 ${release.versionName}", Toast.LENGTH_SHORT).show()
            }
            .create()

        // ★ 在 Service / Application Context 弹窗必须设置 TYPE_APPLICATION_OVERLAY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            @Suppress("DEPRECATION")
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
        }

        dialog.show()
    }

    /**
     * 清洗 Markdown 符号为纯文本
     * ## 标题 → 标题
     * **粗体** → 粗体
     * `代码` → 代码
     * - 列表 → • 列表
     */
    private fun cleanMarkdown(raw: String): String {
        return raw
            .replace(Regex("#{1,6}\\s*"), "")        // ## 标题
            .replace(Regex("\\*{1,3}([^*]+)\\*{1,3}"), "$1")  // **粗体** / *斜体*
            .replace(Regex("`{1,3}([^`]*)`{1,3}"), "$1")       // `代码`
            .replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "• ")  // - 列表项
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")    // [链接](url)
            .replace(Regex("!\\[([^]]*)]\\([^)]+\\)"), "")     // 图片
            .replace(Regex("\r\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")                  // 多空行合并
            .trim()
    }

    // ===== 后台下载 APK =====

    fun startDownload(context: Context, release: ReleaseInfo) {
        val appCtx = context.applicationContext
        createDownloadChannel(appCtx)

        val notificationManager = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifBuilder = NotificationCompat.Builder(appCtx, DOWNLOAD_CHANNEL_ID)
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
                // ★ 下载前先显示 Toast 告知用户
                mainHandler.post {
                    Toast.makeText(appCtx, "开始下载更新，请稍候...", Toast.LENGTH_SHORT).show()
                }

                val apkFile = downloadApk(appCtx, release.apkUrl) { progress ->
                    notifBuilder.setProgress(100, progress, false)
                        .setContentText("正在下载... $progress%")
                    notificationManager.notify(DOWNLOAD_NOTIFY_ID, notifBuilder.build())
                }

                Log.d(TAG, "下载完成: ${apkFile.absolutePath} (${apkFile.length()} bytes)")

                notifBuilder
                    .setProgress(0, 0, false)
                    .setContentText("下载完成，点击安装")
                    .setOngoing(false)

                val installIntent = buildInstallIntent(appCtx, apkFile)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
                val pendingIntent = PendingIntent.getActivity(appCtx, 0, installIntent, flags)
                notifBuilder.setContentIntent(pendingIntent)
                notificationManager.notify(DOWNLOAD_NOTIFY_ID, notifBuilder.build())

                mainHandler.post {
                    showInstallDialog(appCtx, apkFile, release)
                }

            } catch (e: Exception) {
                Log.e(TAG, "下载失败: ${e.message}", e)
                notifBuilder
                    .setProgress(0, 0, false)
                    .setContentText("下载失败：${e.message}")
                    .setOngoing(false)
                notificationManager.notify(DOWNLOAD_NOTIFY_ID, notifBuilder.build())
                mainHandler.post {
                    Toast.makeText(appCtx, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * ★ 下载 APK，支持 GitHub 302 重定向
     * 文件存放到 context.filesDir（内部私有目录，FileProvider 无需外部存储权限）
     */
    private fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): File {
        Log.d(TAG, "开始下载: $apkUrl")

        // 跟随重定向（GitHub Release 会 302 到 CDN）
        var currentUrl = apkUrl
        var conn: HttpURLConnection
        var redirectCount = 0

        while (true) {
            val url = URL(currentUrl)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = false  // 手动跟随，以便处理 http→https
            conn.setRequestProperty("User-Agent", "LookGm-Android")
            conn.connect()

            val responseCode = conn.responseCode
            Log.d(TAG, "HTTP $responseCode for $currentUrl")

            if (responseCode in 300..399) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                currentUrl = location
                redirectCount++
                if (redirectCount > 5) throw IOException("重定向次数过多")
            } else {
                break
            }
        }

        if (conn.responseCode !in 200..299) {
            throw IOException("HTTP ${conn.responseCode} 下载失败")
        }

        val totalSize = conn.contentLengthLong
        Log.d(TAG, "文件大小: $totalSize bytes")

        // ★ 保存到内部 filesDir（FileProvider paths.xml 中已声明 files-path path="."）
        val apkFile = File(context.filesDir, "lookgm_update.apk")

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

        Log.d(TAG, "下载完成，文件路径: ${apkFile.absolutePath}")
        return apkFile
    }

    private fun showInstallDialog(appCtx: Context, apkFile: File, release: ReleaseInfo) {
        val dialog = AlertDialog.Builder(appCtx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("下载完成，立即安装？")
            .setMessage("LookGm ${release.versionName} 已下载完成\n文件大小：${apkFile.length() / 1024 / 1024} MB")
            .setCancelable(false)
            .setPositiveButton("立即安装") { _, _ ->
                installApk(appCtx, apkFile)
            }
            .setNegativeButton("稍后") { _, _ ->
                Toast.makeText(appCtx, "可在通知栏点击安装", Toast.LENGTH_SHORT).show()
            }
            .create()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            @Suppress("DEPRECATION")
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
        }

        dialog.show()
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            val intent = buildInstallIntent(context, apkFile)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "安装失败: ${e.message}", e)
            Toast.makeText(context, "安装失败，请在通知栏手动点击：${e.message}", Toast.LENGTH_LONG).show()
        }
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
                DOWNLOAD_CHANNEL_ID, "版本更新下载", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "更新APK下载进度" }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    // ===== 版本号工具 =====

    fun getCurrentVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (e: Exception) { "0" }
    }

    /**
     * 版本号对比：支持 20260623 / 20260623-beta1 / 2026062402 / 2026062501 格式
     */
    fun compareVersions(a: String, b: String): Int {
        val numA = a.replace(Regex("[-.].*"), "").trim().toLongOrNull() ?: 0L
        val numB = b.replace(Regex("[-.].*"), "").trim().toLongOrNull() ?: 0L
        return when {
            numA > numB -> 1
            numA < numB -> -1
            else -> {
                val isBetaA = a.contains("beta", ignoreCase = true)
                val isBetaB = b.contains("beta", ignoreCase = true)
                when {
                    !isBetaA && isBetaB -> 1
                    isBetaA && !isBetaB -> -1
                    isBetaA && isBetaB -> {
                        val betaNumA = a.replace(Regex(".*beta"), "").toIntOrNull() ?: 0
                        val betaNumB = b.replace(Regex(".*beta"), "").toIntOrNull() ?: 0
                        betaNumA.compareTo(betaNumB)
                    }
                    else -> 0
                }
            }
        }
    }

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
