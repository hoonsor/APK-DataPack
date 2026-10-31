import "server-only";
// 取得目前登入的成員（成員表見 supabase/migrations/*_app_members.sql）
import type { SupabaseClient } from "@supabase/supabase-js";
import { createClient } from "../supabase/server";
import { HttpError } from "./http";

/** 成員表名稱；換專案時改成自己的前綴（例如 ledger_members） */
export const MEMBERS_TABLE = "jv_members";

export interface Member {
  email: string;
}

/** 讀成員資料；受 RLS 保護，非成員讀不到 */
export async function findMember(supabase: SupabaseClient, email: string): Promise<Member | null> {
  const { data } = await supabase.from(MEMBERS_TABLE).select("email").eq("email", email.toLowerCase()).maybeSingle();
  return (data as Member | null) ?? null;
}

/** API 開頭呼叫：未登入 401、不是成員 403 */
export async function requireMember(): Promise<{ supabase: SupabaseClient; member: Member }> {
  const supabase = await createClient();
  const { data } = await supabase.auth.getClaims();
  const email = (data?.claims?.email as string | undefined)?.toLowerCase();
  if (!email) throw new HttpError(401, "請先登入");
  const member = await findMember(supabase, email);
  if (!member) throw new HttpError(403, "這個帳號不是成員");
  return { supabase, member };
}
