package tw.hoonsor.datapack.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import tw.hoonsor.datapack.Graph
import tw.hoonsor.datapack.capture.Pipeline
import tw.hoonsor.datapack.data.ItemEntity
import tw.hoonsor.datapack.data.JobEntity
import tw.hoonsor.datapack.domain.Dedupe
import tw.hoonsor.datapack.domain.Extraction
import tw.hoonsor.datapack.domain.objects
import tw.hoonsor.datapack.query.QueryResult
import tw.hoonsor.datapack.ui.theme.C
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId

@Composable
fun LoginScreen(done: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Text("賈維斯資料包", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("圖片只留在手機，雲端只存文字。用待辦 APP 同一個 Email 登入。", color = C.muted, fontSize = 15.sp)
        Field(email, { email = it }, "Email")
        if (sent) Field(code, { code = it }, "信裡的驗證碼")
        error?.let { Text(it, color = C.danger, fontSize = 14.sp) }
        PrimaryButton(if (busy) "處理中…" else if (sent) "登入" else "寄驗證碼", Modifier.fillMaxWidth(), enabled = !busy && email.isNotBlank()) {
            busy = true
            error = null
            scope.launch {
                runCatching {
                    if (!sent) { Graph.api.sendOtp(email); sent = true }
                    else {
                        Graph.api.verifyOtp(email, code)
                        if (!Graph.api.isMember()) { Graph.api.signOut(); error("這個帳號不在資料包的白名單") }
                        done()
                    }
                }.onFailure { error = it.message }
                busy = false
            }
        }
    }
}

/** 方案 A：兩欄資料包卡片＋摘要＋待確認＋掃描 */
@Composable
fun PacksScreen(nav: Nav) {
    val dao = Graph.db.dao()
    val packs by dao.packsFlow().collectAsState(emptyList())
    val counts by dao.countsFlow().collectAsState(emptyList())
    val review by dao.reviewFlow().collectAsState(emptyList())
    val jobs by dao.openJobsFlow().collectAsState(emptyList())
    val total = counts.sumOf { it.n }
    val sync = Graph.settings.lastSyncAt.takeIf { it > 0 }?.let { "，同步於 " + timeOf(it) }.orEmpty()
    if (packs.isNotEmpty() && total == 0 && jobs.isEmpty()) {
        EmptyState(nav)
        return
    }
    Column(Modifier.fillMaxSize()) {
        Header("資料包", "$total 筆資料，${review.size} 筆待確認$sync")
        if (jobs.isNotEmpty()) {
            val attention = jobs.count { it.state != JobEntity.STATE_QUEUED }
            Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                OutlineButton(
                    if (attention > 0) "$attention 筆擷取要處理" else "處理中 ${jobs.size} 筆",
                    Modifier.fillMaxWidth(), color = C.warn, border = C.warnLine, bg = C.warnBg, min = 52.dp,
                ) { nav.push(Dest.Jobs) }
            }
        }
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.weight(1f).padding(horizontal = 20.dp),
            contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(packs, key = { it.id }) { p ->
                val n = counts.firstOrNull { it.packKey == p.key }?.n ?: 0
                val cfg = remember(p.config) { Graph.repo.parse(p) }
                Column(
                    Modifier.height(132.dp).clip(R24).background(C.surface).border(1.dp, C.line, R24).clickable { nav.push(Dest.Pack(p.key)) }.padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(p.name, color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(cfg?.shapeLabel.orEmpty(), color = C.muted, fontSize = 12.sp)
                    }
                    Column {
                        Text("$n", color = C.pack(p.key), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(if (p.enabled) "筆" else "已停用", color = C.muted, fontSize = 13.sp)
                    }
                }
            }
            item {
                Column(
                    Modifier.height(132.dp).clip(R24).background(C.panel).border(1.dp, C.lineHi, R24).clickable { nav.push(Dest.PackEdit(null)) }.padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("新增資料包", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("只要加設定，不用發新版", color = C.muted, fontSize = 13.sp)
                }
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("掃描", Modifier.weight(2f), onClick = nav.scan)
            OutlineButton("選檔案", Modifier.weight(1f), min = 56.dp, onClick = nav.pick)
        }
    }
}

@Composable
private fun EmptyState(nav: Nav) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Text("先拍一張名片試試", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("拍了就會自動分類、抽出重點。之後對語音總控說「有沒有文具相關的廠商」就查得到。", color = C.muted, fontSize = 16.sp, lineHeight = 25.sp)
        PrimaryButton("掃描第一份資料", Modifier.fillMaxWidth(), onClick = nav.scan)
        OutlineButton("匯入 PDF 說明書", Modifier.fillMaxWidth(), onClick = nav.pick)
        OutlineButton("從備份還原", Modifier.fillMaxWidth()) { nav.push(Dest.Backup) }
        Text("圖片只留在手機，雲端只存文字。公文與人事資料不會收進來。", color = C.muted, fontSize = 13.sp)
    }
}

