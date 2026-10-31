// 登入 API 的輸入驗證（Zod 4）
import { z } from "zod";

export const EmailSchema = z.object({
  email: z.email("Email 格式不正確").transform((s) => s.toLowerCase()),
});

export const VerifySchema = z.object({
  email: z.email("Email 格式不正確").transform((s) => s.toLowerCase()),
  // Supabase 驗證碼長度可在後台調整（6～10 碼）
  token: z.string().trim().regex(/^\d{6,10}$/, "驗證碼是 6～10 位數字"),
});
