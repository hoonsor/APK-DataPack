package tw.hoonsor.datapack.ui

import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.datapack.Graph
import tw.hoonsor.datapack.data.ChunkEntity
import tw.hoonsor.datapack.data.ItemEntity
import tw.hoonsor.datapack.data.SourceEntity
import tw.hoonsor.datapack.domain.BuiltinPacks
import tw.hoonsor.datapack.domain.OcrBlock

/**
 * LAB 預覽模式：模擬器測畫面用，不用登入、不同步，資料全為虛構。
 * adb shell am start -n tw.hoonsor.datapack.lab/tw.hoonsor.datapack.ui.MainActivity --ez preview true
 */
object Preview {
    private val cards = listOf(
        "林雅婷,晨光文具企業,業務經理,02-0000-1101,文具", "吳柏翰,宏遠事務機,技術主任,02-0000-1102,影印機", "陳品妤,順達印刷,客戶專員,04-0000-1103,印刷",
        "張家豪,大安水電行,負責人,02-0000-1104,水電", "黃怡君,青禾會計師事務所,會計師,02-0000-1105,會計", "李承恩,北辰網通,系統工程師,02-0000-1106,網路",
        "王思涵,好時光文具,店長,03-0000-1107,文具", "周育賢,安心保險經紀,業務襄理,07-0000-1108,保險", "蔡佩蓉,綠能冷氣空調,維修專員,02-0000-1109,空調",
        "許志明,大成辦公家具,業務代表,02-0000-1110,家具", "鄭雅文,暖陽牙醫診所,行政主任,02-0000-1111,牙醫", "謝宗翰,捷安資訊,專案經理,02-0000-1112,軟體",
        "郭芷晴,益群文具批發,業務,04-0000-1113,文具", "洪建宏,宏達鎖店,師傅,02-0000-1114,鎖匠", "邱郁婷,明亮眼鏡,驗光師,02-0000-1115,眼鏡",
        "曾俊傑,迅達搬家,調度,02-0000-1116,搬家", "賴怡安,小樹設計,設計師,02-0000-1117,設計", "徐偉倫,光明照明,業務,03-0000-1118,燈具",
        "蘇雅筑,福爾摩沙旅行社,領隊,02-0000-1119,旅遊", "葉書豪,信實律師事務所,律師,02-0000-1120,法律", "呂欣怡,康健物理治療所,治療師,02-0000-1121,復健",
    )

    suspend fun seed() {
        val repo = Graph.repo
        val dao = Graph.db.dao()
        if (dao.allItems().isNotEmpty()) return
        repo.ensureBuiltinPacks()
        val card = BuiltinPacks.all.first { it.key == "business_card" }
        val now = System.currentTimeMillis()
        cards.forEachIndexed { i, line ->
            val (name, company, title, phone, tag) = line.split(",")
            val f = JSONObject().put("name", name).put("company", company).put("title", title).put("phone", JSONArray(listOf(phone))).put("tags", JSONArray(listOf(tag)))
            val low = i == 1
            val item = repo.addItem(card, "$name｜$company", f, "", if (low) ItemEntity.STATUS_REVIEW else ItemEntity.STATUS_ACTIVE, if (low) 0.6 else 0.95, if (low) listOf("「電話」可能有誤") else emptyList(), 1, now - i * 3_600_000L)
            val blocks = listOf(OcrBlock(0, company, 40, 40, 400, 30), OcrBlock(1, name, 40, 90, 200, 50), OcrBlock(2, title, 40, 150, 200, 26), OcrBlock(3, "TEL $phone", 40, 260, 300, 26))
            repo.addSource(SourceEntity(repo.id(), item.id, ocr = blocks.joinToString("\n") { it.text }, blocks = OcrBlock.json(blocks), width = 600, height = 340))
        }
        val manual = BuiltinPacks.all.first { it.key == "manual" }
        val doc = repo.addItem(manual, "某牌除濕機手冊", JSONObject().put("product", "除濕機").put("model", "DH-120"), "濾網兩週清一次，水箱滿會自動停機。", ItemEntity.STATUS_ACTIVE, 0.9, emptyList(), 48, now - 30_000_000L)
        val blocks = listOf(OcrBlock(0, "保養與清潔", 40, 40, 300, 34), OcrBlock(1, "濾網：取下濾網，用清水沖洗灰塵，放在陰涼處完全陰乾，再裝回機器。", 40, 100, 520, 26), OcrBlock(2, "每兩週清潔一次。", 40, 140, 300, 26))
        val src = SourceEntity(repo.id(), doc.id, pageNo = 12, ocr = blocks.joinToString("\n") { it.text }, blocks = OcrBlock.json(blocks), width = 600, height = 800)
        repo.addSource(src)
        repo.addChunks(listOf(ChunkEntity(repo.id(), doc.id, src.id, 12, 0, blocks.joinToString("\n") { it.text }, "[1,2]")))
        val warranty = BuiltinPacks.all.first { it.key == "warranty" }
        repo.addItem(warranty, "變頻冷氣", JSONObject().put("product", "變頻冷氣").put("purchase_date", "2026-05-01").put("warranty_period", "一年").put("warranty_end", "2027-04-30"), "", ItemEntity.STATUS_ACTIVE, 0.92, emptyList(), 1, now - 40_000_000L)
        val health = BuiltinPacks.all.first { it.key == "health_report" }
        val rows = JSONArray(listOf(JSONObject().put("item", "血糖").put("value", "92").put("unit", "mg/dL").put("range", "70-99"), JSONObject().put("item", "總膽固醇").put("value", "210").put("unit", "mg/dL").put("range", "<200").put("flag", "偏高")))
        repo.addItem(health, "2026 年度健檢", JSONObject().put("exam_date", "2026-06-12").put("institution", "示範醫院").put("results", rows), "", ItemEntity.STATUS_REVIEW, 1.0, listOf("這類資料每份都要核對"), 6, now - 50_000_000L)
        val fact = BuiltinPacks.all.first { it.key == "fact" }
        repo.addItem(fact, "保單到期日", JSONObject().put("topic", "保單到期日").put("content", "3 月 15 日到期").put("due_date", "2027-03-15"), "", ItemEntity.STATUS_ACTIVE, 1.0, emptyList(), 0, now - 60_000_000L)
    }
}
