package tw.hoonsor.datapack.domain

import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.kit.llm.LlmJson
import java.time.LocalDate

data class Filter(val field: String, val op: String, val value: String)

data class QueryPlan(
    val packs: List<String>,
    val keywords: List<String>,
    val filters: List<Filter> = emptyList(),
    val sort: String = "",
    val count: Boolean = false,
)

data class Row(val id: String, val packKey: String, val title: String, val fields: JSONObject)

/** 查詢規劃與資料表篩選。統計與列全一律在這裡算，不交給 AI（P7） */
object Query {
    private val STOP = listOf("請問", "幫我", "我想", "知道", "查一下", "查詢", "有沒有", "有哪些", "哪些", "什麼", "怎麼", "如何", "多少", "幾號", "幾個", "是不是", "的", "嗎", "呢", "嗎？")

    fun planSystem(packs: List<PackConfig>, today: LocalDate): String = buildString {
        appendLine("你把倬的口語問題轉成資料查詢，只輸出 JSON。今天是 $today。")
        appendLine("格式：{\"packs\":[資料包代號],\"keywords\":[檢索詞],\"filters\":[{\"field\":欄位代號,\"op\":\"eq|contains|gte|lte|before_days\",\"value\":值}],\"sort\":\"欄位代號 asc 或 desc，可空\",\"count\":是否只問數量}")
        appendLine("keywords 要放同義詞與上位詞，例如文具 → 文具、辦公用品、事務用品、筆。列全部時 keywords 給空陣列。")
        appendLine("before_days：日期欄位在今天起 N 天內，例如「快到期」用 90。")
        appendLine("資料包：")
        packs.forEach { p ->
            val fields = p.fields.joinToString("、") { f ->
                if (f.type == FieldType.ROWS) "${f.key}（${f.label}：${f.columns.joinToString("、") { it.label }}）" else "${f.key}（${f.label}）"
            }
            appendLine("- ${p.key}（${p.name}，${p.shapeLabel}）：$fields")
        }
    }

    fun parsePlan(raw: String, packs: List<PackConfig>, question: String): QueryPlan {
        val j = LlmJson.parseObject(raw)
        val keys = packs.map { it.key }
        val chosen = j.optJSONArray("packs")?.strings().orEmpty().filter { it in keys }
        return QueryPlan(
            packs = chosen.ifEmpty { keys },
            keywords = j.optJSONArray("keywords")?.strings().orEmpty().map { it.trim() }.filter { it.isNotEmpty() },
            filters = j.optJSONArray("filters")?.objects().orEmpty().mapNotNull { f ->
                val field = f.optString("field")
                val op = f.optString("op")
                val value = f.optString("value")
                if (field.isBlank() || op !in OPS) null else Filter(field, op, value)
            },
            sort = j.optString("sort").takeIf { it != "null" }.orEmpty(),
            count = j.optBoolean("count"),
        )
    }

    private val OPS = setOf("eq", "contains", "gte", "lte", "before_days")

    /** AI 連不上時的備援：把問句切成關鍵字，每個資料包都找 */
    fun fallbackPlan(question: String, packs: List<PackConfig>): QueryPlan {
        var s = question
        STOP.forEach { s = s.replace(it, " ") }
        val words = s.split(Regex("[\\s，。？?！!、：:]+")).map { it.trim() }.filter { it.length >= 2 }
        val q = question
        val match = packs.filter { p -> q.contains(p.name) || (p.key == "business_card" && Regex("廠商|電話|聯絡|公司").containsMatchIn(q)) }
        return QueryPlan(match.ifEmpty { packs }.map { it.key }, words)
    }

