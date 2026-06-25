// CloudAiClient.kt - OpenAI/Claude 兼容 API 客户端
package com.gameai.ai

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.gameai.model.ProviderConfig
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 云端 AI API 客户端，支持 OpenAI / Claude / DeepSeek / 通义千问 等
 * 全部兼容 OpenAI Chat Completions 格式
 *
 * 并发安全：全局 ReentrantLock 防止多个协程同时调用 API
 * 双模型支持：语音对话和分析可分别配置不同模型
 *
 * v2 新增：UsageTracker 用量追踪 + SkillManager 技能化提示词
 */
object CloudAiClient {

    private const val TAG = "CloudAiClient"
    private const val TIMEOUT_SECONDS = 30L

    /** 全局并发锁：确保同一时间只发起一个 API 请求，避免 token 混用和 429 限流 */
    private val apiLock = ReentrantLock()

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 当前注入的游戏名/英雄名，供技能系统动态构建提示词 */
    @Volatile var currentGameName: String = ""
    @Volatile var currentHeroName: String = ""

    /**
     * 同步调用 AI 接口分析游戏画面（在后台线程中调用）
     * @param bitmap 当前屏幕截图
     * @param config 模型配置（apiKey, baseUrl, modelName）
     * @param systemPrompt 系统提示词
     * @return 分析文本，失败返回 null
     */
    suspend fun analyze(bitmap: Bitmap, config: ProviderConfig, systemPrompt: String): String? {
        return withContext(Dispatchers.IO) {
            apiLock.lock()
            val startTime = System.currentTimeMillis()
            try {
                // 动态注入技能提示词
                val enhancedPrompt = buildEnhancedSystemPrompt(config.modelName, "analysis", systemPrompt)
                val base64Image = bitmapToBase64(bitmap)
                val requestBody = buildRequestBody(config.modelName, enhancedPrompt, base64Image)
                val response = callApi(config, requestBody)
                val result = parseResponse(response)
                val latency = System.currentTimeMillis() - startTime
                // 追踪用量
                val promptTokens = UsageTracker.estimateTokens(enhancedPrompt) + UsageTracker.estimateTokens("分析当前画面")
                val completionTokens = UsageTracker.estimateTokens(result ?: "")
                UsageTracker.record(config.provider.name, config.modelName, "analysis", promptTokens, completionTokens, latency)
                result
            } catch (e: Exception) {
                e.printStackTrace()
                UsageTracker.record(config.provider.name, config.modelName, "analysis", 0, 0, 0, false)
                null
            } finally {
                apiLock.unlock()
            }
        }
    }

    /**
     * 多轮语音对话（豆包模式）— 支持历史上下文 + 截图 + 语音转文字
     * @param bitmap 当前屏幕截图（可为 null）
     * @param config 模型配置
     * @param userMessage 用户语音转文字
     * @return AI 口语化回复，失败返回 null
     */
    suspend fun converse(
        bitmap: Bitmap?,
        config: ProviderConfig,
        userMessage: String
    ): String? {
        return withContext(Dispatchers.IO) {
            apiLock.lock()
            val startTime = System.currentTimeMillis()
            try {
                val requestBody = buildConversationBody(config.modelName, userMessage, bitmap)
                val response = callApi(config, requestBody)
                val result = parseResponse(response)
                val latency = System.currentTimeMillis() - startTime
                val promptTokens = UsageTracker.estimateTokens(userMessage) + 200  // system prompt ~200 tokens
                val completionTokens = UsageTracker.estimateTokens(result ?: "")
                UsageTracker.record(config.provider.name, config.modelName, "conversation", promptTokens, completionTokens, latency)
                result
            } catch (e: Exception) {
                e.printStackTrace()
                UsageTracker.record(config.provider.name, config.modelName, "conversation", 0, 0, 0, false)
                null
            } finally {
                apiLock.unlock()
            }
        }
    }

