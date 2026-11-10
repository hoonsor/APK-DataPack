package tw.hoonsor.datapack.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.datapack.BuildConfig
import tw.hoonsor.datapack.Graph
import tw.hoonsor.datapack.data.ChunkEntity
import tw.hoonsor.datapack.data.ItemEntity
import tw.hoonsor.datapack.data.PackEntity
import tw.hoonsor.datapack.data.SourceEntity
import tw.hoonsor.datapack.domain.objects
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/**
 * 備份（F22）：zip 內有 backup.json，可選含原圖（images/）。格式見 [BackupZip]。
 * 每日自動備份見 [DataPackBackupSource]（hoonsor-kit android-auto-backup）。
 * 匯入前自動備份一份到 APP 私有資料夾，可「復原到匯入前」。
 */
object Backup {
    const val SCHEMA = 1
    private val dao get() = Graph.db.dao()
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    fun fileName() = "DataPack-backup-${LocalDateTime.now().format(stamp)}.zip"

    private fun beforeImport(ctx: Context) = File(ctx.filesDir, "before-import.zip")
    fun hasBeforeImport(ctx: Context) = beforeImport(ctx).exists()

    /**
     * stable＝自動備份用：不寫匯出時間、各表依 id 排序，同樣的資料每次輸出都一樣（見 [BackupZip]）。
     * 手動匯出也一起排序，只多寫一個 exportedAt。
     */
    private suspend fun snapshot(stable: Boolean, items: List<ItemEntity>, sources: List<SourceEntity>, dataAt: Long): JSONObject {
        val j = JSONObject().put("schema", SCHEMA).put("app", "tw.hoonsor.datapack").put("version", BuildConfig.VERSION_NAME)
            .put("dataAt", Instant.ofEpochMilli(dataAt).toString())
        if (!stable) j.put("exportedAt", Instant.now().toString())
        j.put("packs", JSONArray(dao.packs().sortedBy { it.id }.map { p ->
            JSONObject().put("id", p.id).put("key", p.key).put("name", p.name).put("shape", p.shape).put("config", JSONObject(p.config)).put("version", p.version).put("enabled", p.enabled)
        }))
        j.put("items", JSONArray(items.map { i ->
            JSONObject().put("id", i.id).put("packKey", i.packKey).put("title", i.title).put("fields", JSONObject(i.fields)).put("summary", i.summary)
                .put("searchText", i.searchText).put("status", i.status).put("confidence", i.confidence.toDouble()).put("reviewReasons", i.reviewReasons)
                .put("dedupeKeys", i.dedupeKeys).put("pageCount", i.pageCount).put("capturedAt", i.capturedAt)
        }))
        j.put("sources", JSONArray(sources.map { s ->
            JSONObject().put("id", s.id).put("itemId", s.itemId).put("pageNo", s.pageNo).put("sha256", s.sha256).put("localName", s.localName)
                .put("kind", s.kind).put("width", s.width).put("height", s.height).put("ocr", s.ocr).put("blocks", s.blocks)
        }))
        j.put("chunks", JSONArray(dao.allChunks().sortedBy { it.id }.map { c ->
            JSONObject().put("id", c.id).put("itemId", c.itemId).put("sourceId", c.sourceId).put("pageNo", c.pageNo).put("seq", c.seq).put("text", c.text).put("blockIds", c.blockIds)
        }))
        return j
    }

    private suspend fun writeZip(ctx: Context, out: java.io.OutputStream, withImages: Boolean, stable: Boolean = false) {
        val items = dao.allItems().sortedBy { it.id }
        val sources = dao.allSources().sortedBy { it.id }
        // zip entry 的時間用資料本身的最後修改時間，不用「現在」，否則每次雜湊都不同
        val dataAt = BackupZip.entryTime(items.map { maxOf(it.updatedAt, it.capturedAt) })
        val json = snapshot(stable, items, sources, dataAt).toString()
        val images = if (!withImages) emptyList() else sources.filter { it.kind == SourceEntity.KIND_IMAGE && it.localUri.isNotBlank() }.map { s ->
            BackupZip.Image(s.localName) { ctx.contentResolver.openInputStream(Uri.parse(s.localUri)) }
        }
        BackupZip.write(out, json, images, dataAt)
    }

    /** 每日自動備份（[DataPackBackupSource]）：含原圖、輸出固定，在 JobService 的背景執行緒呼叫 */
    fun writeAuto(ctx: Context, out: java.io.OutputStream) = runBlocking(Dispatchers.IO) {
        writeZip(ctx, out, withImages = true, stable = true)
    }

