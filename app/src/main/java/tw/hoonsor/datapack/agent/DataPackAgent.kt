package tw.hoonsor.datapack.agent

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.agent.AgentRequest
import tw.hoonsor.agent.HoonsorAgent
import tw.hoonsor.agent.HoonsorAgentProvider
import tw.hoonsor.datapack.BuildConfig
import tw.hoonsor.datapack.Graph
import tw.hoonsor.datapack.data.ItemEntity
import tw.hoonsor.datapack.domain.Dates
import tw.hoonsor.datapack.domain.Dedupe
import tw.hoonsor.datapack.query.phoneOf
import tw.hoonsor.datapack.ui.MainActivity

/**
 * 動作清單（describe）。純資料，JVM 測試會跟 docs/agent-manifest.json 比對。
 * 協定與撰寫規則見語音總控的 docs/AGENT_PROTOCOL.md。
 */
object DataPackAgentCatalog {
    const val APP = "賈維斯資料包"
    const val PACKAGE = "tw.hoonsor.datapack"
    const val DESCRIPTION = "倬的個人資料庫：名片、說明書、保固單、健檢報告與記住的事。回答查得到的資料並附來源，不回答一般知識。"

    private fun s(desc: String, enum: List<String>? = null) = JSONObject().put("type", "string").put("description", desc)
        .apply { if (enum != null) put("enum", JSONArray(enum)) }
    private fun obj(vararg p: Pair<String, JSONObject>, required: List<String> = emptyList()) = JSONObject().put("type", "object")
        .put("properties", JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }).put("required", JSONArray(required))

    val actions: List<JSONObject> = listOf(
        HoonsorAgent.action(
            "pack_list", "列出資料包", "列出所有資料包與各自筆數、待確認筆數。", "read", obj(),
            listOf("資料包有哪些", "我的名片有幾張", "資料包現在有多少資料"),
        ),
        HoonsorAgent.action(
            "pack_query", "查資料包",
            "查倬自己存的名片、說明書、保固單、健檢報告與記住的事，回答會附來源。一般知識不要用這個動作。回覆請保留「來源」那句。", "read",
            obj("question" to s("倬的問題，保留原話"), required = listOf("question")),
            listOf("有沒有文具相關的廠商", "除濕機濾網怎麼清", "哪些保固快到期", "上次膽固醇多少", "保單幾號到期", "會計師的電話是多少"),
        ),
        HoonsorAgent.action(
            "pack_source_open", "看原圖", "打開剛剛那個答案的原始照片或頁面，並標出答案所在的位置。", "read", obj(),
            listOf("看原圖", "打開那一頁", "給我看來源"),
        ),
        HoonsorAgent.action(
            "review_list", "查待確認", "唸出有幾筆資料等人工核對。", "read", obj(),
            listOf("有幾筆待確認", "資料包有沒有要核對的", "還有什麼沒確認"),
        ),
        HoonsorAgent.action(
            "capture_open", "開始掃描", "打開掃描畫面，拍名片、說明書、保固單或健檢報告。", "read", obj(),
            listOf("掃名片", "我要掃說明書", "幫我拍這張保固單", "開資料包掃描"),
        ),
        HoonsorAgent.action(
            "contact_call", "打給聯絡人", "從名片找人並開啟撥號畫面，由倬自己按撥出。", "read",
            obj("name" to s("人名或公司名，可不完整"), required = listOf("name")),
            listOf("打給晨光文具", "打給林小姐", "撥電話給會計師"),
        ),
        HoonsorAgent.action(
            "fact_remember", "記住", "記下一件零散事實；同一主題再講一次會更新，舊內容保留。", "reversible",
            obj(
                "topic" to s("主題，短詞，例如保單到期日"),
                "content" to s("要記住的內容，保留倬的原話重點"),
                "due_date" to s("到期日 yyyy-MM-dd，有提到才填"),
                required = listOf("topic", "content"),
            ),
            listOf("記住保單 3 月 15 日到期", "幫我記一下車位是 B2 的 37 號", "記住 Wi-Fi 改成 5G 那組"),
        ),
        HoonsorAgent.action(
            "fact_forget", "忘記", "刪除一件記住的事。一律先確認。", "destructive",
            obj("topic" to s("主題，可不完整"), required = listOf("topic")),
            listOf("忘記保單那件事", "把車位那筆刪掉", "不用記 Wi-Fi 了"),
        ),
        HoonsorAgent.action(
            "item_delete", "刪除資料", "刪除一筆名片、說明書、保固單或健檢報告。一律先確認，10 秒內可在 APP 復原。", "destructive",
            obj("title" to s("資料名稱或人名，可不完整"), required = listOf("title")),
            listOf("刪掉林小姐的名片", "把舊除濕機說明書刪掉", "刪除那張保固單"),
        ),
        HoonsorAgent.action(
            "backup_export", "備份資料包", "打開備份畫面，匯出全部文字資料。", "reversible", obj(),
            listOf("備份資料包", "匯出資料包", "把資料包備份起來"),
        ),
        HoonsorAgent.action(
            "backup_import", "還原資料包", "打開還原畫面，從備份檔匯入。一律先確認。", "destructive", obj(),
            listOf("從備份還原資料包", "匯入資料包備份", "還原資料包"),
        ),
    )

    fun describe(version: String): JSONObject = HoonsorAgent.describe(APP, PACKAGE, version, DESCRIPTION, actions)
}

