package tw.hoonsor.datapack

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.datapack.domain.BuiltinPacks
import tw.hoonsor.datapack.domain.Dates
import tw.hoonsor.datapack.domain.Dedupe
import tw.hoonsor.datapack.domain.Extraction
import tw.hoonsor.datapack.domain.Filter
import tw.hoonsor.datapack.domain.Guard
import tw.hoonsor.datapack.domain.Layout
import tw.hoonsor.datapack.domain.OcrBlock
import tw.hoonsor.datapack.domain.PackConfig
import tw.hoonsor.datapack.domain.Query
import tw.hoonsor.datapack.domain.QueryPlan
import tw.hoonsor.datapack.domain.Row
import tw.hoonsor.kit.llm.LlmJson
import tw.hoonsor.kit.pii.TwPii
import java.time.LocalDate

class DomainTest {
    private val card = BuiltinPacks.all.first { it.key == "business_card" }
    private val warranty = BuiltinPacks.all.first { it.key == "warranty" }
    private val health = BuiltinPacks.all.first { it.key == "health_report" }

    // 依檢查碼規則計算的虛構號碼
    private val validId = "A123456789"

    @Test fun builtinPacksAreValid() {
        assertEquals(5, BuiltinPacks.all.size)
        BuiltinPacks.all.forEach { assertEquals(it.key, emptyList<String>(), it.problems()) }
    }

    @Test fun configRoundTrip() {
        BuiltinPacks.all.forEach { p ->
            assertEquals(p, PackConfig.parse(p.toJson()))
        }
    }

    @Test fun newPackOnlyNeedsConfig() {
        val j = JSONObject("""{"key":"book","name":"藏書","shape":"table","fields":[{"key":"title","label":"書名","required":true}],"dedupe":[["title"]]}""")
        val p = PackConfig.parse(j)
        assertTrue(p.problems().isEmpty())
        val e = Extraction.parseExtraction("""{"title":"原子習慣","fields":{"title":"原子習慣"},"confidence":{"title":0.95}}""", p)
        assertEquals("原子習慣", e.fields.getString("title"))
        assertEquals(listOf("0:title=原子習慣"), Dedupe.keys(p, e.fields))
    }

    @Test fun badConfigReported() {
        val p = PackConfig("Bad Key", "", "x", dedupe = listOf(listOf("nope")))
        assertTrue(p.problems().size >= 3)
    }

    @Test fun piiValidateAndMask() {
        assertTrue(TwPii.isValidId(validId))
        assertFalse(TwPii.isValidId("A123456788"))
        assertEquals("證號 A1*****789 結束", TwPii.maskIds("證號 $validId 結束"))
        assertEquals("電話 0912***678", TwPii.maskMobiles("電話 0912-345-678"))
        assertEquals("A123456788", TwPii.maskIds("A123456788"))
    }

    @Test fun guardBlocksOfficialDocument() {
        val r = Guard.check("受文者：本校\n發文日期：中華民國115年1月1日\n主旨：函轉")
        assertTrue(r.blocked)
        assertTrue(r.reasons.first().contains("公文"))
        assertFalse(Guard.check("受文者：本校\n發文日期：115年\n主旨：", override = true).blocked)
    }

    @Test fun guardBlocksPersonnelAndMasksId() {
        assertTrue(Guard.check("考績 甲等 本俸 年功俸").blocked)
        assertTrue(Guard.check("身分證 $validId 考績").blocked)
        val ok = Guard.check("健康檢查報告 身分證 $validId 血糖 90")
        assertFalse(ok.blocked)
        assertFalse(ok.text.contains(validId))
    }

    @Test fun guardLetsNormalCardsThrough() {
        assertFalse(Guard.check("晨光文具企業 業務經理 林雅婷 02-0000-1101").blocked)
        assertFalse(Guard.check("使用說明書 主旨：本產品安全須知").blocked)
    }

    @Test fun datesNormalize() {
        assertEquals("2026-03-15", Dates.normalize("115年3月15日"))
        assertEquals("2026-03-15", Dates.normalize("民國115年03月15日"))
        assertEquals("2026-03-15", Dates.normalize("2026/3/15"))
        assertEquals("2026-03-15", Dates.normalize("115/03/15"))
        assertNull(Dates.normalize("不知道"))
        assertNull(Dates.normalize("2026-13-40"))
    }

