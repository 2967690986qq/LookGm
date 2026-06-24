// GitHubAuthManager.kt — PKCE OAuth 2.0 认证管理器
// 使用 PKCE (Proof Key for Code Exchange) 无需在客户端存储 client_secret
package com.gameai.utils

import android.content.Context
import android.util.Base64
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

        // 保存 verifier 供后续换 Token
        context.getSharedPreferences("lookgm_auth", Context.MODE_PRIVATE)
            .edit()
            .putString(CODE_VERIFIER_KEY, verifier)
            .apply()

        val params = mapOf(
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "scope" to "user",
            "state" to "lookgm_${System.currentTimeMillis()}",
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

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext TokenResult.Error("Token 请求失败: ${response.code}")
                }

                val tokenResp = gson.fromJson(responseBody, TokenResponse::class.java)

                if (tokenResp.error != null) {
                    return@withContext TokenResult.Error(tokenResp.errorDescription ?: tokenResp.error)
                }

                val accessToken = tokenResp.accessToken
                if (accessToken.isNullOrBlank()) {
                    return@withContext TokenResult.Error("未获取到 access_token")
                }

                // 保存 Token
                prefs.edit()
                    .putString(ACCESS_TOKEN_KEY, accessToken)
                    .remove(CODE_VERIFIER_KEY)  // 用完即删
                    .apply()

                TokenResult.Success(accessToken)
            } catch (e: Exception) {
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

                // 保存用户信息
                context.getSharedPreferences("lookgm_auth", Context.MODE_PRIVATE)
                    .edit()
                    .putString(USER_INFO_KEY, body)
                    .putString("username", user.login)
                    .putString("login_type", "github")
                    .putLong("login_time", System.currentTimeMillis())
                    .putBoolean("logged_in", true)
                    .apply()

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
            .putString("login_type", "local")
            .putBoolean("logged_in", false)
            .apply()
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
