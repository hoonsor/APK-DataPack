package tw.hoonsor.datapack.query

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.datapack.Graph
import tw.hoonsor.datapack.R
import tw.hoonsor.datapack.ai.AiException
import tw.hoonsor.datapack.data.ChunkEntity
import tw.hoonsor.datapack.data.ItemEntity
import tw.hoonsor.datapack.domain.Extraction
import tw.hoonsor.datapack.domain.FieldType
import tw.hoonsor.datapack.domain.PackConfig
import tw.hoonsor.datapack.domain.Query
import tw.hoonsor.datapack.domain.QueryPlan
import tw.hoonsor.datapack.domain.Row
import tw.hoonsor.datapack.domain.objects
import tw.hoonsor.datapack.domain.strings
import tw.hoonsor.datapack.log.AppLog
import tw.hoonsor.datapack.ui.MainActivity
import java.time.LocalDate

data class SourceRef(val itemId: String, val sourceId: String?, val chunkId: String?, val label: String)

data class QueryResult(
    val found: Boolean,
    val speech: String,
    val count: Int,
    val rows: List<Row>,
    val sources: List<SourceRef>,
    val usedAi: Boolean,
)

/**
 * 問句 → 答案＋來源。資料表依欄位在本機算（精確、可列全、離線可用）；
 * 文件先用關鍵字找分段，再交 AI 組答案，答案一定要引用得到分段（P7）。
 */
class QueryEngine(private val ctx: Context) {
    private val dao get() = Graph.db.dao()

    suspend fun ask(question: String, notify: Boolean = true): QueryResult {
        val packs = Graph.repo.packConfigs()
        var usedAi = true
        val plan = try {
            Query.parsePlan(Graph.llm.complete(Query.planSystem(packs, LocalDate.now()), question, 400), packs, question)
        } catch (e: Exception) {
            AppLog.warn("query", "查詢規劃改用備援：${e.message}")
            usedAi = false
            Query.fallbackPlan(question, packs)
        }
        val items = dao.allItems()
        val byId = items.associateBy { it.id }
        // 未核對的不進統計與回答（F18）；待確認筆數另外提醒
        val active = items.filter { it.status == ItemEntity.STATUS_ACTIVE }
        val rows = active.map { Row(it.id, it.packKey, it.title, JSONObject(it.fields)) }
        val tablePacks = packs.filter { it.key in plan.packs && (it.isTable || it.isFact) }
        val tablePlan = plan.copy(packs = tablePacks.map { it.key })
        val hit = Query.filter(rows, { byId[it.id]?.searchText.orEmpty() }, tablePlan, LocalDate.now())
            .takeIf { plan.keywords.isNotEmpty() || plan.filters.isNotEmpty() || plan.count || plan.sort.isNotBlank() } ?: emptyList()

        val pending = items.count { it.status == ItemEntity.STATUS_REVIEW && it.packKey in plan.packs }
        val note = if (pending > 0) "另外有 $pending 筆還沒核對，沒有算進去。" else ""

        val docPacks = packs.filter { it.key in plan.packs && it.isDocument }.map { it.key }
        val valueRows = rowsMatching(hit, packs, plan)
        val result = when {
            plan.count && hit.isNotEmpty() -> tableResult(hit, packs, plan, countOnly = true)
            valueRows.isNotEmpty() && usedAi -> answerFromPassages(question, valueRows, hit) ?: tableResult(hit, packs, plan)
            hit.isNotEmpty() && packs.firstOrNull { it.key == hit.first().packKey }?.isDocument != true -> tableResult(hit, packs, plan)
            docPacks.isNotEmpty() -> docResult(question, plan, docPacks, active.map { it.id }.toSet(), usedAi)
            hit.isNotEmpty() -> tableResult(hit, packs, plan)
            else -> null
        } ?: QueryResult(false, "資料包裡查不到。要不要換個說法，或先把資料拍進來？", 0, emptyList(), emptyList(), usedAi)

        val final = result.copy(speech = listOf(result.speech, note).filter { it.isNotBlank() }.joinToString(" "), usedAi = usedAi)
        remember(question, final)
        if (notify && final.sources.isNotEmpty()) notifySource(final)
        return final
    }

