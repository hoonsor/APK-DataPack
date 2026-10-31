# 語音動作（Agent API）

協定見語音總控 `docs/AGENT_PROTOCOL.md`，這裡只列本 APP 的動作。機器可讀版：`docs/agent-manifest.json`。

| 動作 | 用途 | risk | 參數 | 回傳 |
|---|---|---|---|---|
| `pack_list` | 資料包與筆數 | read | — | `data.packs[]`、`data.review` |
| `pack_query` | 查資料，附來源 | read（30 秒） | `question` | `summary` 含來源；`data.rows[]`、`data.sources[]` |
| `pack_source_open` | 開上一個答案的原圖 | read | — | `NEEDS_UI` |
| `review_list` | 待確認筆數 | read | — | `data.items[]` |
| `capture_open` | 開掃描 | read | — | `NEEDS_UI` |
| `contact_call` | 從名片開撥號畫面 | read | `name` | `NEEDS_UI`；多筆回 `AMBIGUOUS` |
| `fact_remember` | 記住或更新事實 | reversible | `topic`、`content`、`due_date?` | `undo`＝`fact_forget` |
| `fact_forget` | 忘記事實 | destructive | `topic` | 先 `NEEDS_CONFIRMATION` |
| `item_delete` | 刪一筆資料 | destructive | `title` | 先 `NEEDS_CONFIRMATION` |
| `backup_export` | 開備份畫面 | reversible | — | `NEEDS_UI` |
| `backup_import` | 開還原畫面 | destructive | — | 先確認，再 `NEEDS_UI` |

例句見 `docs/agent-manifest.json`。
`pack_query` 同時由資料包發一則「看原圖」通知，所以語音總控改寫回覆時漏掉來源也還能回到原圖。
