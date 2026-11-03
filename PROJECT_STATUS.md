# 賈維斯資料包（APK-DataPack）

> 目前版本：v0.1.0 ｜ 最後更新：2026-11-03

## ✅ 任務進度
- [x] PRD 訪談與定案（docs/PRD.md）
- [x] 版面方案，倬選 A 首頁＋B 查詢頁
- [x] 11 個語音動作，倬確認
- [x] MODULE_PLAN（含實際結果）
- [x] Supabase 資料表、RLS、白名單
- [x] 擷取、防呆、抽取、去重、查詢、語音動作、備份、Log、健康檢查、資料包設定編輯
- [x] 單元測試 31 項（APP）、5 項（網頁）；CI 通過
- [x] 模擬器：首頁、查詢、待確認、清單、單筆；OCR 與公務防呆實測（虛構圖片）
- [x] GitHub repo（公開）
- [x] 照片 APP「送到資料包」：已合進 `hoonsor/APK-Photo` main（v1.4.0 測試版，2026-11-03），tag 等實機測過
- [x] 電腦網頁已部署（被 Vercel 部署保護擋住，見下一步）
- [ ] 共用簽章：repo 已加進腳本清單（2026-11-03），等倬執行
- [x] 語音總控說明書：已在 `hoonsor/APK-VoiceHub` main（2026-11-03 合併）
- [ ] 實機：真實名片與說明書走完拍照、清晰化、抽取、語音查詢、回原圖
- [ ] OCR 基準（真實名片 20 張）、查詢基準 30 題
- [ ] 新增第 6 種資料包只加設定（單元測試已過，實機未測）

## 🕘 版本歷程
| 版本 | 日期 | commit | 變更 | 為什麼 |
|---|---|---|---|---|
| v0.1.0 | 2026-10-31 | a119b51 起 | 第一版：5 個資料包、擷取到查詢全流程、電腦網頁 | 倬要拍了就歸檔、用說的查 |

## ➡️ 下一步
1. 倬：關掉 datapack-web 的 Vercel Authentication（Project Settings › Deployment Protection）
2. 倬：在 A 電腦執行 `36-Claude使用流程改善/setup-hoonsor-signing.ps1`（清單已加入本 repo，要輸入既有金鑰密碼）。完成後 `gh secret list -R hoonsor/APK-DataPack` 應有 4 個 `SIGNING_*`
3. AI：確認 Secrets 後重跑 main 的 CI，核對 APK 簽章指紋與其他 APP 相同，再推 `v0.1.0` tag 發正式版（沒有 Secrets 時 CI 會用 debug 簽章，不可發正式版）
4. 倬：手機用 Obtainium 裝正式版（舊的 debug 簽章版要先備份、解除安裝），登入，填 MiniMax 與 Groq 金鑰，掃真實名片與說明書，再用語音總控問「有沒有文具相關的廠商」
5. AI：依實機回報修；量 OCR 基準；照片 APP 實機測過後推 `v1.4.0` tag

## 🐛 已知問題 / 踩坑
- Raphael_Evaluation 留有 146 MB 的 Groonga 殘留檔：資料庫顯示 158 MB，實際資料表 12 MB。擴充已移除，`pgroonga_database_remove()` 跑完大小沒變，MCP 的 postgres 不是超級使用者。不影響功能，只佔 500 MB 額度。沒有重試
- 說明書同型號補拍是接在後面，還沒依印刷頁碼排序，也還沒比「重複頁留較清晰者」，只略過文字完全相同的頁（PRD F09 未完全做到）
- `jv_pack_versions` 表已建，APP 尚未寫入舊版設定
- 直式名片與手寫內容的 OCR 未測
- 語音動作只過 JVM 測試，還沒從語音總控實際呼叫

## ♻️ 可回收候選
<!-- 介面穩定、實機用過後才勾選 -->
- [ ] Kotlin 個資遮罩｜app/src/main/java/tw/hoonsor/kit/pii/TwPii.kt｜MODULE_PLAN 預定：其他 Android APP 的 Log 遮罩（等實機驗證）
- [ ] Kotlin LLM JSON 修復｜app/src/main/java/tw/hoonsor/kit/llm/LlmJson.kt｜MODULE_PLAN 預定：42 號與之後的 Android APP（等實機驗證）

## 🔁 交接紀錄
### 2026-11-03｜電腦 A
- **完成**：簽章腳本清單加入本 repo；APK-Photo 分支快轉合併進 main 並改 v1.4.0；APK-VoiceHub 的說明書已在 main
- **決定＋原因**：v0.1.0 tag 等 Secrets 寫好才推，避免發出 debug 簽章的正式版（語音總控要同簽章才能呼叫）
- **未完成**：倬執行簽章腳本、發正式版、實機
- **環境注意**：簽章腳本在 `D:\01-Project\36-Claude使用流程改善`（不是 Git repo），金鑰只在 A 的 `%USERPROFILE%\hoonsor-signing`

### 2026-10-31｜電腦 A
- **完成**：PRD、版面、語音動作經倬確認；Supabase `jv_` 資料表；APP v0.1.0；照片 APP 分支；電腦網頁部署
- **決定＋原因**：新 APP＋30 號送件（30 號沒有 Gradle 且承諾照片不經網路）；語音與抽取可經 MiniMax 國際版（倬決定）；檢索改 `ILIKE`（`pgroonga` 佔 146 MB）；每晚匯出沿用 62 號
- **放棄＋原因**：`pgroonga`（額度）；向量檢索（多一個供應商，等基準題庫證明需要）；感知雜湊去重（同模板不同人的名片太像，改用 sha256＋比對鍵）；另寫每晚匯出（62 號已涵蓋）
- **未完成**：見「下一步」。完成定義的實機部分都還沒做
- **下一步**：見上
- **環境注意**：`data/raw/` 有 migration 前備份（不進 Git）；模擬器 Medium_Phone_API_37.0 用 LAB 預覽模式測畫面；新環境變數名稱（web）：`NEXT_PUBLIC_SUPABASE_URL`、`NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY`、`AUTH_ALLOWED_EMAILS`；APP 的 AI 金鑰由倬在手機填，不在 repo
- **網址**：https://hoonsor-datapack.vercel.app ；前一版 https://datapack-ipd52knoa-hoonsors-projects.vercel.app