    private suspend fun tableResult(hit: List<Row>, packs: List<PackConfig>, plan: QueryPlan, countOnly: Boolean = false): QueryResult {
        val first = packs.firstOrNull { it.key == hit.first().packKey }
        val limit = first?.speechLimit ?: 5
        val spoken = hit.take(limit).map { r -> packs.firstOrNull { it.key == r.packKey }?.let { Query.speak(it, r) } ?: r.title }
        val more = hit.size - spoken.size
        val text = when {
            countOnly -> "共 ${hit.size} 筆。"
            hit.size == 1 -> spoken.first() + "。"
            more > 0 -> "找到 ${hit.size} 筆，前 $limit 筆：${spoken.joinToString("；")}。其他 $more 筆在資料包裡。"
            else -> "找到 ${hit.size} 筆：${spoken.joinToString("；")}。"
        }
        val refs = hit.take(limit).map { r ->
            val s = dao.sourcesOf(r.id).firstOrNull()
            SourceRef(r.id, s?.id, null, r.title)
        }
        val source = refs.firstOrNull()?.label?.let { "來源：$it" + if (hit.size > 1) "等 ${hit.size} 筆" else "" }
        return QueryResult(true, listOfNotNull(text, source?.plus("。")).joinToString(" "), hit.size, hit, refs, false)
    }

    /** 多列欄位（健檢項目）命中的列，組成可引用的文字 */
    private fun rowsMatching(hit: List<Row>, packs: List<PackConfig>, plan: QueryPlan): List<Triple<String, Row, String>> {
        val out = mutableListOf<Triple<String, Row, String>>()
        hit.forEach { r ->
            val p = packs.firstOrNull { it.key == r.packKey } ?: return@forEach
            p.fields.filter { it.type == FieldType.ROWS }.forEach { f ->
                (r.fields.optJSONArray(f.key)?.objects().orEmpty()).forEach { row ->
                    val line = f.columns.joinToString(" ") { c -> row.optString(c.key) }.trim()
                    if (plan.keywords.any { line.contains(it, ignoreCase = true) }) out += Triple(line, r, f.key)
                }
            }
        }
        return out.take(12)
    }

    private suspend fun answerFromPassages(question: String, lines: List<Triple<String, Row, String>>, hit: List<Row>): QueryResult? {
        val passages = lines.mapIndexed { i, (line, row, _) -> (i + 1) to "${row.title}：$line" }
        val a = runCatching {
            Query.parseAnswer(Graph.llm.complete(Query.answerSystem(), Query.answerUser(question, passages), 500), passages.map { it.first }.toSet())
        }.getOrNull() ?: return null
        if (!a.found) return null
        val refs = a.cite.distinct().map { lines[it - 1].second }.distinctBy { it.id }.map { r ->
            SourceRef(r.id, dao.sourcesOf(r.id).firstOrNull()?.id, null, r.title)
        }
        return QueryResult(true, a.text + " 來源：${refs.joinToString("、") { it.label }}。", hit.size, hit, refs, true)
    }

