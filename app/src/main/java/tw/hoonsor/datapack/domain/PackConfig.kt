package tw.hoonsor.datapack.domain

import org.json.JSONArray
import org.json.JSONObject

/** 欄位型別。新增資料包只改設定；新增型別才要改程式 */
object FieldType {
    const val TEXT = "text"
    const val NUMBER = "number"
    const val DATE = "date"
    const val PHONE = "phone"
    const val EMAIL = "email"
    const val TAGS = "tags"
    const val MONEY = "money"
    const val ROWS = "rows"
    val all = listOf(TEXT, NUMBER, DATE, PHONE, EMAIL, TAGS, MONEY, ROWS)
    fun label(t: String) = when (t) {
        NUMBER -> "數字"; DATE -> "日期"; PHONE -> "電話"; EMAIL -> "Email"
        TAGS -> "標籤"; MONEY -> "金額"; ROWS -> "多列表格"; else -> "文字"
    }
}

data class FieldDef(
    val key: String,
    val label: String,
    val type: String = FieldType.TEXT,
    val required: Boolean = false,
    val searchable: Boolean = false,
    val repeat: Boolean = false,
    val extract: String = "",
    val computed: String = "",
    val columns: List<FieldDef> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("label", label).put("type", type).apply {
        if (required) put("required", true)
        if (searchable) put("searchable", true)
        if (repeat) put("repeat", true)
        if (extract.isNotBlank()) put("extract", extract)
        if (computed.isNotBlank()) put("computed", computed)
        if (columns.isNotEmpty()) put("columns", JSONArray(columns.map { it.toJson() }))
    }

    companion object {
        fun from(j: JSONObject): FieldDef = FieldDef(
            key = j.getString("key"),
            label = j.optString("label", j.getString("key")),
            type = j.optString("type", FieldType.TEXT),
            required = j.optBoolean("required"),
            searchable = j.optBoolean("searchable"),
            repeat = j.optBoolean("repeat"),
            extract = j.optString("extract"),
            computed = j.optString("computed"),
            columns = j.optJSONArray("columns")?.objects()?.map { from(it) }.orEmpty(),
        )
    }
}

data class ReviewRule(val mode: String = LOW_CONFIDENCE, val threshold: Double = 0.8, val mustCheck: List<String> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("mode", mode).put("threshold", threshold).put("must_check", JSONArray(mustCheck))

    companion object {
        const val LOW_CONFIDENCE = "low_confidence"
        const val ALWAYS = "always"
        fun from(j: JSONObject?): ReviewRule = if (j == null) ReviewRule() else ReviewRule(
            mode = j.optString("mode", LOW_CONFIDENCE),
            threshold = j.optDouble("threshold", 0.8),
            mustCheck = j.optJSONArray("must_check")?.strings().orEmpty(),
        )
    }
}

/** 一個資料包的完整設定；存在 jv_packs.config */
data class PackConfig(
    val key: String,
    val name: String,
    val shape: String,
    val hint: String = "",
    val examples: List<String> = emptyList(),
    val fields: List<FieldDef> = emptyList(),
    val dedupe: List<List<String>> = emptyList(),
    val review: ReviewRule = ReviewRule(),
    val speechRow: String = "",
    val speechLimit: Int = 5,
    val actions: List<String> = emptyList(),
    /** 一次掃描多頁時，每頁各成一筆（名片） */
    val splitPages: Boolean = false,
) {
    val isTable get() = shape == SHAPE_TABLE || shape == SHAPE_TABLE_DOC
    val isDocument get() = shape == SHAPE_DOCUMENT || shape == SHAPE_TABLE_DOC
    val isFact get() = shape == SHAPE_FACT
    val shapeLabel get() = when (shape) {
        SHAPE_FACT -> "事實"; SHAPE_DOCUMENT -> "文件"; SHAPE_TABLE_DOC -> "表格＋文件"; else -> "資料表"
    }

    fun field(key: String): FieldDef? = fields.firstOrNull { it.key == key }

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("name", name).put("shape", shape)
        .put("classify", JSONObject().put("hint", hint).put("examples", JSONArray(examples)))
        .put("fields", JSONArray(fields.map { it.toJson() }))
        .put("dedupe", JSONArray(dedupe.map { JSONArray(it) }))
        .put("review", review.toJson())
        .put("speech", JSONObject().put("row", speechRow).put("limit", speechLimit))
        .put("actions", JSONArray(actions))
        .put("split_pages", splitPages)

    /** 設定有問題就回傳原因（給編輯畫面與 AI 草稿用） */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        if (!Regex("^[a-z][a-z0-9_]{1,40}$").matches(key)) out += "代號只能用小寫英文、數字與底線"
        if (name.isBlank()) out += "要有名稱"
        if (shape !in SHAPES) out += "形式不正確"
        if (fields.isEmpty() && shape != SHAPE_DOCUMENT) out += "至少要有一個欄位"
        val keys = fields.map { it.key }
        if (keys.toSet().size != keys.size) out += "欄位代號重複"
        fields.filter { it.type !in FieldType.all }.forEach { out += "欄位「${it.label}」型別不支援" }
        dedupe.flatten().filter { it !in keys }.forEach { out += "去重依據「$it」不是欄位" }
        review.mustCheck.filter { it !in keys }.forEach { out += "必看欄位「$it」不是欄位" }
        return out.distinct()
    }

    companion object {
        const val SHAPE_FACT = "fact"
        const val SHAPE_TABLE = "table"
        const val SHAPE_DOCUMENT = "document"
        const val SHAPE_TABLE_DOC = "table+document"
        val SHAPES = listOf(SHAPE_FACT, SHAPE_TABLE, SHAPE_DOCUMENT, SHAPE_TABLE_DOC)

        fun parse(j: JSONObject): PackConfig {
            val classify = j.optJSONObject("classify")
            val speech = j.optJSONObject("speech")
            return PackConfig(
                key = j.getString("key"),
                name = j.getString("name"),
                shape = j.getString("shape"),
                hint = classify?.optString("hint").orEmpty(),
                examples = classify?.optJSONArray("examples")?.strings().orEmpty(),
                fields = j.optJSONArray("fields")?.objects()?.map { FieldDef.from(it) }.orEmpty(),
                dedupe = j.optJSONArray("dedupe")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it)?.strings() } }.orEmpty(),
                review = ReviewRule.from(j.optJSONObject("review")),
                speechRow = speech?.optString("row").orEmpty(),
                speechLimit = speech?.optInt("limit", 5) ?: 5,
                actions = j.optJSONArray("actions")?.strings().orEmpty(),
                splitPages = j.optBoolean("split_pages"),
            )
        }
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { i -> opt(i)?.takeIf { it != JSONObject.NULL }?.toString() }
