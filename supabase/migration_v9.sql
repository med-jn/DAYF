-- شغّله مرة واحدة في Supabase → SQL Editor (بعد migration_v8.sql)
-- المشاركة التعاونية: صفوف الشخصية نفسها يعدّلها كل من لديه دور «تعديل»، ويرى الجميع التغييرات.
-- المالك يحوّل أي دعوة إلى «استخدام فقط» متى شاء، ولكل عضو أن يستنسخ الشخصية فيستقل عن الأصل.

alter table public.persona_members add column if not exists role text not null default 'use';
alter table public.persona_members drop constraint if exists persona_members_role_check;
alter table public.persona_members add constraint persona_members_role_check check (role in ('edit', 'use'));

alter table public.persona_shares drop constraint if exists persona_shares_mode_check;
alter table public.persona_shares add constraint persona_shares_mode_check check (mode in ('copy', 'live', 'edit', 'use'));
update public.persona_shares set mode = 'use' where mode = 'live';

-- ---------------------------------------------------------------- الصلاحيات
create or replace function public.can_read(p uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.personas where id = p and user_id = (select auth.uid()))
      or exists (select 1 from public.persona_members where persona_id = p and user_id = (select auth.uid()))
$$;

create or replace function public.can_edit(p uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.personas where id = p and user_id = (select auth.uid()))
      or exists (select 1 from public.persona_members where persona_id = p and user_id = (select auth.uid()) and role = 'edit')
$$;

