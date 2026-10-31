package tw.hoonsor.kit.pii

/**
 * 台灣個資辨識與遮罩（純 Kotlin，不依賴 Android）。
 * 規則參考 hoonsor-kit 的 tw-pii（Python 版）；預定回收成模組。
 */
object TwPii {
    private val ID = Regex("(?<![A-Za-z0-9])[A-Za-z][12489]\\d{8}(?![A-Za-z0-9])")
    private val MOBILE = Regex("(?<!\\d)09\\d{2}[- ]?\\d{3}[- ]?\\d{3}(?!\\d)")
    private const val LETTERS = "ABCDEFGHJKLMNPQRSTUVXYWZIO"

    /** 身分證字號檢查碼正確才算（含新式統一證號的 8、9 開頭） */
    fun isValidId(raw: String): Boolean {
        val id = raw.uppercase()
        if (!Regex("^[A-Z][12489]\\d{8}$").matches(id)) return false
        val n = LETTERS.indexOf(id[0]) + 10
        var sum = n / 10 + n % 10 * 9
        for (i in 1..8) sum += id[i].digitToInt() * (9 - i)
        sum += id[9].digitToInt()
        return sum % 10 == 0
    }

    fun findIds(text: String): List<String> = ID.findAll(text).map { it.value }.filter { isValidId(it) }.toList()

    /** A123456789 → A1*****789 */
    fun maskIds(text: String): String = ID.replace(text) { m ->
        if (isValidId(m.value)) m.value.take(2) + "*****" + m.value.takeLast(3) else m.value
    }

    /** Log 用：手機只留頭尾 */
    fun maskMobiles(text: String): String = MOBILE.replace(text) { m ->
        val digits = m.value.filter { it.isDigit() }
        digits.take(4) + "***" + digits.takeLast(3)
    }

    fun maskForLog(text: String): String = maskMobiles(maskIds(text))
}
