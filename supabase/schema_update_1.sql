-- For projects that already ran schema_v2.sql before this version. Run ONCE in SQL Editor. Safe to re-run. Nothing is deleted.

alter table public.personas add column if not exists gender text default 'male';
alter table public.personas add column if not exists response_speed text default 'balanced';
alter table public.personas add column if not exists fast_mode boolean default true;
alter table public.personas alter column verify_ttl_s set default 180;
update public.personas set verify_ttl_s = 180 where verify_ttl_s = 60;

create table if not exists public.sources (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  persona_id uuid not null references public.personas(id) on delete cascade,
  kind text not null check (kind in ('web', 'youtube', 'supabase')),
  title text not null default '',
  url text not null default '',
  config jsonb not null default '{}'::jsonb,
  digest text default '',
  brief text default '',
  status text default 'pending',
  error text default '',
  updated_at timestamptz default now()
);
create index if not exists sources_persona_idx on public.sources (persona_id);
alter table public.sources enable row level security;
drop policy if exists own_all on public.sources;
create policy own_all on public.sources for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and exists (select 1 from public.personas p where p.id = persona_id and p.user_id = (select auth.uid())));
revoke all on public.sources from anon, authenticated;
grant select, insert, update, delete on public.sources to authenticated;

-- allow encrypted keys for external libraries (lib_<id>) besides gemini / eleven
create or replace function public.save_secret(p_name text, p_value text) returns void
language plpgsql security definer set search_path = '' as $$
declare uid uuid := auth.uid(); k text;
begin
  if uid is null then raise exception 'not authenticated'; end if;
  if p_name not in ('gemini', 'eleven') and p_name not like 'lib\_%' then raise exception 'invalid secret name'; end if;
  select decrypted_secret into k from vault.decrypted_secrets where name = 'tg_master_key';
  insert into public.user_secrets (user_id, name, ciphertext, updated_at)
  values (uid, p_name, extensions.pgp_sym_encrypt(p_value, k), now())
  on conflict (user_id, name) do update set ciphertext = excluded.ciphertext, updated_at = now();
end $$;
