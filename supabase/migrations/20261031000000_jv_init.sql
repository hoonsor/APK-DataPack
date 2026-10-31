-- 賈維斯資料包（64 號專案）初始資料表
-- 唯一來源：hoonsor/APK-DataPack supabase/migrations/。共用 Supabase 專案 Raphael_Evaluation，表名一律 jv_ 前綴。
-- 回滾：執行同資料夾的 20261031000000_jv_init.down.sql

create extension if not exists pgroonga with schema extensions;
create schema if not exists jv_private;

-- 成員白名單：這個 Supabase 專案也有其他應用，不能只靠「登入過」
create table public.jv_members (
  user_id uuid primary key references auth.users(id) on delete cascade,
  email text not null unique,
  created_at timestamptz not null default now()
);
alter table public.jv_members enable row level security;
create policy jv_members_self_read on public.jv_members
  for select to authenticated using (user_id = (select auth.uid()));

create or replace function jv_private.is_member() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.jv_members m where m.user_id = (select auth.uid()));
$$;
revoke all on function jv_private.is_member() from public;
grant usage on schema jv_private to authenticated;
grant execute on function jv_private.is_member() to authenticated;

-- updated_at 一律由伺服器設定（同步以伺服器時間為準）
create or replace function jv_private.touch() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

-- 資料包設定：新增資料包只要多一列，不用發版
create table public.jv_packs (
  id uuid primary key,
  owner uuid not null default auth.uid() references auth.users(id) on delete cascade,
  key text not null check (key ~ '^[a-z][a-z0-9_]{1,40}$'),
  name text not null check (char_length(name) between 1 and 40),
  shape text not null check (shape in ('fact', 'table', 'document', 'table+document')),
  config jsonb not null,
  version integer not null default 1,
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);
create unique index jv_packs_key on public.jv_packs (owner, key) where deleted_at is null;
create index jv_packs_sync on public.jv_packs (owner, updated_at);

create table public.jv_pack_versions (
  id uuid primary key,
  owner uuid not null default auth.uid() references auth.users(id) on delete cascade,
  pack_id uuid not null references public.jv_packs(id) on delete cascade,
  version integer not null,
  config jsonb not null,
  created_at timestamptz not null default now(),
  unique (pack_id, version)
);

-- 一筆資料：一張名片、一份說明書、一份健檢報告、一件記住的事
create table public.jv_items (
  id uuid primary key,
  owner uuid not null default auth.uid() references auth.users(id) on delete cascade,
  pack_key text not null,
  title text not null check (char_length(title) between 1 and 300),
  fields jsonb not null default '{}'::jsonb,
  summary text not null default '',
  search_text text not null default '',
  status text not null default 'active' check (status in ('active', 'review')),
  confidence real not null default 1,
  review_reasons jsonb not null default '[]'::jsonb,
  dedupe_keys text[] not null default '{}',
  page_count integer not null default 0,
  captured_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index jv_items_sync on public.jv_items (owner, updated_at);
create index jv_items_pack on public.jv_items (owner, pack_key) where deleted_at is null;
create index jv_items_dedupe on public.jv_items using gin (dedupe_keys);
create index jv_items_search on public.jv_items using pgroonga (search_text);

-- 舊版保留：更新或還原都先存一份
create table public.jv_item_versions (
  id uuid primary key,
  owner uuid not null default auth.uid() references auth.users(id) on delete cascade,
  item_id uuid not null references public.jv_items(id) on delete cascade,
  title text not null,
  fields jsonb not null,
  reason text not null default '',
  created_at timestamptz not null default now()
);
create index jv_item_versions_item on public.jv_item_versions (item_id, created_at desc);

-- 一頁來源：只有文字與座標，圖片留手機
create table public.jv_sources (
  id uuid primary key,
  owner uuid not null default auth.uid() references auth.users(id) on delete cascade,
  item_id uuid not null references public.jv_items(id) on delete cascade,
  page_no integer not null default 1,
  sha256 text not null default '',
  phash text not null default '',
  local_name text not null default '',
  kind text not null default 'image' check (kind in ('image', 'pdf', 'voice')),
  width integer not null default 0,
  height integer not null default 0,
  ocr text not null default '',
  blocks jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index jv_sources_sync on public.jv_sources (owner, updated_at);
create index jv_sources_item on public.jv_sources (item_id, page_no);
create index jv_sources_sha on public.jv_sources (owner, sha256);

-- 文件分段：回答時指到頁面與區塊
create table public.jv_chunks (
  id uuid primary key,
  owner uuid not null default auth.uid() references auth.users(id) on delete cascade,
  item_id uuid not null references public.jv_items(id) on delete cascade,
  source_id uuid not null references public.jv_sources(id) on delete cascade,
  page_no integer not null default 1,
  seq integer not null default 0,
  text text not null,
  block_ids integer[] not null default '{}',
  created_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index jv_chunks_item on public.jv_chunks (item_id, page_no, seq);
create index jv_chunks_search on public.jv_chunks using pgroonga (text);

create trigger jv_packs_touch before insert or update on public.jv_packs for each row execute function jv_private.touch();
create trigger jv_items_touch before insert or update on public.jv_items for each row execute function jv_private.touch();
create trigger jv_sources_touch before insert or update on public.jv_sources for each row execute function jv_private.touch();

-- RLS：白名單成員且只看自己的列
do $$
declare t text;
begin
  foreach t in array array['jv_packs', 'jv_pack_versions', 'jv_items', 'jv_item_versions', 'jv_sources', 'jv_chunks'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format(
      'create policy %I on public.%I for all to authenticated using (owner = (select auth.uid()) and jv_private.is_member()) with check (owner = (select auth.uid()) and jv_private.is_member())',
      t || '_owner', t
    );
    execute format('grant select, insert, update, delete on public.%I to authenticated', t);
  end loop;
end $$;

-- 文件檢索：terms 由 APP 擴展關鍵字後傳入，任一詞命中即可，依分數排序
create or replace function public.jv_search_chunks(terms text[], pack_keys text[] default null, max_rows integer default 8)
returns table (id uuid, item_id uuid, source_id uuid, page_no integer, seq integer, text text, block_ids integer[], title text, pack_key text, score double precision)
language sql stable set search_path = public, extensions, pg_catalog as $$
  select c.id, c.item_id, c.source_id, c.page_no, c.seq, c.text, c.block_ids, i.title, i.pack_key, pgroonga_score(c.tableoid, c.ctid) as score
  from public.jv_chunks c
  join public.jv_items i on i.id = c.item_id
  where c.deleted_at is null and i.deleted_at is null
    and (pack_keys is null or i.pack_key = any(pack_keys))
    and c.text &@~ array_to_string(array(select '"' || replace(t, '"', '') || '"' from unnest(terms) t where t <> ''), ' OR ')
  order by score desc, c.page_no, c.seq
  limit least(greatest(max_rows, 1), 20);
$$;
revoke all on function public.jv_search_chunks(text[], text[], integer) from public, anon;
grant execute on function public.jv_search_chunks(text[], text[], integer) to authenticated;

-- 倬的帳號（與待辦 APP 同一個登入帳號）
insert into public.jv_members (user_id, email)
select user_id, email from public.todo_members on conflict do nothing;