    fun matches(row: Row, f: Filter, today: LocalDate): Boolean {
        val raw = row.fields.opt(f.field)
        val text = Extraction.display(raw)
        return when (f.op) {
            "eq" -> text == f.value
            "contains" -> text.contains(f.value, ignoreCase = true)
            "gte", "lte" -> {
                val c = compare(text, f.value) ?: return false
                if (f.op == "gte") c >= 0 else c <= 0
            }
            "before_days" -> {
                val d = Dates.normalize(text)?.let(LocalDate::parse) ?: return false
                val days = f.value.toLongOrNull() ?: return false
                !d.isBefore(today) && !d.isAfter(today.plusDays(days))
            }
            else -> false
        }
    }

    private fun compare(a: String, b: String): Int? {
        val da = Dates.normalize(a); val db = Dates.normalize(b)
        if (da != null && db != null) return da.compareTo(db)
        val na = a.toDoubleOrNull(); val nb = b.toDoubleOrNull()
        if (na != null && nb != null) return na.compareTo(nb)
        return null
    }

    /** 全部列出來算，回傳每筆與總數；keywords 空代表不限 */
    fun filter(rows: List<Row>, searchText: (Row) -> String, plan: QueryPlan, today: LocalDate): List<Row> {
        val hit = rows.filter { r ->
            r.packKey in plan.packs &&
                plan.filters.all { matches(r, it, today) } &&
                (plan.keywords.isEmpty() || searchText(r).let { t -> plan.keywords.any { k -> t.contains(k, ignoreCase = true) } })
        }
        val parts = plan.sort.trim().split(Regex("\\s+"))
        val key = parts.getOrNull(0).orEmpty()
        if (key.isEmpty()) {
            // 命中越多關鍵字的排前面
            return hit.sortedByDescending { r -> searchText(r).let { t -> plan.keywords.count { k -> t.contains(k, ignoreCase = true) } } }
        }
        val asc = parts.getOrNull(1)?.lowercase() != "desc"
        val sorted = hit.sortedBy { Extraction.display(it.fields.opt(key)).let { v -> Dates.normalize(v) ?: v } }
        return if (asc) sorted else sorted.reversed()
    }

    /** 依 speech.row 範本組唸出來的一句；範本以逗號分段，段內欄位是空的就整段拿掉 */
    fun speak(p: PackConfig, row: Row): String {
        if (p.speechRow.isBlank()) return row.title
        val holder = Regex("\\{(\\w+)\\}")
        return p.speechRow.split("，").mapNotNull { seg ->
            val keys = holder.findAll(seg).map { it.groupValues[1] }.toList()
            val values = keys.map { Extraction.display(row.fields.opt(it)).split("、").first() }
            if (keys.isNotEmpty() && values.all { it.isBlank() }) return@mapNotNull null
            holder.replace(seg) { m -> Extraction.display(row.fields.opt(m.groupValues[1])).split("、").first() }
                .removePrefix("的").removeSuffix("的")
        }.joinToString("，").ifBlank { row.title }
    }

    fun answerSystem(): String =
        "你只根據提供的資料回答倬的問題，用一到三句台灣口語繁體中文，會被唸出來。" +
            "資料裡沒有答案就把 found 設成 false，不可用常識補。只輸出 JSON：{\"found\":true或false,\"answer\":\"回答\",\"cite\":[用到的資料編號]}"

    fun answerUser(question: String, passages: List<Pair<Int, String>>): String = buildString {
        appendLine("問題：$question")
        appendLine("資料：")
        passages.forEach { (i, t) -> appendLine("[$i] $t") }
    }

    data class Answer(val found: Boolean, val text: String, val cite: List<Int>)

    fun parseAnswer(raw: String, valid: Set<Int>): Answer {
        val j = LlmJson.parseObject(raw)
        val cite = (j.optJSONArray("cite") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.opt(it)?.toString()?.toIntOrNull() } }.filter { it in valid }
        val text = j.optString("answer").trim()
        // 沒有可用的引用就不算找到：寧可說查無資料也不給來源不明的答案（P7）
        return Answer(j.optBoolean("found") && cite.isNotEmpty() && text.isNotEmpty(), text, cite)
    }
}
