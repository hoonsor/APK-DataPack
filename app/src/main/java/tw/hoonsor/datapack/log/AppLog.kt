package tw.hoonsor.datapack.log

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tw.hoonsor.kit.pii.TwPii
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * APP 內 Log（F23）：環形緩衝給畫面即時顯示，同時寫檔供一鍵匯出。
 * 寫入前遮罩身分證與手機；呼叫端不要把 OCR 全文傳進來。檔案超過 1 MB 輪替一次。
 */
object AppLog {
    data class Line(val time: String, val level: String, val tag: String, val text: String) {
        override fun toString() = "$time $level $tag $text"
    }

    private const val MAX_LINES = 500
    private const val MAX_BYTES = 1_000_000L
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    private val state = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = state.asStateFlow()
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "app.log")
    }

    fun info(tag: String, text: String) = add("I", tag, text)
    fun warn(tag: String, text: String) = add("W", tag, text)
    fun error(tag: String, text: String, e: Throwable? = null) = add("E", tag, if (e == null) text else "$text：${e.javaClass.simpleName} ${e.message}")

    @Synchronized
    private fun add(level: String, tag: String, text: String) {
        val line = Line(LocalDateTime.now().format(fmt), level, tag, TwPii.maskForLog(text).take(500))
        state.value = (state.value + line).takeLast(MAX_LINES)
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "app.1.log"))
            f.appendText(line.toString() + "\n")
        }
    }

    fun exportText(): String {
        val f = file ?: return ""
        val older = File(f.parentFile, "app.1.log")
        return (if (older.exists()) older.readText() else "") + (if (f.exists()) f.readText() else "")
    }
}
