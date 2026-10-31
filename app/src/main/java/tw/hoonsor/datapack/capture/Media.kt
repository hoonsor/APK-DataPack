package tw.hoonsor.datapack.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tw.hoonsor.datapack.domain.OcrBlock
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 手機本機的影像、OCR 與 PDF 處理。圖片與文字都不會從這裡離開手機 */
object Media {
    const val FOLDER = "賈維斯資料包"
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    data class Ocr(val blocks: List<OcrBlock>, val width: Int, val height: Int)

    fun inbox(ctx: Context): File = File(ctx.filesDir, "inbox").apply { mkdirs() }

    /** 掃描或分享進來的檔案先放 APP 私有資料夾，通過防呆才進相簿 */
    suspend fun copyToInbox(ctx: Context, uri: Uri, ext: String): File = withContext(Dispatchers.IO) {
        val f = File(inbox(ctx), "${System.nanoTime()}.$ext")
        ctx.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        f
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun ocr(ctx: Context, file: File): Ocr = ocr(InputImage.fromFilePath(ctx, Uri.fromFile(file)))

    suspend fun ocr(bitmap: Bitmap): Ocr = ocr(InputImage.fromBitmap(bitmap, 0))

    private suspend fun ocr(image: InputImage): Ocr = suspendCancellableCoroutine { c ->
        recognizer.process(image)
            .addOnSuccessListener { r ->
                var n = 0
                val blocks = r.textBlocks.flatMap { b -> b.lines }.mapNotNull { line ->
                    val box: Rect = line.boundingBox ?: return@mapNotNull null
                    OcrBlock(n++, line.text, box.left, box.top, box.width(), box.height())
                }
                c.resume(Ocr(blocks, image.width, image.height))
            }
            .addOnFailureListener { c.resumeWithException(it) }
    }

    /** 存進相簿 Pictures/賈維斯資料包/<資料包>，照片 APP 看得到 */
    suspend fun saveImage(ctx: Context, file: File, packName: String, name: String): Uri = withContext(Dispatchers.IO) {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER/$packName")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("無法寫入相簿")
        ctx.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        uri
    }

    suspend fun savePdf(ctx: Context, file: File, packName: String, name: String): Uri = withContext(Dispatchers.IO) {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$packName")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("無法寫入下載資料夾")
        ctx.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        uri
    }

    /** 顯示用，自動套 EXIF 方向，最長邊限制避免 OOM；OCR 座標以原尺寸為準，要另外換算 */
    suspend fun loadImage(ctx: Context, uri: Uri, maxSide: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                val side = maxOf(info.size.width, info.size.height)
                if (side > maxSide) d.setTargetSampleSize(side / maxSide + 1)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    data class PdfPage(val page: Int, val text: String)

    /** 文字層優先；抽不到的頁回空字串，由呼叫端算圖後 OCR */
    suspend fun pdfText(ctx: Context, file: File): List<PdfPage> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        PDDocument.load(file).use { doc ->
            val strip = PDFTextStripper()
            (1..doc.numberOfPages).map { p ->
                strip.startPage = p
                strip.endPage = p
                PdfPage(p, runCatching { strip.getText(doc) }.getOrDefault("").trim())
            }
        }
    }

    suspend fun renderPdfPage(fd: ParcelFileDescriptor, page: Int, maxSide: Int = 2000): Bitmap? = withContext(Dispatchers.IO) {
        PdfRenderer(fd).use { r ->
            if (page !in 1..r.pageCount) return@use null
            r.openPage(page - 1).use { p ->
                val scale = maxSide.toFloat() / maxOf(p.width, p.height)
                val bmp = Bitmap.createBitmap((p.width * scale).toInt(), (p.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }
}
