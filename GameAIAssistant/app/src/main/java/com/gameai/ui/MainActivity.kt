package com.gameai.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.gameai.R
import com.gameai.common.constants.GameConstants
import com.gameai.databinding.ActivityMainBinding
import com.gameai.engine.GameStateManager
import com.gameai.service.FloatingWindowService
import com.gameai.ui.fragments.ChatFragment
import com.gameai.ui.fragments.DashboardFragment
import com.gameai.ui.fragments.GameHistoryFragment
import com.gameai.ui.fragments.ModelsFragment
import com.gameai.ui.fragments.ProfileFragment
import com.gameai.utils.PreferencesManager
import com.gameai.utils.RomCompatHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager

    private val dashboardFragment = DashboardFragment()
    private val chatFragment = ChatFragment()
    private val modelsFragment = ModelsFragment()
    private val historyFragment = GameHistoryFragment()
    private val profileFragment = ProfileFragment()
    private var activeFragment: Fragment = dashboardFragment

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 1001
        private const val REQUEST_AUDIO_PERMISSION = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesManager.getInstance(this)

        // 初始化游戏状态管理器
        GameStateManager.init(this)
        // 初始化 AI 分析引擎
        com.gameai.ai.ScreenAnalysisEngine.init(this)
        // 初始化语音对话引擎
        com.gameai.ai.VoiceConversationEngine.init(this)

        // ===== 录音权限请求（Android 6.0+）=====
        requestAudioPermissionIfNeeded()

        // ===== 悬浮窗权限检查与引导 =====
        checkFloatingBallOnStartup()

        // 初始化五个Fragment
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, profileFragment, "profile").hide(profileFragment)
            .add(R.id.fragment_container, historyFragment, "history").hide(historyFragment)
            .add(R.id.fragment_container, chatFragment, "chat").hide(chatFragment)
            .add(R.id.fragment_container, modelsFragment, "models").hide(modelsFragment)
            .add(R.id.fragment_container, dashboardFragment, "dashboard")
            .commit()

        // 默认选中仪表盘
        binding.bottomNav.selectedItemId = R.id.nav_dashboard

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> showFragment(dashboardFragment)
                R.id.nav_models -> showFragment(modelsFragment)
                R.id.nav_chat -> showFragment(chatFragment)
                R.id.nav_history -> showFragment(historyFragment)
                R.id.nav_profile -> showFragment(profileFragment)
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // 回前台时：若悬浮球开关为 ON 且 service 未运行 → 自动拉起
        autoStartFloatingBallIfNeeded()
    }

    private fun showFragment(fragment: Fragment) {
        if (fragment == activeFragment) return
        supportFragmentManager.beginTransaction()
            .hide(activeFragment)
            .show(fragment)
            .commit()
        activeFragment = fragment
    }

    // ============================================================
    //  录音权限（Android 6.0+）
    // ============================================================

    /** 请求录音权限，用户拒绝时不影响其他功能 */
    private fun requestAudioPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) return

        // 检查是否已经永久拒绝
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this)
                .setTitle("需要录音权限")
                .setMessage("语音对话功能需要使用麦克风，请授权录音权限。")
                .setPositiveButton("去授权") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION
                    )
                }
                .setNegativeButton("暂不", null)
                .show()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                android.widget.Toast.makeText(this, "录音权限已授权，语音对话功能可用", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(this, "未授权录音权限，语音对话功能将不可用", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // ============================================================
    //  悬浮窗权限 & 自启逻辑
    // ============================================================

    /** 首次启动/登录后检查悬浮球状态与权限 */
    private fun checkFloatingBallOnStartup() {
        // 仅当用户已开启悬浮球开关时才检查
        if (!prefs.isFloatingBallEnabled()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // 首次引导：需要悬浮窗权限
            showOverlayPermissionDialog()
        } else {
            // 已有权限 → 直接启动
            startFloatingBallService()
        }
    }

    /** 回到前台时自动检测并拉起悬浮球 */
    private fun autoStartFloatingBallIfNeeded() {
        if (!prefs.isFloatingBallEnabled()) return
        if (FloatingWindowService.isShowing) return  // 已经在显示

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            startFloatingBallService()
        }
    }

    /** 启动悬浮球 Service（不重复启动） */
    private fun startFloatingBallService() {
        if (FloatingWindowService.isShowing) return
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /** 悬浮窗权限引导弹窗 */
    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要悬浮窗权限")
            .setMessage(
                "游戏AI辅助需要悬浮窗权限才能在游戏画面上显示评分和助手面板。\n\n" +
                "开启后，你将在游戏中看到：\n" +
                "  • 实时对局评分\n" +
                "  • 分路任务指引\n" +
                "  • AI 战术分析\n" +
                "  • 语音对话助手\n\n" +
                "请点击「去开启」授权。"
            )
            .setPositiveButton("去开启") { _, _ ->
                openOverlaySettings()
            }
            .setNegativeButton("暂不开启") { _, _ ->
                // 用户拒绝 → 关闭悬浮球开关
                prefs.saveBoolean("enable_floating_ball", false)
            }
            .setCancelable(false)
            .show()
    }

    /** 跳转到系统悬浮窗权限设置页（含ROM适配） */
    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            // 从权限设置页返回后，检查权限是否已授权
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                // 权限已授权 → 启动悬浮球 + ROM 权限检查引导
                if (prefs.isFloatingBallEnabled()) {
                    startFloatingBallService()
                }
                // 国产 ROM 额外权限引导
                showRomPermissionGuideIfNeeded()
            } else {
                // 仍未授权 → 关闭开关
                prefs.saveBoolean("enable_floating_ball", false)
            }
        }
    }

    /** 国产 ROM 特定权限引导（在悬浮窗授权成功后展示） */
    private fun showRomPermissionGuideIfNeeded() {
        val romType = RomCompatHelper.detectRomType()
        if (romType == RomCompatHelper.RomType.UNKNOWN) return  // 原生系统无需额外引导

        val guide = RomCompatHelper.getRomGuide(this)
        // 仅首次成功授权后展示一次
        val keyShown = "rom_guide_shown_${romType.name}"
        if (prefs.getBoolean(keyShown, false)) return
        prefs.saveBoolean(keyShown, true)

        AlertDialog.Builder(this)
            .setTitle("${guide.name} 权限优化")
            .setMessage(
                "${guide.name} 系统可能限制后台运行，建议完成以下设置确保悬浮球不被杀死：\n\n" +
                "① 自启动管理 → 允许 LookGm 开机自启\n" +
                "② 电池优化 → 设为「无限制」\n" +
                "③ 多任务界面 → 锁定 LookGm\n\n" +
                "点击「去设置」跳转到自启动管理页面。"
            )
            .setPositiveButton("去设置") { _, _ ->
                RomCompatHelper.openAutoStartSettings(this)
            }
            .setNeutralButton("电池优化") { _, _ ->
                RomCompatHelper.openBatteryOptimizationSettings(this)
            }
            .setNegativeButton("稍后", null)
            .show()
    }
}