    suspend fun export(ctx: Context, uri: Uri, withImages: Boolean) = withContext(Dispatchers.IO) {
        ctx.contentResolver.openOutputStream(uri)!!.use { writeZip(ctx, it, withImages) }
    }

    suspend fun exportToFile(ctx: Context, file: File, withImages: Boolean) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.outputStream().use { writeZip(ctx, it, withImages) }
    }

    /** CSV 匯出一個資料包，給 Excel 用 */
    suspend fun csv(key: String): String {
        val p = Graph.repo.packConfig(key) ?: return ""
        val cols = p.fields.filter { it.type != tw.hoonsor.datapack.domain.FieldType.ROWS }
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val rows = dao.itemsIn(key).map { i ->
            val f = JSONObject(i.fields)
            cols.joinToString(",") { c -> q(tw.hoonsor.datapack.domain.Extraction.display(f.opt(c.key))) }
        }
        return "﻿" + (listOf(cols.joinToString(",") { q(it.label) }) + rows).joinToString("\r\n")
    }

    suspend fun importZip(ctx: Context, uri: Uri, replace: Boolean): Int = withContext(Dispatchers.IO) {
        val input = ctx.contentResolver.openInputStream(uri) ?: error("打不開備份檔")
        val raw = input.use { readBackupJson(it) } ?: error("這不是資料包的備份檔")
        val j = JSONObject(raw)
        require(j.optString("app") == "tw.hoonsor.datapack") { "這不是資料包的備份檔" }
        require(j.optInt("schema") <= SCHEMA) { "備份比這個版本新，請先更新 APP" }
        exportToFile(ctx, beforeImport(ctx), withImages = false)
        apply(j, replace)
    }

    suspend fun restoreBeforeImport(ctx: Context): Int = withContext(Dispatchers.IO) {
        val raw = beforeImport(ctx).inputStream().use { readBackupJson(it) } ?: error("找不到匯入前的備份")
        apply(JSONObject(raw), replace = true)
    }

    private fun readBackupJson(s: java.io.InputStream): String? {
        ZipInputStream(s).use { z ->
            while (true) {
                val e = z.nextEntry ?: return null
                if (e.name == "backup.json") return z.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    private suspend fun apply(j: JSONObject, replace: Boolean): Int {
        val items = j.getJSONArray("items").objects()
        Graph.db.withTransaction {
            if (replace) Graph.repo.wipeLocal()
            j.getJSONArray("packs").objects().forEach { p ->
                dao.upsertPack(PackEntity(p.getString("id"), p.getString("key"), p.getString("name"), p.getString("shape"), p.getJSONObject("config").toString(), p.optInt("version", 1), p.optBoolean("enabled", true)))
            }
            items.forEach { i ->
                dao.upsertItem(
                    ItemEntity(
                        i.getString("id"), i.getString("packKey"), i.getString("title"), i.getJSONObject("fields").toString(), i.optString("summary"),
                        i.optString("searchText"), i.optString("status", ItemEntity.STATUS_ACTIVE), i.optDouble("confidence", 1.0).toFloat(),
                        i.optString("reviewReasons", "[]"), i.optString("dedupeKeys", "[]"), i.optInt("pageCount"), i.getLong("capturedAt"),
                    ),
                )
            }
            j.getJSONArray("sources").objects().forEach { s ->
                dao.upsertSource(
                    SourceEntity(
                        s.getString("id"), s.getString("itemId"), s.optInt("pageNo", 1), s.optString("sha256"), localName = s.optString("localName"),
                        kind = s.optString("kind", SourceEntity.KIND_IMAGE), width = s.optInt("width"), height = s.optInt("height"), ocr = s.optString("ocr"), blocks = s.optString("blocks", "[]"),
                    ),
                )
            }
            dao.upsertChunks(j.getJSONArray("chunks").objects().map { c ->
                ChunkEntity(c.getString("id"), c.getString("itemId"), c.getString("sourceId"), c.optInt("pageNo", 1), c.optInt("seq"), c.getString("text"), c.optString("blockIds", "[]"))
            })
        }
        tw.hoonsor.datapack.sync.SyncWorker.syncSoon(Graph.app)
        return items.size
    }
}

/** 每日自動備份的來源（AndroidManifest 的 tw.hoonsor.backup.SOURCE 指到這裡）。含原圖：原圖只在手機上 */
class DataPackBackupSource : tw.hoonsor.backup.AutoBackup.Source {
    /** LAB 版另放一個資料夾，不跟正式版混在一起 */
    override fun appName() = if (BuildConfig.FLAVOR == "lab") "DataPack-LAB" else "DataPack"
    override fun extension() = "zip"
    override fun mimeType() = "application/zip"
    override fun write(context: Context, out: java.io.OutputStream) = Backup.writeAuto(context, out)
}
