// 登出（只登出這台裝置）
import { handleError } from "@/lib/auth/http";
import { createClient } from "@/lib/supabase/server";

export async function POST() {
  try {
    const supabase = await createClient();
    await supabase.auth.signOut({ scope: "local" });
    return Response.json({ ok: true });
  } catch (err) {
    return handleError(err);
  }
}
