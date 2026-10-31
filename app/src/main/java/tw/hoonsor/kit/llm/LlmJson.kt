package tw.hoonsor.kit.llm

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * LLM 回覆 → JSON（純 Kotlin）。做法參考 hoonsor-kit 的 llm-extract：
 * 去 <think>、去程式碼圍欄、抓最外層 {…}／[…]，解析失敗時修多逗號與截斷。預定回收成模組。
 */
object LlmJson {
    private val THINK = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("```[a-zA-Z]*")
    private val TRAILING_COMMA = Regex(",\\s*([}\\]])")

    fun stripThink(s: String): String = THINK.replace(s, "").replace(Regex("^[\\s\\S]*</think>"), "").trim()

    /** 解析物件；修不好就丟 [JSONException] */
    fun parseObject(raw: String): JSONObject {
        val text = FENCE.replace(stripThink(raw), "").trim()
        val start = text.indexOf('{')
        if (start < 0) throw JSONException("回覆裡沒有 JSON：${text.take(80)}")
        val body = text.substring(start)
        val end = body.lastIndexOf('}')
        if (end >= 0) runCatching { return JSONObject(body.substring(0, end + 1)) }
        val fixed = repair(body)
        return JSONObject(fixed)
    }

    fun parseArray(raw: String): JSONArray {
        val text = FENCE.replace(stripThink(raw), "").trim()
        val start = text.indexOf('[')
        if (start < 0) throw JSONException("回覆裡沒有 JSON 陣列")
        val body = text.substring(start)
        val end = body.lastIndexOf(']')
        if (end >= 0) runCatching { return JSONArray(body.substring(0, end + 1)) }
        return JSONArray(repair(body))
    }

    /** 去多餘逗號，並依括號堆疊補齊被截斷的結尾 */
    fun repair(body: String): String {
        val cleaned = TRAILING_COMMA.replace(body) { it.groupValues[1] }
        val stack = ArrayDeque<Char>()
        val out = StringBuilder()
        var inString = false
        var escaped = false
        var lastComplete = 0
        for ((i, c) in cleaned.withIndex()) {
            out.append(c)
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> if (stack.isNotEmpty()) stack.removeLast()
            }
            if (c == ',' || c == '}' || c == ']') lastComplete = i
        }
        if (stack.isEmpty() && !inString) return out.toString()
        // 截斷：退回最後一個完整的位置再補括號
        val cut = StringBuilder(cleaned.substring(0, lastComplete + 1).trimEnd().trimEnd(','))
        val closing = ArrayDeque<Char>()
        var str = false
        var esc = false
        for (c in cut) {
            if (str) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') str = false
                continue
            }
            when (c) {
                '"' -> str = true
                '{' -> closing.addLast('}')
                '[' -> closing.addLast(']')
                '}', ']' -> if (closing.isNotEmpty()) closing.removeLast()
            }
        }
        while (closing.isNotEmpty()) cut.append(closing.removeLast())
        return cut.toString()
    }
}
