package com.gameai.utils

import com.gameai.model.ModelProvider
import com.gameai.model.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class ConnectionTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val message: String,
    val availableModels: List<String> = emptyList()
)

class ModelConnectionTester {

    // ModelsFragment 直接用的公开方法
    fun testOpenAICompatible(baseUrl: String, apiKey: String): ConnectionTestResult {
        val start = System.currentTimeMillis()
        return try {
            val url = URL("$baseUrl/models")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 8000
            conn.readTimeout = 10000

            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().readText()
                val latency = System.currentTimeMillis() - start
                val models = parseModelNames(body)
                ConnectionTestResult(true, latency, "连接成功", models)
            } else {
                ConnectionTestResult(false, System.currentTimeMillis() - start,
                    "HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            ConnectionTestResult(false, System.currentTimeMillis() - start,
                e.message ?: "连接失败")
        }
    }

    fun testPCServer(host: String, port: Int): ConnectionTestResult {
        val start = System.currentTimeMillis()
        return try {
            val url = URL("http://$host:$port/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode in 200..299) {
                ConnectionTestResult(true, System.currentTimeMillis() - start, "连接成功")
            } else {
                ConnectionTestResult(false, System.currentTimeMillis() - start,
                    "HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            ConnectionTestResult(false, System.currentTimeMillis() - start,
                e.message ?: "连接超时")
        }
    }

    private fun parseModelNames(json: String): List<String> {
        val models = mutableListOf<String>()
        var remaining = json
        while (true) {
            val idIdx = remaining.indexOf("\"id\"")
            if (idIdx < 0) break
            val colonIdx = remaining.indexOf(':', idIdx)
            if (colonIdx < 0) break
            val q1 = remaining.indexOf('"', colonIdx)
            if (q1 < 0) break
            val q2 = remaining.indexOf('"', q1 + 1)
            if (q2 < 0) break
            val name = remaining.substring(q1 + 1, q2)
            if (name.length > 1 && !name.contains(":")) models.add(name)
            remaining = remaining.substring(q2 + 1)
        }
        return models
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        suspend fun testConnection(config: ProviderConfig, isLocal: Boolean = false): ConnectionTestResult =
            withContext(Dispatchers.IO) {
                val startTime = System.currentTimeMillis()
                try {
                    when {
                        isLocal || config.provider == ModelProvider.CUSTOM || config.provider == ModelProvider.OPENAI ||
                        config.provider == ModelProvider.DEEPSEEK || config.provider == ModelProvider.LOCAL_OLLAMA ||
                        config.provider == ModelProvider.LOCAL_VLLM || config.provider == ModelProvider.LOCAL_LM_STUDIO ->
                            testOpenAICompatStatic(config, startTime)
                        config.provider == ModelProvider.QWEN -> testQwen(config, startTime)
                        config.provider == ModelProvider.ZHIPU -> testZhipu(config, startTime)
                        config.provider == ModelProvider.ERNIE -> testErnie(config, startTime)
                        else -> testOpenAICompatStatic(config, startTime)
                    }
                } catch (e: Exception) {
                    ConnectionTestResult(false, System.currentTimeMillis() - startTime,
                        "连接失败: ${e.javaClass.simpleName}")
                }
            }

        suspend fun testPCServerStatic(host: String, port: Int): ConnectionTestResult =
            withContext(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                try {
                    val req = Request.Builder().url("http://$host:$port/health").get().build()
                    val resp = client.newCall(req).execute()
                    ConnectionTestResult(resp.isSuccessful, System.currentTimeMillis() - start,
                        if (resp.isSuccessful) "连接成功" else "HTTP ${resp.code}")
                } catch (e: Exception) {
                    ConnectionTestResult(false, System.currentTimeMillis() - start,
                        e.message ?: "连接失败")
                }
            }

        private fun testOpenAICompatStatic(config: ProviderConfig, startTime: Long): ConnectionTestResult {
            val url = "${config.baseUrl.trimEnd('/')}/models"
            val req = if (config.apiKey.isNotEmpty())
                Request.Builder().url(url).header("Authorization", "Bearer ${config.apiKey}").get().build()
            else Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            val latency = System.currentTimeMillis() - startTime
            return if (resp.isSuccessful) {
                val body = resp.body?.string() ?: "{}"
                val models = try {
                    val json = JSONObject(body)
                    val arr = json.optJSONArray("data")
                    if (arr != null) (0 until minOf(arr.length(), 5)).map { arr.getJSONObject(it).optString("id") }
                    else emptyList()
                } catch (e: Exception) { emptyList() }
                ConnectionTestResult(true, latency, "连接成功 ${latency}ms", models)
            } else if (resp.code == 401 || resp.code == 403) {
                ConnectionTestResult(false, latency, "API Key 无效")
            } else {
                testChatCompletion(config, startTime)
            }
        }

        private fun testChatCompletion(config: ProviderConfig, startTime: Long): ConnectionTestResult {
            val body = JSONObject().apply {
                put("model", config.modelName)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", "hi") })
                })
                put("max_tokens", 5)
            }
            val req = Request.Builder()
                .url("${config.baseUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer ${config.apiKey}")
                .post(body.toString().toRequestBody(jsonMediaType)).build()
            val resp = client.newCall(req).execute()
            val latency = System.currentTimeMillis() - startTime
            return if (resp.isSuccessful)
                ConnectionTestResult(true, latency, "模型连通 ${latency}ms")
            else ConnectionTestResult(false, latency, "调用失败 HTTP ${resp.code}")
        }

        private fun testQwen(config: ProviderConfig, start: Long) = testChatCompletion(config, start)
        private fun testZhipu(config: ProviderConfig, start: Long) = testChatCompletion(config, start)

        private fun testErnie(config: ProviderConfig, startTime: Long): ConnectionTestResult {
            val url = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/${config.modelName}?access_token=${config.apiKey}"
            val body = JSONObject().apply {
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", "hi") })
                })
            }
            val req = Request.Builder().url(url).post(body.toString().toRequestBody(jsonMediaType)).build()
            val resp = client.newCall(req).execute()
            val latency = System.currentTimeMillis() - startTime
            return if (resp.isSuccessful) ConnectionTestResult(true, latency, "文心一言连通 ${latency}ms")
            else ConnectionTestResult(false, latency, "文心调用失败 HTTP ${resp.code}")
        }
    }
}
