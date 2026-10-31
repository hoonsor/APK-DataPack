package tw.hoonsor.datapack.domain

import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.kit.llm.LlmJson

data class Classification(val packKey: String, val confidence: Double)

data class Extracted(
    val title: String,
    val fields: JSONObject,
    val confidence: Map<String, Double>,
    val summary: String,
    val overall: Double,
)

/** 分類與抽取的提示詞與解析（純邏輯，有測試）。呼叫 LLM 在 ai/ */
object Extraction {
    const val NONE = "none"
    private const val CLASSIFY_LIMIT = 1800
    private const val DEFAULT_CONFIDENCE = 0.85

    fun classifySystem(packs: List<PackConfig>, corrections: List<String>): String = buildString {
        appendLine("你是文件分類器。依文字判斷屬於哪一個資料包，只輸出 JSON：{\"pack\":\"代號\",\"confidence\":0到1}。")
        appendLine("都不符合時 pack 填 \"$NONE\"。文字來自 OCR，可能有錯字。")
        appendLine("資料包：")
        packs.filter { !it.isFact }.forEach { p ->
            appendLine("- ${p.key}（${p.name}）：${p.hint}；例：${p.examples.joinToString("／")}")
        }
        if (corrections.isNotEmpty()) {
            appendLine("之前的人工更正（類似內容照這樣分）：")
            corrections.takeLast(10).forEach { appendLine("- $it") }
        }
    }

    fun classifyUser(ocr: String) = "文字：\n" + ocr.take(CLASSIFY_LIMIT)

    fun parseClassification(raw: String, packs: List<PackConfig>): Classification {
        val j = LlmJson.parseObject(raw)
        val key = j.optString("pack", NONE)
        val conf = j.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
        return if (packs.any { it.key == key }) Classification(key, conf) else Classification(NONE, conf)
    }

    private fun describe(f: FieldDef): String = buildString {
        append("\"${f.key}\"（${f.label}，${FieldType.label(f.type)}")
        if (f.repeat) append("，可多個，輸出陣列")
        if (f.type == FieldType.TAGS) append("，輸出陣列")
        if (f.required) append("，必填")
        append("）")
        if (f.extract.isNotBlank()) append("：${f.extract}")
        if (f.type == FieldType.ROWS) {
            append("。輸出物件陣列，每列欄位：")
            append(f.columns.joinToString("、") { c -> c.key + "（" + c.label + (if (c.extract.isNotBlank()) "，" + c.extract else "") + "）" })
        }
    }

    fun extractSystem(p: PackConfig): String = buildString {
        appendLine("你從 OCR 文字抽取「${p.name}」的欄位，只輸出 JSON，不要說明。")
        appendLine("規則：只抄文字裡真的有的內容，不可推測或補完；沒有就給空字串或空陣列。OCR 看起來有錯字時保留原文，並把該欄信心調低。繁體中文。")
        appendLine("欄位：")
        p.fields.forEach { appendLine("- ${describe(it)}") }
        appendLine("輸出格式：{\"title\":\"這筆資料的短標題\",\"fields\":{欄位代號:值},\"confidence\":{欄位代號:0到1},\"summary\":\"${if (p.isDocument) "3 到 8 句重點" else ""}\"}")
    }

