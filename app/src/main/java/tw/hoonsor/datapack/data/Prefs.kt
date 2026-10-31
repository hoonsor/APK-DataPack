package tw.hoonsor.datapack.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 一般設定與同步狀態（SharedPreferences "settings"） */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun cursor(table: String): String? = p.getString("cursor_$table", null)
    fun setCursor(table: String, v: String) = p.edit().putString("cursor_$table", v).apply()

    var lastSyncAt: Long
        get() = p.getLong("lastSyncAt", 0L)
        set(v) = p.edit().putLong("lastSyncAt", v).apply()
    var lastSyncError: String?
        get() = p.getString("lastSyncError", null)
        set(v) = p.edit().putString("lastSyncError", v).apply()

    // ---- AI（OpenAI 相容 Chat Completions） ----
    var llmBaseUrl: String
        get() = p.getString("llmBaseUrl", DEFAULT_LLM_BASE) ?: DEFAULT_LLM_BASE
        set(v) = p.edit().putString("llmBaseUrl", v.trim()).apply()
    var llmModel: String
        get() = p.getString("llmModel", DEFAULT_LLM_MODEL) ?: DEFAULT_LLM_MODEL
        set(v) = p.edit().putString("llmModel", v.trim()).apply()
    var groqBaseUrl: String
        get() = p.getString("groqBaseUrl", DEFAULT_GROQ_BASE) ?: DEFAULT_GROQ_BASE
        set(v) = p.edit().putString("groqBaseUrl", v.trim()).apply()
    var groqModel: String
        get() = p.getString("groqModel", DEFAULT_GROQ_MODEL) ?: DEFAULT_GROQ_MODEL
        set(v) = p.edit().putString("groqModel", v.trim()).apply()

    /** 人工改類型的紀錄，給下次分類參考（F08） */
    var corrections: List<String>
        get() = runCatching { JSONArray(p.getString("corrections", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        set(v) = p.edit().putString("corrections", JSONArray(v.takeLast(30)).toString()).apply()

    /** 最近一次查詢的來源，「看原圖」用 */
    var lastSourceId: String?
        get() = p.getString("lastSourceId", null)
        set(v) = p.edit().putString("lastSourceId", v).apply()
    var lastChunkId: String?
        get() = p.getString("lastChunkId", null)
        set(v) = p.edit().putString("lastChunkId", v).apply()

    var recentQueries: List<String>
        get() = runCatching { JSONArray(p.getString("recentQueries", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        set(v) = p.edit().putString("recentQueries", JSONArray(v.take(8)).toString()).apply()

    var debugLog: Boolean
        get() = p.getBoolean("debugLog", false)
        set(v) = p.edit().putBoolean("debugLog", v).apply()

    fun clearSync() {
        p.edit().apply {
            p.all.keys.filter { it.startsWith("cursor_") }.forEach { remove(it) }
            remove("lastSyncAt"); remove("lastSyncError")
        }.apply()
    }

    companion object {
        const val DEFAULT_LLM_BASE = "https://api.minimax.io/v1"
        const val DEFAULT_LLM_MODEL = "MiniMax-M3"
        const val DEFAULT_GROQ_BASE = "https://api.groq.com/openai/v1"
        const val DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"
    }
}

/** Android Keystore AES-GCM 加密的小型金庫（登入權杖、AI 金鑰），不進系統備份 */
class KeyVault(context: Context, name: String = "vault") {
    private val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun get(name: String): String? = p.getString(name, null)?.let { decrypt(it) }

    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) p.edit().remove(name).apply()
        else p.edit().putString(name, encrypt(value)).apply()
    }

    fun clear() = p.edit().clear().apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(c.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val ALIAS = "datapack_vault"
    }
}
