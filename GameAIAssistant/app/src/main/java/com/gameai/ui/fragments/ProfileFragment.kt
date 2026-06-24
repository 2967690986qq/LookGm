package com.gameai.ui.fragments

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.browser.customtabs.CustomTabsIntent
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import coil.load
import coil.transform.CircleCropTransformation
import com.gameai.BuildConfig
import com.gameai.R
import com.gameai.databinding.FragmentProfileBinding
import com.gameai.service.FloatingWindowService
import com.gameai.ui.GitHubOAuthActivity
import com.gameai.ui.LoginActivity
import com.gameai.utils.GitHubAuthManager
import com.gameai.utils.PreferencesManager
import com.gameai.utils.UpdateManager
import com.gameai.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val prefs by lazy { PreferencesManager.getInstance(requireContext()) }
    private lateinit var viewModel: MainViewModel

    private val githubAuthReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadUserInfo()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        loadUserInfo()
        loadSettings()
        loadMatchStats()
        setupClickListeners()

        // 监听 GitHub 登录成功广播
        val filter = IntentFilter(GitHubOAuthActivity.ACTION_AUTH_SUCCESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(githubAuthReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(githubAuthReceiver, filter)
        }
    }

    // ===== 用户信息 =====

    private fun loadUserInfo() {
        val authPrefs = requireContext().getSharedPreferences("lookgm_auth", Context.MODE_PRIVATE)
        val username = authPrefs.getString("username", null)
        val loginTime = authPrefs.getLong("login_time", 0L)
        val loginType = authPrefs.getString("login_type", "local") ?: "local"

        if (username != null && loginType == "github" && GitHubAuthManager.isLoggedIn(requireContext())) {
            // GitHub 已登录
            binding.tvUsername.text = username
            binding.tvLoginTime.text = "🐙 GitHub 账号"
            if (loginTime > 0) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                binding.tvLoginTime.text = "🐙 GitHub 账号 · ${sdf.format(Date(loginTime))}"
            }
            binding.btnGithubLogin.text = "已绑定 ✅"

            // 显示 GitHub 头像
            val user = GitHubAuthManager.getCachedUser(requireContext())
            if (user != null) {
                binding.tvAvatar.text = ""  // 隐藏文字头像
                binding.tvAvatar.visibility = View.GONE
                binding.ivGithubAvatar.apply {
                    visibility = View.VISIBLE
                    load(user.avatarUrl) {
                        transformations(CircleCropTransformation())
                        placeholder(R.drawable.bg_card)
                        error(R.drawable.bg_card)
                    }
                }
            } else {
                binding.tvAvatar.visibility = View.VISIBLE
                binding.ivGithubAvatar.visibility = View.GONE
                binding.tvAvatar.text = "🐙"
            }
        } else if (username != null) {
            // 本地登录
            binding.tvUsername.text = username
            val icon = "📱 本地账号"
            if (loginTime > 0) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                binding.tvLoginTime.text = "$icon · 登录于 ${sdf.format(Date(loginTime))}"
            } else {
                binding.tvLoginTime.text = icon
            }
            binding.btnGithubLogin.text = "GitHub 登录"
            binding.tvAvatar.visibility = View.VISIBLE
            binding.ivGithubAvatar.visibility = View.GONE
            binding.tvAvatar.text = "👤"
        } else {
            binding.tvUsername.text = "本地用户"
            binding.tvLoginTime.text = "📱 本地离线模式"
            binding.btnGithubLogin.text = "GitHub 登录"
            binding.tvAvatar.visibility = View.VISIBLE
            binding.ivGithubAvatar.visibility = View.GONE
            binding.tvAvatar.text = "👤"
        }

        // 设备ID（自动生成并持久化）
        val deviceId = getOrCreateDeviceId()
        binding.tvDeviceId.text = deviceId

        // 版本号
        binding.tvCurrentVersion.text = "v${BuildConfig.VERSION_NAME}"
    }

    private fun getOrCreateDeviceId(): String {
        val key = "device_id"
        var id = prefs.getString(key, "")
        if (id.isEmpty()) {
            id = "GM-${UUID.randomUUID().toString().uppercase().replace("-", "").take(12)}"
            prefs.saveString(key, id)
        }
        return id
    }

    // ===== 对局统计 =====

    private fun loadMatchStats() {
        viewModel.matchHistory.observe(viewLifecycleOwner) { matches ->
            if (matches.isEmpty()) {
                binding.tvStatTotal.text = "0"
                binding.tvStatAvg.text = "--"
                binding.tvStatBest.text = "--"
                binding.tvStatWinrate.text = "--"
                return@observe
            }

            binding.tvStatTotal.text = matches.size.toString()
            binding.tvStatAvg.text = matches.map { it.totalScore }.average().toInt().toString()
            binding.tvStatBest.text = matches.maxOf { it.totalScore }.toString()
            val winCount = matches.count { it.isVictory }
            val winRate = (winCount.toFloat() / matches.size * 100).toInt()
            binding.tvStatWinrate.text = "${winRate}%"
        }
    }

    // ===== 配置加载 =====

    private fun loadSettings() {
        val config = prefs.loadConfig()
        binding.switchVoice.isChecked = config.enableVoice

        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(requireContext())
        } else {
            true
        }
        if (config.enableFloatingBall && !hasOverlayPermission) {
            prefs.saveConfig(config.copy(enableFloatingBall = false))
            binding.switchOverlay.isChecked = false
        } else {
            binding.switchOverlay.isChecked = config.enableFloatingBall
        }

        // Beta 通道
        binding.switchBeta.isChecked = UpdateManager.isBetaChannelEnabled(requireContext())
    }

    // ===== 点击事件 =====

    private fun setupClickListeners() {

        // 语音开关
        binding.switchVoice.setOnCheckedChangeListener { _, isChecked ->
            val config = prefs.loadConfig()
            prefs.saveConfig(config.copy(enableVoice = isChecked))
            val msg = if (isChecked) "语音播报已开启" else "语音播报已关闭"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // 悬浮窗开关
        binding.switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            val config = prefs.loadConfig()
            prefs.saveConfig(config.copy(enableFloatingBall = isChecked))
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(requireContext())) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("需要悬浮窗权限")
                        .setMessage("开启悬浮球需要在系统设置中授予「显示在其他应用上层」权限，点击确定跳转设置。")
                        .setPositiveButton("去设置") { _, _ ->
                            startActivity(Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${requireContext().packageName}")
                            ))
                        }
                        .setNegativeButton("取消") { _, _ ->
                            binding.switchOverlay.setOnCheckedChangeListener(null)
                            binding.switchOverlay.isChecked = false
                            prefs.saveConfig(config.copy(enableFloatingBall = false))
                            setupClickListeners()
                        }
                        .show()
                } else {
                    requireContext().startService(Intent(requireContext(), FloatingWindowService::class.java))
                    Toast.makeText(requireContext(), "悬浮窗已开启", Toast.LENGTH_SHORT).show()
                }
            } else {
                val stopIntent = Intent(requireContext(), FloatingWindowService::class.java).apply {
                    action = FloatingWindowService.ACTION_HIDE
                }
                requireContext().startService(stopIntent)
                requireContext().stopService(Intent(requireContext(), FloatingWindowService::class.java))
            }
        }

        // Beta 测试版通道
        binding.switchBeta.setOnCheckedChangeListener { _, isChecked ->
            UpdateManager.setBetaChannel(requireContext(), isChecked)
            val msg = if (isChecked) "已开启 Beta 测试版更新通道" else "已切回正式版更新通道"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // 检测更新
        binding.btnCheckUpdate.setOnClickListener {
            binding.tvCurrentVersion.text = "检测中..."
            UpdateManager.checkManually(requireContext())
            // 1秒后恢复版本号显示（防止死等）
            binding.tvCurrentVersion.postDelayed({
                binding.tvCurrentVersion.text = "v${BuildConfig.VERSION_NAME}"
            }, 8000)
        }

        // GitHub 登录 / 切换
        binding.btnGithubLogin.setOnClickListener {
            if (GitHubAuthManager.isLoggedIn(requireContext())) {
                // 已登录 → 查看或退出
                val user = GitHubAuthManager.getCachedUser(requireContext())
                val name = user?.name ?: user?.login ?: "已绑定"
                AlertDialog.Builder(requireContext())
                    .setTitle("GitHub 账号")
                    .setMessage("已通过 GitHub 账号 @${name} 登录\n\n可退出后重新登录其他账号。")
                    .setPositiveButton("重新登录") { _, _ ->
                        GitHubAuthManager.logout(requireContext())
                        loadUserInfo()
                        startGitHubOAuth()
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            } else {
                startGitHubOAuth()
            }
        }

        // 复制设备ID
        binding.btnCopyDeviceId.setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DeviceID", binding.tvDeviceId.text))
            Toast.makeText(requireContext(), "设备 ID 已复制", Toast.LENGTH_SHORT).show()
        }

        // 关于
        binding.btnAbout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("关于 LookGm")
                .setMessage(
                    "LookGm — 全游戏通用 AI 视觉助手\n\n" +
                    "版本：${BuildConfig.VERSION_NAME}\n" +
                    "Build：${BuildConfig.VERSION_CODE}\n\n" +
                    "核心特性：\n" +
                    "• 纯视觉分析，不读游戏内存（合规）\n" +
                    "• 双模型架构：内网本地模型 + 全网云端 API\n" +
                    "• 实时 KDA/经济/AI 分析\n" +
                    "• 语音对话（豆包模式）\n" +
                    "• 段位评分体系（铜/银/金/顶级）\n\n" +
                    "开源协议：MIT\n" +
                    "由社区共同维护 ❤️"
                )
                .setPositiveButton("确定", null)
                .show()
        }

        // 开源 GitHub
        binding.btnGithub.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/2967690986qq/LookGm"))
            startActivity(intent)
        }

        // 隐私说明
        binding.btnPrivacy.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("隐私说明")
                .setMessage(
                    "LookGm 隐私保护承诺：\n\n" +
                    "✅ 不收集任何个人信息\n" +
                    "✅ 设备 ID 为本地随机生成，不上传\n" +
                    "✅ API Key 等敏感信息仅存本地设备\n" +
                    "✅ 屏幕画面不存储到本地或上传云端\n" +
                    "✅ 对局数据仅保存在本地 SQLite 数据库\n" +
                    "✅ 不读取游戏内存、不模拟任何操作\n" +
                    "✅ 所有代码完全开源，接受社区审计\n\n" +
                    "如需删除所有本地数据，请前往 [设置] -> [清空历史记录]。"
                )
                .setPositiveButton("我已了解", null)
                .show()
        }

        // 退出登录
        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("退出登录")
                .setMessage("确认退出当前账号？本地对局数据不会删除。")
                .setPositiveButton("退出") { _, _ ->
                    // 清理 GitHub 认证
                    GitHubAuthManager.logout(requireContext())
                    // 清理本地认证
                    requireContext().getSharedPreferences("lookgm_auth", Context.MODE_PRIVATE)
                        .edit().clear().apply()
                    startActivity(Intent(requireContext(), LoginActivity::class.java))
                    requireActivity().finish()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 打开 GitHub OAuth 授权页 */
    private fun startGitHubOAuth() {
        try {
            val authUrl = GitHubAuthManager.getAuthorizationUrl(requireContext())
            val builder = CustomTabsIntent.Builder()
            builder.setShowTitle(true)
            builder.setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            val customTabsIntent = builder.build()
            customTabsIntent.launchUrl(requireContext(), Uri.parse(authUrl))
        } catch (e: Exception) {
            // 回退到普通浏览器
            Toast.makeText(requireContext(), "正在打开授权页面...", Toast.LENGTH_SHORT).show()
            val browserIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse(GitHubAuthManager.getAuthorizationUrl(requireContext())))
            startActivity(browserIntent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { requireContext().unregisterReceiver(githubAuthReceiver) } catch (_: Exception) {}
        _binding = null
    }
}