    fun parseExtraction(raw: String, p: PackConfig): Extracted {
        val j = LlmJson.parseObject(raw)
        val src = j.optJSONObject("fields") ?: JSONObject()
        val out = JSONObject()
        val conf = mutableMapOf<String, Double>()
        val confJson = j.optJSONObject("confidence")
        p.fields.forEach { f ->
            normalizeField(f, src.opt(f.key))?.let { out.put(f.key, it) }
            // 模型沒給信心時視為普通可信，真的不確定它會自己標低
            conf[f.key] = confJson?.optDouble(f.key, DEFAULT_CONFIDENCE)?.takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0) ?: DEFAULT_CONFIDENCE
        }
        applyComputed(p, out)
        val filled = p.fields.filter { !isEmpty(out.opt(it.key)) }
        val overall = if (filled.isEmpty()) 0.0 else filled.minOf { conf[it.key] ?: DEFAULT_CONFIDENCE }
        val title = j.optString("title").trim().ifBlank { titleFrom(p, out) }.take(300)
        return Extracted(title, out, conf, j.optString("summary").trim(), overall)
    }

    /** 依欄位型別整理：repeat／tags 一律陣列、日期轉 ISO、電話留原文 */
    fun normalizeField(f: FieldDef, v: Any?): Any? {
        if (v == null || v == JSONObject.NULL) return null
        if (f.type == FieldType.ROWS) {
            val a = v as? JSONArray ?: return null
            val rows = JSONArray()
            a.objects().forEach { row ->
                val r = JSONObject()
                f.columns.forEach { c -> row.opt(c.key)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { r.put(c.key, it) } }
                if (r.length() > 0) rows.put(r)
            }
            return rows.takeIf { it.length() > 0 }
        }
        if (f.repeat || f.type == FieldType.TAGS) {
            val items = when (v) {
                is JSONArray -> v.strings()
                else -> v.toString().split(Regex("[,，、;；/\\n]"))
            }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            return if (items.isEmpty()) null else JSONArray(items)
        }
        val s = (if (v is JSONArray) v.strings().joinToString("、") else v.toString()).trim()
        if (s.isEmpty()) return null
        return if (f.type == FieldType.DATE) Dates.normalize(s) ?: s else s
    }

    fun applyComputed(p: PackConfig, fields: JSONObject) {
        p.fields.filter { it.computed == "warranty_end" }.forEach { f ->
            Dates.warrantyEnd(fields.optString(f.key), fields.optString("purchase_date"), fields.optString("warranty_period"))
                ?.let { fields.put(f.key, it) }
        }
    }

    fun isEmpty(v: Any?): Boolean = v == null || v == JSONObject.NULL || v.toString().isBlank() || (v is JSONArray && v.length() == 0)

    fun titleFrom(p: PackConfig, fields: JSONObject): String =
        p.fields.filter { it.type != FieldType.ROWS }.take(3).mapNotNull { f -> display(fields.opt(f.key)).takeIf { it.isNotBlank() } }
            .joinToString("｜").ifBlank { p.name }

    fun display(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is JSONArray -> v.strings().joinToString("、")
        else -> v.toString()
    }

    /** 判斷要不要進待確認，回傳原因（空的就直接入庫） */
    fun reviewReasons(p: PackConfig, e: Extracted, classifyConfidence: Double): List<String> {
        val r = mutableListOf<String>()
        if (p.review.mode == ReviewRule.ALWAYS) r += "這類資料每份都要核對"
        if (classifyConfidence < p.review.threshold.coerceAtMost(0.8)) r += "分類不確定"
        p.fields.filter { it.required && isEmpty(e.fields.opt(it.key)) }.forEach { r += "「${it.label}」沒抓到" }
        p.fields.forEach { f ->
            val c = e.confidence[f.key] ?: 1.0
            val checked = f.key in p.review.mustCheck || !isEmpty(e.fields.opt(f.key))
            if (checked && !isEmpty(e.fields.opt(f.key)) && c < p.review.threshold) r += "「${f.label}」可能有誤"
        }
        return r.distinct()
    }

    fun searchText(p: PackConfig, title: String, fields: JSONObject, summary: String): String = buildList {
        add(title)
        p.fields.forEach { f ->
            if (f.type == FieldType.ROWS) (fields.optJSONArray(f.key)?.objects().orEmpty()).forEach { row ->
                f.columns.forEach { c -> add(row.optString(c.key)) }
            } else add(display(fields.opt(f.key)))
        }
        add(summary)
    }.filter { it.isNotBlank() }.joinToString(" ")
}
