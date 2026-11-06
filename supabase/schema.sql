-- الصق هذا الملف كاملاً في Supabase → SQL Editor → Run

create table if not exists agent_config (
  id int primary key default 1,
  name text not null default 'ضيف',
  aliases text default '',                       -- أسماء بديلة وتهجئات يلتقطها التفريغ الصوتي، مفصولة بفواصل
  voice text default 'Charon',
  model text,                                    -- فارغ = اختيار تلقائي لأفضل نموذج Live
  vision_model text default 'gemini-2.5-flash',
  dialect text default 'عربية بيضاء بسيطة',
  persona text default 'ضيف ودود وخفيف الظل، يتكلم قليلاً ويصغي كثيراً.',
  extra_instructions text default '',
  opening_line text default 'أنا مساعد ذكاء اصطناعي، سعيد بوجودي معكم.',
  unknown_reply text default 'ما عندي معلومة عن هذا الموضوع.',
  temperature double precision default 0.4,
  silence_ms int default 800,
  address_window_s int default 25,
  followup_window_s int default 20,
  vision_interval_s int default 6,
  frame_interval_ms int default 2000,
  knowledge_inline_chars int default 6000,
  allow_unknown_state boolean default false,
  default_mode text default 'ON_CALL',           -- ON_CALL | AUTO | SILENT
  out_gain double precision default 1.0,
  quick_commands jsonb default '["قدّم نفسك بجملة","اشكر المضيف وودّع الجميع","اسكت الآن"]'::jsonb,
  keywords jsonb default '{"guest":[],"requested":[],"invited":[],"viewer":[],"mic_muted":[],"mic_open":[]}'::jsonb,
  vision_prompt text,
  constraint single_row check (id = 1)
);
insert into agent_config (id) values (1) on conflict do nothing;

create table if not exists rules (
  id bigserial primary key,
  text text not null,
  priority int default 100,
  active boolean default true
);

create table if not exists knowledge (
  id bigserial primary key,
  topic text not null,
  content text not null,
  keywords text default '',
  active boolean default true,
  updated_at timestamptz default now()
);

create table if not exists person_notes (
  id bigserial primary key,
  host text not null default 'default',
  person text not null,
  note text not null,
  created_at timestamptz default now()
);

create table if not exists transcripts (
  id bigserial primary key,
  live_id text,
  host text default 'default',
  role text,
  text text,
  created_at timestamptz default now()
);

create table if not exists unanswered (
  id bigserial primary key,
  host text default 'default',
  question text,
  created_at timestamptz default now()
);

create table if not exists sessions (
  id text primary key,
  host text,
  started_at timestamptz default now()
);

-- صلاحيات التطبيق (مفتاح anon): قراءة الإعدادات والمعرفة فقط، وكتابة الذاكرة والسجلات فقط
alter table agent_config enable row level security;
alter table rules enable row level security;
alter table knowledge enable row level security;
alter table person_notes enable row level security;
alter table transcripts enable row level security;
alter table unanswered enable row level security;
alter table sessions enable row level security;

drop policy if exists app_read on agent_config;
drop policy if exists app_read on rules;
drop policy if exists app_read on knowledge;
create policy app_read on agent_config for select to anon using (true);
create policy app_read on rules for select to anon using (true);
create policy app_read on knowledge for select to anon using (true);

drop policy if exists app_read on person_notes;
drop policy if exists app_write on person_notes;
create policy app_read on person_notes for select to anon using (true);
create policy app_write on person_notes for insert to anon with check (true);

drop policy if exists app_read on transcripts;
drop policy if exists app_write on transcripts;
create policy app_read on transcripts for select to anon using (true);
create policy app_write on transcripts for insert to anon with check (true);

drop policy if exists app_write on unanswered;
create policy app_write on unanswered for insert to anon with check (true);

drop policy if exists app_write on sessions;
create policy app_write on sessions for insert to anon with check (true);

-- أمثلة (عدّلها أو احذفها من لوحة Supabase)
insert into rules (text, priority) values
 ('لا تذكر أي سعر أو رقم أو تاريخ إلا إذا ورد حرفياً في المعرفة.', 10),
 ('لا تعد بشيء باسم صاحب الحساب.', 20),
 ('إن شتمك أحد أو استفزك فلا تردّ بالمثل وغيّر الموضوع بجملة قصيرة.', 30);

insert into knowledge (topic, content, keywords) values
 ('مثال: عن البرنامج', 'هذا نص تجريبي. استبدله بمعلوماتك الحقيقية.', 'برنامج مثال');
