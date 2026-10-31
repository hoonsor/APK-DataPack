package tw.hoonsor.datapack.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** 資料包設定（jv_packs）。dirty＝本機改過、還沒推上雲端 */
@Entity(tableName = "packs")
data class PackEntity(
    @PrimaryKey val id: String,
    val key: String,
    val name: String,
    val shape: String,
    val config: String,
    val version: Int = 1,
    val enabled: Boolean = true,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

@Entity(tableName = "items", indices = [Index("packKey"), Index("status")])
data class ItemEntity(
    @PrimaryKey val id: String,
    val packKey: String,
    val title: String,
    val fields: String,
    val summary: String = "",
    val searchText: String = "",
    val status: String = STATUS_ACTIVE,
    val confidence: Float = 1f,
    val reviewReasons: String = "[]",
    val dedupeKeys: String = "[]",
    val pageCount: Int = 0,
    val capturedAt: Long,
    val updatedAt: Long = capturedAt,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_REVIEW = "review"
    }
}

@Entity(tableName = "item_versions", indices = [Index("itemId")])
data class ItemVersionEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val title: String,
    val fields: String,
    val reason: String,
    val createdAt: Long,
    val dirty: Boolean = true,
)

/** 一頁來源；localUri 只存在這支手機，雲端只有 localName 與 sha256 */
@Entity(tableName = "sources", indices = [Index("itemId"), Index("sha256")])
data class SourceEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val pageNo: Int = 1,
    val sha256: String = "",
    val phash: String = "",
    val localName: String = "",
    val localUri: String = "",
    val kind: String = KIND_IMAGE,
    val width: Int = 0,
    val height: Int = 0,
    val ocr: String = "",
    val blocks: String = "[]",
    val deleted: Boolean = false,
    val dirty: Boolean = true,
) {
    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_PDF = "pdf"
        const val KIND_VOICE = "voice"
    }
}

@Entity(tableName = "chunks", indices = [Index("itemId"), Index("sourceId")])
data class ChunkEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val sourceId: String,
    val pageNo: Int,
    val seq: Int,
    val text: String,
    val blockIds: String = "[]",
    val deleted: Boolean = false,
    val dirty: Boolean = true,
)

/** 擷取工作：離線也能排隊，連上網再補抽取（F21） */
@Entity(tableName = "jobs", indices = [Index("state")])
data class JobEntity(
    @PrimaryKey val id: String,
    /** 暫存檔路徑，JSON 陣列；圖片通過防呆後才存進相簿 */
    val pages: String,
    val origin: String,
    val state: String = STATE_QUEUED,
    val detail: String = "",
    val forcedPack: String? = null,
    val overrideGuard: Boolean = false,
    val itemId: String? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
) {
    companion object {
        const val STATE_QUEUED = "queued"
        const val STATE_WAIT_NET = "wait_net"
        const val STATE_BLOCKED = "blocked"
        const val STATE_DUPLICATE = "duplicate"
        const val STATE_MERGE = "merge"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"
    }
}

data class PackCount(val packKey: String, val n: Int)

@Dao
interface PackDao {
    // ---------- packs ----------
    @Query("select * from packs where deleted = 0 order by name")
    fun packsFlow(): Flow<List<PackEntity>>

    @Query("select * from packs where deleted = 0")
    suspend fun packs(): List<PackEntity>

    @Query("select * from packs where id = :id")
    suspend fun pack(id: String): PackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPack(p: PackEntity)

    @Query("select * from packs where dirty = 1")
    suspend fun dirtyPacks(): List<PackEntity>

    @Query("update packs set dirty = 0 where id = :id")
    suspend fun cleanPack(id: String)

    // ---------- items ----------
    @Query("select * from items where deleted = 0 and packKey = :pack order by capturedAt desc")
    fun itemsFlow(pack: String): Flow<List<ItemEntity>>

    @Query("select * from items where deleted = 0 order by capturedAt desc limit :limit")
    fun recentFlow(limit: Int): Flow<List<ItemEntity>>

    @Query("select * from items where deleted = 0 and status = 'review' order by capturedAt")
    fun reviewFlow(): Flow<List<ItemEntity>>

    @Query("select packKey, count(*) n from items where deleted = 0 group by packKey")
    fun countsFlow(): Flow<List<PackCount>>

    @Query("select * from items where deleted = 0")
    suspend fun allItems(): List<ItemEntity>

