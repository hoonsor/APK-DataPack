package tw.hoonsor.datapack.domain

import tw.hoonsor.kit.pii.TwPii

/**
 * 公務防呆（F03、F04）：OCR 後、送雲端前在手機上判斷，公文與人事資料不進這個系統。
 * 判斷要偏保守地擋，誤判可以手動放行；身分證字號單獨出現時只遮罩（健檢、保單常見）。
 */
object Guard {
    data class Result(val blocked: Boolean, val reasons: List<String>, val text: String)

    /** 公文格式詞：單獨一個不算，名片或說明書也可能出現 */
    private val OFFICIAL = listOf("受文者", "發文日期", "發文字號", "速別", "密等", "檔號", "保存年限", "正本：", "副本：", "主旨：", "擬辦", "會辦單位", "決行")
    /** 人事資料詞 */
    private val PERSONNEL = listOf("考績", "獎懲", "俸點", "俸額", "本俸", "敘薪", "銓敘", "年功俸", "公保", "退撫", "員工編號", "人事資料", "差假", "留職停薪", "兼職費")

    fun check(ocr: String, override: Boolean = false): Result {
        val compact = ocr.replace(Regex("[ \\t\\u3000]"), "")
        val official = OFFICIAL.filter { compact.contains(it) }
        val personnel = PERSONNEL.filter { compact.contains(it) }
        val ids = TwPii.findIds(ocr)
        val reasons = mutableListOf<String>()
        if (official.size >= 2) reasons += "看起來是公文（${official.take(3).joinToString("、")}）"
        if (personnel.size >= 2 || (personnel.isNotEmpty() && ids.isNotEmpty())) {
            reasons += "看起來是人事資料（${personnel.take(3).joinToString("、")}）"
        }
        return Result(blocked = reasons.isNotEmpty() && !override, reasons = reasons, text = TwPii.maskIds(ocr))
    }
}
