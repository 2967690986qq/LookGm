// SettingsActivity.kt - 完整模型配置与设置（重构版）
package com.gameai.ui.settings

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gameai.R
import com.gameai.model.*
import com.gameai.utils.ModelConnectionTester
import com.gameai.utils.PreferencesManager
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private var currentMode = ModelMode.CLOUD
    private lateinit var currentConfig: GameConfig

    // UI引用
    private lateinit var btnModeCloud: TextView
    private lateinit var btnModeLocal: TextView
    private lateinit var btnModeCustom: TextView
    private lateinit var layoutCloudConfig: LinearLayout
    private lateinit var layoutLocalConfig: LinearLayout
    private lateinit var layoutCustomConfig: LinearLayout

    // 云端模型
    private lateinit var spinnerProvider: Spinner
    private lateinit var etApiKey: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var etModelName: EditText

    // PC服务器
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText

    // 本地模型
    private lateinit var spinnerLocalService: Spinner
    private lateinit var etLocalHost: EditText
    private lateinit var etLocalPort: EditText
    private lateinit var etLocalModel: EditText

    // 自定义接口
    private lateinit var etCustomBaseUrl: EditText
    private lateinit var etCustomApiKey: EditText
    private lateinit var etCustomModelName: EditText

    // 游戏
    private lateinit var spinnerGame: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PreferencesManager.getInstance(this)
        currentConfig = prefs.loadConfig()
        currentMode = currentConfig.modelMode

        bindViews()
        setupListeners()
        loadConfigToUI()
        switchMode(currentMode)
    }

    private fun bindViews() {
        // 模式切换
        btnModeCloud = findViewById(R.id.btn_mode_cloud)
        btnModeLocal = findViewById(R.id.btn_mode_local)
        btnModeCustom = findViewById(R.id.btn_mode_custom)
        layoutCloudConfig = findViewById(R.id.layout_cloud_config)
        layoutLocalConfig = findViewById(R.id.layout_local_config)
        layoutCustomConfig = findViewById(R.id.layout_custom_config)

        // PC服务器
        etHost = findViewById(R.id.et_host)
        etPort = findViewById(R.id.et_port)

        // 云端
        spinnerProvider = findViewById(R.id.spinner_provider)
        etApiKey = findViewById(R.id.et_api_key)
        etBaseUrl = findViewById(R.id.et_base_url)
        etModelName = findViewById(R.id.et_model_name)

        // 本地
        spinnerLocalService = findViewById(R.id.spinner_local_service)
        etLocalHost = findViewById(R.id.et_local_host)
        etLocalPort = findViewById(R.id.et_local_port)
        etLocalModel = findViewById(R.id.et_local_model)

        // 自定义
        etCustomBaseUrl = findViewById(R.id.et_custom_base_url)
        etCustomApiKey = findViewById(R.id.et_custom_api_key)
        etCustomModelName = findViewById(R.id.et_custom_model_name)

        // 游戏
        spinnerGame = findViewById(R.id.spinner_game)
    }

    private fun setupListeners() {
        // 返回
        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }

        // 保存
        findViewById<TextView>(R.id.btn_save).setOnClickListener { saveAndExit() }

        // 模式切换
        btnModeCloud.setOnClickListener { switchMode(ModelMode.CLOUD) }
        btnModeLocal.setOnClickListener { switchMode(ModelMode.LOCAL) }
        btnModeCustom.setOnClickListener { switchMode(ModelMode.CUSTOM) }

        // 供应商切换 → 自动填充默认值
        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val provider = ModelProvider.entries[pos]
                // 如果用户没改过baseUrl和modelName，自动填充默认值
                if (etBaseUrl.text.isBlank() || etBaseUrl.text.toString() == getCurrentDefaultBaseUrl()) {
                    etBaseUrl.setText(provider.defaultBaseUrl)
                }
                if (etModelName.text.isBlank() || etModelName.text.toString() == getCurrentDefaultModel()) {
                    etModelName.setText(provider.defaultModel)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 本地服务切换 → 自动填充端口和模型
        spinnerLocalService.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val services = listOf(ModelProvider.LOCAL_OLLAMA, ModelProvider.LOCAL_VLLM, ModelProvider.LOCAL_LM_STUDIO)
                if (pos < services.size) {
                    val svc = services[pos]
                    etLocalPort.setText(svc.defaultBaseUrl.split(":").last().split("/").first())
                    etLocalModel.setText(svc.defaultModel)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // SeekBar
        setupSeekBar(R.id.seekbar_fps, R.id.tv_fps_value, "fps")
        setupSeekBar(R.id.seekbar_quality, R.id.tv_quality_value, "%")
        setupSeekBar(R.id.seekbar_alpha, R.id.tv_alpha_value, "%")

        // 测试按钮
        findViewById<TextView>(R.id.btn_test_server).setOnClickListener { testPCServer() }
        findViewById<TextView>(R.id.btn_test_model).setOnClickListener { testCloudModel() }
        findViewById<TextView>(R.id.btn_test_local).setOnClickListener { testLocalModel() }
        findViewById<TextView>(R.id.btn_test_custom).setOnClickListener { testCustomModel() }
        findViewById<TextView>(R.id.btn_start_pc_guide).setOnClickListener { showPCGuide() }

        // 清空历史
        findViewById<TextView>(R.id.btn_clear_history).setOnClickListener { clearHistory() }

        // 关于
        findViewById<TextView>(R.id.btn_about).setOnClickListener {
            val info = this.packageManager.getPackageInfo(this.packageName, 0)
            Toast.makeText(this, "LookGm 全游戏AI助手 v${info.versionName}\nBuild: ${info.versionCode}\n开源项目 - MIT协议", Toast.LENGTH_LONG).show()
        }
    }

    // ===== 模式切换 =====

    private fun switchMode(mode: ModelMode) {
        currentMode = mode

        val activeBg = getDrawable(R.drawable.mode_tab_active)
        val inactiveBg = getDrawable(R.drawable.mode_tab_inactive)

        btnModeCloud.background = if (mode == ModelMode.CLOUD) activeBg else inactiveBg
        btnModeCloud.setTextColor(if (mode == ModelMode.CLOUD) 0xFFFFFFFF.toInt() else 0xFF8A8A9A.toInt())
        btnModeLocal.background = if (mode == ModelMode.LOCAL) activeBg else inactiveBg
        btnModeLocal.setTextColor(if (mode == ModelMode.LOCAL) 0xFFFFFFFF.toInt() else 0xFF8A8A9A.toInt())
        btnModeCustom.background = if (mode == ModelMode.CUSTOM) activeBg else inactiveBg
        btnModeCustom.setTextColor(if (mode == ModelMode.CUSTOM) 0xFFFFFFFF.toInt() else 0xFF8A8A9A.toInt())

        layoutCloudConfig.visibility = if (mode == ModelMode.CLOUD) View.VISIBLE else View.GONE
        layoutLocalConfig.visibility = if (mode == ModelMode.LOCAL) View.VISIBLE else View.GONE
        layoutCustomConfig.visibility = if (mode == ModelMode.CUSTOM) View.VISIBLE else View.GONE
    }

    // ===== 加载配置到UI =====

    private fun loadConfigToUI() {
        etHost.setText(currentConfig.serverHost)
        etPort.setText(currentConfig.serverPort.toString())

        // 游戏
        val gameNames = com.gameai.common.constants.GameConstants.SUPPORTED_GAMES.keys.toList()
        val gameAdapter = ArrayAdapter(this, R.layout.item_spinner_selected, gameNames).also {
            it.setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerGame.adapter = gameAdapter
        val gameIdx = gameNames.indexOf(currentConfig.gameName)
        if (gameIdx >= 0) spinnerGame.setSelection(gameIdx)

        // 云端供应商
        val cloudProviders = ModelProvider.entries.filter { !it.isLocal && it != ModelProvider.CUSTOM }
        val providerNames = cloudProviders.map { "${it.displayName} (${it.defaultModel})" }
        val providerAdapter = ArrayAdapter(this, R.layout.item_spinner_selected, providerNames).also {
            it.setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerProvider.adapter = providerAdapter
        val providerIdx = cloudProviders.indexOfFirst { it == currentConfig.currentProvider }
        if (providerIdx >= 0) spinnerProvider.setSelection(providerIdx)

        // 加载当前供应商配置
        val pConfig = currentConfig.getCurrentProviderConfig()
        etApiKey.setText(pConfig.apiKey)
        etBaseUrl.setText(pConfig.baseUrl)
        etModelName.setText(pConfig.modelName)

        // 本地模型
        val localServices = listOf(
            "Ollama (端口11434)",
            "vLLM (端口8000)",
            "LM Studio (端口1234)"
        )
        val localAdapter = ArrayAdapter(this, R.layout.item_spinner_selected, localServices).also {
            it.setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerLocalService.adapter = localAdapter
        etLocalHost.setText(currentConfig.localModelHost)
        etLocalPort.setText(currentConfig.localModelPort.toString())
        etLocalModel.setText(currentConfig.localModelName)

        // 自定义
        etCustomBaseUrl.setText(currentConfig.customBaseUrl)
        etCustomApiKey.setText(currentConfig.customApiKey)
        etCustomModelName.setText(currentConfig.customModelName)
    }

    // ===== 保存 =====

    private fun saveAndExit() {
        val providerIdx = spinnerProvider.selectedItemPosition
        val cloudProviders = ModelProvider.entries.filter { !it.isLocal && it != ModelProvider.CUSTOM }
        val selectedProvider = if (providerIdx in cloudProviders.indices) cloudProviders[providerIdx] else ModelProvider.OPENAI

        // 构建当前供应商配置
        val pConfig = ProviderConfig(
            provider = selectedProvider,
            apiKey = etApiKey.text.toString().trim(),
            baseUrl = etBaseUrl.text.toString().trim(),
            modelName = etModelName.text.toString().trim()
        )

        // 更新cloudProviderConfigs
        val updatedCloudConfigs = currentConfig.cloudProviderConfigs.toMutableMap()
        updatedCloudConfigs[selectedProvider.name] = pConfig

        val newConfig = currentConfig.copy(
            modelMode = currentMode,
            currentProvider = selectedProvider,
            cloudProviderConfigs = updatedCloudConfigs,
            gameName = spinnerGame.selectedItem?.toString() ?: "王者荣耀",
            serverHost = etHost.text.toString().trim(),
            serverPort = etPort.text.toString().toIntOrNull() ?: 8765,
            localModelHost = etLocalHost.text.toString().trim(),
            localModelPort = etLocalPort.text.toString().toIntOrNull() ?: 11434,
            localModelName = etLocalModel.text.toString().trim(),
            customBaseUrl = etCustomBaseUrl.text.toString().trim(),
            customApiKey = etCustomApiKey.text.toString().trim(),
            customModelName = etCustomModelName.text.toString().trim()
        )

        prefs.saveConfig(newConfig)
        Toast.makeText(this, "配置已保存 ✓", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ===== 连接测试 =====

    private fun testPCServer() {
        val host = etHost.text.toString().trim()
        val port = etPort.text.toString().toIntOrNull() ?: 8765
        val statusTv = findViewById<TextView>(R.id.tv_server_status)

        statusTv.text = "⏳ 正在测试..."
        lifecycleScope.launch {
            val result = ModelConnectionTester.testPCServerStatic(host, port)
            runOnUiThread {
                statusTv.text = result.message
                statusTv.setTextColor(
                    if (result.success) 0xFF4CAF50.toInt() else 0xFFFF5252.toInt()
                )
            }
        }
    }

    private fun testCloudModel() {
        val providerIdx = spinnerProvider.selectedItemPosition
        val cloudProviders = ModelProvider.entries.filter { !it.isLocal && it != ModelProvider.CUSTOM }
        if (providerIdx !in cloudProviders.indices) return

        val provider = cloudProviders[providerIdx]
        val config = ProviderConfig(
            provider = provider,
            apiKey = etApiKey.text.toString().trim(),
            baseUrl = etBaseUrl.text.toString().trim(),
            modelName = etModelName.text.toString().trim()
        )
        val statusTv = findViewById<TextView>(R.id.tv_model_status)

        statusTv.text = "⏳ 正在测试 ${provider.displayName}..."
        lifecycleScope.launch {
            val result = ModelConnectionTester.testConnection(config, false)
            runOnUiThread {
                statusTv.text = result.message
                statusTv.setTextColor(
                    if (result.success) 0xFF4CAF50.toInt() else 0xFFFF5252.toInt()
                )
            }
        }
    }

    private fun testLocalModel() {
        val host = etLocalHost.text.toString().trim()
        val port = etLocalPort.text.toString().toIntOrNull() ?: 11434
        val model = etLocalModel.text.toString().trim()
        val config = ProviderConfig(
            provider = ModelProvider.LOCAL_OLLAMA,
            baseUrl = "http://${host}:${port}/v1",
            modelName = model
        )
        val statusTv = findViewById<TextView>(R.id.tv_local_status)

        statusTv.text = "⏳ 正在测试本地模型..."
        lifecycleScope.launch {
            val result = ModelConnectionTester.testConnection(config, true)
            runOnUiThread {
                statusTv.text = result.message
                statusTv.setTextColor(
                    if (result.success) 0xFF4CAF50.toInt() else 0xFFFF5252.toInt()
                )
            }
        }
    }

    private fun testCustomModel() {
        val baseUrl = etCustomBaseUrl.text.toString().trim()
        val apiKey = etCustomApiKey.text.toString().trim()
        val model = etCustomModelName.text.toString().trim()
        val config = ProviderConfig(
            provider = ModelProvider.CUSTOM,
            apiKey = apiKey,
            baseUrl = baseUrl,
            modelName = model
        )
        val statusTv = findViewById<TextView>(R.id.tv_custom_status)

        statusTv.text = "⏳ 正在测试自定义接口..."
        lifecycleScope.launch {
            val result = ModelConnectionTester.testConnection(config, false)
            runOnUiThread {
                statusTv.text = result.message
                statusTv.setTextColor(
                    if (result.success) 0xFF4CAF50.toInt() else 0xFFFF5252.toInt()
                )
            }
        }
    }

    private fun showPCGuide() {
        val msg = """
            ▎PC端模型启动指南
            
            【Ollama】
            1. 下载: https://ollama.com
            2. 安装后运行:
               ollama serve
            3. 下载模型:
               ollama pull qwen2.5:7b
            4. 端口: 11434
            
            【vLLM】
            pip install vllm
            vllm serve Qwen/Qwen2.5-VL-7B-Instruct --host 0.0.0.0 --port 8000
            
            【LM Studio】
            1. 下载: https://lmstudio.ai
            2. 搜索下载模型
            3. 开启Local Server
            4. 端口: 1234
        """.trimIndent()
        Toast.makeText(this, "指南已复制到剪贴板，请查看详情", Toast.LENGTH_LONG).show()
        // 复制到剪贴板
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PC指南", msg))
    }

    private fun clearHistory() {
        android.app.AlertDialog.Builder(this)
            .setTitle("确认清空")
            .setMessage("将删除所有对局历史记录，此操作不可恢复。")
            .setPositiveButton("确认清除") { _, _ ->
                lifecycleScope.launch {
                    val db = com.gameai.db.AppDatabase.getInstance(this@SettingsActivity)
                    db.matchDao().deleteAll()
                    runOnUiThread {
                        Toast.makeText(this@SettingsActivity, "对局历史已清空 ✓", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 辅助方法 =====

    private fun setupSeekBar(seekbarId: Int, textViewId: Int, suffix: String) {
        val seekBar = findViewById<SeekBar>(seekbarId)
        val textView = findViewById<TextView>(textViewId)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val display = when (seekbarId) {
                    R.id.seekbar_fps -> "${progress + 1} $suffix"
                    else -> "$progress$suffix"
                }
                textView.text = display
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun getCurrentDefaultBaseUrl(): String {
        val idx = spinnerProvider.selectedItemPosition
        val providers = ModelProvider.entries.filter { !it.isLocal && it != ModelProvider.CUSTOM }
        return if (idx in providers.indices) providers[idx].defaultBaseUrl else ""
    }

    private fun getCurrentDefaultModel(): String {
        val idx = spinnerProvider.selectedItemPosition
        val providers = ModelProvider.entries.filter { !it.isLocal && it != ModelProvider.CUSTOM }
        return if (idx in providers.indices) providers[idx].defaultModel else ""
    }
}
