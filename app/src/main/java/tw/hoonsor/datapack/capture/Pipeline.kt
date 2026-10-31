package tw.hoonsor.datapack.capture

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.datapack.Graph
import tw.hoonsor.datapack.ai.AiException
import tw.hoonsor.datapack.data.ChunkEntity
import tw.hoonsor.datapack.data.ItemEntity
import tw.hoonsor.datapack.data.JobEntity
import tw.hoonsor.datapack.data.SourceEntity
import tw.hoonsor.datapack.domain.Dedupe
import tw.hoonsor.datapack.domain.Extraction
import tw.hoonsor.datapack.domain.Guard
import tw.hoonsor.datapack.domain.Layout
import tw.hoonsor.datapack.domain.OcrBlock
import tw.hoonsor.datapack.domain.PackConfig
import tw.hoonsor.datapack.domain.strings
import tw.hoonsor.datapack.log.AppLog
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** 一頁處理中的內容 */
private data class PageWork(val file: File?, val page: Int, val blocks: List<OcrBlock>, val text: String, val width: Int, val height: Int)

/**
 * 擷取流程（F01–F09）：OCR → 公務防呆 → 分類 → 抽取 → 去重 → 入庫＋存相簿。
 * 公務防呆在任何文字離開手機之前執行；沒網路時 OCR 做完就排隊，連上網自動續跑。
 */
object Pipeline {
    private const val EXTRACT_LIMIT = 8000
    private const val EXTRACT_LIMIT_TABLE = 14000
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private val dao get() = Graph.db.dao()

    suspend fun enqueue(ctx: Context, files: List<File>, origin: String, forcedPack: String? = null) {
        val now = System.currentTimeMillis()
        dao.upsertJob(JobEntity(Graph.repo.id(), JSONArray(files.map { it.path }).toString(), origin, forcedPack = forcedPack, createdAt = now))
        CaptureWorker.start(ctx)
    }

    suspend fun retry(ctx: Context, job: JobEntity, forcedPack: String? = job.forcedPack, overrideGuard: Boolean = job.overrideGuard) {
        dao.upsertJob(job.copy(state = JobEntity.STATE_QUEUED, detail = "", forcedPack = forcedPack, overrideGuard = overrideGuard, updatedAt = System.currentTimeMillis()))
        CaptureWorker.start(ctx)
    }

    /** 丟棄這筆擷取並清暫存；被防呆擋下的圖片不會留下 */
    suspend fun discard(job: JobEntity) {
        files(job).forEach { it.delete() }
        dao.deleteJob(job.id)
    }

    private fun files(job: JobEntity) = JSONArray(job.pages).strings().map { File(it) }

    /** 回傳 false 表示要等網路再跑 */
    suspend fun runAll(ctx: Context): Boolean {
        var needNet = false
        var batch = dao.pendingJobs()
        while (batch.isNotEmpty()) {
            batch.forEach { job ->
                try {
                    process(ctx, job)
                } catch (e: AiException) {
                    AppLog.warn("capture", "抽取暫停：${e.message}")
                    val net = e.network || !Graph.llm.configured
                    dao.upsertJob(job.copy(state = if (net) JobEntity.STATE_WAIT_NET else JobEntity.STATE_FAILED, detail = e.message.orEmpty(), updatedAt = System.currentTimeMillis()))
                    if (e.network) needNet = true
                } catch (e: Exception) {
                    AppLog.error("capture", "擷取失敗", e)
                    dao.upsertJob(job.copy(state = JobEntity.STATE_FAILED, detail = "${e.javaClass.simpleName}：${e.message}", updatedAt = System.currentTimeMillis()))
                }
            }
            // 名片拆頁會新增工作；等網路的留到下次，不在這裡空轉
            batch = dao.queuedJobs()
        }
        return !needNet
    }

