// 編輯、刪除、還原一筆資料。權限靠 RLS；改內容前先留舊版
import { randomUUID } from "node:crypto";
import { z } from "zod";
import { handleError, HttpError } from "@/lib/auth/http";
import { requireMember } from "@/lib/auth/member";
import { dedupeKeys, searchText, type PackConfig } from "@/lib/pack";

const PatchSchema = z.object({
  title: z.string().trim().min(1).max(300),
  fields: z.record(z.string(), z.unknown()),
  status: z.enum(["active", "review"]).optional(),
});

type Ctx = { params: Promise<{ id: string }> };

export async function PATCH(request: Request, ctx: Ctx) {
  try {
    const { id } = await ctx.params;
    const body = PatchSchema.parse(await request.json());
    const { supabase } = await requireMember();
    const { data: item } = await supabase.from("jv_items").select("*").eq("id", id).maybeSingle();
    if (!item) throw new HttpError(404, "找不到這筆資料");
    const { data: pack } = await supabase.from("jv_packs").select("config").eq("key", item.pack_key).is("deleted_at", null).maybeSingle();
    if (!pack) throw new HttpError(404, "找不到資料包設定");
    const cfg = pack.config as PackConfig;

    const { error: vErr } = await supabase.from("jv_item_versions").insert({ id: randomUUID(), item_id: id, title: item.title, fields: item.fields, reason: "網頁修改" });
    if (vErr) throw new HttpError(500, vErr.message);
    const { error } = await supabase
      .from("jv_items")
      .update({
        title: body.title,
        fields: body.fields,
        search_text: searchText(cfg, body.title, body.fields, item.summary),
        dedupe_keys: dedupeKeys(cfg, body.fields),
        status: body.status ?? item.status,
        ...(body.status === "active" ? { review_reasons: [] } : {}),
      })
      .eq("id", id);
    if (error) throw new HttpError(500, error.message);
    return Response.json({ ok: true });
  } catch (err) {
    return handleError(err);
  }
}

/** 軟刪除；body { restore: true } 是復原 */
export async function DELETE(request: Request, ctx: Ctx) {
  try {
    const { id } = await ctx.params;
    const { restore } = z.object({ restore: z.boolean().optional() }).parse(await request.json().catch(() => ({})));
    const { supabase } = await requireMember();
    const deleted_at = restore ? null : new Date().toISOString();
    const { error } = await supabase.from("jv_items").update({ deleted_at }).eq("id", id);
    if (error) throw new HttpError(500, error.message);
    await supabase.from("jv_sources").update({ deleted_at }).eq("item_id", id);
    await supabase.from("jv_chunks").update({ deleted_at }).eq("item_id", id);
    return Response.json({ ok: true });
  } catch (err) {
    return handleError(err);
  }
}
