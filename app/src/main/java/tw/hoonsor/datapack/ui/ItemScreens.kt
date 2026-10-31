package tw.hoonsor.datapack.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.datapack.Graph
import tw.hoonsor.datapack.backup.Backup
import tw.hoonsor.datapack.capture.Media
import tw.hoonsor.datapack.capture.Pipeline
import tw.hoonsor.datapack.data.ItemEntity
import tw.hoonsor.datapack.data.SourceEntity
import tw.hoonsor.datapack.domain.Extraction
import tw.hoonsor.datapack.domain.FieldDef
import tw.hoonsor.datapack.domain.FieldType
import tw.hoonsor.datapack.domain.OcrBlock
import tw.hoonsor.datapack.domain.PackConfig
import tw.hoonsor.datapack.domain.objects
import tw.hoonsor.datapack.domain.strings
import tw.hoonsor.datapack.query.phoneOf
import tw.hoonsor.datapack.ui.theme.C
import java.io.File

/** 欄位編輯用：陣列用頓號串成一行 */
private fun editText(v: Any?) = Extraction.display(v)

private fun applyEdits(p: PackConfig, base: JSONObject, edits: Map<String, String>): JSONObject {
    val out = JSONObject(base.toString())
    p.fields.filter { it.type != FieldType.ROWS }.forEach { f ->
        val text = edits[f.key] ?: return@forEach
        val v = Extraction.normalizeField(f, text)
        if (v == null) out.remove(f.key) else out.put(f.key, v)
    }
    return out
}

