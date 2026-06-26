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
     * OCR 识别屏幕内容（用视觉模型识别图片中的文字和画面信息）
     * @param bitmap 当前屏幕截图
     * @param config 视觉模型配置
     * @return 识别到的屏幕内容文本，失败返回 null
     */
    suspend fun ocrRecognize(bitmap: Bitmap, config: ProviderConfig): String? {
        return withContext(Dispatchers.IO) {
            apiLock.lock()
            val startTime = System.currentTimeMillis()
            try {
                val systemPrompt = """
你是一个屏幕内容识别助手。请仔细识别图片中的所有内容，包括：
1. 所有可见的文字内容（按钮文字、标题、菜单文字、聊天内容等）
2. 界面布局和主要元素
3. 当前页面的类型和状态

请用简洁清晰的语言描述你看到的屏幕内容，按以下格式输出：
- 页面类型：[这是什么页面/应用]
- 主要内容：[页面上的主要文字和信息]
- 关键元素：[重要的按钮、图标、状态指示等]
""".trimIndent()

                val base64Image = bitmapToBase64(bitmap)
                val requestBody = buildOcrRequestBody(config.modelName, systemPrompt, base64Image)
                val response = callApi(config, requestBody)
                val result = parseResponse(response)
                val latency = System.currentTimeMillis() - startTime
                val promptTokens = UsageTracker.estimateTokens(systemPrompt) + 50
                val completionTokens = UsageTracker.estimateTokens(result ?: "")
                UsageTracker.record(config.provider.name, config.modelName, "vision", promptTokens, completionTokens, latency)
                result
            } catch (e: Exception) {
                e.printStackTrace()
                UsageTracker.record(config.provider.name, config.modelName, "vision", 0, 0, 0, false)
                null
            } finally {
                apiLock.unlock()
            }
        }
    }

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
                    android.util.Log.e(TAG, "SSE error ${response.code}: $errorBody")
                    response.close()

                    // 判断是否是不支持流式的错误，自动回退到阻塞模式
                    if (isStreamingNotSupportedError(response.code, errorBody)) {
                        android.util.Log.i(TAG, "流式输出不支持，自动回退到阻塞模式")
                        fallbackToBlockingMode(bitmap, config, userMessage, onToken, onComplete, onError)
                    } else {
                        Handler(Looper.getMainLooper()).post {
                            onError("请求失败 (HTTP ${response.code}): ${extractErrorMessage(errorBody)}")
                        }
                        UsageTracker.record(config.provider.name, config.modelName, "conversation", 0, 0, 0, false)
                    }
                    return@launch
                }

                val source = response.body?.source() ?: run {
                    response.close()
                    Handler(Looper.getMainLooper()).post { onError("响应为空，请检查网络连接") }
                    return@launch
                }

                val accumulated = StringBuilder()
                var lineCount = 0
                var hasValidContent = false

                while (!source.exhausted() && !call.isCancelled) {
                    val line = source.readUtf8Line() ?: break
                    lineCount++

                    if (line.isEmpty() || line.startsWith(":")) continue

                    if (line == "data: [DONE]") break
                    if (!line.startsWith("data: ")) {
                        // 可能是错误信息
                        android.util.Log.w(TAG, "SSE line: $line")
                        continue
                    }

                    val jsonStr = line.removePrefix("data: ").trim()
                    if (jsonStr.isEmpty() || jsonStr == "[DONE]") break

                    try {
                        val json = org.json.JSONObject(jsonStr)
                        val choices = json.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            val contentObj = delta?.opt("content")
                            val content = if (contentObj is String) contentObj else ""
                            if (content.isNotEmpty()) {
                                hasValidContent = true
                                accumulated.append(content)
                                Handler(Looper.getMainLooper()).post {
                                    onToken(accumulated.toString())
                                }
                            }
                        }
                    } catch (_: Exception) {
                        android.util.Log.w(TAG, "SSE解析失败: $jsonStr")
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
                } else if (!hasValidContent && lineCount > 0) {
                    // 有数据但没有有效内容，尝试回退到阻塞模式
                    android.util.Log.e(TAG, "SSE 流无有效内容，尝试回退到阻塞模式")
                    fallbackToBlockingMode(bitmap, config, userMessage, onToken, onComplete, onError)
                } else {
                    Handler(Looper.getMainLooper()).post {
                        onError("AI 未返回有效回复 (${lineCount}行数据)")
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
                    // 其他流式处理错误，尝试回退到阻塞模式
                    android.util.Log.e(TAG, "流式处理错误，尝试回退到阻塞模式: ${e.message}")
                    fallbackToBlockingMode(bitmap, config, userMessage, onToken, onComplete, onError)
                }
            }
        }

        call.scope = scope
        return call
    }

    /** 判断错误是否是因为不支持流式输出 */
    private fun isStreamingNotSupportedError(code: Int, errorBody: String): Boolean {
        return when (code) {
            400, 404, 422 -> {
                errorBody.contains("stream") ||
                errorBody.contains("streaming") ||
                errorBody.contains("not support") ||
                errorBody.contains("unsupported") ||
                errorBody.contains("invalid") ||
                errorBody.contains("bad request")
            }
            else -> false
        }
    }

    /** 回退到阻塞模式调用 */
    private suspend fun fallbackToBlockingMode(
        bitmap: Bitmap?,
        config: ProviderConfig,
        userMessage: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        apiLock.lock()
        val startTime = System.currentTimeMillis()
        try {
            val requestBody = buildConversationBody(config.modelName, userMessage, bitmap)
            val response = callApi(config, requestBody)
            val result = parseResponse(response)

            if (result != null) {
                // 阻塞模式没有流式输出，直接调用回调
                onToken(result)
                onComplete(result)
            } else {
                onError("阻塞模式也未返回有效回复")
            }

            val latency = System.currentTimeMillis() - startTime
            val promptTokens = UsageTracker.estimateTokens(userMessage) + 200
            val completionTokens = UsageTracker.estimateTokens(result ?: "")
            UsageTracker.record(config.provider.name, config.modelName, "conversation", promptTokens, completionTokens, latency)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "回退到阻塞模式失败: ${e.message}")
            onError("调用失败: ${e.message}")
            UsageTracker.record(config.provider.name, config.modelName, "conversation", 0, 0, 0, false)
        } finally {
            apiLock.unlock()
        }
    }

    /** 流式调用的可取消句柄 */
    class StreamingCall {
        var scope: CoroutineScope? = null
        var responseRef: Response? = null
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

    /** 构建 OCR 识别请求体 */
    private fun buildOcrRequestBody(model: String, systemPrompt: String, base64Image: String): String {
        val messages = JSONArray()

        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        val userContent = JSONArray()
        userContent.put(JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", "data:image/jpeg;base64,$base64Image")
                put("detail", "auto")
            })
        })
        userContent.put(JSONObject().apply {
            put("type", "text")
            put("text", "请识别这张图片的内容")
        })

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userContent)
        })

        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", 500)
            put("temperature", 0.3)
        }.toString()
    }

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
你是一个智能语音助手，名叫"小吉"。用户正在和你进行语音对话。

