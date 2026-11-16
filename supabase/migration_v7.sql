-- شغّله مرة واحدة في Supabase → SQL Editor (بعد migration_v6.sql)
-- مجلدات المراجع + أعمدة الصوتيات المحفوظة في Google Drive الخاص بالمستخدم.

create table if not exists public.source_folders (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  persona_id uuid not null references public.personas(id) on delete cascade,
  name text not null,
  auto boolean not null default true,       -- true: أنشأها التطبيق تلقائياً
  created_at timestamptz not null default now(),
  unique (persona_id, name)
);
alter table public.source_folders enable row level security;
drop policy if exists own_all on public.source_folders;
create policy own_all on public.source_folders for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));
grant select, insert, update, delete on public.source_folders to authenticated;

alter table public.sources add column if not exists folder_id uuid references public.source_folders(id) on delete set null;
alter table public.sources add column if not exists folder_manual boolean default false;   -- true: نقله المستخدم بنفسه، فلا يلمسه التنظيم الذكي
alter table public.sources add column if not exists drive_file_id text default '';          -- معرّف الملف الصوتي في Drive
alter table public.sources add column if not exists media_mime text default '';
alter table public.sources add column if not exists media_secs int default 0;
create index if not exists sources_folder_idx on public.sources (folder_id);
