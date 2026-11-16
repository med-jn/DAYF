-- شغّله مرة واحدة في Supabase → SQL Editor (بعد schema_v2.sql)
-- الهدف: قراءة المراجع مرة واحدة فقط وتخزين محتواها كاملاً بصيغة Markdown مقسّمة إلى أجزاء،
-- فلا يُستهلك أي توكن عند كل سؤال: الوكيل يجلب الأجزاء ذات الصلة فقط.

-- ---------------------------------------------------------------- sources: أعمدة جديدة
alter table public.sources drop constraint if exists sources_kind_check;
alter table public.sources add constraint sources_kind_check
  check (kind in ('web', 'site', 'youtube', 'file', 'supabase'));

-- type = التصنيف الذكي للمحتوى: article | docs | site | book | document | sheet | video | image | text | library
alter table public.sources add column if not exists type text default 'article';
alter table public.sources add column if not exists progress text default '';        -- مرحلة القراءة الحالية بلغة مفهومة
alter table public.sources add column if not exists attempts int default 0;           -- عدد المحاولات التلقائية
alter table public.sources add column if not exists next_retry_at timestamptz;        -- موعد إعادة المحاولة التلقائية
alter table public.sources add column if not exists outline text default '';          -- فهرس المحتوى (الخريطة التي يقرأها الوكيل أولاً)
alter table public.sources add column if not exists chunk_count int default 0;
alter table public.sources add column if not exists char_count int default 0;
alter table public.sources add column if not exists page_count int default 0;
alter table public.sources add column if not exists file_name text default '';
alter table public.sources add column if not exists mime text default '';
alter table public.sources add column if not exists via text default '';              -- direct | reader | archive | gemini | file
alter table public.sources add column if not exists content_hash text default '';
alter table public.sources add column if not exists note text default '';
alter table public.sources add column if not exists fetched_at timestamptz;

-- ---------------------------------------------------------------- أجزاء المحتوى (Markdown)
create extension if not exists pg_trgm with schema extensions;

create table if not exists public.source_chunks (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  persona_id uuid not null references public.personas(id) on delete cascade,
  source_id uuid not null references public.sources(id) on delete cascade,
  idx int not null,                 -- ترتيب الجزء داخل المصدر
  heading text not null default '', -- مسار العناوين: فصل › باب › فقرة
  page int not null default 0,      -- رقم الصفحة إن عُرف
  text text not null
);
create index if not exists source_chunks_src_idx on public.source_chunks (source_id, idx);
create index if not exists source_chunks_persona_idx on public.source_chunks (persona_id);
create index if not exists source_chunks_text_trgm on public.source_chunks using gin (text extensions.gin_trgm_ops);

alter table public.source_chunks enable row level security;
drop policy if exists own_all on public.source_chunks;
create policy own_all on public.source_chunks for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));
grant select, insert, update, delete on public.source_chunks to authenticated;
grant usage, select on all sequences in schema public to authenticated;

-- ---------------------------------------------------------------- البحث في الأجزاء (يعمل بصلاحيات المستخدم، فيحترم RLS)
create or replace function public.search_source_chunks(p_persona uuid, p_words text[], p_limit int default 4)
returns table (source_id uuid, title text, idx int, heading text, page int, text text, score int)
language sql stable security invoker set search_path = '' as $$
  select c.source_id, s.title, c.idx, c.heading, c.page, c.text,
         ( (select count(*) from unnest(p_words) w where c.text ilike '%' || w || '%') * 2
         + (select count(*) from unnest(p_words) w where c.heading ilike '%' || w || '%') * 3
         + (select count(*) from unnest(p_words) w where s.title ilike '%' || w || '%') )::int as score
  from public.source_chunks c
  join public.sources s on s.id = c.source_id
  where c.persona_id = p_persona
    and s.status = 'ready'
    and exists (select 1 from unnest(p_words) w
                where c.text ilike '%' || w || '%' or c.heading ilike '%' || w || '%')
  order by score desc, c.idx asc
  limit greatest(1, least(p_limit, 12));
$$;
grant execute on function public.search_source_chunks(uuid, text[], int) to authenticated;
