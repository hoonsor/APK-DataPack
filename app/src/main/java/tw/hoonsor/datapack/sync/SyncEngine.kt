package tw.hoonsor.datapack.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.datapack.Graph
import tw.hoonsor.datapack.data.ChunkEntity
import tw.hoonsor.datapack.data.PackDao
import tw.hoonsor.datapack.data.ItemEntity
import tw.hoonsor.datapack.data.ItemVersionEntity
import tw.hoonsor.datapack.data.PackEntity
import tw.hoonsor.datapack.data.Repository
import tw.hoonsor.datapack.data.Settings
import tw.hoonsor.datapack.data.SourceEntity
import tw.hoonsor.datapack.log.AppLog
import java.time.Instant
import java.util.concurrent.TimeUnit

sealed interface SyncStatus {
    data object Done : SyncStatus
    data object SignedOut : SyncStatus
    data object NotMember : SyncStatus
    data class Failed(val reason: String) : SyncStatus
}

/** 本機 ↔ Supabase。Supabase 是真實來源，Room 是快取＋待推送佇列 */
class SyncEngine(private val api: Supabase, private val dao: PackDao, private val repo: Repository, private val settings: Settings, private val onChanged: () -> Unit) {
    private val lock = Mutex()

    suspend fun sync(): SyncStatus = lock.withLock {
        if (!api.signedIn) return SyncStatus.SignedOut
        try {
            push()
            val changed = pull()
            repo.ensureBuiltinPacks()
            push()
            settings.lastSyncAt = System.currentTimeMillis()
            settings.lastSyncError = null
            if (changed) onChanged()
            SyncStatus.Done
        } catch (e: ApiException) {
            AppLog.warn("sync", "同步失敗：${e.message}")
            settings.lastSyncError = e.message
            if (e.notMember) SyncStatus.NotMember else SyncStatus.Failed(e.message.orEmpty())
        }
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun nowOrNull(deleted: Boolean): Any = if (deleted) Instant.now().toString() else JSONObject.NULL

    private suspend fun push() {
        dao.dirtyPacks().let { rows ->
            api.upsert("jv_packs", JSONArray(rows.map { p ->
                JSONObject().put("id", p.id).put("key", p.key).put("name", p.name).put("shape", p.shape)
                    .put("config", JSONObject(p.config)).put("version", p.version).put("enabled", p.enabled)
                    .put("deleted_at", nowOrNull(p.deleted))
            }))
            rows.forEach { dao.cleanPack(it.id) }
        }
        dao.dirtyItems().let { rows ->
            rows.chunked(100).forEach { batch ->
                api.upsert("jv_items", JSONArray(batch.map { i ->
                    JSONObject().put("id", i.id).put("pack_key", i.packKey).put("title", i.title)
                        .put("fields", JSONObject(i.fields)).put("summary", i.summary).put("search_text", i.searchText)
                        .put("status", i.status).put("confidence", i.confidence.toDouble())
                        .put("review_reasons", JSONArray(i.reviewReasons)).put("dedupe_keys", JSONArray(i.dedupeKeys))
                        .put("page_count", i.pageCount).put("captured_at", iso(i.capturedAt)).put("deleted_at", nowOrNull(i.deleted))
                }))
                batch.forEach { dao.cleanItem(it.id, it.updatedAt) }
            }
        }
        dao.dirtyVersions().let { rows ->
            api.upsert("jv_item_versions", JSONArray(rows.map { v ->
                JSONObject().put("id", v.id).put("item_id", v.itemId).put("title", v.title).put("fields", JSONObject(v.fields))
                    .put("reason", v.reason).put("created_at", iso(v.createdAt))
            }))
            rows.forEach { dao.cleanVersion(it.id) }
        }
        dao.dirtySources().let { rows ->
            rows.chunked(50).forEach { batch ->
                api.upsert("jv_sources", JSONArray(batch.map { s ->
                    JSONObject().put("id", s.id).put("item_id", s.itemId).put("page_no", s.pageNo).put("sha256", s.sha256)
                        .put("phash", s.phash).put("local_name", s.localName).put("kind", s.kind).put("width", s.width).put("height", s.height)
                        .put("ocr", s.ocr).put("blocks", JSONArray(s.blocks)).put("deleted_at", nowOrNull(s.deleted))
                }))
                batch.forEach { dao.cleanSource(it.id) }
            }
        }
        while (true) {
            val batch = dao.dirtyChunks()
            if (batch.isEmpty()) break
            api.upsert("jv_chunks", JSONArray(batch.map { c ->
                JSONObject().put("id", c.id).put("item_id", c.itemId).put("source_id", c.sourceId).put("page_no", c.pageNo)
                    .put("seq", c.seq).put("text", c.text).put("block_ids", JSONArray(c.blockIds)).put("deleted_at", nowOrNull(c.deleted))
            }))
            dao.cleanChunks(batch.map { it.id })
        }
    }

    /** 依 updated_at 增量拉取；本機還沒推上去的列不蓋掉 */
    private suspend fun pull(): Boolean {
        var changed = false
        changed = pullTable("jv_packs", "updated_at") { j ->
            if (dao.pack(j.getString("id"))?.dirty == true) return@pullTable
            dao.upsertPack(
                PackEntity(
                    id = j.getString("id"), key = j.getString("key"), name = j.getString("name"), shape = j.getString("shape"),
                    config = j.getJSONObject("config").toString(), version = j.optInt("version", 1), enabled = j.optBoolean("enabled", true),
                    deleted = !j.isNull("deleted_at"), dirty = false,
                ),
            )
        } || changed
        changed = pullTable("jv_items", "updated_at") { j ->
            val old = dao.item(j.getString("id"))
            if (old?.dirty == true) return@pullTable
            val at = Instant.parse(normalizeTs(j.getString("captured_at"))).toEpochMilli()
            dao.upsertItem(
                ItemEntity(
                    id = j.getString("id"), packKey = j.getString("pack_key"), title = j.getString("title"),
                    fields = j.getJSONObject("fields").toString(), summary = j.optString("summary"), searchText = j.optString("search_text"),
                    status = j.optString("status", ItemEntity.STATUS_ACTIVE), confidence = j.optDouble("confidence", 1.0).toFloat(),
                    reviewReasons = j.optJSONArray("review_reasons")?.toString() ?: "[]", dedupeKeys = j.optJSONArray("dedupe_keys")?.toString() ?: "[]",
                    pageCount = j.optInt("page_count"), capturedAt = at, updatedAt = at, deleted = !j.isNull("deleted_at"), dirty = false,
                ),
            )
        } || changed
        changed = pullTable("jv_sources", "updated_at") { j ->
            val old = dao.source(j.getString("id"))
            if (old?.dirty == true) return@pullTable
            dao.upsertSource(
                SourceEntity(
                    id = j.getString("id"), itemId = j.getString("item_id"), pageNo = j.optInt("page_no", 1), sha256 = j.optString("sha256"),
                    phash = j.optString("phash"), localName = j.optString("local_name"), localUri = old?.localUri.orEmpty(),
                    kind = j.optString("kind", SourceEntity.KIND_IMAGE), width = j.optInt("width"), height = j.optInt("height"),
                    ocr = j.optString("ocr"), blocks = j.optJSONArray("blocks")?.toString() ?: "[]", deleted = !j.isNull("deleted_at"), dirty = false,
                ),
            )
        } || changed
        pullTable("jv_item_versions", "created_at") { j ->
            dao.upsertVersion(
                ItemVersionEntity(
                    j.getString("id"), j.getString("item_id"), j.getString("title"), j.getJSONObject("fields").toString(),
                    j.optString("reason"), Instant.parse(normalizeTs(j.getString("created_at"))).toEpochMilli(), dirty = false,
                ),
            )
        }
        changed = pullTable("jv_chunks", "created_at") { j ->
            if (dao.chunk(j.getString("id"))?.dirty == true) return@pullTable
            dao.upsertChunks(
                listOf(
                    ChunkEntity(
                        j.getString("id"), j.getString("item_id"), j.getString("source_id"), j.optInt("page_no", 1), j.optInt("seq"),
                        j.getString("text"), j.optJSONArray("block_ids")?.toString() ?: "[]", deleted = !j.isNull("deleted_at"), dirty = false,
                    ),
                ),
            )
        } || changed
        return changed
    }

    private fun normalizeTs(s: String): String = java.time.OffsetDateTime.parse(s).toInstant().toString()

    private suspend fun pullTable(table: String, stamp: String, apply: suspend (JSONObject) -> Unit): Boolean {
        var any = false
        while (true) {
            val cursor = settings.cursor(table)
            val filter = if (cursor != null) "&$stamp=gt.${api.enc(cursor)}" else ""
            val rows = api.select(table, "select=*&order=$stamp.asc&limit=500$filter")
            if (rows.length() == 0) return any
            for (i in 0 until rows.length()) {
                val j = rows.getJSONObject(i)
                apply(j)
                settings.setCursor(table, j.getString(stamp))
            }
            any = true
            if (rows.length() < 500) return any
        }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = when (Graph.sync.sync()) {
        is SyncStatus.Failed -> Result.retry()
        else -> Result.success()
    }

    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun syncSoon(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "sync-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online)
                    .setInitialDelay(2, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build(),
            )
        }

        fun schedulePeriodic(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(online).build(),
            )
        }
    }
}
