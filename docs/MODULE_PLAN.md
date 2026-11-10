# MODULE_PLAN：賈維斯資料包

> PRD 版本：v1.0 ｜ 盤點日期：2026-10-31 ｜ 流程：`D:\01-Project\44-共用模組庫\docs\模組優先流程.md`

## 功能對照

| PRD 項目 | 功能 | 來源 | 做法 | 預估改動 | 預定模組化 | 狀態 |
| --- | --- | --- | --- | --- | --- | --- |
| F01 | 掃描、清晰化 | 外部 ML Kit Document Scanner | 依賴套件 | - | 否 | 完成 |
| F02 | 本機 OCR＋區塊座標 | 外部 ML Kit Text Recognition v2（中文） | 依賴套件 | - | 否 | 完成 |
| F03 | 公務防呆 | 自寫 | 新寫（純 Kotlin） | - | 否 | 完成 |
| F04 | 身分證遮罩 | 模組 tw-pii | 參考後自寫（模組是 Python，這裡要 Kotlin） | 重寫 100% | 是 | 完成 |
| F05、F06 | 分類與抽取 | 模組 llm-extract＋專案 42 | 參考 README 的 JSON 修復與坑，Kotlin 重寫；LlmClient 由 42 複製 | 重寫 100% | 是 | 完成 |
| F07 | 待確認並排核對 | 專案 50 | 參考做法後自寫 | - | 否 | 完成 |
| F08、F09 | 改類型、去重 | 自寫 | 新寫 | - | 否 | 完成 |
| F10 | 資料包設定 | 自寫 | 新寫 | - | 否 | 完成 |
| F11 | 查詢 | 自寫＋`ILIKE` | 新寫 | - | 否 | 完成 |
| F12 | 來源核對 | 自寫 | 新寫 | - | 否 | 完成 |
| F13 | 瀏覽與編輯 | 專案 57 | 沿用 UI 元件與 Repository 寫法 | - | 否 | 完成 |
| F14 | 照片 APP 送件 | 專案 30 | 局部修改 | - | 否 | 完成 |
| F15 | 打電話、聯絡人、vCard | 自寫 | 新寫（約 100 行） | - | 否 | 完成 |
| F16 | 匯入 PDF | 外部 PdfBox-Android＋Android PdfRenderer | 依賴套件 | - | 否 | 完成 |
| F17 | 記住 | 自寫 | 新寫 | - | 否 | 完成 |
| F18 | 健檢逐項抽取 | 自寫 | 新寫 | - | 否 | 完成 |
| F19 | 保固到期計算 | 自寫 | 新寫 | - | 否 | 完成 |
| F20 | 電腦網頁 | 模組 supabase-email-otp、pwa-shell、vercel-dotenvx-deploy；參考 todo-web | 安裝 | < 20% | 否 | 完成 |
| F21 | 離線補跑 | 專案 57 | 沿用 WorkManager＋本機佇列 | - | 否 | 完成 |
| F22 | 備份四件套 | 專案 42、57 規範 | 新寫 | - | 否 | 完成 |
| F22 | 每日自動備份（含原圖） | 模組 android-auto-backup 1.0.0 | 安裝＋寫 Source；zip 改成固定輸出（BackupZip） | 小 | BackupZip 可併入模組當範例 | 完成（待實機） |
| F23 | Log | 自寫 | 新寫（Android 無對應模組） | - | 否 | 完成 |
| F24 | 健康檢查、AI 設定 | 專案 42 | 參考後自寫 | - | 否 | 完成 |
| F25 | Agent API | 專案 42 共用 SDK | 複製不修改 | 0% | 否 | 已裝 |
| — | App 內更新 | 專案 57 | 複製，只改 REPO 與 import | < 5% | 否 | 已裝 |

## 外部來源

| 工具 | 網址 | 授權 | 版本或 commit | 用法 | 檢查結果 |
| --- | --- | --- | --- | --- | --- |
| ML Kit Document Scanner | https://developers.google.com/ml-kit/vision/doc-scanner | Google ML Kit 條款 | 16.0.0 | 依賴 | 在 Play 服務內處理，文件未送雲端 |
| ML Kit Text Recognition v2 Chinese | https://developers.google.com/ml-kit/vision/text-recognition/v2 | Google ML Kit 條款 | 16.0.1 | 依賴 | 內嵌模型、本機執行 |
| PdfBox-Android | https://github.com/TomRoush/PdfBox-Android | Apache-2.0 | 2.0.27.0 | 依賴 | 不連網；只取文字層 |

## 預定模組化

| 功能 | 預定路徑 | 第二個使用者 | 專案專屬邏輯怎麼抽 |
| --- | --- | --- | --- |
| Kotlin 個資遮罩（身分證、手機） | `app/src/main/java/tw/hoonsor/kit/pii/` | 其他 Android APP 的 Log 遮罩 | 只收字串，規則全在模組內 |
| Kotlin LLM JSON 修復與抽取 | `app/src/main/java/tw/hoonsor/kit/llm/` | 42 號、之後的 Android APP | LLM 呼叫由參數注入 |

## 實際結果（收工填）

| 模組或功能 | 預估改動 | 實際改動 | 做法有沒有變 | 原因 |
| --- | --- | --- | --- | --- |
| supabase-email-otp | < 20% | member.ts 約 6 行、登入頁 2 行；未改其他檔 | 沒變 | 只換成員表與文案 |
| pwa-shell | < 20% | 只改 appConfig.ts；AppShell 未使用 | 沒變 | 網頁單頁，不需要底部導覽 |
| vercel-dotenvx-deploy | 0% | 0% | 部署改用 CLI，尚未連 GitHub | 網頁在 repo 子資料夾 |
| tw-pii（Kotlin 重寫） | 重寫 | 重寫 | 沒變 | 模組是 Python |
| llm-extract（Kotlin 重寫） | 重寫 | 重寫，只取 JSON 修復與已知坑 | 沒變 | 模組是 TypeScript／Python |
| App 內更新（57 號） | < 5% | 只改 REPO、前綴與 import | 沒變 | — |

F22 的每晚匯出沒有另外寫：62 號已每晚匯出整個 Raphael_Evaluation，含 `jv_` 資料表。