    @Query("select * from items where deleted = 0 and packKey = :pack")
    suspend fun itemsIn(pack: String): List<ItemEntity>

    @Query("select * from items where deleted = 0 and status = 'review' order by capturedAt")
    suspend fun reviewItems(): List<ItemEntity>

    @Query("select * from items where id = :id")
    suspend fun item(id: String): ItemEntity?

    @Query("select * from items where id = :id")
    fun itemFlow(id: String): Flow<ItemEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(i: ItemEntity)

    @Query("select * from items where dirty = 1")
    suspend fun dirtyItems(): List<ItemEntity>

    @Query("update items set dirty = 0 where id = :id and updatedAt = :at")
    suspend fun cleanItem(id: String, at: Long)

    // ---------- versions ----------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVersion(v: ItemVersionEntity)

    @Query("select * from item_versions where itemId = :item order by createdAt desc")
    fun versionsFlow(item: String): Flow<List<ItemVersionEntity>>

    @Query("select * from item_versions where dirty = 1")
    suspend fun dirtyVersions(): List<ItemVersionEntity>

    @Query("update item_versions set dirty = 0 where id = :id")
    suspend fun cleanVersion(id: String)

    // ---------- sources ----------
    @Query("select * from sources where deleted = 0 and itemId = :item order by pageNo")
    suspend fun sourcesOf(item: String): List<SourceEntity>

    @Query("select * from sources where deleted = 0 and itemId = :item order by pageNo")
    fun sourcesFlow(item: String): Flow<List<SourceEntity>>

    @Query("select * from sources where id = :id")
    suspend fun source(id: String): SourceEntity?

    @Query("select * from sources where deleted = 0 and sha256 = :sha limit 1")
    suspend fun sourceBySha(sha: String): SourceEntity?

    @Query("select * from sources where deleted = 0")
    suspend fun allSources(): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(s: SourceEntity)

    @Query("select * from sources where dirty = 1")
    suspend fun dirtySources(): List<SourceEntity>

    @Query("update sources set dirty = 0 where id = :id")
    suspend fun cleanSource(id: String)

    @Query("update sources set deleted = 1, dirty = 1 where itemId = :item")
    suspend fun deleteSourcesOf(item: String)

    // ---------- chunks ----------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunks(c: List<ChunkEntity>)

    @Query("select * from chunks where deleted = 0 and itemId = :item order by pageNo, seq")
    suspend fun chunksOf(item: String): List<ChunkEntity>

    @Query("select * from chunks where id = :id")
    suspend fun chunk(id: String): ChunkEntity?

    @Query("select * from chunks where dirty = 1 limit 200")
    suspend fun dirtyChunks(): List<ChunkEntity>

    @Query("select * from chunks where deleted = 0")
    suspend fun allChunks(): List<ChunkEntity>

    @Query("update chunks set dirty = 0 where id in (:ids)")
    suspend fun cleanChunks(ids: List<String>)

    @Query("update chunks set deleted = 1, dirty = 1 where itemId = :item")
    suspend fun deleteChunksOf(item: String)

    // ---------- jobs ----------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(j: JobEntity)

    @Query("select * from jobs where id = :id")
    suspend fun job(id: String): JobEntity?

    @Query("select * from jobs where state in ('queued', 'wait_net') order by createdAt")
    suspend fun pendingJobs(): List<JobEntity>

    @Query("select * from jobs where state = 'queued' order by createdAt")
    suspend fun queuedJobs(): List<JobEntity>

    @Query("select * from jobs where state not in ('done') order by createdAt desc")
    fun openJobsFlow(): Flow<List<JobEntity>>

    @Query("delete from jobs where id = :id")
    suspend fun deleteJob(id: String)

    // ---------- 全部清空（登出、取代式匯入） ----------
    @Query("delete from packs") suspend fun wipePacks()
    @Query("delete from items") suspend fun wipeItems()
    @Query("delete from item_versions") suspend fun wipeVersions()
    @Query("delete from sources") suspend fun wipeSources()
    @Query("delete from chunks") suspend fun wipeChunks()
}

@Database(
    entities = [PackEntity::class, ItemEntity::class, ItemVersionEntity::class, SourceEntity::class, ChunkEntity::class, JobEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PackDb : RoomDatabase() {
    abstract fun dao(): PackDao

    companion object {
        fun create(context: Context): PackDb = Room.databaseBuilder(context, PackDb::class.java, "datapack.db").build()
    }
}
