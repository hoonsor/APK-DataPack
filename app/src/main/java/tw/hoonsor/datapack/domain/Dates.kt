package tw.hoonsor.datapack.domain

import java.time.LocalDate

/** 日期與保固計算。AI 只抽原文，算術一律由這裡做（P7） */
object Dates {
    private val ROC_TEXT = Regex("(?:民國)?\\s*(\\d{2,3})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?")
    private val YMD = Regex("(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})")
    private val ROC_SLASH = Regex("^\\s*(\\d{2,3})\\s*[-/.]\\s*(\\d{1,2})\\s*[-/.]\\s*(\\d{1,2})\\s*$")
    private val CN_DIGITS = mapOf('零' to 0, '一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)

    /** 西元、民國、斜線都轉成 yyyy-MM-dd；看不懂回 null */
    fun normalize(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        YMD.find(s)?.let { m -> return iso(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        ROC_TEXT.find(s)?.let { m -> return iso(m.groupValues[1].toInt() + 1911, m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        ROC_SLASH.find(s)?.let { m -> return iso(m.groupValues[1].toInt() + 1911, m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        return null
    }

    private fun iso(y: Int, m: Int, d: Int): String? = runCatching { LocalDate.of(y, m, d).toString() }.getOrNull()

    private fun number(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        if (s == "十") return 10
        if (s.length == 2 && s[0] == '十') return 10 + (CN_DIGITS[s[1]] ?: return null)
        if (s.length == 2 && s[1] == '十') return (CN_DIGITS[s[0]] ?: return null) * 10
        if (s.length == 3 && s[1] == '十') return (CN_DIGITS[s[0]] ?: return null) * 10 + (CN_DIGITS[s[2]] ?: return null)
        return s.singleOrNull()?.let { CN_DIGITS[it] }
    }

    /** 「一年」「24個月」「兩年半」→ 月數；看不懂回 null */
    fun periodMonths(period: String?): Int? {
        val s = period?.replace(" ", "").orEmpty()
        Regex("([0-9一二兩三四五六七八九十]+)個?年(半)?").find(s)?.let { m ->
            val y = number(m.groupValues[1]) ?: return null
            return y * 12 + if (m.groupValues[2].isNotEmpty()) 6 else 0
        }
        Regex("([0-9一二兩三四五六七八九十]+)個?月").find(s)?.let { m -> return number(m.groupValues[1]) }
        Regex("([0-9]+)\\s*(?:years?|yrs?)", RegexOption.IGNORE_CASE).find(s)?.let { m -> return number(m.groupValues[1])?.times(12) }
        Regex("([0-9]+)\\s*months?", RegexOption.IGNORE_CASE).find(s)?.let { m -> return number(m.groupValues[1]) }
        return null
    }

    /** 保固到期日：單據有寫就用，否則購買日＋保固期（到期前一天） */
    fun warrantyEnd(written: String?, purchase: String?, period: String?): String? {
        normalize(written)?.let { return it }
        val start = normalize(purchase) ?: return null
        val months = periodMonths(period) ?: return null
        return LocalDate.parse(start).plusMonths(months.toLong()).minusDays(1).toString()
    }
}