    /**
     * SSE 流式语音对话 — 仿豆包电话实时体验
     *
     * 原理：openai-compatible /chat/completions + stream=true
     * 服务端通过 SSE (Server-Sent Events) 逐 token 推送，客户端实时接收并回调。
     *
     * 与豆包电话相同的关键体验：
     * 1. 用户说出第一个字时 AI 就开始"思考"
     * 2. AI 的第一个 token 在几百毫秒内到达（首token延迟）
     * 3. 后续 token 连续流式到达，实现"边说边想"的效果
     *
     * @param bitmap 屏幕截图（可为 null）
     * @param config 对话模型配置
     * @param userMessage 语音识别出的文字
     * @param onToken 每个新 token 的回调（含完整累积文本）
     * @param onComplete 流结束回调（完整文本）
     * @param onError 错误回调
     * @return 用于取消的 Cancelable
     */
    fun converseStreaming(
        bitmap: Bitmap?,
        config: ProviderConfig,
        userMessage: String,
        onToken: (accumulatedText: String) -> Unit,
        onComplete: (fullText: String) -> Unit,
        onError: (String) -> Unit
    ): StreamingCall {
        val call = StreamingCall()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        scope.launch {
            val startTime = System.currentTimeMillis()
            try {
                val body = buildConversationBodyStreaming(config.modelName, userMessage, bitmap)

                val url = normalizeApiUrl(config.baseUrl) + "/chat/completions"
                val requestBody = body.toRequestBody(JSON)
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .addHeader("Accept", "text/event-stream")
                    .build()

                val response = client.newCall(request).execute()
                call.responseRef = response

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    android.util.Log.w(TAG, "SSE error ${response.code}: $errorBody")
                    Handler(Looper.getMainLooper()).post {
                        onError("AI 请求失败 (HTTP ${response.code})")
                    }
                    UsageTracker.record(config.provider.name, config.modelName, "conversation", 0, 0, 0, false)
                    return@launch
                }

                val source = response.body?.source() ?: run {
                    Handler(Looper.getMainLooper()).post { onError("响应为空") }
                    return@launch
                }

                val accumulated = StringBuilder()
                var lineCount = 0

                while (!source.exhausted() && !call.isCancelled) {
                    val line = source.readUtf8Line() ?: break
                    lineCount++

                    if (line.isEmpty() || line.startsWith(":")) continue

                    if (line == "data: [DONE]") break
                    if (!line.startsWith("data: ")) continue

                    val jsonStr = line.removePrefix("data: ").trim()
                    if (jsonStr.isEmpty() || jsonStr == "[DONE]") break

                    try {
                        val json = org.json.JSONObject(jsonStr)
                        val choices = json.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            val content = delta?.optString("content", "") ?: ""
                            if (content.isNotEmpty()) {
                                accumulated.append(content)
                                Handler(Looper.getMainLooper()).post {
                                    onToken(accumulated.toString())
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // 跳过无法解析的行
                    }
                }

                response.close()
                val fullText = accumulated.toString()

                // 追踪用量
                val latency = System.currentTimeMillis() - startTime
                val promptTokens = UsageTracker.estimateTokens(userMessage) + 200
                val completionTokens = UsageTracker.estimateTokens(fullText)
                UsageTracker.record(config.provider.name, config.modelName, "conversation", promptTokens, completionTokens, latency)

                if (fullText.isNotBlank() && !call.isCancelled) {
                    Handler(Looper.getMainLooper()).post {
                        onComplete(fullText)
                    }
                } else if (call.isCancelled) {
                    android.util.Log.d(TAG, "SSE 流被用户打断")
                } else {
                    Handler(Looper.getMainLooper()).post {
                        onError("AI 未返回有效回复")
                    }
                }

            } catch (e: java.io.IOException) {
                if (!call.isCancelled) {
                    Handler(Looper.getMainLooper()).post {
                        onError("网络错误: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (!call.isCancelled) {
                    Handler(Looper.getMainLooper()).post {
                        onError("流式处理错误: ${e.message}")
                    }
                }
            }
        }

        call.scope = scope
        return call
    }

    /** 流式调用的可取消句柄 */
    class StreamingCall {
        internal var scope: CoroutineScope? = null
        internal var responseRef: okhttp3.Response? = null
        @Volatile var isCancelled: Boolean = false
            private set

        /** 取消流式请求（打断用） */
        fun cancel() {
            isCancelled = true
            try {
                responseRef?.close()
            } catch (_: Exception) {}
            try {
                scope?.cancel()
            } catch (_: Exception) {}
        }
    }

    // ============================================================
    //  Skill-enhanced 系统提示词（参考 OpenClaw 技能注入机制）
    // ============================================================

    /**
     * 构建增强版系统提示词 = 基础提示词 + 技能知识 + 用户画像 + 游戏上下文
     * 参考 OpenClaw: 技能以紧凑片段形式注入，不重复、不冗余
     */
    private suspend fun buildEnhancedSystemPrompt(modelName: String, purpose: String, basePrompt: String): String {
        val sb = StringBuilder()
        sb.append(basePrompt)

        // 注入技能知识
        if (currentGameName.isNotBlank()) {
            try {
                val skillContext = SkillManager.buildSkillContext(currentGameName, currentHeroName)
                if (skillContext.isNotBlank()) {
                    sb.append(skillContext)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Skill context build failed", e)
            }
        }

        // 注入用户画像
        try {
            val userProfile = MemoryManager.buildUserProfileContext()
            if (userProfile.isNotBlank()) {
                sb.append("\n\n")
                sb.append(userProfile)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "User profile build failed", e)
        }

        return sb.toString()
    }

    private fun callApi(config: ProviderConfig, body: String): String? {
        val url = normalizeApiUrl(config.baseUrl) + "/chat/completions"
        val requestBody = body.toRequestBody(JSON)

        val builder = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")

        // 根据供应商设置不同的鉴权头
        when {
            config.apiKey.isNotEmpty() -> {
                builder.addHeader("Authorization", "Bearer ${config.apiKey}")
            }
        }

        val request = builder.build()
        val call = client.newCall(request)

        return try {
            val response = call.execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                val errorBody = response.body?.string() ?: ""
                android.util.Log.w(TAG, "API error ${response.code}: $errorBody")
                null
            }
        } catch (e: IOException) {
            android.util.Log.e(TAG, "API call failed", e)
            null
        }
    }

    // ============================================================
    //  请求构建
    // ============================================================

    private fun buildRequestBody(model: String, systemPrompt: String, base64Image: String): String {
        val messages = JSONArray()

        // System message
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        // User message with image
        val userContent = JSONArray()
        userContent.put(JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", "data:image/jpeg;base64,$base64Image")
                put("detail", "low")  // 低细节模式，更快更省 token
            })
        })
        userContent.put(JSONObject().apply {
            put("type", "text")
            put("text", "分析当前画面，给出简洁的战术建议。")
        })

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userContent)
        })

        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", 150)
            put("temperature", 0.3)
        }.toString()
    }

    /**
     * 构建语音对话请求体（多轮上下文 + 截图 + 语音文字）
     */
    private suspend fun buildConversationBody(
        model: String,
        userMessage: String,
        bitmap: Bitmap?
    ): String {
        val messages = JSONArray()

        // 动态构建系统提示词
        val baseSystemPrompt = """
你是一个游戏语音助手，名叫"小G"。你正在通过语音和玩家对话。玩家在玩王者荣耀，你能看到他的手机屏幕。

说话要求：
1. 使用口语化中文，像朋友聊天一样自然
2. 回复简洁，控制在 2-4 句话（约 30-80 字）
3. 基于看到的屏幕画面给出建议
4. 如果玩家问战术问题，给出具体可操作的建议
5. 语气轻松友好，适当使用语气词（"哦""呢""吧"）
6. 不要使用任何格式标记（不用markdown、不用编号）

你的能力：
- 能看到玩家屏幕上的游戏画面
- 可以分析局势、阵容、装备、小地图
- 给出实时战术指导
""".trimIndent()

        val systemPrompt = buildEnhancedSystemPrompt(model, "conversation", baseSystemPrompt)

        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        // 当前用户消息（含截图）
        if (bitmap != null) {
            val base64Image = bitmapToBase64(bitmap)
            val userContent = JSONArray()
            userContent.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64Image")
                    put("detail", "low")
                })
            })
            userContent.put(JSONObject().apply {
                put("type", "text")
                put("text", userMessage)
            })
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })
        } else {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", 200)
            put("temperature", 0.7)
        }.toString()
    }

    /** 构建流式语音对话请求体（SSE，stream=true） */
    private suspend fun buildConversationBodyStreaming(
        model: String,
        userMessage: String,
        bitmap: Bitmap?
    ): String {
        val messages = JSONArray()

        val baseSystemPrompt = """
你是一个游戏语音助手，名叫"小G"。你正在通过语音和玩家对话。玩家在玩王者荣耀，你能看到他的手机屏幕。

说话要求：
1. 使用口语化中文，像朋友聊天一样自然
2. 回复简洁，控制在 2-4 句话（约 30-80 字）
3. 基于看到的屏幕画面给出建议
4. 如果玩家问战术问题，给出具体可操作的建议
5. 语气轻松友好，适当使用语气词（"哦""呢""吧"）
6. 不要使用任何格式标记（不用markdown、不用编号）

你的能力：
- 能看到玩家屏幕上的游戏画面
- 可以分析局势、阵容、装备、小地图
- 给出实时战术指导
""".trimIndent()

        val systemPrompt = buildEnhancedSystemPrompt(model, "conversation", baseSystemPrompt)

        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        if (bitmap != null) {
            val base64Image = bitmapToBase64(bitmap)
            val userContent = JSONArray()
            userContent.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64Image")
                    put("detail", "low")
                })
            })
            userContent.put(JSONObject().apply {
                put("type", "text")
                put("text", userMessage)
            })
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })
        } else {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", 200)
            put("temperature", 0.7)
            put("stream", true)
        }.toString()
    }

    // ============================================================
    //  响应解析
    // ============================================================

    private fun parseResponse(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val json = JSONObject(body)
            val choices = json.getJSONArray("choices")
            if (choices.length() > 0) {
                val message = choices.getJSONObject(0).getJSONObject("message")
                message.getString("content").trim()
            } else null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Parse error", e)
            null
        }
    }

    // ============================================================
    //  工具方法
    // ============================================================

    /** Bitmap → Base64 JPEG（低质量、小尺寸，减少 token 消耗）*/
    private fun bitmapToBase64(bitmap: Bitmap): String {
        // 缩放到 512px 宽以节省 token
        val scaleWidth = 512
        val scaleHeight = (bitmap.height.toFloat() / bitmap.width * scaleWidth).toInt()
        val scaled = Bitmap.createScaledBitmap(bitmap, scaleWidth, scaleHeight, true)

        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 50, bos)
        val bytes = bos.toByteArray()
        bos.close()

        if (scaled != bitmap) scaled.recycle()

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** 标准化 API 地址：去掉尾部斜杠和 /v1 */
    private fun normalizeApiUrl(url: String): String {
        return url.trimEnd('/').let {
            // 部分供应商已在 baseUrl 中包含 /v1
            if (it.endsWith("/v1")) it.removeSuffix("/v1") else it
        }.let { it + "/v1" }
    }
}
