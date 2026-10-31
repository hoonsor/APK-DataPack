#!/usr/bin/env node
// 把 dotenvx 加密 .env 裡的變數送上 Vercel（hoonsor-kit vercel-dotenvx-deploy）
//
// 一定要透過 dotenvx 執行，值才會被解密成環境變數：
//   dotenvx run --quiet -- node scripts/vercel-env-push.mjs [--target production] [--only A,B] [--exclude C] [--dry-run]
//
// 安全設計：
// - 只從 .env 讀「變數名稱」，值一律從 process.env 取（dotenvx 已解密）
// - 值只經過 stdin 交給 `vercel env add`，不放命令列參數、不印出、不寫檔
// - 值還是 encrypted: 開頭就中止（表示沒有經過 dotenvx run）
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

const NAME_RE = /^[A-Z_][A-Z0-9_]*$/;
const SKIP = new Set(["DOTENV_PUBLIC_KEY", "DOTENV_PRIVATE_KEY"]);
const TARGETS = new Set(["production", "preview", "development"]);

/** 從 .env 內容取出變數名稱（不碰值） */
export function parseEnvNames(content) {
  const names = [];
  for (const line of content.split(/\r?\n/)) {
    const m = line.match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=/);
    if (m && !SKIP.has(m[1]) && !names.includes(m[1])) names.push(m[1]);
  }
  return names;
}

/** 解析命令列參數 */
export function parseArgs(argv) {
  const opts = { targets: ["production"], only: null, exclude: [], dryRun: false, envFile: ".env" };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      const v = argv[++i];
      if (v === undefined) throw new Error(`${a} 需要參數`);
      return v;
    };
    if (a === "--target") opts.targets = next().split(",").map((s) => s.trim()).filter(Boolean);
    else if (a === "--only") opts.only = next().split(",").map((s) => s.trim()).filter(Boolean);
    else if (a === "--exclude") opts.exclude = next().split(",").map((s) => s.trim()).filter(Boolean);
    else if (a === "--env-file") opts.envFile = next();
    else if (a === "--dry-run") opts.dryRun = true;
    else throw new Error(`不認得的參數：${a}`);
  }
  for (const t of opts.targets) if (!TARGETS.has(t)) throw new Error(`target 只能是 production、preview、development：${t}`);
  return opts;
}

/** 決定要送哪些變數；回傳 { push: 名稱[], problems: 訊息[] }，不回傳任何值 */
export function buildPlan(names, opts, env) {
  const problems = [];
  const selected = names.filter((n) => (opts.only ? opts.only.includes(n) : true) && !opts.exclude.includes(n));
  if (opts.only) for (const n of opts.only) if (!names.includes(n)) problems.push(`${n}：.env 裡沒有這個變數`);
  const push = [];
  for (const n of selected) {
    const v = env[n];
    if (!NAME_RE.test(n)) problems.push(`${n}：名稱格式不合（只能大寫英數與底線）`);
    else if (v === undefined || v === "") problems.push(`${n}：沒有值（是否忘了用 dotenvx run 執行？）`);
    else if (v.startsWith("encrypted:")) problems.push(`${n}：值還是密文，請用 dotenvx run --quiet -- node … 執行`);
    else push.push(n);
  }
  return { push, problems };
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  const envPath = path.resolve(opts.envFile);
  if (!existsSync(envPath)) throw new Error(`找不到 ${envPath}`);
  if (existsSync(path.resolve(".env.local"))) {
    console.warn("⚠ 發現 .env.local：vercel link／env pull 可能寫了明文機密，請確認後自行刪除（本腳本不讀取它）");
  }
  const names = parseEnvNames(readFileSync(envPath, "utf8"));
  const { push, problems } = buildPlan(names, opts, process.env);
  if (problems.length) {
    console.error("中止，以下變數有問題：\n  " + problems.join("\n  "));
    process.exit(1);
  }
  console.log(`目標環境：${opts.targets.join("、")}`);
  console.log(`要送出的變數（只列名稱）：${push.join("、") || "（無）"}`);
  if (opts.dryRun) return;

  let failed = 0;
  for (const target of opts.targets) {
    for (const name of push) {
      // shell: true 才能在 Windows 找到 vercel.cmd；名稱已驗證只含 [A-Z0-9_]，不會被注入
      const r = spawnSync("vercel", ["env", "add", name, target, "--force", "--yes"], {
        input: process.env[name],
        stdio: ["pipe", "ignore", "pipe"],
        shell: process.platform === "win32",
        encoding: "utf8",
      });
      if (r.status === 0) console.log(`  ✓ ${name} → ${target}`);
      else {
        failed++;
        // stderr 不會含值（值只走 stdin），但仍只印第一行
        console.error(`  ✗ ${name} → ${target}：${(r.stderr || r.error?.message || "").split("\n").find(Boolean) ?? "失敗"}`);
      }
    }
  }
  if (failed) process.exit(1);
  console.log("完成。重新部署後才會生效（git push 或在 Vercel 後台 Redeploy）。");
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    main();
  } catch (err) {
    console.error(err instanceof Error ? err.message : err);
    process.exit(1);
  }
}
