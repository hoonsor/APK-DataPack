package tw.hoonsor.datapack.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.launch
import org.json.JSONObject
import tw.hoonsor.datapack.BuildConfig
import tw.hoonsor.datapack.Graph
import tw.hoonsor.datapack.backup.Backup
import tw.hoonsor.datapack.data.PackEntity
import tw.hoonsor.datapack.domain.FieldDef
import tw.hoonsor.datapack.domain.FieldType
import tw.hoonsor.datapack.domain.PackConfig
import tw.hoonsor.datapack.domain.ReviewRule
import tw.hoonsor.datapack.log.AppLog
import tw.hoonsor.datapack.sync.SyncStatus
import tw.hoonsor.datapack.ui.theme.C
import tw.hoonsor.datapack.update.UpdateCard
import tw.hoonsor.kit.llm.LlmJson

@Composable
fun SettingsScreen(nav: Nav) {
    val s = Graph.settings
    val llm = Graph.llm
    var mainKey by remember { mutableStateOf(llm.mainKey.orEmpty()) }
    var groqKey by remember { mutableStateOf(llm.groqKey.orEmpty()) }
    var base by remember { mutableStateOf(s.llmBaseUrl) }
    var model by remember { mutableStateOf(s.llmModel) }
    var groqModel by remember { mutableStateOf(s.groqModel) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun show(vararg words: String) = q.isBlank() || words.any { it.contains(q, ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("設定", color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp))
        Text("v${BuildConfig.VERSION_NAME}　${Graph.api.session?.email.orEmpty()}", color = C.muted, fontSize = 14.sp)
        Field(q, { q = it }, "搜尋設定")
        if (show("AI", "金鑰", "MiniMax", "Groq", "模型")) {
            Text("AI（只送文字，不送圖片）", color = C.muted, fontSize = 14.sp)
            Field(mainKey, { mainKey = it }, "MiniMax 金鑰", secret = true)
            Field(base, { base = it }, "網址")
            Field(model, { model = it }, "模型 ID")
            Field(groqKey, { groqKey = it }, "Groq 金鑰（備援）", secret = true)
            Field(groqModel, { groqModel = it }, "Groq 模型 ID")
            testResult?.let { Text(it, color = C.textSoft, fontSize = 14.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("儲存", Modifier.weight(1f)) {
                    llm.mainKey = mainKey.trim(); llm.groqKey = groqKey.trim()
                    s.llmBaseUrl = base; s.llmModel = model; s.groqModel = groqModel
                    nav.toast("已儲存")
                }
                OutlineButton("測試 AI", Modifier.weight(1f)) {
                    llm.mainKey = mainKey.trim(); llm.groqKey = groqKey.trim()
                    s.llmBaseUrl = base; s.llmModel = model; s.groqModel = groqModel
                    testResult = "測試中…"
                    scope.launch {
                        val main = if (mainKey.isBlank()) "主力未設定" else runCatching { "主力正常：" + llm.test(base, mainKey.trim(), model) }.getOrElse { "主力失敗：${it.message}" }
                        val back = if (groqKey.isBlank()) "備援未設定" else runCatching { "備援正常：" + llm.test(s.groqBaseUrl, groqKey.trim(), groqModel) }.getOrElse { "備援失敗：${it.message}" }
                        testResult = "$main\n$back"
                    }
                }
            }
        }
        if (show("備份", "匯出", "匯入", "還原")) OutlineButton("備份與還原", Modifier.fillMaxWidth()) { nav.push(Dest.Backup) }
        if (show("健康", "權限", "狀態")) OutlineButton("健康檢查", Modifier.fillMaxWidth()) { nav.push(Dest.Health) }
        if (show("Log", "紀錄", "除錯")) OutlineButton("Log 紀錄", Modifier.fillMaxWidth()) { nav.push(Dest.Log) }
        if (show("測試", "語音", "API")) OutlineButton("立刻同步", Modifier.fillMaxWidth()) {
            scope.launch {
                nav.toast(when (val r = Graph.sync.sync()) {
                    SyncStatus.Done -> "同步完成"
                    SyncStatus.SignedOut -> "還沒登入"
                    SyncStatus.NotMember -> "這個帳號不在白名單"
                    is SyncStatus.Failed -> "同步失敗：${r.reason}"
                })
            }
        }
        if (show("更新", "版本")) UpdateCard()
        if (show("登出")) OutlineButton("登出（清除本機文字，不動相簿）", Modifier.fillMaxWidth(), color = C.danger, border = C.dangerLine, bg = C.dangerBg) {
            scope.launch {
                Graph.signOut()
                nav.signedOut()
            }
        }
        Text("", modifier = Modifier.padding(bottom = 12.dp))
    }
}

@Composable
fun BackupScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var withImages by remember { mutableStateOf(false) }
    var replace by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var canRestore by remember { mutableStateOf(Backup.hasBeforeImport(ctx)) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            status = "匯出中…"
            status = runCatching { Backup.export(ctx, uri, withImages); "已匯出" }.getOrElse { "匯出失敗：${it.message}" }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            status = "匯入中…"
            status = runCatching { "已匯入 ${Backup.importZip(ctx, uri, replace)} 筆" }.getOrElse { "匯入失敗：${it.message}" }
            canRestore = Backup.hasBeforeImport(ctx)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header("備份與還原", back = nav.pop)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Toggle("備份包含原圖（檔案會變大）", withImages) { withImages = it }
            OutlineButton("匯出到檔案", Modifier.fillMaxWidth(), min = 52.dp) { save.launch(Backup.fileName()) }
            OutlineButton("分享備份檔", Modifier.fillMaxWidth(), min = 52.dp) {
                scope.launch {
                    status = "準備中…"
                    status = runCatching {
                        val f = java.io.File(java.io.File(ctx.cacheDir, "share"), Backup.fileName())
                        Backup.exportToFile(ctx, f, withImages)
                        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享備份"))
                        "已開啟分享"
                    }.getOrElse { "失敗：${it.message}" }
                }
            }
            Toggle("匯入時取代全部（關閉＝合併）", replace) { replace = it }
            OutlineButton("從檔案匯入", Modifier.fillMaxWidth(), min = 52.dp) { open.launch(arrayOf("application/zip", "application/octet-stream")) }
            if (canRestore) OutlineButton("復原到匯入前", Modifier.fillMaxWidth(), min = 52.dp, color = C.warn, border = C.warnLine) {
                scope.launch { status = runCatching { "已復原 ${Backup.restoreBeforeImport(ctx)} 筆" }.getOrElse { "復原失敗：${it.message}" } }
            }
            status?.let { Text(it, color = C.textSoft, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { change(!on) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(on, change, colors = SwitchDefaults.colors(checkedTrackColor = C.accentDeep, checkedThumbColor = C.accent))
    }
}

@Composable
fun LogScreen(nav: Nav) {
    val lines by AppLog.lines.collectAsState()
    var level by remember { mutableStateOf("全部") }
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    val shown = lines.filter { level == "全部" || it.level == level }
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) list.scrollToItem(shown.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        Header("Log 紀錄", "${shown.size} 行，個資已遮罩", back = nav.pop)
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "I", "W", "E").forEach { l -> Chip(l, l == level) { level = l } }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 8.dp), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(shown) { l ->
                Text(l.toString(), color = when (l.level) { "E" -> C.danger; "W" -> C.warn; else -> C.textSoft }, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            PrimaryButton("一鍵匯出 Log", Modifier.fillMaxWidth()) {
                val dir = java.io.File(ctx.cacheDir, "share").apply { mkdirs() }
                val f = java.io.File(dir, "datapack-log.txt").apply { writeText(AppLog.exportText()) }
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "匯出 Log"))
            }
        }
    }
}

