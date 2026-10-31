import { describe, expect, it } from "vitest";
import { applyEdits, dedupeKeys, searchText, toCsv, type Item, type PackConfig } from "./pack";

const card: PackConfig = {
  key: "business_card",
  name: "名片",
  shape: "table",
  fields: [
    { key: "name", label: "姓名" },
    { key: "phone", label: "電話", type: "phone", repeat: true },
    { key: "tags", label: "標籤", type: "tags" },
  ],
  dedupe: [["name", "phone"]],
};

describe("資料包規則", () => {
  it("比對鍵與 Android 相同：電話只留數字、姓名去空白", () => {
    expect(dedupeKeys(card, { name: "王 小明", phone: ["0912-345-678"] })).toEqual(["0:name=王小明|phone=0912345678"]);
  });

  it("缺欄位時不產生比對鍵", () => {
    expect(dedupeKeys(card, { name: "王小明" })).toEqual([]);
  });

  it("編輯：多值切成陣列，空白刪除欄位", () => {
    const out = applyEdits(card, { name: "王", tags: ["a"] }, { phone: "02-1、02-2", tags: "" });
    expect(out).toEqual({ name: "王", phone: ["02-1", "02-2"] });
  });

  it("搜尋文字包含多列欄位", () => {
    const cfg: PackConfig = { ...card, fields: [{ key: "results", label: "項目", type: "rows", columns: [{ key: "item", label: "項目" }] }] };
    expect(searchText(cfg, "健檢", { results: [{ item: "血糖" }] }, "")).toBe("健檢 血糖");
  });

  it("CSV 有 BOM 且跳脫引號", () => {
    const item = { fields: { name: '王"小明' } } as unknown as Item;
    expect(toCsv(card, [item])).toContain('"王""小明"');
    expect(toCsv(card, []).charCodeAt(0)).toBe(0xfeff);
  });
});
