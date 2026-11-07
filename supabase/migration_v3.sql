-- شغّل هذا الملف مرة واحدة في Supabase → SQL Editor (على المشروع الذي أنشأته سابقاً)
-- ⚠ قبل التشغيل: غيّر CHANGE_ME_SECRET إلى كلمة سر طويلة من اختيارك،
--    ثم أدخل الكلمة نفسها في التطبيق: الإعدادات ← الربط ← رمز الإدارة.

alter table agent_config add column if not exists voice_provider text default 'gemini';
alter table agent_config add column if not exists eleven_voice_id text default '';
alter table agent_config add column if not exists eleven_voice_2_id text default '';
alter table agent_config add column if not exists eleven_voice_1_name text default 'الصوت 1';
alter table agent_config add column if not exists eleven_voice_2_name text default 'الصوت 2';
alter table agent_config add column if not exists eleven_active int default 1;
alter table agent_config add column if not exists eleven_model text default 'eleven_flash_v2_5';
alter table agent_config add column if not exists eleven_stability double precision default 0.45;
alter table agent_config add column if not exists eleven_similarity double precision default 0.8;
alter table agent_config add column if not exists greet_on_join boolean default true;
alter table agent_config add column if not exists screen_mode text default 'on_demand';   -- on_demand | periodic | manual
alter table agent_config add column if not exists verify_ttl_s int default 60;

-- نموذج الرؤية يُختار تلقائياً (القديم gemini-2.5-flash لم يعد متاحاً)، ولا حاجة لعبارة الإفصاح
update agent_config set vision_model = '', opening_line = '' where id = 1;
alter table agent_config alter column vision_model set default '';
alter table agent_config alter column opening_line set default '';

-- صلاحية الكتابة من التطبيق: لا تُقبل إلا مع رمز الإدارة (ترويسة x-dayf-admin)
create or replace function public.is_dayf_admin() returns boolean
language sql stable as $$
  select coalesce(
    (coalesce(nullif(current_setting('request.headers', true), ''), '{}')::json ->> 'x-dayf-admin'), ''
  ) = 'CHANGE_ME_SECRET'
$$;

drop policy if exists admin_all on agent_config;
drop policy if exists admin_all on rules;
drop policy if exists admin_all on knowledge;
create policy admin_all on agent_config for all to anon using (public.is_dayf_admin()) with check (public.is_dayf_admin());
create policy admin_all on rules        for all to anon using (public.is_dayf_admin()) with check (public.is_dayf_admin());
create policy admin_all on knowledge    for all to anon using (public.is_dayf_admin()) with check (public.is_dayf_admin());
