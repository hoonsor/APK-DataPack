"use client";

import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { useToast } from "@/components/Toast";
import { applyEdits, display, toCsv, type Fields, type Item, type Pack } from "@/lib/pack";

const PAGE_SIZE = 50;

interface Hit {
  id: string;
  item_id: string;
  page_no: number;
  text: string;
  title: string;
}

function download(name: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

export function Workspace({ packs, initial }: { packs: Pack[]; initial: Item[] }) {
  const [items, setItems] = useState(initial);
  const [tab, setTab] = useState(packs[0]?.key ?? "");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<Item | null>(null);
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [hits, setHits] = useState<Hit[] | null>(null);
  const toast = useToast();
  const router = useRouter();

  const pack = packs.find((p) => p.key === tab);
  const columns = (pack?.config.fields ?? []).filter((f) => f.type !== "rows").slice(0, 6);
  const rows = useMemo(() => {
    const t = q.trim().toLowerCase();
    return items.filter((i) => i.pack_key === tab && (!t || `${i.title} ${JSON.stringify(i.fields)} ${i.summary}`.toLowerCase().includes(t)));
  }, [items, tab, q]);
  const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  const shown = rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);
  const review = items.filter((i) => i.status === "review").length;

  async function save(status?: "active") {
    if (!editing || !pack) return;
    const fields: Fields = applyEdits(pack.config, editing.fields, edits);
    const res = await fetch(`/api/items/${editing.id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title: editing.title, fields, status }),
    });
    if (!res.ok) return toast({ text: "儲存失敗", tone: "error" });
    setItems((all) => all.map((i) => (i.id === editing.id ? { ...i, fields, status: status ?? i.status } : i)));
    setEditing(null);
    setEdits({});
  }

  async function remove(item: Item) {
    const res = await fetch(`/api/items/${item.id}`, { method: "DELETE" });
    if (!res.ok) return toast({ text: "刪除失敗", tone: "error" });
    setItems((all) => all.filter((i) => i.id !== item.id));
    setEditing(null);
    toast({ text: `已刪除「${item.title}」`, tone: "ok", action: { label: "復原", run: () => undo(item) } }, 10_000);
  }

  async function undo(item: Item) {
    await fetch(`/api/items/${item.id}`, { method: "DELETE", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ restore: true }) });
    setItems((all) => [item, ...all]);
  }

  async function searchDocs() {
    const res = await fetch(`/api/search?q=${encodeURIComponent(q)}`);
    const j = await res.json();
    setHits(j.hits ?? []);
  }

  return (
    <main className="wrap">
      <div className="top">
        <div>
          <h1>資料包</h1>
          <p className="sub">
            {items.length} 筆，{review} 筆待確認。原圖只在手機上。
          </p>
        </div>
        <button
          className="pill"
          onClick={async () => {
            await fetch("/api/auth/logout", { method: "POST" });
            router.replace("/login");
          }}
        >
          登出
        </button>
      </div>

      <div className="tabs">
        {packs.map((p) => (
          <button
            key={p.key}
            className="pill"
            data-on={p.key === tab}
            onClick={() => {
              setTab(p.key);
              setPage(0);
              setEditing(null);
            }}
          >
            {p.name} {items.filter((i) => i.pack_key === p.key).length}
          </button>
        ))}
      </div>

      <div className="tabs">
        <input
          aria-label="搜尋"
          className="input"
          style={{ flex: "1 1 280px", width: "auto" }}
          placeholder="搜尋這個資料包"
          value={q}
          onChange={(e) => {
            setQ(e.target.value);
            setPage(0);
          }}
        />
        <button className="pill" onClick={searchDocs}>
          搜尋文件內文
        </button>
        {pack && (
          <button className="pill" onClick={() => download(`${pack.key}.csv`, toCsv(pack.config, rows))}>
            匯出 CSV
          </button>
        )}
      </div>

      {hits && (
        <section>
          <p className="sub">文件內文 {hits.length} 段</p>
          {hits.map((h) => (
            <div key={h.id} className="hit">
              <strong>
                {h.title} 第 {h.page_no} 頁
              </strong>
              {"\n"}
              {h.text}
            </div>
          ))}
        </section>
      )}

      {editing && pack && (
        <section className="panel">
          <strong>{editing.title}</strong>
          {pack.config.fields.map((f) =>
            f.type === "rows" ? (
              <div key={f.key} className="sub">
                {f.label}：
                {((editing.fields[f.key] as Record<string, unknown>[] | undefined) ?? []).map((r) => (f.columns ?? []).map((c) => display(r[c.key])).filter(Boolean).join(" ")).join("；")}
              </div>
            ) : (
              <label key={f.key}>
                {f.label}
                <input className="input" value={edits[f.key] ?? display(editing.fields[f.key])} onChange={(e) => setEdits({ ...edits, [f.key]: e.target.value })} />
              </label>
            ),
          )}
          {editing.summary && <p className="sub">{editing.summary}</p>}
          <div className="actions">
            <button className="pill danger" onClick={() => remove(editing)}>
              刪除
            </button>
            <button className="pill" onClick={() => setEditing(null)}>
              取消
            </button>
            <button className="pill primary" onClick={() => save(editing.status === "review" ? "active" : undefined)}>
              {editing.status === "review" ? "確認" : "儲存"}
            </button>
          </div>
        </section>
      )}

      <div className="tablebox">
        <table className="data">
          <thead>
            <tr>
              <th>名稱</th>
              {columns.map((c) => (
                <th key={c.key}>{c.label}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {shown.map((i) => (
              <tr
                key={i.id}
                className="row"
                onClick={() => {
                  setEditing(i);
                  setEdits({});
                }}
              >
                <td>
                  {i.title} {i.status === "review" && <span className="tag">待確認</span>}
                </td>
                {columns.map((c) => (
                  <td key={c.key}>{display(i.fields[c.key])}</td>
                ))}
              </tr>
            ))}
            {shown.length === 0 && (
              <tr>
                <td colSpan={columns.length + 1} className="sub">
                  沒有資料
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {pages > 1 && (
        <div className="pager">
          <button className="pill" onClick={() => setPage(Math.max(0, page - 1))}>
            上一頁
          </button>
          第 {page + 1} / {pages} 頁
          <button className="pill" onClick={() => setPage(Math.min(pages - 1, page + 1))}>
            下一頁
          </button>
        </div>
      )}
    </main>
  );
}
