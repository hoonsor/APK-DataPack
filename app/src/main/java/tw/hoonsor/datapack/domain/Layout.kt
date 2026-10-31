package tw.hoonsor.datapack.domain

import org.json.JSONArray
import org.json.JSONObject

/** OCR 的一行文字與座標（像素） */
data class OcrBlock(val id: Int, val text: String, val x: Int, val y: Int, val w: Int, val h: Int) {
    fun toJson(): JSONObject = JSONObject().put("i", id).put("t", text).put("x", x).put("y", y).put("w", w).put("h", h)

    companion object {
        fun from(j: JSONObject) = OcrBlock(j.optInt("i"), j.optString("t"), j.optInt("x"), j.optInt("y"), j.optInt("w"), j.optInt("h"))
        fun list(json: String): List<OcrBlock> = runCatching { JSONArray(json).objects().map { from(it) } }.getOrDefault(emptyList())
        fun json(blocks: List<OcrBlock>): String = JSONArray(blocks.map { it.toJson() }).toString()
    }
}

data class Chunk(val page: Int, val seq: Int, val text: String, val blockIds: List<Int>)

/** 由座標重建閱讀順序與表格列（健檢報告靠這個對齊欄位） */
object Layout {
    /** 垂直重疊過半的行視為同一列，由左到右排 */
    fun rows(blocks: List<OcrBlock>): List<List<OcrBlock>> {
        val out = mutableListOf<MutableList<OcrBlock>>()
        blocks.sortedBy { it.y + it.h / 2 }.forEach { b ->
            val mid = b.y + b.h / 2
            val row = out.lastOrNull()?.takeIf { r ->
                val ref = r.first()
                mid in ref.y..(ref.y + ref.h) || (ref.y + ref.h / 2) in b.y..(b.y + b.h)
            }
            if (row != null) row += b else out += mutableListOf(b)
        }
        return out.map { r -> r.sortedBy { it.x } }
    }

    /** 給 LLM 的文字：同列用 ｜ 分欄，保留表格結構 */
    fun text(blocks: List<OcrBlock>): String = rows(blocks).joinToString("\n") { r -> r.joinToString(" ｜ ") { it.text } }

    /** 分段：約 500 字一段，段與段重疊最後一行，保留區塊 id 才能回到原圖高亮 */
    fun chunks(page: Int, blocks: List<OcrBlock>, plainText: String = "", maxChars: Int = 500): List<Chunk> {
        if (blocks.isEmpty()) return chunkPlain(page, plainText, maxChars)
        val out = mutableListOf<Chunk>()
        var cur = mutableListOf<OcrBlock>()
        var size = 0
        val flush = {
            if (cur.isNotEmpty()) out += Chunk(page, out.size, text(cur), cur.map { it.id })
        }
        rows(blocks).forEach { row ->
            val len = row.sumOf { it.text.length + 1 }
            if (size + len > maxChars && cur.isNotEmpty()) {
                flush()
                val tail = cur.takeLast(1)
                cur = tail.toMutableList()
                size = tail.sumOf { it.text.length }
            }
            cur += row
            size += len
        }
        flush()
        return out
    }

    private fun chunkPlain(page: Int, text: String, maxChars: Int): List<Chunk> {
        val out = mutableListOf<Chunk>()
        val buf = StringBuilder()
        text.lines().filter { it.isNotBlank() }.forEach { line ->
            if (buf.length + line.length > maxChars && buf.isNotEmpty()) {
                out += Chunk(page, out.size, buf.toString().trim(), emptyList())
                buf.clear()
            }
            buf.appendLine(line)
        }
        if (buf.isNotBlank()) out += Chunk(page, out.size, buf.toString().trim(), emptyList())
        return out
    }
}
