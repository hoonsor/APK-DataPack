package tw.hoonsor.datapack.backup

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 備份 zip 的格式（純邏輯、不碰 Android，有 JVM 測試）。
 *
 * 同樣的資料寫兩次要得到完全相同的 bytes，每日自動備份才能用 SHA-256 判斷「內容沒變不另存」：
 * - 每個 entry 的時間固定成 [entryTime]（資料本身的最後修改時間），不用 ZipOutputStream 預設的「現在」
 * - 圖片依名稱排序、同名只放一次
 * - 原圖（JPEG 本來就壓過）用 Deflater.NO_COMPRESSION：不花 CPU 去壓壓不小的東西；
 *   不用 STORED 是因為 STORED 要先算好 CRC 與大小，等於每張圖讀兩次
 */
object BackupZip {
    /** 沒有任何資料時的固定時間：2020-01-01 00:00（UTC） */
    const val EMPTY_TIME = 1_577_836_800_000L

    /** DOS 時間只能表示 1980～2107，超出範圍時退回 [EMPTY_TIME]，避免 ZipEntry 改寫成延伸時間欄位 */
    fun entryTime(dataTimes: Iterable<Long>): Long {
        val t = dataTimes.maxOrNull() ?: return EMPTY_TIME
        return if (t in 315_532_800_000L..4_354_819_199_000L) t else EMPTY_TIME
    }

    /** 一張要放進 zip 的圖片；open 回 null（檔案已被刪掉）就略過 */
    class Image(val name: String, val open: () -> InputStream?)

    fun write(out: OutputStream, json: String, images: List<Image>, entryTime: Long) {
        ZipOutputStream(out).use { z ->
            z.setLevel(Deflater.DEFAULT_COMPRESSION)
            z.putNextEntry(ZipEntry("backup.json").apply { time = entryTime })
            z.write(json.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.setLevel(Deflater.NO_COMPRESSION)
            val seen = HashSet<String>()
            images.filter { it.name.isNotBlank() }.sortedBy { it.name }.forEach { img ->
                if (!seen.add(img.name)) return@forEach
                // 讀不到的圖（被使用者從相簿刪掉）略過，不讓整份備份失敗
                val input = runCatching { img.open() }.getOrNull() ?: return@forEach
                input.use {
                    z.putNextEntry(ZipEntry("images/${img.name}").apply { time = entryTime })
                    it.copyTo(z)
                    z.closeEntry()
                }
            }
        }
    }
}
