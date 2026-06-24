// GitHubOAuthActivity.kt — 接收 GitHub OAuth DeepLink 回调
package com.gameai.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gameai.utils.GitHubAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 接收 GitHub OAuth 回调：
 *   lookgm://oauth/callback?code=XXXXXX&state=YYYYYY
 *
 * Intent Filter 在 AndroidManifest.xml 中定义。
 */
class GitHubOAuthActivity : AppCompatActivity() {

    companion object {
        const val ACTION_AUTH_SUCCESS = "com.gameai.action.GITHUB_AUTH_SUCCESS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data

        if (uri == null) {
            Toast.makeText(this, "无效的回调链接", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        val state = uri.getQueryParameter("state")

        if (error != null) {
            val errorDesc = uri.getQueryParameter("error_description") ?: error
            Toast.makeText(this, "授权被取消: $errorDesc", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (code.isNullOrBlank()) {
            Toast.makeText(this, "未获取到授权码，请重试", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // 验证 state 防 CSRF（非阻塞：即使 state 校验失败也继续流程，仅做日志记录）
        if (!GitHubAuthManager.validateState(this, state)) {
            android.util.Log.w("GitHubOAuth", "State 校验失败，可能为非本人发起或多次点击")
        }

        // 异步换取 Token + 获取用户信息
        lifecycleScope.launch {
            when (val result = GitHubAuthManager.exchangeCodeForToken(this@GitHubOAuthActivity, code)) {
                is GitHubAuthManager.TokenResult.Success -> {
                    // 获取用户信息
                    val user = GitHubAuthManager.fetchUserInfo(this@GitHubOAuthActivity)

                    withContext(Dispatchers.Main) {
                        if (user != null) {
                            Toast.makeText(
                                this@GitHubOAuthActivity,
                                "GitHub 登录成功: @${user.login}",
                                Toast.LENGTH_SHORT
                            ).show()

                            // 发送广播通知界面刷新
                            sendBroadcast(Intent(ACTION_AUTH_SUCCESS).apply {
                                setPackage(packageName)
                            })
                        } else {
                            Toast.makeText(
                                this@GitHubOAuthActivity,
                                "Token 获取成功，用户信息拉取失败",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                is GitHubAuthManager.TokenResult.Error -> {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@GitHubOAuthActivity,
                            "登录失败: ${result.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            finish()
        }
    }
}
