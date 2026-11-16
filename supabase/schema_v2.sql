-- ============================================================================
-- Tik Guest — production schema: multi-tenant with Row Level Security.
-- Run ONCE in Supabase → SQL Editor.
-- ⚠ This DROPS the old single-user test tables (agent_config, rules, knowledge, ...).
-- If it fails on "vault": Database → Extensions → enable "supabase_vault", then run again.
-- ============================================================================

drop table if exists public.transcripts, public.unanswered, public.sessions, public.person_notes,
  public.knowledge, public.rules, public.agent_config, public.reports, public.user_secrets, public.sources,
  public.profiles, public.personas cascade;
drop function if exists public.is_dayf_admin();

create extension if not exists pgcrypto with schema extensions;

-- Master key used to encrypt users' API keys. It lives in Vault, never in an API-readable table.
do $$
begin
  if not exists (select 1 from vault.secrets where name = 'tg_master_key') then
    perform vault.create_secret(encode(extensions.gen_random_bytes(32), 'hex'), 'tg_master_key', 'Tik Guest master key');
  end if;
end $$;

-- ---------------------------------------------------------------- tables
create table public.personas (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  title text not null default 'ضيفي',
  name text not null default 'ضيف',
  gender text default 'male',
  aliases text default '',
  dialect text default 'عربية بيضاء',
  speech_style text default 'دافئ وقريب',
  persona text default '',
  extra_instructions text default '',
  unknown_reply text default 'ما عندي معلومة عن هذا الموضوع.',
  greet_on_join boolean default true,
  -- voice
  voice_provider text default 'gemini',
  voice text default 'Charon',
  eleven_voice_id text default '',
  eleven_voice_2_id text default '',
  eleven_voice_1_name text default 'الصوت 1',
  eleven_voice_2_name text default 'الصوت 2',
  eleven_active int default 1,
  eleven_model text default 'eleven_flash_v2_5',
  eleven_stability double precision default 0.45,
  eleven_similarity double precision default 0.8,
  out_gain double precision default 1.0,
  -- behaviour
  temperature double precision default 0.4,
  silence_ms int default 700,
  response_speed text default 'balanced',
  fast_mode boolean default true,
  default_mode text default 'AUTO',
  address_window_s int default 25,
  followup_window_s int default 20,
  allow_unknown_state boolean default false,
  screen_mode text default 'on_demand',
  verify_ttl_s int default 180,
  frame_interval_ms int default 3000,
  interrupt_policy text default 'smart',
  yield_chars int default 30,
  yield_window_ms int default 3000,
  proactive_audio boolean default true,
  affective_dialog boolean default true,
  store_memory boolean default false,
  knowledge_inline_chars int default 6000,
  quick_commands jsonb default '["قدّم نفسك بجملة","اشكر المضيف وودّع الجميع","اسكت الآن"]'::jsonb,
  vision_prompt text,
  model text,
  vision_model text default '',
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);

create table public.profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  active_persona_id uuid references public.personas(id) on delete set null,
  created_at timestamptz default now()
);

create table public.rules (
  id bigserial primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  persona_id uuid not null references public.personas(id) on delete cascade,
  text text not null,
  priority int default 100,
  active boolean default true
);

create table public.knowledge (
  id bigserial primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  persona_id uuid not null references public.personas(id) on delete cascade,
  topic text not null,
  content text not null,
  keywords text default '',
  active boolean default true,
  updated_at timestamptz default now()
);

-- Opt-in only (personas.store_memory). The app never stores what other people say.
create table public.person_notes (
  id bigserial primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  persona_id uuid not null references public.personas(id) on delete cascade,
  host text not null default 'default',
  person text not null,
  note text not null,
  created_at timestamptz default now()
);

create table public.unanswered (
  id bigserial primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  persona_id uuid not null references public.personas(id) on delete cascade,
  question text,
  created_at timestamptz default now()
);

-- In-app reports of inappropriate AI replies (Google Play generative-AI policy). Stores the agent's own text only.
create table public.reports (
  id bigserial primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  persona_id uuid references public.personas(id) on delete set null,
  agent_text text,
  reason text,
  created_at timestamptz default now()
);