/** 本 APP 的 Agent API；寫入一律走 Repository */
class AgentProvider : HoonsorAgentProvider() {
    private val dao get() = Graph.db.dao()

    override fun describe(): JSONObject = DataPackAgentCatalog.describe(BuildConfig.VERSION_NAME)
        .put("package", context?.packageName ?: DataPackAgentCatalog.PACKAGE)

    override fun health(): JSONObject {
        val issues = JSONArray()
        if (!Graph.api.signedIn) issues.put("資料包還沒登入")
        if (!Graph.llm.configured) issues.put("資料包還沒設定 AI 金鑰")
        Graph.settings.lastSyncError?.let { issues.put("上次同步失敗：$it") }
        return JSONObject().put("ok", issues.length() == 0).put("issues", issues)
    }

    override fun timeoutMs(action: String): Long = if (action == "pack_query") 30_000L else HoonsorAgent.DEFAULT_TIMEOUT_MS

    private fun openApp(extra: Intent.() -> Unit): String =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply(extra).toUri(Intent.URI_INTENT_SCHEME)

    override fun execute(request: AgentRequest): JSONObject = runBlocking {
        if (!Graph.api.signedIn) return@runBlocking HoonsorAgent.fail(HoonsorAgent.PERMISSION_DENIED, "資料包還沒登入，請先打開 APP 登入")
        val p = request.params
        when (request.action) {
            "pack_list" -> packList()
            "pack_query" -> query(p)
            "pack_source_open" -> sourceOpen()
            "review_list" -> reviewList()
            "capture_open" -> HoonsorAgent.needsUi("好，打開掃描畫面", openApp { putExtra(MainActivity.EXTRA_SCAN, true) })
            "contact_call" -> call(p)
            "fact_remember" -> remember(p, request.dryRun)
            "fact_forget" -> deleteItem(p.optString("topic"), "fact", request)
            "item_delete" -> deleteItem(p.optString("title"), null, request)
            "backup_export" -> HoonsorAgent.needsUi("好，打開備份畫面", openApp { putExtra(MainActivity.EXTRA_BACKUP, true) })
            "backup_import" -> if (request.confirmToken == "restore") HoonsorAgent.needsUi("好，打開還原畫面", openApp { putExtra(MainActivity.EXTRA_BACKUP, true) })
            else HoonsorAgent.needsConfirmation("還原會改動現有資料", "restore")
            else -> HoonsorAgent.fail(HoonsorAgent.NOT_FOUND, "沒有這個動作：${request.action}")
        }
    }

    private suspend fun packList(): JSONObject {
        val items = dao.allItems()
        val packs = Graph.repo.packConfigs().map { c -> c to items.count { it.packKey == c.key } }
        val review = items.count { it.status == ItemEntity.STATUS_REVIEW }
        val text = packs.joinToString("、") { (c, n) -> "${c.name} $n 筆" } + if (review > 0) "，待確認 $review 筆" else ""
        return HoonsorAgent.ok(
            "資料包有$text。",
            JSONObject().put("packs", JSONArray(packs.map { (c, n) -> JSONObject().put("key", c.key).put("name", c.name).put("count", n) })).put("review", review),
        )
    }

