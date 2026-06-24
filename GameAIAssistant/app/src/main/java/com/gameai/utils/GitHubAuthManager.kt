// GitHubAuthManager.kt — PKCE OAuth 2.0 认证管理器
// 使用 PKCE (Proof Key for Code Exchange) 无需在客户端存储 client_secret
package com.gameai.utils

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

object GitHubAuthManager {

    // ============ OAuth App 配置 ============
    // 请在 GitHub Settings → Developer settings → OAuth Apps 创建后填入
    const val CLIENT_ID = "Ov23liQAokvYI7DAVmJv"   // GitHub OAuth App Client ID
    private const val CLIENT_SECRET = "da164aad998317762b367b5885011b727f6066b8"  // 标准 OAuth App 换 Token 必须
    private const val REDIRECT_URI = "lookgm://oauth/callback"
    private const val AUTH_URL = "https://github.com/login/oauth/authorize"
    private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val USER_API_URL = "https://api.github.com/user"

    // PKCE 参数
    private const val CODE_VERIFIER_KEY = "gh_code_verifier"
    private const val ACCESS_TOKEN_KEY = "gh_access_token"
    private const val USER_INFO_KEY = "gh_user_info"
    private const val AUTH_STATE_KEY = "gh_auth_state"

    private val gson = Gson()
    private val random = SecureRandom()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ============ PKCE 工具 ============

    /** 生成 128 字节随机字符串 → Base64 URL-safe */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** SHA-256(code_verifier) → Base64 URL-safe */
    fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    // ============ OAuth 授权 URL ============

    fun getAuthorizationUrl(context: Context): String {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = "lookgm_${System.currentTimeMillis()}"

        // 使用 commit() 同步写入，防止进程被系统杀死时数据未落地
        context.getSharedPreferences("lookgm_auth", Context.MODE_PRIVATE)
            .edit()
            .putString(CODE_VERIFIER_KEY, verifier)
            .putString(AUTH_STATE_KEY, state)
            .commit()

        Log.d("GitHubAuth", "OAuth 授权开始, state=$state")

        val params = mapOf(
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "scope" to "user",
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256"
        )

        val queryString = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        return "$AUTH_URL?$queryString"
    }

    // ============ Code 换 Token ============

