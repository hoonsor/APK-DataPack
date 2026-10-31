// 寄出 Email 驗證碼（只寄給白名單）
import { isAllowedEmail } from "@/lib/auth/allowlist";
import { handleError, HttpError, otpErrorMessage, rateLimit } from "@/lib/auth/http";
import { EmailSchema } from "@/lib/auth/schema";
import { createClient } from "@/lib/supabase/server";

export async function POST(request: Request) {
  try {
    const { email } = EmailSchema.parse(await request.json());
    rateLimit(`otp:${email}`, 5, 10 * 60_000);
    if (!isAllowedEmail(email)) throw new HttpError(403, "這個 Email 沒有使用權限");
    const supabase = await createClient();
    const { error } = await supabase.auth.signInWithOtp({ email, options: { shouldCreateUser: true } });
    if (error) throw new HttpError(error.status ?? 400, otpErrorMessage(error));
    return Response.json({ ok: true });
  } catch (err) {
    return handleError(err);
  }
}
