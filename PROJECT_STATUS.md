# 賈維斯資料包（APK-DataPack）

> 目前版本：v0.2.0（分支 feat/auto-backup，未發布）｜ 最後更新：2026-11-10

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
- [x] 共用簽章：Secrets 2026-11-03 寫入；APK 簽章 SHA-256 `514ec00e…4e4548825` 與其他 APP 相同；2026-11-06 發布 v0.1.0 正式版
- [x] 語音總控說明書：已在 `hoonsor/APK-VoiceHub` main（2026-11-03 合併）
- [x] 每日自動備份（hoonsor-kit android-auto-backup 1.0.0）：含原圖、zip 輸出固定（JVM 測試通過）
- [ ] 實機：每日自動備份（見交接紀錄 2026-11-10 的驗證清單）
- [ ] 實機：真實名片與說明書走完拍照、清晰化、抽取、語音查詢、回原圖
- [ ] OCR 基準（真實名片 20 張）、查詢基準 30 題
- [ ] 新增第 6 種資料包只加設定（單元測試已過，實機未測）

## 🕘 版本歷程
| 版本 | 日期 | commit | 變更 | 為什麼 |
|---|---|---|---|---|
| v0.2.0 | 2026-11-10 | feat/auto-backup | 每日自動備份到 `Documents/HoonsorBackup/DataPack/`（含原圖，Dropsync 同步到 Dropbox）；備份 zip 輸出固定（entry 時間＝資料時間、各表依 id 排序、原圖不再壓縮） | 原圖只在手機上，手機遺失就沒了；倬要每天自動備份 |
| v0.1.0 | 2026-10-31 | a119b51 起 | 第一版：5 個資料包、擷取到查詢全流程、電腦網頁 | 倬要拍了就歸檔、用說的查 |

## ➡️ 下一步
0. 倬：確認並合併 `feat/auto-backup`（v0.2.0），實機驗證每日自動備份後再打 tag 發版
1. 倬：關掉 datapack-web 的 Vercel Authentication（Project Settings › Deployment Protection）
2. ~~簽章與 v0.1.0 正式版~~（2026-11-06 完成）
3. 倬：Obtainium 新增 `https://github.com/hoonsor/APK-DataPack`，手機用 Obtainium 裝正式版（舊的 debug 簽章版要先備份、解除安裝），登入，填 MiniMax 與 Groq 金鑰，掃真實名片與說明書，再用語音總控問「有沒有文具相關的廠商」
4. AI：依實機回報修；量 OCR 基準；照片 APP 實機測過後推 `v1.4.0` tag

## 🐛 已知問題 / 踩坑
- Raphael_Evaluation 留有 146 MB 的 Groonga 殘留檔：資料庫顯示 158 MB，實際資料表 12 MB。擴充已移除，`pgroonga_database_remove()` 跑完大小沒變，MCP 的 postgres 不是超級使用者。不影響功能，只佔 500 MB 額度。沒有重試
- 說明書同型號補拍是接在後面，還沒依印刷頁碼排序，也還沒比「重複頁留較清晰者」，只略過文字完全相同的頁（PRD F09 未完全做到）
- `jv_pack_versions` 表已建，APP 尚未寫入舊版設定
- 直式名片與手寫內容的 OCR 未測
- 語音動作只過 JVM 測試，還沒從語音總控實際呼叫

## ♻️ 可回收候選
<!-- 介面穩定、實機用過後才勾選 -->
- 已套用模組：android-auto-backup 1.0.0（`app/src/main/java/tw/hoonsor/backup/`，不要在這裡改，要改先改模組庫）
- [ ] 固定輸出的備份 zip（entry 時間固定、圖片不壓縮）｜app/src/main/java/tw/hoonsor/datapack/backup/BackupZip.kt｜其他要備份檔案的 APP 套 android-auto-backup 時都會遇到「zip 每次雜湊不同」，可併入該模組當範例（等實機驗證）
- [ ] Kotlin 個資遮罩｜app/src/main/java/tw/hoonsor/kit/pii/TwPii.kt｜MODULE_PLAN 預定：其他 Android APP 的 Log 遮罩（等實機驗證）
- [ ] Kotlin LLM JSON 修復｜app/src/main/java/tw/hoonsor/kit/llm/LlmJson.kt｜MODULE_PLAN 預定：42 號與之後的 Android APP（等實機驗證）

## 🔁 交接紀錄
### 2026-11-10｜電腦 A
- **完成**：套用 android-auto-backup（worktree `D:\dev\APK-DataPack-backup`，分支 `feat/auto-backup`，未 push）。`DataPackBackupSource`（appName `DataPack`／LAB `DataPack-LAB`、zip、含原圖）；Manifest 登記 meta-data 與 `BackupJobService`；`App.onCreate` 呼叫 `ensureScheduled`；設定 › 備份與還原加自動備份區塊；版本 0.2.0
- **決定＋原因**：
  - 備份核心本來就寫到 OutputStream（`writeZip`），直接加 `Backup.writeAuto(ctx, out)`（`runBlocking(Dispatchers.IO)`），不用暫存檔再複製；模組自己會先寫到 cacheDir 再算雜湊
  - zip 每次 bytes 相同：entry 時間固定成「資料最後修改時間」（items 的 max(updatedAt, capturedAt)，沒有資料時用 2020-01-01）；自動備份不寫 `exportedAt`（改寫 `dataAt`）；各表與圖片依 id／名稱排序、同名圖只放一次。否則每天雜湊都不同，每天多一份大檔
  - 原圖用 `Deflater.NO_COMPRESSION`（不用 STORED：STORED 要先算 CRC 與大小，每張圖要讀兩次）。JPEG 再壓也小不了，省 CPU 與電
  - 讀圖中途出錯改成整份失敗（以前會吞掉例外，可能寫出壞掉的 zip）；打不開的圖（已從相簿刪掉）仍略過
- **待倬確認**：自動備份含原圖、經 Dropsync 上 Dropbox，和 AGENTS.md「Never：把圖片送雲端」、README「圖片只留在手機」字面上衝突（名片、健康報告的照片會到 Dropbox）。這次依倬的需求做了，預設開；若確認要這樣，請把 AGENTS.md 的規則改成「不送 Supabase／AI，只到倬自己的 Dropbox 備份」
- **未完成**：
  - 匯入只還原文字資料，不會把 zip 裡的 `images/` 放回相簿、也不會補 `localUri`（換機後原圖要手動解壓）
  - 實機驗證：① 設定頁看到狀態，按「立即備份」，`Documents/HoonsorBackup/DataPack/` 出現 zip ② 不改資料再等排程或關開開關，確認沒多一份 ③ Dropsync 有上傳 ④ 用該 zip「從檔案匯入」 ⑤ 原圖多時的檔案大小與耗時（cacheDir 會暫時多一份同大小的檔）
- **環境注意**：APP 升版時 `version` 欄位會變，會多存一份（正常）；手動「立即備份」一律另存一份

### 2026-11-06｜電腦 A
- **完成**：確認 Secrets；main 重跑 CI 後核對 APK 簽章與共用金鑰相同；發布 v0.1.0 正式版（Releases 有正式版與 LAB 版 APK）
- **坑**：tag 打在 `[skip ci]` commit 上不會觸發建置，改用 `gh workflow run build.yml --ref v0.1.0` 補跑（不用刪 tag）
- **未完成**：實機（倬）

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
