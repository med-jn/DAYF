-- شغّله مرة واحدة في Supabase → SQL Editor (بعد migration_v7.sql)
-- مشاركة الشخصيات: نسخة مستقلة (copy) أو استخدام فقط يتبع تحديثات المالك (live).

-- ---------------------------------------------------------------- إعدادات عامة (مفتاح Drive API للملفات المشاركة)
create table if not exists public.app_config (key text primary key, value text not null);
alter table public.app_config enable row level security;
drop policy if exists read_all on public.app_config;
create policy read_all on public.app_config for select to authenticated using (true);
grant select on public.app_config to authenticated;
-- بعد إنشاء مفتاح API في Google Cloud (مقيّد بـ Google Drive API) نفّذ مرة واحدة:
--   insert into public.app_config (key, value) values ('drive_api_key', 'AIza...') on conflict (key) do update set value = excluded.value;

-- ---------------------------------------------------------------- روابط المشاركة والأعضاء
create table if not exists public.persona_shares (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  persona_id uuid not null references public.personas(id) on delete cascade,
  mode text not null check (mode in ('copy', 'live')),
  code text not null unique,
  label text default '',
  revoked boolean not null default false,
  uses int not null default 0,
  created_at timestamptz default now()
);
alter table public.persona_shares enable row level security;
drop policy if exists owner_all on public.persona_shares;
create policy owner_all on public.persona_shares for all to authenticated
  using (owner_id = (select auth.uid()))
  with check (owner_id = (select auth.uid()) and exists (select 1 from public.personas p where p.id = persona_id and p.user_id = (select auth.uid())));
grant select, insert, update, delete on public.persona_shares to authenticated;

