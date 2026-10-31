-- 移除 pgroonga：一啟用資料庫就從 12 MB 變 158 MB（500 MB 免費額度的 30%），而本專案資料量只有數千段。
-- 改用 ILIKE＋命中分數：中文子字串精確、不用分詞、不需要索引也夠快。
drop index if exists public.jv_items_search;
drop index if exists public.jv_chunks_search;
drop function if exists public.jv_search_chunks(text[], text[], integer);
drop extension if exists pgroonga;

create or replace function public.jv_search_chunks(terms text[], pack_keys text[] default null, max_rows integer default 8)
returns table (id uuid, item_id uuid, source_id uuid, page_no integer, seq integer, text text, block_ids integer[], title text, pack_key text, score double precision)
language sql stable set search_path = public, pg_catalog as $$
  with q as (
    select array_agg('%' || replace(replace(replace(t, '\', '\\'), '%', '\%'), '_', '\_') || '%') as pats
    from unnest(terms) t where length(trim(t)) > 0
  )
  select c.id, c.item_id, c.source_id, c.page_no, c.seq, c.text, c.block_ids, i.title, i.pack_key,
    (select count(*)::double precision from unnest(q.pats) p where c.text ilike p or i.title ilike p) as score
  from public.jv_chunks c
  join public.jv_items i on i.id = c.item_id
  cross join q
  where c.deleted_at is null and i.deleted_at is null
    and (pack_keys is null or i.pack_key = any(pack_keys))
    and (c.text ilike any(q.pats) or i.title ilike any(q.pats))
  order by score desc, c.page_no, c.seq
  limit least(greatest(max_rows, 1), 20);
$$;
revoke all on function public.jv_search_chunks(text[], text[], integer) from public, anon;
grant execute on function public.jv_search_chunks(text[], text[], integer) to authenticated;