    private suspend fun query(p: JSONObject): JSONObject {
        val q = p.optString("question").trim()
        if (q.isEmpty()) return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "要查什麼？")
        val r = Graph.query.ask(q)
        return HoonsorAgent.ok(r.speech, Graph.query.toJson(r))
    }

    private fun sourceOpen(): JSONObject {
        val s = Graph.settings.lastSourceId ?: return HoonsorAgent.fail(HoonsorAgent.NOT_FOUND, "還沒有可以打開的來源，先問一個問題吧")
        return HoonsorAgent.needsUi("打開原圖", openApp {
            putExtra(MainActivity.EXTRA_SOURCE, s)
            putExtra(MainActivity.EXTRA_CHUNK, Graph.settings.lastChunkId)
        })
    }

    private suspend fun reviewList(): JSONObject {
        val list = dao.reviewItems()
        if (list.isEmpty()) return HoonsorAgent.ok("沒有要核對的資料。")
        return HoonsorAgent.ok(
            "有 ${list.size} 筆待確認：${list.take(3).joinToString("、") { it.title }}。",
            JSONObject().put("items", JSONArray(list.take(20).map { JSONObject().put("id", it.id).put("title", it.title) })),
        )
    }

    private suspend fun call(p: JSONObject): JSONObject {
        val name = p.optString("name").trim()
        val cards = dao.itemsIn("business_card").filter { it.searchText.contains(name, ignoreCase = true) }
        if (cards.isEmpty()) return HoonsorAgent.fail(HoonsorAgent.NOT_FOUND, "名片裡找不到$name，要不要換個名字？")
        if (cards.size > 1) return HoonsorAgent.ambiguous("找到 ${cards.size} 張，要打給哪一位？", cards.take(5).map { it.title })
        val phone = phoneOf(JSONObject(cards.first().fields)) ?: return HoonsorAgent.fail(HoonsorAgent.NOT_FOUND, "${cards.first().title}的名片上沒有電話")
        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return HoonsorAgent.needsUi("打開撥號畫面，${cards.first().title}", dial.toUri(Intent.URI_INTENT_SCHEME))
    }

    private suspend fun remember(p: JSONObject, dryRun: Boolean): JSONObject {
        val pack = Graph.repo.packConfig("fact") ?: return HoonsorAgent.fail(HoonsorAgent.INTERNAL, "還沒同步到資料包設定，請先打開 APP")
        val topic = p.optString("topic").trim()
        val content = p.optString("content").trim()
        if (topic.isEmpty() || content.isEmpty()) return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "要記住什麼？")
        val due = Dates.normalize(p.optString("due_date"))
        val fields = JSONObject().put("topic", topic).put("content", content).apply { if (due != null) put("due_date", due) }
        if (dryRun) return HoonsorAgent.ok("會記住：$topic，$content")
        val keys = Dedupe.keys(pack, fields)
        val same = dao.itemsIn("fact").firstOrNull { Dedupe.overlaps(keys, jsonList(it.dedupeKeys)) }
        if (same != null) {
            Graph.repo.updateItem(same, pack, topic, fields, "語音更新")
            return HoonsorAgent.ok("已更新：$topic，$content")
        }
        val item = Graph.repo.addItem(pack, topic, fields, "", ItemEntity.STATUS_ACTIVE, 1.0, emptyList(), 0)
        return HoonsorAgent.ok("記住了：$topic，$content", JSONObject().put("id", item.id), "fact_forget", JSONObject().put("topic", topic))
    }

    private fun jsonList(s: String): List<String> = JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } }

    private suspend fun deleteItem(name: String, pack: String?, req: AgentRequest): JSONObject {
        val list = (if (pack != null) dao.itemsIn(pack) else dao.allItems()).filter { it.title.contains(name, ignoreCase = true) || it.searchText.contains(name, ignoreCase = true) }
        if (list.isEmpty()) return HoonsorAgent.fail(HoonsorAgent.NOT_FOUND, "找不到$name")
        if (list.size > 1) return HoonsorAgent.ambiguous("找到 ${list.size} 筆，要刪哪一筆？", list.take(5).map { it.title })
        val item = list.first()
        if (req.confirmToken != item.id) return HoonsorAgent.needsConfirmation("要刪掉「${item.title}」", item.id)
        if (req.dryRun) return HoonsorAgent.ok("會刪掉 ${item.title}")
        Graph.repo.delete(item)
        return HoonsorAgent.ok("已刪掉「${item.title}」。")
    }
}
