import { redirect } from "next/navigation";
import { Workspace } from "@/components/Workspace";
import { requireMember } from "@/lib/auth/member";
import type { Item, Pack } from "@/lib/pack";

const PAGE = 1000;

export default async function Home() {
  const { supabase } = await requireMember().catch(() => redirect("/login"));
  const { data: packs } = await supabase.from("jv_packs").select("id,key,name,shape,config,enabled").is("deleted_at", null).order("key");
  const items: Item[] = [];
  // PostgREST 預設一次 1000 筆，分頁讀完才不會漏
  for (let from = 0; ; from += PAGE) {
    const { data } = await supabase
      .from("jv_items")
      .select("id,pack_key,title,fields,summary,status,captured_at")
      .is("deleted_at", null)
      .order("captured_at", { ascending: false })
      .order("id")
      .range(from, from + PAGE - 1);
    items.push(...((data ?? []) as Item[]));
    if (!data || data.length < PAGE) break;
  }
  return <Workspace packs={(packs ?? []) as Pack[]} initial={items} />;
}