    @Test fun warrantyEndComputed() {
        assertEquals("2027-03-14", Dates.warrantyEnd(null, "2026-03-15", "一年"))
        assertEquals("2028-03-14", Dates.warrantyEnd(null, "115/03/15", "24個月"))
        assertEquals("2028-09-14", Dates.warrantyEnd("", "2026-03-15", "兩年半"))
        assertEquals("2030-01-01", Dates.warrantyEnd("2030/1/1", "2026-03-15", "一年"))
        assertNull(Dates.warrantyEnd(null, "2026-03-15", "終身"))
        assertNull(Dates.warrantyEnd(null, null, "一年"))
        assertEquals(36, Dates.periodMonths("3 years"))
        assertEquals(120, Dates.periodMonths("十年"))
    }

    @Test fun jsonRepairHandlesCommonFailures() {
        assertEquals(1, LlmJson.parseObject("<think>想一下</think>```json\n{\"a\":1}\n```").getInt("a"))
        assertEquals(2, LlmJson.parseObject("結果：{\"a\":1,\"b\":2,}").getInt("b"))
        assertEquals(1, LlmJson.parseObject("{\"a\":1,\"list\":[{\"x\":1},{\"x\":2").getInt("a"))
        assertEquals(2, LlmJson.parseArray("[1,2,]").length())
    }

    @Test fun extractCardNormalizesFields() {
        val e = Extraction.parseExtraction(
            """{"title":"林雅婷｜晨光","fields":{"name":"林雅婷","phone":"02-0000-1101、02-0000-1102","tags":"文具,辦公用品","unknown":"x"},"confidence":{"name":0.99,"phone":0.6}}""",
            card,
        )
        assertEquals(2, e.fields.getJSONArray("phone").length())
        assertEquals(2, e.fields.getJSONArray("tags").length())
        assertFalse(e.fields.has("unknown"))
        assertEquals(0.6, e.overall, 0.001)
        assertTrue(Extraction.reviewReasons(card, e, 0.95).any { it.contains("電話") })
    }

    @Test fun highConfidenceCardSkipsReview() {
        val e = Extraction.parseExtraction("""{"fields":{"name":"王","phone":"021"},"confidence":{"name":0.99,"phone":0.95}}""", card)
        assertEquals(emptyList<String>(), Extraction.reviewReasons(card, e, 0.95))
    }

    @Test fun missingRequiredGoesToReview() {
        val e = Extraction.parseExtraction("""{"fields":{"company":"晨光"},"confidence":{"company":0.99}}""", card)
        assertTrue(Extraction.reviewReasons(card, e, 0.95).any { it.contains("姓名") })
    }

    @Test fun healthReportAlwaysReviewedAndRowsKept() {
        val e = Extraction.parseExtraction(
            """{"fields":{"exam_date":"115/06/12","institution":"某醫院","results":[{"item":"血糖","value":"92","unit":"mg/dL","range":"70-99"},{"item":"膽固醇","value":"210","flag":"偏高"}]},"confidence":{"exam_date":1,"institution":1,"results":1}}""",
            health,
        )
        assertEquals("2026-06-12", e.fields.getString("exam_date"))
        assertEquals(2, e.fields.getJSONArray("results").length())
        assertTrue(Extraction.reviewReasons(health, e, 0.99).isNotEmpty())
        assertTrue(Extraction.searchText(health, "x", e.fields, "").contains("膽固醇"))
    }

    @Test fun warrantyEndFilledByRuleNotAi() {
        val e = Extraction.parseExtraction("""{"fields":{"product":"冷氣","purchase_date":"2026-05-01","warranty_period":"一年","warranty_end":""},"confidence":{"product":1}}""", warranty)
        assertEquals("2027-04-30", e.fields.getString("warranty_end"))
    }

    @Test fun dedupeKeysMatchAcrossFormatting() {
        val a = JSONObject().put("name", "王 小明").put("mobile", JSONArray(listOf("0912-345-678")))
        val b = JSONObject().put("name", "王小明").put("mobile", JSONArray(listOf("0912345678", "0988000111")))
        assertTrue(Dedupe.overlaps(Dedupe.keys(card, a), Dedupe.keys(card, b)))
        val c = JSONObject().put("name", "王小明").put("mobile", JSONArray(listOf("0900000000")))
        assertFalse(Dedupe.overlaps(Dedupe.keys(card, a), Dedupe.keys(card, c)))
    }

    @Test fun dedupeChangesIgnoreMissingNewValues() {
        val old = JSONObject().put("name", "王").put("title", "經理").put("company", "A")
        val new = JSONObject().put("name", "王").put("title", "協理")
        val c = Dedupe.changes(card, old, new)
        assertEquals(listOf("title"), c.map { it.key })
        val merged = Dedupe.merge(card, old, new, setOf("title"))
        assertEquals("協理", merged.getString("title"))
        assertEquals("A", merged.getString("company"))
    }

