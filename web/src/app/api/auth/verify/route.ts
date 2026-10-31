// 驗證 Email 驗證碼，成功後由伺服器寫入長效登入 cookie
import { isAllowedEmail } from "@/lib/auth/allowlist";
import { handleError, HttpError, rateLimit } from "@/lib/auth/http";
import { findMember } from "@/lib/auth/member";
import { VerifySchema } from "@/lib/auth/schema";
import { createClient } from "@/lib/supabase/server";

export async function POST(request: Request) {
  try {
    const { email, token } = VerifySchema.parse(await request.json());
    rateLimit(`verify:${email}`, 10, 10 * 60_000);
    if (!isAllowedEmail(email)) throw new HttpError(403, "這個 Email 沒有使用權限");
    const supabase = await createClient();
    const { error } = await supabase.auth.verifyOtp({ email, token, type: "email" });
    if (error) throw new HttpError(400, "驗證碼錯誤或已過期，請重新寄送");

    const member = await findMember(supabase, email);
    if (!member) {
      await supabase.auth.signOut();
      throw new HttpError(403, "這個帳號還沒有加入成員名單");
    }
    return Response.json({ ok: true, member });
  } catch (err) {
    return handleError(err);
  }
}