create or replace function public.is_shared(p uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select public.can_read(p) and (
    exists (select 1 from public.persona_shares where persona_id = p and not revoked)
    or exists (select 1 from public.persona_members where persona_id = p))
$$;

create or replace function public.my_role(p uuid) returns text
language sql stable security definer set search_path = '' as $$
  select case
    when exists (select 1 from public.personas where id = p and user_id = (select auth.uid())) then 'owner'
    else coalesce((select role from public.persona_members where persona_id = p and user_id = (select auth.uid())), '')
  end
$$;
grant execute on function public.can_read(uuid), public.can_edit(uuid), public.is_shared(uuid), public.my_role(uuid) to authenticated;

-- المحرّرون يكتبون في نفس الصفوف (القواعد والمعارف والمراجع والمجلدات وأجزاء المحتوى)
drop policy if exists team_write on public.rules;
create policy team_write on public.rules for all to authenticated using (public.can_edit(persona_id)) with check (public.can_edit(persona_id));
drop policy if exists team_write on public.knowledge;
create policy team_write on public.knowledge for all to authenticated using (public.can_edit(persona_id)) with check (public.can_edit(persona_id));
drop policy if exists team_write on public.sources;
create policy team_write on public.sources for all to authenticated using (public.can_edit(persona_id)) with check (public.can_edit(persona_id));
drop policy if exists team_write on public.source_chunks;
create policy team_write on public.source_chunks for all to authenticated using (public.can_edit(persona_id)) with check (public.can_edit(persona_id));
drop policy if exists team_write on public.source_folders;
create policy team_write on public.source_folders for all to authenticated using (public.can_edit(persona_id)) with check (public.can_edit(persona_id));

-- إعدادات الشخصية نفسها: تعديل فقط (الحذف للمالك وحده)
drop policy if exists team_update on public.personas;
create policy team_update on public.personas for update to authenticated using (public.can_edit(id)) with check (public.can_edit(id));

create or replace function public.keep_persona_owner() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  new.user_id := old.user_id;
  return new;
end $$;
drop trigger if exists keep_owner on public.personas;
create trigger keep_owner before update on public.personas for each row execute function public.keep_persona_owner();

-- ما يضيفه المحرّر يبقى مملوكاً لصاحب الشخصية (فلا يضيع إن حُذف حساب المحرّر)
create or replace function public.team_owner() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  new.user_id := (select user_id from public.personas where id = new.persona_id);
  return new;
end $$;
drop trigger if exists team_owner_ins on public.rules;
create trigger team_owner_ins before insert on public.rules for each row execute function public.team_owner();
drop trigger if exists team_owner_ins on public.knowledge;
create trigger team_owner_ins before insert on public.knowledge for each row execute function public.team_owner();
drop trigger if exists team_owner_ins on public.sources;
create trigger team_owner_ins before insert on public.sources for each row execute function public.team_owner();
drop trigger if exists team_owner_ins on public.source_chunks;
create trigger team_owner_ins before insert on public.source_chunks for each row execute function public.team_owner();
drop trigger if exists team_owner_ins on public.source_folders;
create trigger team_owner_ins before insert on public.source_folders for each row execute function public.team_owner();

-- ---------------------------------------------------------------- الاستنساخ (نسخة مستقلة لمن يملك وصولاً)
create or replace function public._clone_core(p_src uuid, p_me uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  rec public.personas;
  newp uuid;
begin
  select * into rec from public.personas where id = p_src;
  rec.id := gen_random_uuid();
  rec.user_id := p_me;
  rec.title := left(rec.title || ' (نسخة)', 60);
  rec.created_at := now();
  rec.updated_at := now();
  insert into public.personas values (rec.*);
  newp := rec.id;

  insert into public.rules (user_id, persona_id, text, priority, active)
    select p_me, newp, text, priority, active from public.rules where persona_id = p_src;
  insert into public.knowledge (user_id, persona_id, topic, content, keywords, active)
    select p_me, newp, topic, content, keywords, active from public.knowledge where persona_id = p_src;

  create temp table _fmap (old_id uuid, new_id uuid) on commit drop;
  insert into _fmap select id, gen_random_uuid() from public.source_folders where persona_id = p_src;
  insert into public.source_folders (id, user_id, persona_id, name, auto)
    select m.new_id, p_me, newp, f.name, f.auto from public.source_folders f join _fmap m on m.old_id = f.id;

  create temp table _smap (old_id uuid, new_id uuid) on commit drop;
  insert into _smap select id, gen_random_uuid() from public.sources where persona_id = p_src and status = 'ready' and kind <> 'supabase';
  insert into public.sources (id, user_id, persona_id, kind, type, title, url, config, digest, brief, status, error, progress, attempts,
                              outline, chunk_count, char_count, page_count, file_name, mime, via, content_hash, note, fetched_at,
                              folder_id, folder_manual, drive_file_id, media_mime, media_secs, updated_at)
    select sm.new_id, p_me, newp, x.kind, x.type, x.title, x.url, x.config, x.digest, x.brief, 'ready', '', '', 0,
           x.outline, x.chunk_count, x.char_count, x.page_count, x.file_name, x.mime, x.via, x.content_hash, x.note, x.fetched_at,
           (select fm.new_id from _fmap fm where fm.old_id = x.folder_id), x.folder_manual, x.drive_file_id, x.media_mime, x.media_secs, now()
    from public.sources x join _smap sm on sm.old_id = x.id;

  return jsonb_build_object(
    'persona_id', newp,
    'pairs', coalesce((select jsonb_agg(jsonb_build_object('o', sm.old_id, 'n', sm.new_id))
                       from _smap sm join public.sources x on x.id = sm.old_id where x.chunk_count > 0), '[]'::jsonb),
    'media', coalesce((select jsonb_agg(jsonb_build_object('id', sm.new_id, 'file', x.drive_file_id, 'mime', x.media_mime, 'name', x.file_name))
                       from _smap sm join public.sources x on x.id = sm.old_id where x.drive_file_id <> ''), '[]'::jsonb));
end $$;
revoke all on function public._clone_core(uuid, uuid) from public, anon, authenticated;

create or replace function public.clone_persona(p_persona uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'not authenticated'; end if;
  if not public.is_member(p_persona) then raise exception 'not a member'; end if;
  return public._clone_core(p_persona, auth.uid()) || jsonb_build_object('mode', 'copy');
end $$;

create or replace function public.clone_chunks(p_src uuid, p_pairs jsonb) returns int
language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  r jsonb;
  total int := 0;
  n int;
  np uuid;
begin
  if me is null then raise exception 'not authenticated'; end if;
  if not public.is_member(p_src) then raise exception 'not a member'; end if;
  for r in select * from jsonb_array_elements(p_pairs) loop
    select persona_id into np from public.sources where id = (r ->> 'n')::uuid and user_id = me;
    if np is null then continue; end if;
    if not exists (select 1 from public.sources where id = (r ->> 'o')::uuid and persona_id = p_src) then continue; end if;
    delete from public.source_chunks where source_id = (r ->> 'n')::uuid;
    insert into public.source_chunks (user_id, persona_id, source_id, idx, heading, page, text)
      select me, np, (r ->> 'n')::uuid, c.idx, c.heading, c.page, c.text from public.source_chunks c where c.source_id = (r ->> 'o')::uuid;
    get diagnostics n = row_count;
    total := total + n;
  end loop;
  return total;
end $$;

-- ---------------------------------------------------------------- الدعوات
create or replace function public.create_share(p_persona uuid, p_mode text, p_label text default '') returns text
language plpgsql security definer set search_path = '' as $$
declare c text;
begin
  if auth.uid() is null then raise exception 'not authenticated'; end if;
  if not exists (select 1 from public.personas where id = p_persona and user_id = auth.uid()) then raise exception 'not owner'; end if;
  if p_mode not in ('edit', 'use') then raise exception 'bad mode'; end if;
  loop
    c := upper(substr(md5(random()::text || clock_timestamp()::text), 1, 10));
    exit when not exists (select 1 from public.persona_shares where code = c);
  end loop;
  insert into public.persona_shares (owner_id, persona_id, mode, code, label) values (auth.uid(), p_persona, p_mode, c, coalesce(p_label, ''));
  return c;
end $$;

create or replace function public.accept_share(p_code text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  s public.persona_shares;
  nm text;
begin
  if me is null then raise exception 'not authenticated'; end if;
  select * into s from public.persona_shares where code = upper(trim(p_code)) and not revoked;
  if not found then raise exception 'invalid code'; end if;
  if s.owner_id = me then raise exception 'own persona'; end if;
  update public.persona_shares set uses = uses + 1 where id = s.id;

  if s.mode = 'copy' then
    return public._clone_core(s.persona_id, me) || jsonb_build_object('mode', 'copy');
  end if;

  select coalesce(nullif(u.raw_user_meta_data ->> 'full_name', ''), split_part(u.email, '@', 1)) into nm from auth.users u where u.id = me;
  insert into public.persona_members (persona_id, user_id, share_id, role, display)
    values (s.persona_id, me, s.id, case when s.mode = 'edit' then 'edit' else 'use' end, coalesce(nm, ''))
    on conflict (persona_id, user_id) do update set role = excluded.role, share_id = excluded.share_id;
  return jsonb_build_object('mode', case when s.mode = 'edit' then 'edit' else 'use' end, 'persona_id', s.persona_id);
end $$;

-- تحويل دعوة (وكل من دخل بها) بين «تعاون» و«استخدام فقط»
create or replace function public.set_share_mode(p_share uuid, p_mode text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if p_mode not in ('edit', 'use') then raise exception 'bad mode'; end if;
  if not exists (select 1 from public.persona_shares where id = p_share and owner_id = auth.uid()) then raise exception 'not owner'; end if;
  update public.persona_shares set mode = p_mode where id = p_share;
  update public.persona_members set role = p_mode where share_id = p_share;
end $$;

revoke all on function public.clone_persona(uuid) from public, anon;
revoke all on function public.clone_chunks(uuid, jsonb) from public, anon;
revoke all on function public.set_share_mode(uuid, text) from public, anon;
grant execute on function public.clone_persona(uuid) to authenticated;
grant execute on function public.clone_chunks(uuid, jsonb) to authenticated;
grant execute on function public.set_share_mode(uuid, text) to authenticated;
