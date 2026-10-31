package tw.hoonsor.datapack.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.datapack.data.KeyVault
import tw.hoonsor.datapack.data.Settings
import tw.hoonsor.datapack.log.AppLog
import tw.hoonsor.kit.llm.LlmJson
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiException(message: String, val network: Boolean = false) : Exception(message)

/** OpenAI 相容 Chat Completions：MiniMax 國際版為主、Groq 備援（AI 只收文字，不收圖片） */
class Llm(private val settings: Settings, private val vault: KeyVault) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    var mainKey: String?
        get() = vault.get("llmKey")
        set(v) = vault.put("llmKey", v)
    var groqKey: String?
        get() = vault.get("groqKey")
        set(v) = vault.put("groqKey", v)
    val configured get() = !mainKey.isNullOrBlank() || !groqKey.isNullOrBlank()

    /** 主力失敗自動換備援；兩個都失敗才丟例外（U14） */
    suspend fun complete(system: String, user: String, maxTokens: Int = 1500): String {
        var last: AiException? = null
        mainKey?.takeIf { it.isNotBlank() }?.let { key ->
            try {
                return chat(settings.llmBaseUrl, key, settings.llmModel, system, user, maxTokens)
            } catch (e: AiException) {
                AppLog.warn("ai", "主力 AI 失敗，改用備援：${e.message}")
                last = e
            }
        }
        groqKey?.takeIf { it.isNotBlank() }?.let { key ->
            try {
                return chat(settings.groqBaseUrl, key, settings.groqModel, system, user, maxTokens)
            } catch (e: AiException) {
                last = e
            }
        }
        throw last ?: AiException("還沒設定 AI 金鑰，請到設定頁填寫")
    }

    suspend fun test(base: String, key: String, model: String): String =
        chat(base, key, model, "只回答 OK", "測試", 20).let { LlmJson.stripThink(it).take(40) }

    private suspend fun chat(base: String, key: String, model: String, system: String, user: String, maxTokens: Int): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("model", model).put("temperature", 0.2).put("max_tokens", maxTokens)
                .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)))
            // MiniMax：推理會吃光 max_tokens，抽取時關掉（llm-extract 已知坑）
            if (base.contains("minimax", ignoreCase = true)) body.put("reasoning_split", true).put("thinking", JSONObject().put("type", "disabled"))
            val req = Request.Builder().url(base.trimEnd('/') + "/chat/completions").header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody(json)).build()
            val (code, text) = try {
                http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
            } catch (e: IOException) {
                throw AiException("連不上 AI（${e.message}）", network = true)
            }
            if (code !in 200..299) throw AiException("AI 回應 $code：${text.take(160)}", network = code == 429 || code >= 500)
            val content = runCatching {
                JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }.getOrNull() ?: throw AiException("AI 回應格式不對：${text.take(160)}")
            if (content.isBlank()) throw AiException("AI 回了空白內容")
            content
        }
}