说话要求：
1. 用户问什么就回答什么，自然友好
2. 回复简洁，控制在 2-4 句话
3. 如果用户提到游戏相关问题，结合屏幕画面分析给出建议
4. 语气轻松友好，像朋友聊天一样自然
5. 不要强行关联到特定游戏，用户问什么都正常回答

你的能力：
- 能看到用户手机屏幕的画面（如果有）
- 可以分析屏幕内容回答用户的问题
- 如果用户问的是游戏问题，可以给出建议
- 如果用户问其他问题，也正常回答
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
你是一个智能语音助手，名叫"小吉"。用户正在和你进行语音对话。

说话要求：
1. 用户问什么就回答什么，自然友好
2. 回复简洁，控制在 2-4 句话
3. 如果用户提到游戏相关问题，结合屏幕画面分析给出建议
4. 语气轻松友好，像朋友聊天一样自然
5. 不要强行关联到特定游戏，用户问什么都正常回答

你的能力：
- 能看到用户手机屏幕的画面（如果有）
- 可以分析屏幕内容回答用户的问题
- 如果用户问的是游戏问题，可以给出建议
- 如果用户问其他问题，也正常回答
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
                val contentObj = message.opt("content")
                val content = if (contentObj is String) contentObj.trim() else ""
                content.ifBlank { null }
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

    /** 从 API 错误响应中提取错误信息 */
    private fun extractErrorMessage(errorBody: String): String {
        return try {
            val json = JSONObject(errorBody)
            json.optString("error", json.optString("message", errorBody.take(100)))
        } catch (_: Exception) {
            errorBody.take(100)
        }
    }

    /** 标准化 API 地址：去掉尾部斜杠，智能处理版本路径 */
    private fun normalizeApiUrl(url: String): String {
        val trimmed = url.trimEnd('/')
        // 如果 URL 已经包含版本路径段 (如 /v1, /v4, /v1beta 等)，直接使用
        if (trimmed.matches(Regex(".*/v\\d+.*$")) || trimmed.endsWith("/v1beta")) {
            return trimmed
        }
        // 否则默认追加 OpenAI 兼容的 /v1 路径
        return "$trimmed/v1"
    }
}
