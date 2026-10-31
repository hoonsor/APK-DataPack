package tw.hoonsor.datapack.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.datapack.BuildConfig
import tw.hoonsor.datapack.data.KeyVault
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message) {
    /** 不在 jv_members 白名單（RLS 擋下） */
    val notMember get() = code == 403 || message?.contains("row-level security") == true || message?.contains("42501") == true
    val unauthorized get() = code == 401
    val offline get() = code == 0
}

data class Session(val userId: String, val email: String, val accessToken: String, val refreshToken: String, val expiresAt: Long)

/**
 * Supabase 的登入（Email 驗證碼）與 REST（PostgREST），寫法沿用待辦 APP。
 * 用 publishable key＋使用者的 access token；權限完全由 RLS 決定。
 */
class Supabase(private val vault: KeyVault) {
    val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    val apiKey = BuildConfig.SUPABASE_KEY
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val refreshLock = Mutex()

    val session: Session?
        get() {
            val access = vault.get("access") ?: return null
            return Session(
                userId = vault.get("userId") ?: return null,
                email = vault.get("email").orEmpty(),
                accessToken = access,
                refreshToken = vault.get("refresh") ?: return null,
                expiresAt = vault.get("expiresAt")?.toLongOrNull() ?: 0L,
            )
        }

    val signedIn get() = session != null

    suspend fun sendOtp(email: String) {
        call("POST", "/auth/v1/otp", JSONObject().put("email", email.trim()).put("create_user", false), auth = false)
    }

    suspend fun verifyOtp(email: String, code: String): Session {
        val r = call("POST", "/auth/v1/verify", JSONObject().put("type", "email").put("email", email.trim()).put("token", code.trim()), auth = false)
        return save(JSONObject(r))
    }

    fun signOut() {
        vault.clear()
    }

    private fun save(j: JSONObject): Session {
        val user = j.optJSONObject("user") ?: throw ApiException(500, "登入回應缺少使用者資料")
        val s = Session(
            userId = user.getString("id"),
            email = user.optString("email"),
            accessToken = j.getString("access_token"),
            refreshToken = j.getString("refresh_token"),
            expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000,
        )
        vault.put("userId", s.userId); vault.put("email", s.email); vault.put("access", s.accessToken)
        vault.put("refresh", s.refreshToken); vault.put("expiresAt", s.expiresAt.toString())
        return s
    }

    /** 取得有效的 access token；快過期時用 refresh token 換新 */
    suspend fun accessToken(force: Boolean = false): String = refreshLock.withLock {
        val s = session ?: throw ApiException(401, "尚未登入")
        if (!force && s.expiresAt - System.currentTimeMillis() > 60_000) return s.accessToken
        val r = try {
            call("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", s.refreshToken), auth = false)
        } catch (e: ApiException) {
            if (e.code in 400..499) signOut() // refresh token 失效：要重新登入
            throw e
        }
        save(JSONObject(r)).accessToken
    }

    suspend fun select(table: String, query: String): JSONArray = JSONArray(call("GET", "/rest/v1/$table?$query", null))

    /** 批次新增或覆寫（客戶端產生 id，離線新增不會撞號） */
    suspend fun upsert(table: String, rows: JSONArray) {
        if (rows.length() == 0) return
        call("POST", "/rest/v1/$table?on_conflict=id", rows, prefer = "resolution=merge-duplicates,return=minimal")
    }

    suspend fun isMember(): Boolean = select("jv_members", "select=user_id&limit=1").length() > 0

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private suspend fun call(method: String, path: String, body: Any?, auth: Boolean = true, prefer: String? = null, retried: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val token = if (auth) accessToken() else null
            val req = Request.Builder().url(baseUrl + path)
                .header("apikey", apiKey)
                .header("Authorization", "Bearer ${token ?: apiKey}")
                .apply { if (prefer != null) header("Prefer", prefer) }
                .method(method, body?.toString()?.toRequestBody(json))
                .build()
            val (code, text) = try {
                http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
            } catch (e: IOException) {
                throw ApiException(0, "連不上伺服器（${e.message}）")
            }
            if (code == 401 && auth && !retried) {
                accessToken(force = true)
                return@withContext call(method, path, body, auth, prefer, retried = true)
            }
            if (code !in 200..299) throw ApiException(code, errorText(code, text))
            text
        }

    private fun errorText(code: Int, text: String): String {
        val msg = runCatching {
            val j = JSONObject(text)
            listOf("msg", "message", "error_description", "error").firstNotNullOfOrNull { k -> j.optString(k).takeIf { it.isNotBlank() } }
                ?.let { m -> j.optString("code").takeIf { it.isNotBlank() }?.let { "$m（$it）" } ?: m }
        }.getOrNull() ?: text.take(200)
        return when (code) {
            429 -> "太頻繁了，請稍後再試（$msg）"
            else -> "HTTP $code：$msg"
        }
    }
}
