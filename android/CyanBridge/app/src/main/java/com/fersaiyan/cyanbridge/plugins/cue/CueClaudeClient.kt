package com.fersaiyan.cyanbridge.plugins.cue

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Model ids Cue calls. Exact strings; these carry no date suffix. */
object CueModelIds {
    /** Context and vision. Used for the photo path, where reading a chart is the whole test. */
    const val SONNET = "claude-sonnet-5"

    /** The cheap path: roll call name mapping and roster briefings. */
    const val HAIKU = "claude-haiku-4-5"
}

/** The model declined the request. Distinct from a transport failure — retrying will not help. */
class CueRefusalException(val category: String?, message: String) : Exception(message)

data class CueClaudeRequest(
    val model: String,
    val system: String,
    val userText: String,
    /** Base64 image bytes, no newlines. Sent before the text block, which is where vision wants it. */
    val imageBase64: String? = null,
    val imageMediaType: String = "image/jpeg",
    /**
     * Deliberately small. Every answer Cue speaks is under fifteen words, and a large ceiling here
     * only buys the model room to write a paragraph the user will have to sit through.
     */
    val maxTokens: Int = 256,
    /** `low` | `medium` | `high` | `xhigh` | `max`. Omitted on models that do not accept it. */
    val effort: String? = null,
    /**
     * Turns thinking off. Every Cue call is latency critical and none of them need reasoning depth,
     * so the fast paths disable it rather than paying for adaptive thinking on a four-word answer.
     */
    val disableThinking: Boolean = false,
)

/**
 * Talks to the Anthropic Messages API directly.
 *
 * The upstream fork routes AI through a public relay with someone else's subscription billing
 * attached. That is not something to ship, so Cue holds its own key and calls the API itself.
 *
 * Raw HTTP over the OkHttp dependency this app already has, rather than the Anthropic Java SDK:
 * every other network path in this codebase is hand-rolled OkHttp, and pulling a JVM SDK plus
 * Jackson into the APK for four small request shapes is not a trade worth making here.
 */
class CueClaudeClient(
    private val apiKeyProvider: () -> String,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    suspend fun ask(request: CueClaudeRequest): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No Anthropic API key configured"))
        }

        val payload = runCatching { buildPayload(request) }.getOrElse {
            return@withContext Result.failure(it)
        }

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        runCatching {
            client.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty().ifBlank { "HTTP ${response.code}" }
                    error(detail)
                }
                parseText(body)
            }
        }.onFailure { Log.w(TAG, "Claude call failed: ${it.message}") }
    }

    private fun buildPayload(request: CueClaudeRequest): JSONObject {
        val content = JSONArray()
        request.imageBase64?.let { base64 ->
            content.put(
                JSONObject().apply {
                    put("type", "image")
                    put(
                        "source",
                        JSONObject().apply {
                            put("type", "base64")
                            put("media_type", request.imageMediaType)
                            put("data", base64)
                        },
                    )
                },
            )
        }
        content.put(JSONObject().apply { put("type", "text"); put("text", request.userText) })

        return JSONObject().apply {
            put("model", request.model)
            put("max_tokens", request.maxTokens)
            put("system", request.system)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", content)
                    },
                ),
            )
            if (request.disableThinking) {
                put("thinking", JSONObject().apply { put("type", "disabled") })
            }
            request.effort?.let { level ->
                put("output_config", JSONObject().apply { put("effort", level) })
            }
        }
    }

    /**
     * Reads the reply.
     *
     * `stop_reason` is checked before `content` is touched: a declined request returns a perfectly
     * successful HTTP 200 with an empty content array, and indexing into it would crash rather than
     * degrade.
     */
    private fun parseText(body: String): String {
        val json = JSONObject(body)
        if (json.optString("stop_reason") == "refusal") {
            val category = json.optJSONObject("stop_details")?.optString("category")
            throw CueRefusalException(category, "Model declined this request")
        }
        val content = json.optJSONArray("content") ?: JSONArray()
        val text = buildString {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                if (block.optString("type") != "text") continue
                append(block.optString("text"))
            }
        }.trim()
        if (text.isEmpty()) error("Model returned no text")
        return text
    }

    companion object {
        private const val TAG = "CueClaude"
        private const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
