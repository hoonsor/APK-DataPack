package tw.hoonsor.datapack.domain

import org.json.JSONArray

/** 第一版內建資料包。第一次登入且雲端沒有設定時寫入，之後一律以資料庫為準 */
object BuiltinPacks {
    private const val JSON = """
[
 {"key":"business_card","name":"名片","shape":"table","split_pages":true,
  "classify":{"hint":"印有人名、公司、職稱與聯絡方式的小卡片","examples":["經理 02-1234-5678 xx有限公司"]},
  "fields":[
   {"key":"name","label":"姓名","type":"text","required":true,"searchable":true},
   {"key":"title","label":"職稱","type":"text","searchable":true},
   {"key":"company","label":"公司","type":"text","searchable":true},
   {"key":"department","label":"部門","type":"text"},
   {"key":"phone","label":"電話","type":"phone","repeat":true},
   {"key":"mobile","label":"手機","type":"phone","repeat":true},
   {"key":"email","label":"Email","type":"email"},
   {"key":"address","label":"地址","type":"text"},
   {"key":"website","label":"網站","type":"text"},
   {"key":"tags","label":"業務標籤","type":"tags","searchable":true,"extract":"推論這家公司賣什麼或做什麼，用 2 到 5 個短詞，例如文具、影印機、辦公用品"}
  ],
  "dedupe":[["name","mobile"],["name","phone"],["name","company"]],
  "review":{"mode":"low_confidence","threshold":0.8,"must_check":["name","phone","mobile"]},
  "speech":{"row":"{company}的{name}，{title}，電話{phone}","limit":5},
  "actions":["call","contact"]},
 {"key":"manual","name":"說明書","shape":"document",
  "classify":{"hint":"軟硬體的使用說明書、操作手冊、安裝指南","examples":["使用說明書 安全注意事項 故障排除"]},
  "fields":[
   {"key":"product","label":"產品","type":"text","required":true,"searchable":true},
   {"key":"model","label":"型號","type":"text","searchable":true},
   {"key":"brand","label":"品牌","type":"text","searchable":true}
  ],
  "dedupe":[["model"],["product"]],
  "review":{"mode":"low_confidence","threshold":0.7,"must_check":[]},
  "speech":{"row":"{product}","limit":3}},
 {"key":"warranty","name":"保固單","shape":"table",
  "classify":{"hint":"保固卡、保證書、購買憑證，上面有產品、購買日或保固期限","examples":["保固書 保固期限 一年 購買日期"]},
  "fields":[
   {"key":"product","label":"品名","type":"text","required":true,"searchable":true},
   {"key":"model","label":"型號","type":"text","searchable":true},
   {"key":"serial","label":"序號","type":"text"},
   {"key":"purchase_date","label":"購買日","type":"date"},
   {"key":"warranty_period","label":"保固期","type":"text","extract":"照單據寫法，例如一年、24個月"},
   {"key":"warranty_end","label":"保固到期日","type":"date","computed":"warranty_end","extract":"單據直接寫出到期日才填，否則留空"},
   {"key":"store","label":"店家","type":"text","searchable":true}
  ],
  "dedupe":[["model","serial"],["product","purchase_date"]],
  "review":{"mode":"low_confidence","threshold":0.8,"must_check":["warranty_end"]},
  "speech":{"row":"{product}保固到{warranty_end}","limit":5}},
 {"key":"health_report","name":"健檢報告","shape":"table+document",
  "classify":{"hint":"健康檢查報告、檢驗報告，有檢驗項目、數值、參考值","examples":["血糖 膽固醇 參考值 檢驗結果"]},
  "fields":[
   {"key":"exam_date","label":"檢查日期","type":"date","required":true},
   {"key":"institution","label":"檢查機構","type":"text","searchable":true},
   {"key":"results","label":"檢驗項目","type":"rows","extract":"報告上每一列檢驗項目都要列出，不可省略，數值照原文",
    "columns":[
     {"key":"item","label":"項目","type":"text","searchable":true},
     {"key":"value","label":"數值","type":"text"},
     {"key":"unit","label":"單位","type":"text"},
     {"key":"range","label":"參考範圍","type":"text"},
     {"key":"flag","label":"異常標記","type":"text","extract":"報告標示偏高、偏低或異常才填，沒有就留空"}
    ]}
  ],
  "dedupe":[["exam_date","institution"]],
  "review":{"mode":"always","threshold":1.0,"must_check":["results"]},
  "speech":{"row":"{exam_date}在{institution}的檢查","limit":3}},
 {"key":"fact","name":"記住","shape":"fact",
  "classify":{"hint":"倬用說的要記住的零散事實，不經過拍照","examples":["保單 3 月 15 日到期"]},
  "fields":[
   {"key":"topic","label":"主題","type":"text","required":true,"searchable":true},
   {"key":"content","label":"內容","type":"text","required":true,"searchable":true},
   {"key":"due_date","label":"到期日","type":"date"}
  ],
  "dedupe":[["topic"]],
  "review":{"mode":"low_confidence","threshold":0,"must_check":[]},
  "speech":{"row":"{topic}：{content}","limit":5}}
]
"""

    val all: List<PackConfig> by lazy { JSONArray(JSON).objects().map { PackConfig.parse(it) } }
}