    private suspend fun docResult(question: String, plan: QueryPlan, docPacks: List<String>, activeIds: Set<String>, usedAi: Boolean): QueryResult? {
        val titles = dao.allItems().associateBy { it.id }
        val terms = plan.keywords.ifEmpty { Query.fallbackPlan(question, emptyList()).keywords }
        val scored = dao.allChunks()
            .filter { it.itemId in activeIds && titles[it.itemId]?.packKey in docPacks }
            .map { c -> c to terms.count { c.text.contains(it, ignoreCase = true) || titles[c.itemId]?.title?.contains(it, ignoreCase = true) == true } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ChunkEntity, Int>> { it.second }.thenBy { it.first.pageNo })
            .take(6).map { it.first }
        if (scored.isEmpty()) return null
        if (!usedAi) {
            // AI 連不上：不組答案，直接給最相關的頁
            val c = scored.first()
            val title = titles[c.itemId]?.title.orEmpty()
            return QueryResult(true, "AI 暫時連不上，先給你最相關的地方：$title 第 ${c.pageNo} 頁。", 1, emptyList(), listOf(SourceRef(c.itemId, c.sourceId, c.id, "$title 第 ${c.pageNo} 頁")), false)
        }
        val passages = scored.mapIndexed { i, c -> (i + 1) to "《${titles[c.itemId]?.title}》第 ${c.pageNo} 頁：${c.text}" }
        val a = try {
            Query.parseAnswer(Graph.llm.complete(Query.answerSystem(), Query.answerUser(question, passages), 600), passages.map { it.first }.toSet())
        } catch (e: AiException) {
            AppLog.warn("query", "組答案失敗：${e.message}")
            val c = scored.first()
            val t = titles[c.itemId]?.title.orEmpty()
            return QueryResult(true, "AI 暫時連不上，先給你最相關的地方：$t 第 ${c.pageNo} 頁。", 1, emptyList(), listOf(SourceRef(c.itemId, c.sourceId, c.id, "$t 第 ${c.pageNo} 頁")), false)
        }
        if (!a.found) return null
        val refs = a.cite.distinct().map { scored[it - 1] }.map { c ->
            SourceRef(c.itemId, c.sourceId, c.id, "${titles[c.itemId]?.title} 第 ${c.pageNo} 頁")
        }
        return QueryResult(true, "${a.text} 來源：${refs.joinToString("、") { it.label }}。", refs.size, emptyList(), refs, true)
    }

    private fun remember(question: String, r: QueryResult) {
        val s = Graph.settings
        s.recentQueries = (listOf(JSONObject().put("q", question).put("a", r.speech.take(120)).put("source", r.sources.firstOrNull()?.sourceId ?: "").put("chunk", r.sources.firstOrNull()?.chunkId ?: "").toString()) + s.recentQueries)
        r.sources.firstOrNull()?.let {
            s.lastSourceId = it.sourceId
            s.lastChunkId = it.chunkId
        }
    }

    /** 來源通知由資料包自己發，語音總控的 AI 改寫時漏掉來源也還在（風險表） */
    private fun notifySource(r: QueryResult) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "查詢來源", NotificationManager.IMPORTANCE_DEFAULT))
        val first = r.sources.first()
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_ITEM, first.itemId).putExtra(MainActivity.EXTRA_SOURCE, first.sourceId).putExtra(MainActivity.EXTRA_CHUNK, first.chunkId)
        val pi = PendingIntent.getActivity(ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("看原圖：${first.label}").setContentText(r.speech.take(80)).setStyle(android.app.Notification.BigTextStyle().bigText(r.speech))
            .setContentIntent(pi).setAutoCancel(true).build()
        runCatching { nm.notify(NOTIFY_ID, n) }
    }

    fun toJson(r: QueryResult): JSONObject = JSONObject().put("found", r.found).put("count", r.count)
        .put("rows", JSONArray(r.rows.take(20).map { JSONObject().put("id", it.id).put("title", it.title).put("fields", it.fields) }))
        .put("sources", JSONArray(r.sources.map { JSONObject().put("label", it.label).put("sourceId", it.sourceId ?: "") }))

    companion object {
        private const val CHANNEL = "source"
        private const val NOTIFY_ID = 64
    }
}

/** 名片的第一個可撥電話：手機優先 */
fun phoneOf(fields: JSONObject): String? =
    listOf("mobile", "phone").firstNotNullOfOrNull { k -> fields.optJSONArray(k)?.strings()?.firstOrNull { it.isNotBlank() } }
        ?: Extraction.display(fields.opt("phone")).takeIf { it.isNotBlank() }
