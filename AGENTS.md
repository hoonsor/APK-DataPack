# AGENTS.md — 賈維斯資料包（APK-DataPack，64 號專案）

> 給接手的 AI。不看對話紀錄也要能接手。

## 專案目的
拍下或說出值得留的資料，自動分類、抽取、歸檔，之後用一句話查得到，答案能回到原始照片或頁面核對。
新增資料類型只加設定，不寫程式。合併了原本的「萬能相機」「賈維斯長期記憶」「健康數據語音日誌」。

## 先讀這些（唯一來源）
| 文件 | 內容 |
|---|---|
| `docs/IDEA.md` | 倬的原話、限制 |
| `docs/PRD.md` | 功能編號、決定紀錄（D1–D12）、里程碑 |
| `docs/DATA_MODEL.md` | 資料表、同步、資料包設定格式 |
| `docs/DESIGN.md` | 色票與元件；設計稿連結 |
| `docs/AGENT_API.md`＋`docs/agent-manifest.json` | 語音動作。協定見語音總控 `docs/AGENT_PROTOCOL.md` |
| `docs/MODULE_PLAN.md` | 模組盤點 |
| `supabase/migrations/` | 已套用的 SQL |

## 技術棧
- Kotlin、Compose＋Material3、Room、WorkManager、OkHttp；minSdk 30／targetSdk 35；AGP 8.7.3、Kotlin 2.1.0、Gradle 8.11.1
- ML Kit Document Scanner（Play 服務）、ML Kit Text Recognition v2 中文（內嵌、本機）、PdfBox-Android
- flavor：`prod`（tw.hoonsor.datapack）、`lab`（tw.hoonsor.datapack.lab）
- Supabase 專案 Raphael_Evaluation（共用，表名 `jv_` 前綴，每張表 RLS，`jv_members` 白名單）
- AI：MiniMax 國際版為主、Groq 備援，金鑰存 Keystore
- 電腦網頁：`web/`（Next 16，Supabase Email OTP，無 AI）

## 建置與測試
```powershell
$env:JAVA_HOME="C:\Users\hoonsor\.jdks\jbr-21.0.11"; .\gradlew.bat testProdDebugUnitTest assembleRelease
```
- 不要用 Android Studio 內附的 JBR 25；程式碼放英文路徑
- 改語音動作：`$env:UPDATE_MANIFEST="1"; .\gradlew.bat testProdDebugUnitTest` 重新產生 manifest，同一次工作更新語音總控 `docs/manual/MANUAL.md`
- 預覽模式（模擬器，不用登入、不同步、21 筆以上虛構資料）：
  `adb shell am start -n tw.hoonsor.datapack.lab/tw.hoonsor.datapack.ui.MainActivity --ez preview true`

## 目錄
```
app/src/main/java/tw/hoonsor/
├─ agent/HoonsorAgent.kt   共用 SDK（從語音總控複製，不要在這裡改）
├─ kit/                    預定回收成模組：pii（身分證、手機遮罩）、llm（JSON 修復）
└─ datapack/
   ├─ domain/   純邏輯、有測試：PackConfig、BuiltinPacks、Guard、Extraction、Dedupe、Layout、QueryPlan、Dates
   ├─ data/     Room（Db.kt）、Repository（唯一寫入入口）、Prefs（設定、Keystore）
   ├─ sync/     Supabase（登入、REST）、SyncEngine（push dirty、依 updated_at pull）
   ├─ capture/  Media（OCR、相簿、PDF）、Pipeline（擷取流程與 CaptureWorker）
   ├─ ai/ query/ agent/ backup/ log/ update/
   └─ ui/       MainActivity（導覽與收件）、各畫面、Preview（LAB 預覽資料）
web/            電腦精簡網頁
scripts/        每晚本機匯出
```

## 關鍵設計決策（＋原因）
完整見 `docs/PRD.md` 第 8 節。最常碰到的：
- **公務防呆在送雲端之前**：`Pipeline.process` 先 `Guard.check`，再分類。順序不能換
- **統計與列全在本機算**：`Query.filter` 依欄位篩選，AI 只負責把問句轉成條件。答案沒有有效引用就算查無資料
- **未核對的資料不進回答與統計**
- **來源通知由資料包自己發**：語音總控的 AI 改寫回覆時可能漏掉來源
- **一筆工作只產生一筆資料**：名片多頁掃描時拆成多筆工作（`splitPages`）
- **圖片通過防呆才進相簿**：之前只放 `filesDir/inbox`
- **語音路徑可經 MiniMax 國際版**：倬 2026-10-31 決定，語音總控不改程式

## 絕對不要重踩的坑
- **不要在 Raphael_Evaluation 啟用 `pgroonga`**：一啟用就多 146 MB，移除擴充後檔案還留著，MCP 的 postgres 角色清不掉（見交接紀錄）
- Room 的 DAO 介面不能叫 `Dao`，會跟 `@Dao` 註解撞名
- PowerShell 自訂函式不能叫 `R`（`Invoke-History` 別名）
- ML Kit 文件掃描器要 Play 服務；沒有時走「選檔案」
- OCR 座標以原圖為準，畫高亮要用 `SourceEntity.width` 換算
- 語音總控 `read` 動作的結果會回到 AI 改寫，所以 description 要請它保留來源

## 倬的偏好
繁體中文（台灣）、深色質感、HSL 色票、長按多選、刪除可復原、每個設定有測試按鈕、做不到要直說。完整見 `36-Claude使用流程改善/AI-PROFILE.md`。

## Always／Ask／Never
- **Always**：改程式前 commit；改資料規則先改 `docs/DATA_MODEL.md` 並同步 `web/`；改語音動作遵守相容規則並更新說明書；每版更新 PROJECT_STATUS.md
- **Ask**：改資料表、刪雲端資料、改套件名、換簽章
- **Never**：在 APP 放 service role key；實體刪除資料（一律軟刪除）；讓人事資料或公文進系統；把圖片送雲端；`--force` push
