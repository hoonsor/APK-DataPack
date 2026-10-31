// 資料包規則（對應 Android domain/Extraction.kt、Dedupe.kt；規則見 docs/DATA_MODEL.md）
export interface FieldDef {
  key: string;
  label: string;
  type?: string;
  repeat?: boolean;
  computed?: string;
  columns?: FieldDef[];
}

export interface PackConfig {
  key: string;
  name: string;
  shape: string;
  fields: FieldDef[];
  dedupe?: string[][];
}

export interface Pack {
  id: string;
  key: string;
  name: string;
  shape: string;
  config: PackConfig;
  enabled: boolean;
}

export type Fields = Record<string, unknown>;

export interface Item {
  id: string;
  pack_key: string;
  title: string;
  fields: Fields;
  summary: string;
  status: "active" | "review";
  captured_at: string;
}

export function display(v: unknown): string {
  if (v === null || v === undefined) return "";
  if (Array.isArray(v)) return v.map(String).join("、");
  return String(v);
}

/** 文字框內容 → 欄位值：repeat 與標籤用頓號切成陣列，空白刪除 */
export function parseValue(f: FieldDef, text: string): unknown {
  const t = text.trim();
  if (!t) return undefined;
  if (f.repeat || f.type === "tags") return [...new Set(t.split(/[,，、;；\n]/).map((s) => s.trim()).filter(Boolean))];
  return t;
}

export function applyEdits(cfg: PackConfig, base: Fields, edits: Record<string, string>): Fields {
  const out: Fields = { ...base };
  for (const f of cfg.fields) {
    if (f.type === "rows" || !(f.key in edits)) continue;
    const v = parseValue(f, edits[f.key]);
    if (v === undefined) delete out[f.key];
    else out[f.key] = v;
  }
  return out;
}

export function searchText(cfg: PackConfig, title: string, fields: Fields, summary: string): string {
  const parts: string[] = [title];
  for (const f of cfg.fields) {
    if (f.type === "rows") {
      for (const row of (fields[f.key] as Record<string, unknown>[] | undefined) ?? []) {
        for (const c of f.columns ?? []) parts.push(display(row[c.key]));
      }
    } else parts.push(display(fields[f.key]));
  }
  parts.push(summary);
  return parts.filter((p) => p.trim()).join(" ");
}

function normalize(f: FieldDef | undefined, v: unknown): string[] {
  const raw = Array.isArray(v) ? v.map(String) : v === null || v === undefined ? [] : [String(v)];
  return raw
    .map((s) => (f?.type === "phone" ? s.replace(/\D/g, "") : s.toLowerCase().replace(/[\s\p{P}\p{S}]/gu, "")))
    .filter(Boolean);
}

/** 與 Android Dedupe.keys 相同的比對鍵 */
export function dedupeKeys(cfg: PackConfig, fields: Fields): string[] {
  const out = new Set<string>();
  (cfg.dedupe ?? []).forEach((rule, i) => {
    const parts = rule.map((k) => normalize(cfg.fields.find((f) => f.key === k), fields[k]).map((v) => `${k}=${v}`));
    if (parts.length === 0 || parts.some((p) => p.length === 0)) return;
    parts.reduce<string[]>((acc, vals) => acc.flatMap((a) => vals.map((v) => (a ? `${a}|${v}` : v))), [""]).forEach((k) => out.add(`${i}:${k}`));
  });
  return [...out];
}

export function toCsv(cfg: PackConfig, items: Item[]): string {
  const cols = cfg.fields.filter((f) => f.type !== "rows");
  const q = (s: string) => `"${s.replace(/"/g, '""')}"`;
  const rows = items.map((i) => cols.map((c) => q(display(i.fields[c.key]))).join(","));
  return "﻿" + [cols.map((c) => q(c.label)).join(","), ...rows].join("\r\n");
}