create table if not exists public.persona_members (
  persona_id uuid not null references public.personas(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  share_id uuid references public.persona_shares(id) on delete cascade,
  display text default '',
  created_at timestamptz default now(),
  primary key (persona_id, user_id)
);
alter table public.persona_members enable row level security;
drop policy if exists see_members on public.persona_members;
drop policy if exists del_members on public.persona_members;
create policy see_members on public.persona_members for select to authenticated
  using (user_id = (select auth.uid()) or exists (select 1 from public.personas p where p.id = persona_id and p.user_id = (select auth.uid())));
create policy del_members on public.persona_members for delete to authenticated
  using (user_id = (select auth.uid()) or exists (select 1 from public.personas p where p.id = persona_id and p.user_id = (select auth.uid())));
grant select, delete on public.persona_members to authenticated;

-- ---------------------------------------------------------------- قراءة فقط للأعضاء (الاستخدام المباشر)
create or replace function public.is_member(p uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.persona_members m where m.persona_id = p and m.user_id = (select auth.uid()))
$$;
grant execute on function public.is_member(uuid) to authenticated;

drop policy if exists member_select on public.personas;
create policy member_select on public.personas for select to authenticated using (public.is_member(id));
drop policy if exists member_select on public.rules;
create policy member_select on public.rules for select to authenticated using (public.is_member(persona_id));
drop policy if exists member_select on public.knowledge;
create policy member_select on public.knowledge for select to authenticated using (public.is_member(persona_id));
drop policy if exists member_select on public.sources;
create policy member_select on public.sources for select to authenticated using (public.is_member(persona_id));
drop policy if exists member_select on public.source_chunks;
create policy member_select on public.source_chunks for select to authenticated using (public.is_member(persona_id));
drop policy if exists member_select on public.source_folders;
create policy member_select on public.source_folders for select to authenticated using (public.is_member(persona_id));

-- الشخصية النشطة قد تكون شخصية مشاركة معي
create or replace function public.ensure_profile() returns uuid
language plpgsql security definer set search_path = '' as $$
declare uid uuid := auth.uid(); pid uuid;
begin
  if uid is null then raise exception 'not authenticated'; end if;
  select active_persona_id into pid from public.profiles where user_id = uid;
  if pid is not null and (
       exists (select 1 from public.personas where id = pid and user_id = uid)
    or exists (select 1 from public.persona_members where persona_id = pid and user_id = uid)) then
    return pid;
  end if;
  select id into pid from public.personas where user_id = uid order by created_at limit 1;
  if pid is null then
    insert into public.personas (user_id, title, name) values (uid, 'ضيفي الأول', 'ضيف') returning id into pid;
    insert into public.rules (user_id, persona_id, text, priority) values
      (uid, pid, 'لا تذكر أي سعر أو رقم أو تاريخ إلا إذا ورد في المعرفة.', 10),
      (uid, pid, 'لا تعد بشيء باسم صاحب الحساب.', 20),
      (uid, pid, 'إن شتمك أحد أو استفزك فلا تردّ بالمثل وغيّر الموضوع بجملة قصيرة.', 30);
  end if;
  insert into public.profiles (user_id, active_persona_id) values (uid, pid)
    on conflict (user_id) do update set active_persona_id = excluded.active_persona_id;
  return pid;
end $$;

-- ---------------------------------------------------------------- الدوال
create or replace function public.create_share(p_persona uuid, p_mode text, p_label text default '') returns text
language plpgsql security definer set search_path = '' as $$
declare c text;
begin
  if auth.uid() is null then raise exception 'not authenticated'; end if;
  if not exists (select 1 from public.personas where id = p_persona and user_id = auth.uid()) then raise exception 'not owner'; end if;
  if p_mode not in ('copy', 'live') then raise exception 'bad mode'; end if;
  loop
    c := upper(substr(md5(random()::text || clock_timestamp()::text), 1, 10));
    exit when not exists (select 1 from public.persona_shares where code = c);
  end loop;
  insert into public.persona_shares (owner_id, persona_id, mode, code, label) values (auth.uid(), p_persona, p_mode, c, coalesce(p_label, ''));
  return c;
end $$;

create or replace function public.preview_share(p_code text)
returns table (persona_id uuid, title text, name text, dialect text, mode text, owner_name text, rules_n int, knowledge_n int, sources_n int, already boolean)
language sql security definer set search_path = '' as $$
  select p.id, p.title, p.name, p.dialect, s.mode,
         coalesce(nullif(u.raw_user_meta_data ->> 'full_name', ''), split_part(u.email, '@', 1)),
         (select count(*)::int from public.rules r where r.persona_id = p.id),
         (select count(*)::int from public.knowledge k where k.persona_id = p.id),
         (select count(*)::int from public.sources x where x.persona_id = p.id and x.status = 'ready'),
         (s.owner_id = auth.uid() or exists (select 1 from public.persona_members m where m.persona_id = p.id and m.user_id = auth.uid()))
  from public.persona_shares s
  join public.personas p on p.id = s.persona_id
  join auth.users u on u.id = s.owner_id
  where s.code = upper(trim(p_code)) and not s.revoked and auth.uid() is not null
$$;

create or replace function public.accept_share(p_code text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  s public.persona_shares;
  rec public.personas;
  newp uuid;
begin
  if me is null then raise exception 'not authenticated'; end if;
  select * into s from public.persona_shares where code = upper(trim(p_code)) and not revoked;
  if not found then raise exception 'invalid code'; end if;
  if s.owner_id = me then raise exception 'own persona'; end if;
  update public.persona_shares set uses = uses + 1 where id = s.id;

  if s.mode = 'live' then
    insert into public.persona_members (persona_id, user_id, share_id) values (s.persona_id, me, s.id) on conflict do nothing;
    return jsonb_build_object('mode', 'live', 'persona_id', s.persona_id);
  end if;

  -- copy: an independent persona owned by the caller
  select * into rec from public.personas where id = s.persona_id;
  rec.id := gen_random_uuid();
  rec.user_id := me;
  rec.title := left(rec.title || ' (نسخة)', 60);
  rec.created_at := now();
  rec.updated_at := now();
  insert into public.personas values (rec.*);
  newp := rec.id;

  insert into public.rules (user_id, persona_id, text, priority, active)
    select me, newp, text, priority, active from public.rules where persona_id = s.persona_id;
  insert into public.knowledge (user_id, persona_id, topic, content, keywords, active)
    select me, newp, topic, content, keywords, active from public.knowledge where persona_id = s.persona_id;

  create temp table _fmap (old_id uuid, new_id uuid) on commit drop;
  insert into _fmap select id, gen_random_uuid() from public.source_folders where persona_id = s.persona_id;
  insert into public.source_folders (id, user_id, persona_id, name, auto)
    select m.new_id, me, newp, f.name, f.auto from public.source_folders f join _fmap m on m.old_id = f.id;

  create temp table _smap (old_id uuid, new_id uuid) on commit drop;
  insert into _smap select id, gen_random_uuid() from public.sources where persona_id = s.persona_id and status = 'ready' and kind <> 'supabase';
  insert into public.sources (id, user_id, persona_id, kind, type, title, url, config, digest, brief, status, error, progress, attempts,
                              outline, chunk_count, char_count, page_count, file_name, mime, via, content_hash, note, fetched_at,
                              folder_id, folder_manual, drive_file_id, media_mime, media_secs, updated_at)
    select sm.new_id, me, newp, x.kind, x.type, x.title, x.url, x.config, x.digest, x.brief, 'ready', '', '', 0,
           x.outline, x.chunk_count, x.char_count, x.page_count, x.file_name, x.mime, x.via, x.content_hash, x.note, x.fetched_at,
           (select fm.new_id from _fmap fm where fm.old_id = x.folder_id), x.folder_manual, x.drive_file_id, x.media_mime, x.media_secs, now()
    from public.sources x join _smap sm on sm.old_id = x.id;

  return jsonb_build_object(
    'mode', 'copy', 'persona_id', newp,
    'pairs', coalesce((select jsonb_agg(jsonb_build_object('o', sm.old_id, 'n', sm.new_id))
                       from _smap sm join public.sources x on x.id = sm.old_id where x.chunk_count > 0), '[]'::jsonb),
    'media', coalesce((select jsonb_agg(jsonb_build_object('id', sm.new_id, 'file', x.drive_file_id, 'mime', x.media_mime, 'name', x.file_name))
                       from _smap sm join public.sources x on x.id = sm.old_id where x.drive_file_id <> ''), '[]'::jsonb));
end $$;

-- the chunks of copied sources, in batches (so one request never runs long)
create or replace function public.clone_chunks(p_code text, p_pairs jsonb) returns int
language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  sp uuid;
  r jsonb;
  total int := 0;
  n int;
  np uuid;
begin
  if me is null then raise exception 'not authenticated'; end if;
  select persona_id into sp from public.persona_shares where code = upper(trim(p_code)) and not revoked and mode = 'copy';
  if sp is null then raise exception 'invalid code'; end if;
  for r in select * from jsonb_array_elements(p_pairs) loop
    select persona_id into np from public.sources where id = (r ->> 'n')::uuid and user_id = me;
    if np is null then continue; end if;
    if not exists (select 1 from public.sources where id = (r ->> 'o')::uuid and persona_id = sp) then continue; end if;
    delete from public.source_chunks where source_id = (r ->> 'n')::uuid;
    insert into public.source_chunks (user_id, persona_id, source_id, idx, heading, page, text)
      select me, np, (r ->> 'n')::uuid, c.idx, c.heading, c.page, c.text from public.source_chunks c where c.source_id = (r ->> 'o')::uuid;
    get diagnostics n = row_count;
    total := total + n;
  end loop;
  return total;
end $$;

create or replace function public.revoke_share(p_share uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if not exists (select 1 from public.persona_shares where id = p_share and owner_id = auth.uid()) then raise exception 'not owner'; end if;
  update public.persona_shares set revoked = true where id = p_share;
  delete from public.persona_members where share_id = p_share;
end $$;

revoke all on function public.create_share(uuid, text, text) from public, anon;
revoke all on function public.preview_share(text) from public, anon;
revoke all on function public.accept_share(text) from public, anon;
revoke all on function public.clone_chunks(text, jsonb) from public, anon;
revoke all on function public.revoke_share(uuid) from public, anon;
grant execute on function public.create_share(uuid, text, text) to authenticated;
grant execute on function public.preview_share(text) to authenticated;
grant execute on function public.accept_share(text) to authenticated;
grant execute on function public.clone_chunks(text, jsonb) to authenticated;
grant execute on function public.revoke_share(uuid) to authenticated;