    private fun b(id: Int, t: String, x: Int, y: Int, w: Int = 100, h: Int = 20) = OcrBlock(id, t, x, y, w, h)

    @Test fun layoutRebuildsTableRows() {
        val blocks = listOf(b(1, "92", 300, 102), b(2, "血糖", 20, 100), b(3, "膽固醇", 20, 140), b(4, "210", 300, 141))
        assertEquals("血糖 ｜ 92\n膽固醇 ｜ 210", Layout.text(blocks))
    }

    @Test fun chunksKeepBlockIdsAndOverlap() {
        val blocks = (1..40).map { b(it, "第${it}行".padEnd(30, '字'), 10, it * 30) }
        val chunks = Layout.chunks(3, blocks, maxChars = 200)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.page == 3 && it.blockIds.isNotEmpty() })
        assertTrue(chunks[1].blockIds.contains(chunks[0].blockIds.last()))
        assertEquals(blocks.map { it.id }.toSet(), chunks.flatMap { it.blockIds }.toSet())
    }

    @Test fun chunksFromPdfTextLayer() {
        val chunks = Layout.chunks(2, emptyList(), "第一段\n第二段")
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].text.contains("第二段"))
    }

    private val today = LocalDate.of(2026, 10, 31)
    private fun row(id: String, pack: String, vararg kv: Pair<String, Any>) =
        Row(id, pack, id, JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } })

    @Test fun filterListsEverythingWithExactCount() {
        val rows = (1..30).map { row("c$it", "business_card", "company" to if (it % 3 == 0) "晨光文具" else "大安水電") }
        val plan = QueryPlan(listOf("business_card"), listOf("文具", "辦公用品"))
        assertEquals(10, Query.filter(rows, { it.fields.toString() }, plan, today).size)
    }

    @Test fun beforeDaysFindsExpiringWarranty() {
        val rows = listOf(
            row("a", "warranty", "warranty_end" to "2026-11-20"),
            row("b", "warranty", "warranty_end" to "2027-11-20"),
            row("c", "warranty", "warranty_end" to "2026-01-01"),
        )
        val plan = QueryPlan(listOf("warranty"), emptyList(), listOf(Filter("warranty_end", "before_days", "90")))
        assertEquals(listOf("a"), Query.filter(rows, { "" }, plan, today).map { it.id })
    }

    @Test fun sortByDateDesc() {
        val rows = listOf(row("a", "w", "d" to "2026-01-01"), row("b", "w", "d" to "2026-03-01"))
        val plan = QueryPlan(listOf("w"), emptyList(), sort = "d desc")
        assertEquals("b", Query.filter(rows, { "" }, plan, today).first().id)
    }

    @Test fun fallbackPlanWorksWithoutAi() {
        val p = Query.fallbackPlan("有沒有文具相關的廠商", BuiltinPacks.all)
        assertEquals(listOf("business_card"), p.packs)
        assertTrue(p.keywords.any { it.contains("文具") })
    }

    @Test fun fallbackPlanDropsFieldNames() {
        val p = Query.fallbackPlan("會計師電話", BuiltinPacks.all)
        assertEquals(listOf("business_card"), p.packs)
        assertEquals(listOf("會計師"), p.keywords)
    }

    @Test fun parsePlanDropsUnknownPacksAndOps() {
        val p = Query.parsePlan("""{"packs":["business_card","ghost"],"keywords":["文具"],"filters":[{"field":"x","op":"drop","value":"1"}]}""", BuiltinPacks.all, "q")
        assertEquals(listOf("business_card"), p.packs)
        assertTrue(p.filters.isEmpty())
    }

    @Test fun speakSkipsEmptySegments() {
        val r = row("x", "business_card", "company" to "晨光文具", "name" to "林雅婷", "phone" to JSONArray(listOf("02-1", "02-2")))
        assertEquals("晨光文具的林雅婷，電話02-1", Query.speak(card, r))
    }

    @Test fun answerWithoutValidCitationIsNotFound() {
        assertFalse(Query.parseAnswer("""{"found":true,"answer":"猜的","cite":[9]}""", setOf(1, 2)).found)
        val a = Query.parseAnswer("""{"found":true,"answer":"水洗陰乾","cite":[1]}""", setOf(1, 2))
        assertTrue(a.found)
        assertEquals(listOf(1), a.cite)
        assertFalse(Query.parseAnswer("""{"found":false,"answer":"","cite":[]}""", setOf(1)).found)
    }
}
