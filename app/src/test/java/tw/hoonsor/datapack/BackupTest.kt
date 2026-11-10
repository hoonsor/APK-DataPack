package tw.hoonsor.datapack

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.backup.AutoBackup
import tw.hoonsor.datapack.backup.BackupZip
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.zip.ZipInputStream
import kotlin.random.Random

/** hoonsor-kit android-auto-backup 的純邏輯（模組附的測試，改成資料包的名稱） */
class AutoBackupTest {
    @Test fun fileName() {
        val c = Calendar.getInstance().apply { set(2026, 10, 10, 9, 5) }
        assertEquals("DataPack-20261110-0905.zip", AutoBackup.fileName("DataPack", "zip", c.time))
    }

    @Test fun onlyOwnFiles() {
        assertTrue(AutoBackup.isBackupName("DataPack-20261110-0905.zip", "DataPack", "zip"))
        assertTrue(AutoBackup.isBackupName("DataPack-20261110-0905 (1).zip", "DataPack", "zip"))
        assertFalse(AutoBackup.isBackupName("DataPack-LAB-20261110-0905.zip", "DataPack", "zip"))
        // 手動匯出的檔名（Backup.fileName）不會被自動備份刪掉
        assertFalse(AutoBackup.isBackupName("DataPack-backup-20261110-0905.zip", "DataPack", "zip"))
        assertFalse(AutoBackup.isBackupName("DataPack-20261110-0905.json", "DataPack", "zip"))
        assertTrue(AutoBackup.isBackupName("DataPack-LAB-20261110-0905.zip", "DataPack-LAB", "zip"))
    }

    @Test fun keepNewest() {
        val names = (1..9).map { "DataPack-2026110$it-1200.zip" } + "notes.txt"
        val del = AutoBackup.toDelete(names, "DataPack", "zip", 7)
        assertEquals(listOf("DataPack-20261102-1200.zip", "DataPack-20261101-1200.zip"), del)
        assertTrue(AutoBackup.toDelete(names.take(7), "DataPack", "zip", 7).isEmpty())
    }
}

/** 備份 zip：同樣的資料匯出兩次要 bytes 完全相同，自動備份的「內容沒變不另存」才成立 */
class BackupZipTest {
    private val json = """{"schema":1,"app":"tw.hoonsor.datapack","items":[{"id":"a","title":"名片"}]}"""
    private val photoA = Random(1).nextBytes(200_000) // 模擬已壓縮過的 JPEG（隨機資料壓不小）
    private val photoB = Random(2).nextBytes(50_000)
    private val dataAt = 1_793_000_000_000L // 2026-10 某個時間

    private fun images(order: Boolean = true) = listOf(
        BackupZip.Image("b.jpg") { ByteArrayInputStream(photoB) },
        BackupZip.Image("a.jpg") { ByteArrayInputStream(photoA) },
    ).let { if (order) it else it.reversed() }

    private fun zip(imgs: List<BackupZip.Image> = images(), time: Long = dataAt) =
        ByteArrayOutputStream().also { BackupZip.write(it, json, imgs, time) }.toByteArray()

    private fun entries(bytes: ByteArray): List<Triple<String, Long, ByteArray>> {
        val out = mutableListOf<Triple<String, Long, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out += Triple(e.name, e.time, z.readBytes())
            }
        }
        return out
    }

    @Test fun sameDataSameBytes() {
        val first = zip()
        Thread.sleep(2_100) // 跨過 zip 時間的 2 秒精度：若用「現在」當 entry 時間，這裡就會不同
        assertArrayEquals(first, zip())
        // 圖片傳入順序不同也一樣
        assertArrayEquals(first, zip(images(order = false)))
    }

    @Test fun entryTimeIsDataTime() {
        val es = entries(zip())
        assertEquals(listOf("backup.json", "images/a.jpg", "images/b.jpg"), es.map { it.first })
        // DOS 時間精度 2 秒
        es.forEach { assertEquals(dataAt / 2000, it.second / 2000) }
        assertEquals(json, es[0].third.toString(Charsets.UTF_8))
        assertArrayEquals(photoA, es[1].third)
        assertArrayEquals(photoB, es[2].third)
    }

    @Test fun dataChangedBytesChanged() {
        assertFalse(zip().contentEquals(zip(time = dataAt + 60_000)))
        assertFalse(zip().contentEquals(zip(images().take(1))))
    }

    @Test fun skipsMissingAndDuplicateImages() {
        val imgs = images() + BackupZip.Image("gone.jpg") { null } + BackupZip.Image("a.jpg") { ByteArrayInputStream(photoB) } +
            BackupZip.Image("") { ByteArrayInputStream(photoB) } + BackupZip.Image("err.jpg") { error("沒有權限") }
        val es = entries(zip(imgs))
        assertEquals(listOf("backup.json", "images/a.jpg", "images/b.jpg"), es.map { it.first })
    }

    @Test fun imagesNotRecompressed() {
        // 原圖用 NO_COMPRESSION：zip 大小約等於原圖總和，只多一點標頭
        val size = zip().size
        assertTrue("zip $size bytes", size < photoA.size + photoB.size + 2_000)
        assertTrue(size > photoA.size + photoB.size)
    }

    @Test fun entryTimeFallback() {
        assertEquals(BackupZip.EMPTY_TIME, BackupZip.entryTime(emptyList()))
        assertEquals(BackupZip.EMPTY_TIME, BackupZip.entryTime(listOf(0L)))
        assertEquals(dataAt, BackupZip.entryTime(listOf(5L, dataAt, 1_700_000_000_000L)))
    }
}