    private suspend fun process(ctx: Context, job: JobEntity) {
        val all = files(job)
        if (all.isEmpty() || !all.first().exists()) error("暫存檔不見了，請重新掃描")
        val pages = readPages(ctx, job, all)
        val combined = pages.joinToString("\n") { it.text }
        if (combined.isBlank()) {
            finish(job, JobEntity.STATE_FAILED, "這份沒有抓到文字，可能太模糊或是照片")
            return
        }

        // 防呆：OCR 後、送雲端前
        val guard = Guard.check(combined, job.overrideGuard)
        if (guard.blocked) {
            AppLog.warn("guard", "攔下：${guard.reasons.joinToString("；")}")
            finish(job, JobEntity.STATE_BLOCKED, guard.reasons.joinToString("；"))
            return
        }
        if (job.overrideGuard && guard.reasons.isNotEmpty()) AppLog.warn("guard", "手動放行：${guard.reasons.joinToString("；")}")

        // 同一張圖略過
        val hashes = all.map { Media.sha256(it) }
        val known = hashes.mapNotNull { dao.sourceBySha(it) }
        if (known.size == hashes.size) {
            val title = dao.item(known.first().itemId)?.title.orEmpty()
            finish(job, JobEntity.STATE_DUPLICATE, "已經有了：$title")
            return
        }

        val packs = Graph.repo.packConfigs()
        val masked = Guard.check(combined, override = true).text
        val (pack, classifyConfidence) = choosePack(job, packs, masked) ?: run {
            finish(job, JobEntity.STATE_FAILED, "不知道要放哪一類，請選資料包")
            return
        }

        // 名片這類一頁一筆：拆成多筆工作，各自抽取與去重
        if (pack.splitPages && all.size > 1) {
            all.drop(1).forEach { f -> enqueueChild(job, f, pack.key) }
            dao.upsertJob(job.copy(pages = JSONArray(listOf(all.first().path)).toString(), forcedPack = pack.key))
            process(ctx, dao.job(job.id)!!)
            return
        }

        val limit = if (pack.isTable) EXTRACT_LIMIT_TABLE else EXTRACT_LIMIT
        val raw = Graph.llm.complete(Extraction.extractSystem(pack), "OCR 文字：\n" + masked.take(limit), if (pack.isTable) 4000 else 1500)
        val e = Extraction.parseExtraction(raw, pack)
        val reasons = Extraction.reviewReasons(pack, e, classifyConfidence)

        // 去重：同人或同物
        val keys = Dedupe.keys(pack, e.fields)
        val same = dao.itemsIn(pack.key).firstOrNull { Dedupe.overlaps(keys, JSONArray(it.dedupeKeys).strings()) }
        if (same != null) {
            if (pack.isDocument && !pack.isTable) {
                appendPages(ctx, pack, same, pages, all, hashes)
                finish(job, JobEntity.STATE_DONE, "已併入：${same.title}", same.id)
                return
            }
            val changes = Dedupe.changes(pack, JSONObject(same.fields), e.fields)
            if (changes.isEmpty()) {
                finish(job, JobEntity.STATE_DUPLICATE, "已經有了：${same.title}", same.id)
                return
            }
            val detail = JSONObject().put("itemId", same.id).put("title", same.title).put("fields", e.fields)
                .put("changes", JSONArray(changes.map { JSONObject().put("key", it.key).put("label", it.label).put("old", it.old).put("new", it.new) }))
            finish(job, JobEntity.STATE_MERGE, detail.toString(), same.id)
            return
        }

        val status = if (reasons.isEmpty()) ItemEntity.STATUS_ACTIVE else ItemEntity.STATUS_REVIEW
        val item = Graph.repo.addItem(pack, e.title, e.fields, e.summary, status, e.overall, reasons, all.size.coerceAtLeast(pages.size))
        store(ctx, pack, item, pages, all, hashes, firstPage = 1)
        AppLog.info("capture", "入庫 ${pack.name}（${pages.size} 頁，${if (reasons.isEmpty()) "直接入庫" else "待確認"}）")
        finish(job, JobEntity.STATE_DONE, e.title, item.id)
    }

    private suspend fun enqueueChild(job: JobEntity, f: File, pack: String) {
        dao.upsertJob(JobEntity(Graph.repo.id(), JSONArray(listOf(f.path)).toString(), job.origin, forcedPack = pack, createdAt = System.currentTimeMillis()))
    }

    private suspend fun finish(job: JobEntity, state: String, detail: String, itemId: String? = null) {
        dao.upsertJob(job.copy(state = state, detail = detail, itemId = itemId, updatedAt = System.currentTimeMillis()))
        if (state == JobEntity.STATE_DONE || state == JobEntity.STATE_DUPLICATE) files(job).forEach { it.delete() }
    }

    private suspend fun choosePack(job: JobEntity, packs: List<PackConfig>, text: String): Pair<PackConfig, Double>? {
        job.forcedPack?.let { key -> packs.firstOrNull { it.key == key }?.let { return it to 1.0 } }
        val raw = Graph.llm.complete(Extraction.classifySystem(packs, Graph.settings.corrections), Extraction.classifyUser(text), 120)
        val c = Extraction.parseClassification(raw, packs)
        return packs.firstOrNull { it.key == c.packKey }?.let { it to c.confidence }
    }

    /** OCR 結果存檔：等網路重跑時不用再辨識一次 */
    private suspend fun readPages(ctx: Context, job: JobEntity, all: List<File>): List<PageWork> {
        if (all.size == 1 && all.first().extension.equals("pdf", true)) return readPdf(ctx, all.first())
        return all.mapIndexed { i, f ->
            val cache = File(f.path + ".ocr.json")
            val r = if (cache.exists()) {
                val j = JSONObject(cache.readText())
                Media.Ocr(OcrBlock.list(j.getString("blocks")), j.getInt("w"), j.getInt("h"))
            } else {
                Media.ocr(ctx, f).also { o ->
                    cache.writeText(JSONObject().put("blocks", OcrBlock.json(o.blocks)).put("w", o.width).put("h", o.height).toString())
                }
            }
            PageWork(f, i + 1, r.blocks, Layout.text(r.blocks), r.width, r.height)
        }
    }

