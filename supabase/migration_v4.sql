-- شغّله مرة واحدة في Supabase → SQL Editor (بعد migration_v3.sql)

alter table agent_config add column if not exists interrupt_policy text default 'smart';   -- never | smart | always
alter table agent_config add column if not exists yield_chars int default 30;
alter table agent_config add column if not exists yield_window_ms int default 3000;
alter table agent_config add column if not exists proactive_audio boolean default true;

-- الوضع الافتراضي: ذكي (النموذج يقرر ما يُرَدّ عليه)
update agent_config set default_mode = 'AUTO' where id = 1;