@Composable
fun HealthScreen(nav: Nav) {
    val ctx = LocalContext.current
    val play = remember { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == 0 }
    val notify = remember { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val s = Graph.settings
    @Composable
    fun Line(label: String, ok: Boolean, note: String = "") {
        Card(border = if (ok) C.line else C.warnLine) {
            Text((if (ok) "正常　" else "要處理　") + label, color = if (ok) C.text else C.warn, fontSize = 15.sp)
            if (note.isNotBlank()) Text(note, color = C.muted, fontSize = 13.sp)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header("健康檢查", back = nav.pop)
        Column(Modifier.weight(1f).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Line("登入", Graph.api.signedIn, Graph.api.session?.email.orEmpty())
            Line("Google Play 服務（文件掃描要用）", play, if (play) "" else "沒有 Play 服務時只能從相簿選圖")
            Line("AI 金鑰", Graph.llm.configured, "主力${if (Graph.llm.mainKey.isNullOrBlank()) "未設定" else "已設定"}，備援${if (Graph.llm.groqKey.isNullOrBlank()) "未設定" else "已設定"}")
            Line("通知（看原圖要用）", notify)
            Line("同步", s.lastSyncError == null, s.lastSyncError ?: s.lastSyncAt.takeIf { it > 0 }?.let { "最後同步 ${dateOf(it)} ${timeOf(it)}" }.orEmpty())
            Line("Agent API", true, "語音總控掃描得到；ColorOS 要在 設定 › 應用 › 自動啟動 打開本 APP 的「關聯啟動」，否則語音查不到")
            OutlineButton("打開系統的 APP 設定", Modifier.fillMaxWidth()) {
                ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)))
            }
        }
    }
}