-- References the agent learns from: web pages / PDFs / images / YouTube videos (condensed into a digest by Gemini)
-- and external Supabase libraries (searched live, never copied).
create table public.sources (
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
create index on public.sources (persona_id);

-- Users' API keys, encrypted. No API access at all: only the functions below can touch it.
create table public.user_secrets (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null,
  ciphertext bytea not null,
  updated_at timestamptz default now(),
  primary key (user_id, name)
);

create index on public.rules (persona_id);
create index on public.knowledge (persona_id);
create index on public.person_notes (persona_id, host);

create or replace function public.set_updated_at() returns trigger language plpgsql as $$
begin new.updated_at = now(); return new; end $$;
create trigger personas_updated before update on public.personas for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------- Row Level Security
alter table public.personas enable row level security;
alter table public.profiles enable row level security;
alter table public.rules enable row level security;
alter table public.knowledge enable row level security;
alter table public.person_notes enable row level security;
alter table public.unanswered enable row level security;
alter table public.reports enable row level security;
alter table public.sources enable row level security;
alter table public.user_secrets enable row level security;   -- no policies = nobody via the API

create policy own_all on public.personas for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy own_all on public.profiles for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

create policy own_all on public.rules for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and exists (select 1 from public.personas p where p.id = persona_id and p.user_id = (select auth.uid())));
create policy own_all on public.knowledge for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and exists (select 1 from public.personas p where p.id = persona_id and p.user_id = (select auth.uid())));
create policy own_all on public.person_notes for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and exists (select 1 from public.personas p where p.id = persona_id and p.user_id = (select auth.uid())));
create policy own_all on public.unanswered for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and exists (select 1 from public.personas p where p.id = persona_id and p.user_id = (select auth.uid())));

create policy own_all on public.sources for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and exists (select 1 from public.personas p where p.id = persona_id and p.user_id = (select auth.uid())));

create policy own_select on public.reports for select to authenticated using (user_id = (select auth.uid()));
create policy own_insert on public.reports for insert to authenticated with check (user_id = (select auth.uid()));

-- ---------------------------------------------------------------- table privileges (defence in depth)
revoke all on all tables in schema public from anon, authenticated;
grant select, insert, update, delete on public.personas, public.profiles, public.rules, public.knowledge,
  public.person_notes, public.unanswered, public.sources to authenticated;
grant select, insert on public.reports to authenticated;
grant usage, select on all sequences in schema public to authenticated;

-- ---------------------------------------------------------------- functions
create or replace function public.ensure_profile() returns uuid
language plpgsql security definer set search_path = '' as $$
declare uid uuid := auth.uid(); pid uuid;
begin
  if uid is null then raise exception 'not authenticated'; end if;
  select active_persona_id into pid from public.profiles where user_id = uid;
  if pid is not null and exists (select 1 from public.personas where id = pid and user_id = uid) then
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

create or replace function public.get_secret(p_name text) returns text
language plpgsql security definer set search_path = '' as $$
declare uid uuid := auth.uid(); k text; v text;
begin
  if uid is null then raise exception 'not authenticated'; end if;
  select decrypted_secret into k from vault.decrypted_secrets where name = 'tg_master_key';
  select extensions.pgp_sym_decrypt(ciphertext, k) into v from public.user_secrets where user_id = uid and name = p_name;
  return v;
end $$;

create or replace function public.delete_secret(p_name text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'not authenticated'; end if;
  delete from public.user_secrets where user_id = auth.uid() and name = p_name;
end $$;

-- Account deletion required by Google Play: removes the auth user; every table cascades.
create or replace function public.delete_my_account() returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'not authenticated'; end if;
  delete from auth.users where id = auth.uid();
end $$;

revoke all on function public.ensure_profile() from public, anon;
revoke all on function public.save_secret(text, text) from public, anon;
revoke all on function public.get_secret(text) from public, anon;
revoke all on function public.delete_secret(text) from public, anon;
revoke all on function public.delete_my_account() from public, anon;
grant execute on function public.ensure_profile() to authenticated;
grant execute on function public.save_secret(text, text) to authenticated;
grant execute on function public.get_secret(text) to authenticated;
grant execute on function public.delete_secret(text) to authenticated;
grant execute on function public.delete_my_account() to authenticated;
