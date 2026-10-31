package tw.hoonsor.datapack.data

import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.datapack.domain.BuiltinPacks
import tw.hoonsor.datapack.domain.Dedupe
import tw.hoonsor.datapack.domain.Extraction
import tw.hoonsor.datapack.domain.PackConfig
import java.util.UUID

/** 唯一寫入入口：先寫 Room 並標 dirty，再由同步引擎推上雲端 */
class Repository(val db: PackDb, private val changed: () -> Unit) {
    private val dao = db.dao()

    fun id(): String = UUID.randomUUID().toString()

    suspend fun packConfigs(): List<PackConfig> = dao.packs().filter { it.enabled }.mapNotNull { parse(it) }
    suspend fun packConfig(key: String): PackConfig? = dao.packs().firstOrNull { it.key == key }?.let { parse(it) }
    fun parse(p: PackEntity): PackConfig? = runCatching { PackConfig.parse(JSONObject(p.config)) }.getOrNull()

    /** 雲端與本機都沒有設定時寫入內建資料包 */
    suspend fun ensureBuiltinPacks() {
        val have = dao.packs().map { it.key }.toSet()
        if (have.isNotEmpty()) return
        BuiltinPacks.all.forEach { c -> dao.upsertPack(PackEntity(id(), c.key, c.name, c.shape, c.toJson().toString())) }
        changed()
    }

    suspend fun savePack(c: PackConfig, existing: PackEntity?) {
        val version = (existing?.version ?: 0) + 1
        dao.upsertPack(PackEntity(existing?.id ?: id(), c.key, c.name, c.shape, c.toJson().toString(), version, existing?.enabled ?: true))
        changed()
    }

    suspend fun setPackEnabled(p: PackEntity, enabled: Boolean) {
        dao.upsertPack(p.copy(enabled = enabled, dirty = true))
        changed()
    }

    suspend fun addItem(p: PackConfig, title: String, fields: JSONObject, summary: String, status: String, confidence: Double, reasons: List<String>, pageCount: Int, now: Long = System.currentTimeMillis()): ItemEntity {
        val item = ItemEntity(
            id = id(), packKey = p.key, title = title, fields = fields.toString(), summary = summary,
            searchText = Extraction.searchText(p, title, fields, summary), status = status, confidence = confidence.toFloat(),
            reviewReasons = JSONArray(reasons).toString(), dedupeKeys = JSONArray(Dedupe.keys(p, fields)).toString(),
            pageCount = pageCount, capturedAt = now,
        )
        dao.upsertItem(item)
        changed()
        return item
    }

    /** 改內容一律先留舊版，可以還原（F09） */
    suspend fun updateItem(item: ItemEntity, p: PackConfig, title: String, fields: JSONObject, reason: String, status: String = item.status, summary: String = item.summary) {
        snapshot(item, reason)
        Extraction.applyComputed(p, fields)
        dao.upsertItem(
            item.copy(
                packKey = p.key, title = title.ifBlank { Extraction.titleFrom(p, fields) }, fields = fields.toString(), summary = summary,
                searchText = Extraction.searchText(p, title, fields, summary), status = status,
                dedupeKeys = JSONArray(Dedupe.keys(p, fields)).toString(), updatedAt = System.currentTimeMillis(), dirty = true,
                reviewReasons = if (status == ItemEntity.STATUS_ACTIVE) "[]" else item.reviewReasons,
            ),
        )
        changed()
    }

    suspend fun snapshot(item: ItemEntity, reason: String) {
        dao.upsertVersion(ItemVersionEntity(id(), item.id, item.title, item.fields, reason, System.currentTimeMillis()))
    }

    suspend fun confirm(item: ItemEntity) {
        dao.upsertItem(item.copy(status = ItemEntity.STATUS_ACTIVE, reviewReasons = "[]", updatedAt = System.currentTimeMillis(), dirty = true))
        changed()
    }

    /** 軟刪除，10 秒內可 [restore] */
    suspend fun delete(item: ItemEntity) {
        dao.upsertItem(item.copy(deleted = true, updatedAt = System.currentTimeMillis(), dirty = true))
        dao.deleteSourcesOf(item.id)
        dao.deleteChunksOf(item.id)
        changed()
    }

    suspend fun restoreVersion(item: ItemEntity, p: PackConfig, v: ItemVersionEntity) {
        updateItem(item, p, v.title, JSONObject(v.fields), "還原舊版")
    }

    suspend fun addSource(s: SourceEntity) {
        dao.upsertSource(s)
        changed()
    }

    suspend fun addChunks(c: List<ChunkEntity>) {
        if (c.isEmpty()) return
        dao.upsertChunks(c)
        changed()
    }

    suspend fun undelete(item: ItemEntity, sources: List<SourceEntity>, chunks: List<ChunkEntity>) {
        dao.upsertItem(item.copy(deleted = false, updatedAt = System.currentTimeMillis(), dirty = true))
        sources.forEach { dao.upsertSource(it.copy(deleted = false, dirty = true)) }
        dao.upsertChunks(chunks.map { it.copy(deleted = false, dirty = true) })
        changed()
    }

    suspend fun wipeLocal() {
        dao.wipePacks(); dao.wipeItems(); dao.wipeVersions(); dao.wipeSources(); dao.wipeChunks()
    }
}
