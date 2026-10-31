// 文件全文檢索（網頁沒有 AI，只回分段與頁碼）
import { handleError, HttpError } from "@/lib/auth/http";
import { requireMember } from "@/lib/auth/member";

export async function GET(request: Request) {
  try {
    const q = new URL(request.url).searchParams.get("q")?.trim() ?? "";
    if (!q) return Response.json({ hits: [] });
    const { supabase } = await requireMember();
    const terms = q.split(/\s+/).slice(0, 8);
    const { data, error } = await supabase.rpc("jv_search_chunks", { terms, pack_keys: null, max_rows: 20 });
    if (error) throw new HttpError(500, error.message);
    return Response.json({ hits: data ?? [] });
  } catch (err) {
    return handleError(err);
  }
}
