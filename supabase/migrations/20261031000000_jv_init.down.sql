-- 回滾 20261031000000_jv_init.sql。會刪掉所有 jv_ 資料，執行前先匯出。
drop function if exists public.jv_search_chunks(text[], text[], integer);
drop table if exists public.jv_chunks;
drop table if exists public.jv_sources;
drop table if exists public.jv_item_versions;
drop table if exists public.jv_items;
drop table if exists public.jv_pack_versions;
drop table if exists public.jv_packs;
drop table if exists public.jv_members;
drop schema if exists jv_private cascade;
-- pgroonga 擴充保留（其他應用之後可能用，且 drop 需要確認沒有相依）
