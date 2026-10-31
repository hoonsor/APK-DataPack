# 資料模型

Supabase 是真實來源；手機 Room 是快取＋待推送佇列。網頁與 APP 都依這份，規則改了兩邊一起改。

## 資料表（Raphael_Evaluation，每張表 RLS：本人＋`jv_members`）

| 表 | 一列是 | 重點欄位 |
|---|---|---|
| `jv_members` | 白名單帳號 | `user_id` |
| `jv_packs` | 一個資料包 | `key`、`shape`、`config`（JSON）、`version`、`enabled` |
| `jv_pack_versions` | 設定舊版 | `pack_id`、`version`、`config`（預留，目前 APP 未寫入） |
| `jv_items` | 一筆資料 | `pack_key`、`title`、`fields`、`summary`、`search_text`、`status`（active／review）、`dedupe_keys` |
| `jv_item_versions` | 舊版內容 | `item_id`、`fields`、`reason` |
| `jv_sources` | 一頁來源 | `item_id`、`page_no`、`sha256`、`local_name`、`kind`、`ocr`、`blocks`（文字＋座標） |
| `jv_chunks` | 文件分段 | `item_id`、`source_id`、`page_no`、`text`、`block_ids` |

- 圖片不上雲端。`local_name`＋`sha256` 用來在手機上找回原圖
- 刪除一律軟刪除（`deleted_at`）
- `updated_at` 由伺服器觸發器設定
- 檢索函式 `jv_search_chunks(terms, pack_keys, max_rows)`：`ILIKE`＋命中分數，給網頁用；APP 在本機 Room 上算同樣邏輯，才能離線

## 同步
- 所有寫入走 `Repository`，先寫 Room 標 `dirty`，`SyncWorker` 推送
- 推送用 `POST ...?on_conflict=id`＋`resolution=merge-duplicates`
- 拉取依 `updated_at`（`jv_chunks`、`jv_item_versions` 用 `created_at`）增量；本機 `dirty` 的列不蓋掉
- 內建資料包只在雲端與本機都沒有時才寫入

## 資料包設定（`jv_packs.config`）
格式範例見 `docs/PRD.md` 第 4 節，內建 5 個在 `domain/BuiltinPacks.kt`。

| 鍵 | 用途 |
|---|---|
| `shape` | `fact`、`table`、`document`、`table+document` |
| `classify.hint`、`examples` | 分類線索 |
| `fields[]` | `key`、`label`、`type`、`required`、`searchable`、`repeat`、`extract`、`computed`、`columns` |
| `dedupe` | 比對鍵，例如 `[["name","mobile"],["name","company"]]` |
| `review` | `mode`（`low_confidence`／`always`）、`threshold`、`must_check` |
| `speech` | `row` 唸法範本、`limit` 唸幾筆 |
| `actions` | `call`、`contact` |
| `split_pages` | 一次掃描多頁時每頁各成一筆 |

**新增資料包**：`jv_packs` 多一列，或在 APP 的「新增資料包」填表。新欄位型別或形式才需要改程式。

## 擷取狀態（手機本機 `jobs`）
`queued` → `done`；分支：`wait_net`（等網路或額度）、`blocked`（公務防呆）、`duplicate`（已有）、`merge`（有新資訊待決定）、`failed`（可選資料包重跑）。