/** 方案 B：大搜尋框＋建議詞膠囊＋最近查過 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(nav: Nav) {
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<QueryResult?>(null) }
    var recent by remember { mutableStateOf(Graph.settings.recentQueries) }
    val scope = rememberCoroutineScope()
    val run: (String) -> Unit = { text ->
        if (text.isNotBlank() && !busy) {
            busy = true
            result = null
            scope.launch {
                runCatching { Graph.query.ask(text, notify = false) }.onSuccess { result = it }.onFailure { nav.toast("查詢失敗：${it.message}") }
                recent = Graph.settings.recentQueries
                busy = false
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("想找什麼？", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(q, { q = it }, "有沒有文具相關的廠商", Modifier.weight(1f), onDone = { run(q) })
            OutlineButton("查", color = C.onAccent, border = C.accent, bg = C.accent, min = 52.dp) { run(q) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("快到期的保固", "上次膽固醇", "會計師電話", "保單到期日").forEach { c -> Chip(c) { q = c; run(c) } }
        }
        if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            Text("查詢中…", color = C.muted)
        }
        result?.let { r ->
            Card(border = if (r.found) C.accent else C.line) {
                Text(r.speech, color = C.text, fontSize = 16.sp, lineHeight = 24.sp)
                if (!r.usedAi) Text("AI 沒連上，這是關鍵字結果", color = C.warn, fontSize = 13.sp)
            }
            r.sources.forEach { s ->
                OutlineButton("看原圖：${s.label}", Modifier.fillMaxWidth(), color = C.accentSoft) { nav.push(Dest.Source(s.itemId, s.sourceId, s.chunkId)) }
            }
            if (r.rows.size > r.sources.size) r.rows.drop(r.sources.size).take(30).forEach { row ->
                Card(onClick = { nav.push(Dest.Item(row.id)) }) { Text(row.title, color = C.text, fontSize = 15.sp) }
            }
        }
        if (result == null && !busy && recent.isNotEmpty()) {
            Text("最近查過", color = C.muted, fontSize = 15.sp)
            recent.forEach { raw ->
                val j = remember(raw) { runCatching { JSONObject(raw) }.getOrNull() } ?: return@forEach
                Card(onClick = {
                    val s = j.optString("source")
                    if (s.isNotBlank()) nav.push(Dest.Source(null, s, j.optString("chunk").ifBlank { null })) else { q = j.optString("q"); run(q) }
                }) {
                    Text(j.optString("q"), color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(j.optString("a"), color = C.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 一個資料包的清單：搜尋、分頁、長按多選 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PackItemsScreen(key: String, nav: Nav) {
    val dao = Graph.db.dao()
    val all by dao.itemsFlow(key).collectAsState(emptyList())
    var cfgName by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }
    val selected = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(key) { cfgName = Graph.repo.packConfig(key)?.name ?: key }
    val hits = all.filter { q.isBlank() || it.searchText.contains(q, ignoreCase = true) }
    val pageSize = 50
    val pages = (hits.size + pageSize - 1) / pageSize
    val shown = hits.drop(page * pageSize).take(pageSize)
    Column(Modifier.fillMaxSize()) {
        Header(cfgName, "${hits.size} 筆", back = nav.pop)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().clip(R20).background(C.accentDeep).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("已選 ${selected.size}", color = C.text, fontWeight = FontWeight.Bold)
                    Row {
                        Text("全選", color = C.accentSoft, modifier = Modifier.clickable { selected.clear(); selected.addAll(hits.map { it.id }) }.padding(12.dp))
                        Text("取消", color = C.accentSoft, modifier = Modifier.clickable { selected.clear() }.padding(12.dp))
                    }
                }
            }
            Field(q, { q = it; page = 0 }, "搜尋這個資料包")
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { item ->
                val on = item.id in selected
                Column(
                    Modifier.fillMaxWidth().clip(R20).background(if (on) C.accentDeep else C.surface)
                        .border(1.dp, if (on) C.accent else if (item.status == ItemEntity.STATUS_REVIEW) C.warnLine else C.line, R20)
                        .combinedClickable(
                            onClick = { if (selected.isNotEmpty()) toggle(selected, item.id) else nav.push(Dest.Item(item.id)) },
                            onLongClick = { toggle(selected, item.id) },
                        ).padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.title, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (item.status == ItemEntity.STATUS_REVIEW) Text("待確認", color = C.warn, fontSize = 12.sp)
                    }
                    Text(subtitle(item), color = C.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (pages > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlineButton("上一頁", min = 44.dp) { if (page > 0) page-- }
            Text("第 ${page + 1} / $pages 頁", color = C.muted)
            OutlineButton("下一頁", min = 44.dp) { if (page < pages - 1) page++ }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected.isEmpty()) {
                OutlineButton("匯出 CSV", Modifier.weight(1f)) { scope.launch { shareCsv(ctx, key, cfgName) } }
                OutlineButton("資料包設定", Modifier.weight(1f)) { nav.push(Dest.PackEdit(key)) }
            } else {
                val chosen = all.filter { it.id in selected }
                OutlineButton("分享", Modifier.weight(1f)) { shareItems(ctx, chosen, key) }
                if (key == "business_card") OutlineButton("存聯絡人", Modifier.weight(1f)) { chosen.firstOrNull()?.let { addContact(ctx, JSONObject(it.fields)) } }
                OutlineButton("刪除", Modifier.weight(1f), color = C.danger, border = C.dangerLine, bg = C.dangerBg) {
                    scope.launch {
                        val backup = chosen.map { Triple(it, dao.sourcesOf(it.id), dao.chunksOf(it.id)) }
                        chosen.forEach { Graph.repo.delete(it) }
                        selected.clear()
                        nav.undo(Undo("已刪除 ${backup.size} 筆") { backup.forEach { (i, s, c) -> Graph.repo.undelete(i, s, c) } })
                    }
                }
            }
        }
    }
}

private fun toggle(list: MutableList<String>, id: String) {
    if (id in list) list.remove(id) else list.add(id)
}

private fun subtitle(item: ItemEntity): String {
    val f = runCatching { JSONObject(item.fields) }.getOrDefault(JSONObject())
    val parts = listOf("title", "phone", "warranty_end", "model", "institution", "content").mapNotNull { k -> Extraction.display(f.opt(k)).takeIf { it.isNotBlank() } }
    val pages = if (item.pageCount > 1) "${item.pageCount} 頁" else null
    return (parts + listOfNotNull(pages)).joinToString("　").ifBlank { dateOf(item.capturedAt) }
}

fun dateOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()
fun timeOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

/** 擷取工作：被擋、不知道類型、有新資訊、失敗都在這裡處理 */
@Composable
fun JobsScreen(nav: Nav) {
    val dao = Graph.db.dao()
    val jobs by dao.openJobsFlow().collectAsState(emptyList())
    val packs by dao.packsFlow().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Header("擷取進度", "${jobs.size} 筆", back = nav.pop)
        LazyColumn(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(jobs, key = { it.id }) { job ->
                val warn = job.state != JobEntity.STATE_QUEUED && job.state != JobEntity.STATE_WAIT_NET
                Card(border = if (warn) C.warnLine else C.line) {
                    when (job.state) {
                        JobEntity.STATE_QUEUED -> Text("處理中…", color = C.text)
                        JobEntity.STATE_WAIT_NET -> {
                            Text("等網路或額度，恢復後自動繼續", color = C.text)
                            Text(job.detail, color = C.muted, fontSize = 13.sp)
                        }
                        JobEntity.STATE_BLOCKED -> {
                            Text("已攔下，沒有送到雲端", color = C.warn, fontWeight = FontWeight.Bold)
                            Text(job.detail, color = C.text, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlineButton("丟棄", Modifier.weight(1f)) { scope.launch { Pipeline.discard(job) } }
                                OutlineButton("這不是公務資料，放行", Modifier.weight(1f), color = C.warn, border = C.warnLine) { scope.launch { Pipeline.retry(ctx, job, overrideGuard = true) } }
                            }
                        }
                        JobEntity.STATE_DUPLICATE -> {
                            Text(job.detail, color = C.text)
                            OutlineButton("知道了") { scope.launch { dao.deleteJob(job.id) } }
                        }
                        JobEntity.STATE_MERGE -> MergeCard(job)
                        else -> {
                            Text("沒有完成", color = C.warn, fontWeight = FontWeight.Bold)
                            Text(job.detail, color = C.text, fontSize = 14.sp)
                            Text("選資料包重新處理：", color = C.muted, fontSize = 13.sp)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                packs.filter { it.key != "fact" }.forEach { p -> OutlineButton(p.name, Modifier.fillMaxWidth(), min = 44.dp) { scope.launch { Pipeline.retry(ctx, job, forcedPack = p.key) } } }
                                OutlineButton("丟棄", Modifier.fillMaxWidth(), min = 44.dp, color = C.danger, border = C.dangerLine) { scope.launch { Pipeline.discard(job) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 同一人有新資訊：列差異，勾的才更新，舊版保留（F09） */
@Composable
private fun MergeCard(job: JobEntity) {
    val j = remember(job.detail) { JSONObject(job.detail) }
    val changes = remember(job.detail) { j.getJSONArray("changes").objects() }
    val accepted = remember { mutableStateListOf<String>().apply { addAll(changes.map { it.getString("key") }) } }
    val scope = rememberCoroutineScope()
    Text("「${j.optString("title")}」有新資訊", color = C.warn, fontWeight = FontWeight.Bold)
    changes.forEach { c ->
        val key = c.getString("key")
        val on = key in accepted
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) C.accentDeep else C.panel).clickable { toggle(accepted, key) }.padding(10.dp),
        ) {
            Text(c.getString("label"), color = C.muted, fontSize = 13.sp)
            Text("${c.optString("old").ifBlank { "（空白）" }} → ${c.getString("new")}", color = C.text, fontSize = 14.sp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlineButton("不更新", Modifier.weight(1f)) { scope.launch { Pipeline.discard(job) } }
        OutlineButton("更新勾選的", Modifier.weight(1f), color = C.accentSoft, border = C.accent) {
            scope.launch {
                val item = Graph.db.dao().item(j.getString("itemId")) ?: return@launch
                val pack = Graph.repo.packConfig(item.packKey) ?: return@launch
                val merged = Dedupe.merge(pack, JSONObject(item.fields), j.getJSONObject("fields"), accepted.toSet())
                Graph.repo.updateItem(item, pack, item.title, merged, "重複掃描更新")
                Pipeline.discard(job)
            }
        }
    }
}

@Suppress("unused")
private val keepImport = JSONArray::class
