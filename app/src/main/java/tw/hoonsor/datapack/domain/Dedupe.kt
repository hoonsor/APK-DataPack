package tw.hoonsor.datapack.domain

import org.json.JSONArray
import org.json.JSONObject

/** 去重（F09）：設定指定用哪些欄位組成比對鍵 */
object Dedupe {
    data class Change(val key: String, val label: String, val old: String, val new: String)

    private fun normalize(f: FieldDef?, v: Any?): List<String> {
        val raw = when (v) {
            is JSONArray -> v.strings()
            null, JSONObject.NULL -> emptyList()
            else -> listOf(v.toString())
        }
        return raw.map { s ->
            if (f?.type == FieldType.PHONE) s.filter { it.isDigit() }
            else s.lowercase().replace(Regex("[\\s\\p{Punct}　，。、（）]"), "")
        }.filter { it.isNotEmpty() }
    }

    /** 每組規則展開成比對鍵，例如 `name=王小明|mobile=0912345678` */
    fun keys(p: PackConfig, fields: JSONObject): List<String> {
        val out = mutableSetOf<String>()
        p.dedupe.forEachIndexed { i, rule ->
            val parts = rule.map { k -> normalize(p.field(k), fields.opt(k)).map { "$k=$it" } }
            if (parts.isEmpty() || parts.any { it.isEmpty() }) return@forEachIndexed
            parts.fold(listOf("")) { acc, vals -> acc.flatMap { a -> vals.map { v -> if (a.isEmpty()) v else "$a|$v" } } }
                .forEach { out += "$i:$it" }
        }
        return out.toList()
    }

    fun overlaps(a: List<String>, b: List<String>): Boolean = a.any { it in b }

    /** 新資料跟舊資料不同的地方；舊的有值、新的沒有不算（新掃描漏抓不該蓋掉舊值） */
    fun changes(p: PackConfig, old: JSONObject, new: JSONObject): List<Change> =
        p.fields.filter { it.type != FieldType.ROWS }.mapNotNull { f ->
            val before = Extraction.display(old.opt(f.key))
            val after = Extraction.display(new.opt(f.key))
            if (after.isBlank() || normalize(f, old.opt(f.key)).toSet() == normalize(f, new.opt(f.key)).toSet()) null
            else Change(f.key, f.label, before, after)
        }

    /** 套用差異：repeat 欄位合併，其他以新值為準 */
    fun merge(p: PackConfig, old: JSONObject, new: JSONObject, accepted: Set<String>): JSONObject {
        val out = JSONObject(old.toString())
        p.fields.filter { it.key in accepted }.forEach { f ->
            val n = new.opt(f.key) ?: return@forEach
            if (f.repeat || f.type == FieldType.TAGS) {
                val merged = (old.optJSONArray(f.key)?.strings().orEmpty() + ((n as? JSONArray)?.strings() ?: listOf(n.toString()))).distinct()
                out.put(f.key, JSONArray(merged))
            } else out.put(f.key, n)
        }
        return out
    }
}