    private suspend fun readPdf(ctx: Context, file: File): List<PageWork> {
        val text = Media.pdfText(ctx, file)
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return fd.use {
            text.map { p ->
                if (p.text.length >= 20) PageWork(null, p.page, emptyList(), p.text, 0, 0)
                else {
                    val bmp = ParcelFileDescriptor.dup(it.fileDescriptor).let { dup -> Media.renderPdfPage(dup, p.page) }
                    if (bmp == null) PageWork(null, p.page, emptyList(), "", 0, 0)
                    else Media.ocr(bmp).let { o -> PageWork(null, p.page, o.blocks, Layout.text(o.blocks), o.width, o.height).also { bmp.recycle() } }
                }
            }
        }
    }

    /** 通過防呆才把圖片存進相簿，並寫來源與分段 */
    private suspend fun store(ctx: Context, pack: PackConfig, item: ItemEntity, pages: List<PageWork>, all: List<File>, hashes: List<String>, firstPage: Int) {
        val time = LocalDateTime.now().format(stamp)
        val isPdf = all.size == 1 && all.first().extension.equals("pdf", true)
        var pdfUri: Uri? = null
        var pdfName = ""
        if (isPdf) {
            pdfName = "${pack.name}-$time.pdf"
            pdfUri = Media.savePdf(ctx, all.first(), pack.name, pdfName)
        }
        val chunks = mutableListOf<ChunkEntity>()
        pages.forEachIndexed { i, p ->
            val pageNo = firstPage + i
            val hash = if (isPdf) "${hashes.first()}#${p.page}" else hashes.getOrElse(i) { "" }
            val name = if (isPdf) pdfName else "${pack.name}-$time-$pageNo.jpg"
            val uri = if (isPdf) pdfUri else p.file?.let { Media.saveImage(ctx, it, pack.name, name) }
            val text = Guard.check(p.text, override = true).text
            val masked = p.blocks.map { b -> b.copy(text = Guard.check(b.text, override = true).text) }
            val source = SourceEntity(
                id = Graph.repo.id(), itemId = item.id, pageNo = pageNo, sha256 = hash, localName = name, localUri = uri?.toString().orEmpty(),
                kind = if (isPdf) SourceEntity.KIND_PDF else SourceEntity.KIND_IMAGE, width = p.width, height = p.height,
                ocr = text, blocks = OcrBlock.json(masked),
            )
            Graph.repo.addSource(source)
            if (pack.isDocument) {
                Layout.chunks(pageNo, masked, text).forEach { c ->
                    chunks += ChunkEntity(Graph.repo.id(), item.id, source.id, pageNo, c.seq, c.text, JSONArray(c.blockIds).toString())
                }
            }
        }
        Graph.repo.addChunks(chunks)
    }

    /** 同一份說明書補拍：內容完全相同的頁略過，其餘接在後面 */
    private suspend fun appendPages(ctx: Context, pack: PackConfig, item: ItemEntity, pages: List<PageWork>, all: List<File>, hashes: List<String>) {
        val existing = dao.sourcesOf(item.id)
        fun norm(s: String) = s.replace(Regex("\\s"), "")
        val seen = existing.map { norm(it.ocr) }.toSet()
        val keep = pages.indices.filter { norm(pages[it].text) !in seen }
        if (keep.isEmpty()) return
        val start = (existing.maxOfOrNull { it.pageNo } ?: 0) + 1
        store(ctx, pack, item, keep.map { pages[it] }, keep.map { all.getOrElse(it) { all.first() } }, keep.map { hashes.getOrElse(it) { "" } }, start)
        Graph.repo.snapshot(item, "補拍 ${keep.size} 頁")
        dao.upsertItem(item.copy(pageCount = existing.size + keep.size, updatedAt = System.currentTimeMillis(), dirty = true))
    }

    /** 人工改類型：用新資料包重新抽取，不必重拍（F08） */
    suspend fun reclassify(item: ItemEntity, target: PackConfig) {
        val text = dao.sourcesOf(item.id).joinToString("\n") { it.ocr }
        val raw = Graph.llm.complete(Extraction.extractSystem(target), "OCR 文字：\n" + text.take(EXTRACT_LIMIT_TABLE), if (target.isTable) 4000 else 1500)
        val e = Extraction.parseExtraction(raw, target)
        val reasons = Extraction.reviewReasons(target, e, 1.0)
        val from = item.packKey
        Graph.settings.corrections = Graph.settings.corrections + "${text.take(60).replace('\n', ' ')} → ${target.key}（原本判成 $from）"
        Graph.repo.updateItem(
            item.copy(reviewReasons = JSONArray(reasons).toString()), target, e.title, e.fields, "改類型：$from → ${target.key}",
            status = if (reasons.isEmpty()) ItemEntity.STATUS_ACTIVE else ItemEntity.STATUS_REVIEW, summary = e.summary,
        )
    }
}

class CaptureWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ok = Pipeline.runAll(applicationContext)
        if (!ok) {
            // 連上網再跑；免費額度 429 也走這裡，等一下再試
            startWhenOnline(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "capture"

        fun start(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<CaptureWorker>().build())
        }

        fun startWhenOnline(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<CaptureWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("$NAME-online", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