@Composable
private fun RowsTable(f: FieldDef, value: JSONArray?) {
    val rows = value?.objects().orEmpty()
    Text("${f.label}（${rows.size} 項）", color = C.muted, fontSize = 13.sp)
    rows.forEach { r ->
        val flag = r.optString("flag")
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel).padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(r.optString("item"), color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(listOf(r.optString("value"), r.optString("unit")).filter { it.isNotBlank() }.joinToString(" "), color = if (flag.isNotBlank()) C.warn else C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(r.optString("range"), color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

@Composable
fun ItemScreen(id: String, nav: Nav) {
    val dao = Graph.db.dao()
    val item by dao.itemFlow(id).collectAsState(null)
    val versions by dao.versionsFlow(id).collectAsState(emptyList())
    val sources by dao.sourcesFlow(id).collectAsState(emptyList())
    val packs by dao.packsFlow().collectAsState(emptyList())
    var pack by remember { mutableStateOf<PackConfig?>(null) }
    val edits = remember(id) { mutableStateMapOf<String, String>() }
    var changing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val cur = item
    LaunchedEffect(cur?.packKey) { cur?.let { pack = Graph.repo.packConfig(it.packKey) } }
    val p = pack
    if (cur == null || p == null) return
    val fields = remember(cur.fields) { JSONObject(cur.fields) }
    Column(Modifier.fillMaxSize()) {
        Header(cur.title, "${p.name}，${dateOf(cur.capturedAt)}", back = nav.pop)
        Column(Modifier.weight(1f).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            p.fields.forEach { f ->
                if (f.type == FieldType.ROWS) RowsTable(f, fields.optJSONArray(f.key))
                else {
                    Text(f.label + if (f.computed.isNotBlank()) "（自動計算）" else "", color = C.muted, fontSize = 13.sp)
                    Field(edits[f.key] ?: editText(fields.opt(f.key)), { edits[f.key] = it }, f.label, single = false)
                }
            }
            if (cur.summary.isNotBlank()) {
                Text("重點", color = C.muted, fontSize = 13.sp)
                Text(cur.summary, color = C.text, fontSize = 15.sp, lineHeight = 23.sp)
            }
            sources.firstOrNull()?.let { s ->
                OutlineButton(if (sources.size > 1) "看原圖（${sources.size} 頁）" else "看原圖", Modifier.fillMaxWidth(), color = C.accentSoft) { nav.push(Dest.Source(cur.id, s.id, null)) }
            }
            if ("call" in p.actions) phoneOf(fields)?.let { phone ->
                OutlineButton("打電話 $phone", Modifier.fillMaxWidth()) {
                    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })))
                }
            }
            if ("contact" in p.actions) OutlineButton("存到手機聯絡人", Modifier.fillMaxWidth()) { addContact(ctx, fields) }
            OutlineButton(if (busy) "重新抽取中…" else "分類錯了，改類型", Modifier.fillMaxWidth()) { if (!busy) changing = true }
            if (versions.isNotEmpty()) {
                Text("舊版（${versions.size}）", color = C.muted, fontSize = 13.sp)
                versions.take(5).forEach { v ->
                    Card {
                        Text("${dateOf(v.createdAt)} ${timeOf(v.createdAt)}　${v.reason}", color = C.text, fontSize = 14.sp)
                        OutlineButton("還原這一版", min = 40.dp) { scope.launch { Graph.repo.restoreVersion(cur, p, v) } }
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("刪除", Modifier.weight(1f), color = C.danger, border = C.dangerLine, bg = C.dangerBg, min = 56.dp) {
                scope.launch {
                    val s = dao.sourcesOf(cur.id)
                    val c = dao.chunksOf(cur.id)
                    Graph.repo.delete(cur)
                    nav.undo(Undo("已刪除「${cur.title}」") { Graph.repo.undelete(cur, s, c) })
                    nav.pop()
                }
            }
            PrimaryButton(if (cur.status == ItemEntity.STATUS_REVIEW) "確認" else "儲存", Modifier.weight(2f)) {
                scope.launch {
                    val merged = applyEdits(p, fields, edits)
                    if (edits.isNotEmpty()) Graph.repo.updateItem(cur, p, cur.title, merged, "手動修改", ItemEntity.STATUS_ACTIVE)
                    else Graph.repo.confirm(cur)
                    edits.clear()
                    nav.toast("已儲存")
                }
            }
        }
    }
    if (changing) AlertDialog(
        onDismissRequest = { changing = false },
        containerColor = C.surfaceHi,
        title = { Text("改成哪個資料包？", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                packs.filter { it.key != p.key && it.key != "fact" }.forEach { t ->
                    OutlineButton(t.name, Modifier.fillMaxWidth()) {
                        changing = false
                        busy = true
                        scope.launch {
                            val target = Graph.repo.packConfig(t.key)
                            runCatching { Pipeline.reclassify(cur, target!!) }.onFailure { nav.toast("改類型失敗：${it.message}") }
                            busy = false
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ changing = false }) { Text("取消", color = C.muted) } },
    )
}

/** 待確認：原圖與抽取結果並排，低信心直接標出（U20） */
@Composable
fun ReviewScreen(nav: Nav) {
    val dao = Graph.db.dao()
    val list by dao.reviewFlow().collectAsState(emptyList())
    var index by remember { mutableStateOf(0) }
    val edits = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    if (list.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("都核對完了", color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("新資料信心不夠時會出現在這裡。", color = C.muted, fontSize = 15.sp)
        }
        return
    }
    val item = list[index.coerceIn(0, list.lastIndex)]
    var pack by remember(item.id) { mutableStateOf<PackConfig?>(null) }
    var sources by remember(item.id) { mutableStateOf<List<SourceEntity>>(emptyList()) }
    LaunchedEffect(item.id) {
        pack = Graph.repo.packConfig(item.packKey)
        sources = dao.sourcesOf(item.id)
        edits.clear()
    }
    val p = pack ?: return
    val reasons = remember(item.reviewReasons) { JSONArray(item.reviewReasons).strings() }
    val fields = remember(item.fields) { JSONObject(item.fields) }
    Column(Modifier.fillMaxSize()) {
        Header("待確認 ${index.coerceIn(0, list.lastIndex) + 1} / ${list.size}", "${p.name}：${reasons.joinToString("、")}")
        Column(Modifier.weight(1f).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.firstOrNull()?.let { s -> PageImage(s, emptySet(), Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
            p.fields.forEach { f ->
                if (f.type == FieldType.ROWS) RowsTable(f, fields.optJSONArray(f.key))
                else {
                    val warn = reasons.any { it.contains(f.label) }
                    Text(f.label + if (warn) "（可能有誤）" else "", color = if (warn) C.warn else C.muted, fontSize = 13.sp)
                    Field(edits[f.key] ?: editText(fields.opt(f.key)), { edits[f.key] = it }, f.label, single = false)
                }
            }
            OutlineButton("開完整畫面（改類型、看所有頁）", Modifier.fillMaxWidth()) { nav.push(Dest.Item(item.id)) }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton("略過", Modifier.weight(1f), min = 56.dp) { index = (index + 1) % list.size }
            PrimaryButton("確認，下一筆", Modifier.weight(2f)) {
                scope.launch {
                    if (edits.isNotEmpty()) Graph.repo.updateItem(item, p, item.title, applyEdits(p, fields, edits), "核對修改", ItemEntity.STATUS_ACTIVE)
                    else Graph.repo.confirm(item)
                    edits.clear()
                }
            }
        }
    }
}

/** 原圖或 PDF 頁；highlight 的區塊畫框 */
@Composable
private fun PageImage(s: SourceEntity, highlight: Set<Int>, modifier: Modifier = Modifier, zoomable: Boolean = false) {
    val ctx = LocalContext.current
    var bmp by remember(s.id) { mutableStateOf<Bitmap?>(null) }
    var missing by remember(s.id) { mutableStateOf(false) }
    LaunchedEffect(s.id) {
        bmp = when {
            s.localUri.isBlank() -> null
            s.kind == SourceEntity.KIND_PDF -> runCatching {
                withContext(Dispatchers.IO) { ctx.contentResolver.openFileDescriptor(Uri.parse(s.localUri), "r") }?.let { Media.renderPdfPage(it, s.pageNo) }
            }.getOrNull()
            else -> Media.loadImage(ctx, Uri.parse(s.localUri))
        }
        missing = bmp == null
    }
    val b = bmp
    if (b == null) {
        if (missing) Text("原圖不在這支手機（圖片不上雲端），下面是 OCR 文字。", color = C.warn, fontSize = 14.sp, modifier = modifier.padding(vertical = 8.dp))
        return
    }
    val blocks = remember(s.blocks) { OcrBlock.list(s.blocks) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val ratio = b.width.toFloat() / b.height
    Box(
        modifier.aspectRatio(ratio, matchHeightConstraintsFirst = false).clip(RoundedCornerShape(16.dp))
            .then(if (zoomable) Modifier.pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            } else Modifier),
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)) {
            Image(b.asImageBitmap(), "原圖", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            if (highlight.isNotEmpty() && s.width > 0) Canvas(Modifier.fillMaxSize()) {
                val k = size.width / s.width
                blocks.filter { it.id in highlight }.forEach { o ->
                    val tl = Offset(o.x * k - 4, o.y * k - 4)
                    val sz = Size(o.w * k + 8, o.h * k + 8)
                    drawRect(C.accent.copy(alpha = 0.25f), tl, sz)
                    drawRect(C.accentDeep, tl, sz, style = Stroke(4f))
                }
            }
        }
    }
}

/** 回到原頁：高亮答案所在區塊，可翻頁、可看文字（F12） */
@Composable
fun SourceScreen(d: Dest.Source, nav: Nav) {
    val dao = Graph.db.dao()
    var pages by remember { mutableStateOf<List<SourceEntity>>(emptyList()) }
    var index by remember { mutableStateOf(0) }
    var highlight by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var title by remember { mutableStateOf("") }
    var showText by remember { mutableStateOf(false) }
    LaunchedEffect(d) {
        val src = d.sourceId?.let { dao.source(it) }
        val itemId = d.itemId ?: src?.itemId ?: return@LaunchedEffect
        title = dao.item(itemId)?.title.orEmpty()
        pages = dao.sourcesOf(itemId)
        index = pages.indexOfFirst { it.id == src?.id }.coerceAtLeast(0)
        d.chunkId?.let { dao.chunk(it) }?.let { c -> highlight = JSONArray(c.blockIds).let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } }
    }
    val s = pages.getOrNull(index)
    Column(Modifier.fillMaxSize()) {
        Header(title, s?.let { if (it.kind == SourceEntity.KIND_VOICE) "你說的話" else "第 ${it.pageNo} 頁" }.orEmpty(), back = nav.pop)
        Column(Modifier.weight(1f).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s == null) Text("這筆資料沒有原圖（例如用說的記住的）。", color = C.muted)
            else {
                if (!showText) PageImage(s, if (index == pages.indexOfFirst { it.id == d.sourceId }) highlight else emptySet(), Modifier.fillMaxWidth(), zoomable = true)
                if (showText || s.localUri.isBlank()) Text(s.ocr, color = C.text, fontSize = 15.sp, lineHeight = 23.sp)
            }
        }
        if (s != null) Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pages.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlineButton("上一頁", min = 44.dp) { if (index > 0) index-- }
                Text("${index + 1} / ${pages.size}", color = C.muted)
                OutlineButton("下一頁", min = 44.dp) { if (index < pages.lastIndex) index++ }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton(if (showText) "看原圖" else "看文字", Modifier.weight(1f)) { showText = !showText }
                OutlineButton("開這筆資料", Modifier.weight(1f)) { nav.push(Dest.Item(s.itemId)) }
            }
        }
    }
}

fun addContact(ctx: Context, f: JSONObject) {
    val phones = f.optJSONArray("mobile")?.strings().orEmpty() + f.optJSONArray("phone")?.strings().orEmpty()
    ctx.startActivity(
        Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
            .putExtra(ContactsContract.Intents.Insert.NAME, f.optString("name"))
            .putExtra(ContactsContract.Intents.Insert.COMPANY, f.optString("company"))
            .putExtra(ContactsContract.Intents.Insert.JOB_TITLE, f.optString("title"))
            .putExtra(ContactsContract.Intents.Insert.EMAIL, f.optString("email"))
            .putExtra(ContactsContract.Intents.Insert.PHONE, phones.firstOrNull().orEmpty())
            .putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, phones.getOrNull(1).orEmpty()),
    )
}

/** vCard 3.0；檔案放快取，透過 FileProvider 分享 */
fun vcard(f: JSONObject): String = buildString {
    fun e(s: String) = s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")
    appendLine("BEGIN:VCARD")
    appendLine("VERSION:3.0")
    appendLine("FN:${e(f.optString("name"))}")
    f.optString("company").takeIf { it.isNotBlank() }?.let { appendLine("ORG:${e(it)}") }
    f.optString("title").takeIf { it.isNotBlank() }?.let { appendLine("TITLE:${e(it)}") }
    f.optJSONArray("mobile")?.strings()?.forEach { appendLine("TEL;TYPE=CELL:$it") }
    f.optJSONArray("phone")?.strings()?.forEach { appendLine("TEL;TYPE=WORK:$it") }
    f.optString("email").takeIf { it.isNotBlank() }?.let { appendLine("EMAIL:${e(it)}") }
    f.optString("address").takeIf { it.isNotBlank() }?.let { appendLine("ADR:;;${e(it)};;;;") }
    appendLine("END:VCARD")
}

private fun shareFile(ctx: Context, name: String, mime: String, body: String) {
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
    val f = File(dir, name).apply { writeText(body) }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享"))
}

fun shareItems(ctx: Context, items: List<ItemEntity>, key: String) {
    if (key == "business_card") shareFile(ctx, "cards.vcf", "text/x-vcard", items.joinToString("") { vcard(JSONObject(it.fields)) })
    else shareFile(ctx, "datapack.txt", "text/plain", items.joinToString("\n\n") { it.title + "\n" + it.searchText })
}

suspend fun shareCsv(ctx: Context, key: String, name: String) {
    shareFile(ctx, "$key.csv", "text/csv", Backup.csv(key))
}