    suspend fun exchangeCodeForToken(context: Context, code: String): TokenResult {
        return withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("lookgm_auth", Context.MODE_PRIVATE)
            val verifier = prefs.getString(CODE_VERIFIER_KEY, null)
                ?: return@withContext TokenResult.Error("PKCE verifier 丢失，请重新授权")

            try {
                val body = mapOf(
                    "client_id" to CLIENT_ID,
                    "client_secret" to CLIENT_SECRET,
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "code_verifier" to verifier,
                    "grant_type" to "authorization_code"
                )

                val formBody = body.entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }

                val request = Request.Builder()
                    .url(TOKEN_URL)
                    .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .header("Accept", "application/json")
                    .build()

                Log.d("GitHubAuth", "发起 Token 交换请求, code=${code.take(10)}..., verifier=${verifier.take(10)}...")
                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                Log.d("GitHubAuth", "Token 响应 HTTP ${response.code}, body=${responseBody.take(300)}")

                if (!response.isSuccessful) {
                    // 尝试解析错误详情
                    try {
                        val errResp = gson.fromJson(responseBody, TokenResponse::class.java)
                        if (errResp.error != null) {
                            return@withContext TokenResult.Error(
                                "${errResp.errorDescription ?: errResp.error} (HTTP ${response.code})"
                            )
                        }
                    } catch (_: Exception) {}
                    return@withContext TokenResult.Error("Token 请求失败: HTTP ${response.code}\n${responseBody.take(200)}")
                }

                val tokenResp = gson.fromJson(responseBody, TokenResponse::class.java)

                if (tokenResp.error != null) {
                    Log.e("GitHubAuth", "GitHub 返回错误: ${tokenResp.error} - ${tokenResp.errorDescription}")
                    return@withContext TokenResult.Error(tokenResp.errorDescription ?: tokenResp.error)
                }

                val accessToken = tokenResp.accessToken
                if (accessToken.isNullOrBlank()) {
                    Log.e("GitHubAuth", "响应中无 access_token, body=$responseBody")
                    return@withContext TokenResult.Error("GitHub 未返回 access_token")
                }

                Log.d("GitHubAuth", "获取 Token 成功, token=${accessToken.take(8)}...")

                // 保存 Token（同步写入防止丢失）
                prefs.edit()
                    .putString(ACCESS_TOKEN_KEY, accessToken)
                    .remove(CODE_VERIFIER_KEY)  // 用完即删
                    .remove(AUTH_STATE_KEY)
                    .commit()

                TokenResult.Success(accessToken)
            } catch (e: java.net.UnknownHostException) {
                Log.e("GitHubAuth", "网络不可达: ${e.message}")
                TokenResult.Error("网络不可达，请检查网络连接")
            } catch (e: java.net.SocketTimeoutException) {
                Log.e("GitHubAuth", "请求超时: ${e.message}")
                TokenResult.Error("请求超时，请重试")
            } catch (e: javax.net.ssl.SSLException) {
                Log.e("GitHubAuth", "SSL 错误: ${e.message}")
                TokenResult.Error("安全连接失败，请检查系统时间")
            } catch (e: Exception) {
                Log.e("GitHubAuth", "Token 交换异常", e)
                TokenResult.Error("网络异常: ${e.message}")
            }
        }
    }

    // ============ 用户信息 ============

    suspend fun fetchUserInfo(context: Context): GitHubUser? {
        return withContext(Dispatchers.IO) {
            val token = getAccessToken(context) ?: return@withContext null
            try {
                val request = Request.Builder()
                    .url(USER_API_URL)
                    .header("Authorization", "token $token")
                    .header("Accept", "application/json")
                    .header("User-Agent", "LookGm")
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext null

                if (!response.isSuccessful) return@withContext null

                val user = gson.fromJson(body, GitHubUser::class.java)

                // 保存用户信息（同步写入）
                context.getSharedPreferences("lookgm_auth", Context.MODE_PRIVATE)
                    .edit()
                    .putString(USER_INFO_KEY, body)
                    .putString("username", user.login)
                    .putString("login_type", "github")
                    .putLong("login_time", System.currentTimeMillis())
                    .putBoolean("logged_in", true)
                    .commit()

                Log.d("GitHubAuth", "用户信息已缓存: @${user.login}")

                user
            } catch (e: Exception) {
                null
            }
        }
    }

    // ============ 本地状态查询 ============

    fun getAccessToken(context: Context): String? {
        return context.getSharedPreferences("lookgm_auth", Context.MODE_PRIVATE)
            .getString(ACCESS_TOKEN_KEY, null)
    }

    /** 验证回调 state 是否与本机发起的请求匹配（防 CSRF） */
    fun validateState(context: Context, callbackState: String?): Boolean {
        if (callbackState.isNullOrBlank()) return false
        val savedState = context.getSharedPreferences("lookgm_auth", Context.MODE_PRIVATE)
            .getString(AUTH_STATE_KEY, null)
        val valid = callbackState == savedState
        if (!valid) {
            Log.w("GitHubAuth", "State 不匹配! 期望=$savedState, 收到=$callbackState")
        }
        return valid
    }

    fun getCachedUser(context: Context): GitHubUser? {
        val json = context.getSharedPreferences("lookgm_auth", Context.MODE_PRIVATE)
            .getString(USER_INFO_KEY, null) ?: return null
        return try { gson.fromJson(json, GitHubUser::class.java) } catch (_: Exception) { null }
    }

    fun isLoggedIn(context: Context): Boolean {
        val prefs = context.getSharedPreferences("lookgm_auth", Context.MODE_PRIVATE)
        return prefs.getString("login_type", "local") == "github"
                && !prefs.getString(ACCESS_TOKEN_KEY, "").isNullOrBlank()
    }

    fun logout(context: Context) {
        context.getSharedPreferences("lookgm_auth", Context.MODE_PRIVATE)
            .edit()
            .remove(ACCESS_TOKEN_KEY)
            .remove(USER_INFO_KEY)
            .remove(CODE_VERIFIER_KEY)
            .remove(AUTH_STATE_KEY)
            .putString("login_type", "local")
            .putBoolean("logged_in", false)
            .commit()
    }

    // ============ 数据模型 ============

    data class TokenResponse(
        @SerializedName("access_token") val accessToken: String?,
        @SerializedName("token_type") val tokenType: String?,
        @SerializedName("scope") val scope: String?,
        @SerializedName("error") val error: String?,
        @SerializedName("error_description") val errorDescription: String?
    )

    sealed class TokenResult {
        data class Success(val accessToken: String) : TokenResult()
        data class Error(val message: String) : TokenResult()
    }

    data class GitHubUser(
        @SerializedName("login") val login: String,
        @SerializedName("id") val id: Long,
        @SerializedName("avatar_url") val avatarUrl: String,
        @SerializedName("name") val name: String?,
        @SerializedName("bio") val bio: String?,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("public_repos") val publicRepos: Int = 0,
        @SerializedName("followers") val followers: Int = 0
    )
}