/** 資料包設定：欄位表單＋一句話請 AI 產草稿（確認才生效，F10） */
@Composable
fun PackEditScreen(key: String?, nav: Nav) {
    val dao = Graph.db.dao()
    var entity by remember { mutableStateOf<PackEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var packKey by remember { mutableStateOf("") }
    var shape by remember { mutableStateOf(PackConfig.SHAPE_TABLE) }
    var hint by remember { mutableStateOf("") }
    var dedupe by remember { mutableStateOf("") }
    var speech by remember { mutableStateOf("") }
    var always by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf<PackConfig?>(null) }
    val fields = remember { mutableStateListOf<FieldDef>() }
    var ask by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun load(c: PackConfig) {
        base = c
        name = c.name; packKey = c.key; shape = c.shape; hint = c.hint
        dedupe = c.dedupe.joinToString("；") { it.joinToString("+") }
        speech = c.speechRow; always = c.review.mode == ReviewRule.ALWAYS
        fields.clear(); fields.addAll(c.fields)
    }
    LaunchedEffect(key) {
        if (key != null) {
            entity = dao.packs().firstOrNull { it.key == key }
            entity?.let { Graph.repo.parse(it) }?.let { load(it) }
        }
    }
    fun build(): PackConfig = (base ?: PackConfig(packKey, name, shape)).copy(
        key = packKey.trim(), name = name.trim(), shape = shape, hint = hint.trim(), fields = fields.toList(),
        dedupe = dedupe.split("；", ";").map { r -> r.split("+").map { it.trim() }.filter { it.isNotEmpty() } }.filter { it.isNotEmpty() },
        speechRow = speech.trim(),
        review = (base?.review ?: ReviewRule()).copy(mode = if (always) ReviewRule.ALWAYS else ReviewRule.LOW_CONFIDENCE),
    )
    Column(Modifier.fillMaxSize()) {
        Header(if (key == null) "新增資料包" else "$name 設定", entity?.let { "第 ${it.version} 版，改完不用發新版" } ?: "只要加設定", back = nav.pop)
        Column(Modifier.weight(1f).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (key == null) {
                Field(ask, { ask = it }, "用一句話描述，例如：我買的書，要書名、作者、買的日期", single = false)
                OutlineButton(if (busy) "產生中…" else "請 AI 產草稿", Modifier.fillMaxWidth(), color = C.accentSoft, border = C.accent) {
                    if (ask.isBlank() || busy) return@OutlineButton
                    busy = true
                    scope.launch {
                        runCatching {
                            val raw = Graph.llm.complete(DRAFT_SYSTEM, ask, 1200)
                            load(PackConfig.parse(LlmJson.parseObject(raw)))
                        }.onFailure { nav.toast("產草稿失敗：${it.message}") }
                        busy = false
                    }
                }
            }
            Text("名稱", color = C.muted, fontSize = 13.sp)
            Field(name, { name = it }, "藏書")
            Text("代號（英文小寫，存檔後不要改）", color = C.muted, fontSize = 13.sp)
            Field(packKey, { packKey = it }, "book")
            Text("形式", color = C.muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(PackConfig.SHAPE_TABLE to "資料表", PackConfig.SHAPE_DOCUMENT to "文件", PackConfig.SHAPE_TABLE_DOC to "表格＋文件", PackConfig.SHAPE_FACT to "事實").forEach { (k, l) ->
                    Chip(l, k == shape) { shape = k }
                }
            }
            Text("分類線索（怎麼認出這類資料）", color = C.muted, fontSize = 13.sp)
            Field(hint, { hint = it }, "例如：保固卡、保證書", single = false)
            Text("欄位", color = C.muted, fontSize = 13.sp)
            fields.forEachIndexed { i, f ->
                Card {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(f.label.ifBlank { "新欄位" }, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("移除", color = C.danger, fontSize = 13.sp, modifier = Modifier.clickable { fields.removeAt(i) }.padding(8.dp))
                    }
                    Field(f.label, { fields[i] = f.copy(label = it) }, "欄位名稱")
                    Field(f.key, { fields[i] = f.copy(key = it) }, "代號（英文）")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(FieldType.TEXT, FieldType.DATE, FieldType.PHONE, FieldType.TAGS).forEach { t ->
                            Chip(FieldType.label(t), f.type == t) { fields[i] = f.copy(type = t) }
                        }
                    }
                    Toggle("必填", f.required) { fields[i] = f.copy(required = it) }
                    Toggle("可搜尋", f.searchable) { fields[i] = f.copy(searchable = it) }
                }
            }
            OutlineButton("新增欄位", Modifier.fillMaxWidth()) { fields.add(FieldDef("field${fields.size + 1}", "")) }
            Text("去重依據（欄位代號，+ 表示同時相同，；分組）", color = C.muted, fontSize = 13.sp)
            Field(dedupe, { dedupe = it }, "model+serial；product")
            Text("語音唸法（{欄位代號}）", color = C.muted, fontSize = 13.sp)
            Field(speech, { speech = it }, "{product}保固到{warranty_end}")
            Toggle("每一份都要人工核對", always) { always = it }
            problems.forEach { Text(it, color = C.danger, fontSize = 14.sp) }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            entity?.let { e ->
                OutlineButton(if (e.enabled) "停用" else "啟用", Modifier.weight(1f), min = 56.dp) {
                    scope.launch {
                        Graph.repo.setPackEnabled(e, !e.enabled)
                        nav.pop()
                    }
                }
            }
            PrimaryButton("儲存生效", Modifier.weight(2f)) {
                val c = build()
                problems = c.problems()
                if (problems.isEmpty()) scope.launch {
                    Graph.repo.savePack(c, entity)
                    nav.toast("已儲存「${c.name}」")
                    nav.pop()
                }
            }
        }
    }
}

private val DRAFT_SYSTEM = """
你幫倬設計資料包設定。依他的描述只輸出 JSON，格式：
{"key":"英文小寫代號","name":"中文名稱","shape":"table","classify":{"hint":"怎麼認出這類資料","examples":["範例文字"]},
"fields":[{"key":"英文代號","label":"中文","type":"text|date|phone|email|tags|number|money","required":true或false,"searchable":true或false}],
"dedupe":[["欄位代號"]],"review":{"mode":"low_confidence","threshold":0.8,"must_check":[]},"speech":{"row":"{欄位代號}的唸法","limit":5}}
shape 用 table（一筆一列）或 document（長文件）。繁體中文。
""".trimIndent()

@Suppress("unused")
private val keep = JSONObject::class
